package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.testing.FakeVersionDetection.gateWithoutClients;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CliHealthSnapshot;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.HealthReport;
import de.dadecker.inubit.mcp.domain.model.HealthStatus;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LoadFigures;
import de.dadecker.inubit.mcp.domain.model.MemoryFigures;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessCounts;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.CliHealthPort;
import de.dadecker.inubit.mcp.domain.port.ClientVersionPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * T054: the health service on a fake {@link MonitoringPort} (research R-10, R-15, FR-005 –
 * FR-008, SC-002).
 */
@Timeout(30)
class HealthServiceTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId QA1 = NodeId.parse("qa/node1");
    private static final NodeId QA2 = NodeId.parse("qa/node2");
    private static final List<NodeId> SERVERS = List.of(DEV, QA1, QA2);
    private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant SERVER_TIME = Instant.parse("2026-10-01T07:59:58Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final Duration SLACK = Duration.ofSeconds(1);

    private static final SystemInfo SYSTEM_INFO = new SystemInfo(Optional.of("8.1.17"),
        Optional.of("OpenJDK 17"), Optional.of("Linux"), Optional.of("9216 MB"),
        Optional.of(true), Optional.of(401), Map.of("FreeMemory", "2548"));
    private static final ClientVersion CLIENT_8117 = new ClientVersion(version("8.1.17"),
        Optional.of(version("4.0.1")), Optional.of(version("8.1.17")));
    private static final LoadFigures LOAD = LoadFigures.of(5500, 3716, 9216, 0, 999_999, 401, 0,
        200_000);

    private final Map<NodeId, FakeMonitoring> monitorings = new ConcurrentHashMap<>();
    private final Map<NodeId, List<String>> warnings = new HashMap<>();
    private final Set<NodeId> undetected = new HashSet<>();
    private final Map<NodeId, RuntimeException> gatewayFailures = new HashMap<>();
    private final Map<NodeId, CountDownLatch> gatewayBlocks = new HashMap<>();
    private final AtomicInteger detections = new AtomicInteger();
    /** Feature 009: the {@code versionLine} of each server ({@code V8_1} if absent). */
    private final Map<NodeId, VersionSetting> versionLines = new ConcurrentHashMap<>();
    /** Moves only when a test advances it (the 60 s back-off of a failed client probe). */
    private final MutableClock clock = new MutableClock(NOW);
    private final NodeVersions versions = new NodeVersions(clock,
        node -> versionLines.getOrDefault(node, VersionSetting.V8_1),
        node -> Duration.ofMinutes(10));
    /** Feature 009: nodes with a StartCLI installation; only these are probed. */
    private final Set<NodeId> withClient = ConcurrentHashMap.newKeySet();
    /** Feature 009: the client each probe reports (8.1.17 with range 4.0.1 – 8.1.17 if absent). */
    private final Map<NodeId, Supplier<ClientVersion>> clients = new ConcurrentHashMap<>();
    private final List<Duration> probeTimeouts = java.util.Collections.synchronizedList(
        new ArrayList<>());
    /** Feature 009: detections requested by the version gate (never for get_health). */
    private final AtomicInteger gateDetections = new AtomicInteger();
    /** Feature 010: how each server answers {@code prepareCredentials} ({@code READY} if absent). */
    private final Map<NodeId, Function<Duration, CredentialState>> credentials =
        new ConcurrentHashMap<>();
    /** Feature 010: the bounds {@code prepareCredentials} was called with. */
    private final List<Duration> credentialBounds = java.util.Collections.synchronizedList(
        new ArrayList<>());
    /** Feature 011: the StartCLI health port of each CLI-only node (none if absent). */
    private final Map<NodeId, CliHealthPort> cliPorts = new ConcurrentHashMap<>();

    private FakeMonitoring monitoring(NodeId server) {
        return monitorings.computeIfAbsent(server, s -> new FakeMonitoring());
    }

    private HealthService service() {
        return service(CapabilityMatrix.production());
    }

    private HealthService service(CapabilityMatrix matrix) {
        return service(matrix, null, null);
    }

    /**
     * @param singleCliBudget  the bound of a call for one CLI-only node; {@code null} for the
     *                         production budgets of the public constructor
     * @param overviewBudget   the bound of a call for several nodes
     */
    private HealthService service(CapabilityMatrix matrix, Duration singleCliBudget,
        Duration overviewBudget) {
        GatewayFactory gateways = new GatewayFactory() {
            @Override
            public Gateway forServer(NodeId server) {
                detections.incrementAndGet(); // a blocking detection: health must not wait for it
                awaitQuietly(new CountDownLatch(1));
                throw new AssertionError("unreachable");
            }

            @Override
            public InubitVersion detect(NodeId server) {
                gateDetections.incrementAndGet();
                throw new AssertionError("get_health's version gate never detects");
            }

            @Override
            public CredentialState prepareCredentials(NodeId server, Duration bound) {
                credentialBounds.add(bound);
                return credentials.getOrDefault(server, b -> CredentialState.READY).apply(bound);
            }

            @Override
            public MonitoringPort monitoring(NodeId server) {
                if (gatewayFailures.containsKey(server)) {
                    throw gatewayFailures.get(server);
                }
                CountDownLatch block = gatewayBlocks.get(server);
                if (block != null) {
                    awaitQuietly(block);
                }
                return HealthServiceTest.this.monitoring(server);
            }

            @Override
            public ProcessQueryPort processes(NodeId server) {
                throw new AssertionError("get_health does not query processes");
            }

            @Override
            public LogPort logs(NodeId server) {
                throw new AssertionError("get_health does not query logs");
            }

            @Override
            public InventoryPort inventory(NodeId server) {
                throw new AssertionError("no inventory here");
            }

            @Override
            public ProcessControlPort processControl(NodeId server) {
                throw new AssertionError("no process control here");
            }

            @Override
            public Optional<CliHealthPort> cliHealth(NodeId server) {
                return Optional.ofNullable(cliPorts.get(server));
            }

            @Override
            public Optional<Gateway> knownGateway(NodeId server) {
                return undetected.contains(server) ? Optional.empty()
                    : Optional.of(new FakeGateway(server, warnings.getOrDefault(server,
                        List.of()), HealthServiceTest.this.monitoring(server)));
            }
        };
        ClientVersionPort probe = (server, timeout) -> {
            probeTimeouts.add(timeout);
            return clients.getOrDefault(server, () -> CLIENT_8117).get();
        };
        VersionGate gate = gateWithoutClients(versions, gateways, matrix);
        return singleCliBudget == null
            ? new HealthService(gateways, new TargetResolver(SERVERS), new FanOut(),
                server -> TIMEOUT, Clock.fixed(NOW, ZoneOffset.UTC), gate, versions, probe,
                withClient::contains)
            : new HealthService(gateways, new TargetResolver(SERVERS), new FanOut(),
                server -> TIMEOUT, Clock.fixed(NOW, ZoneOffset.UTC), gate, versions, probe,
                withClient::contains, singleCliBudget, overviewBudget);
    }

    private HealthReport checkOne(NodeId server, boolean includeSystemInfo) {
        List<HealthReport> reports = service().check(Optional.of(server.value()),
            includeSystemInfo);
        assertThat(reports).hasSize(1);
        return reports.get(0);
    }

    private static ToolErrorException error(ErrorCode code, NodeId server, String message) {
        return new ToolErrorException(ToolError.of(code, message, "cause", "next step")
            .withNode(server));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    // --- assembly --------------------------------------------------------------------------

    @Test
    void theReportIsAssembledFromTheFourCalls() {
        HealthReport report = checkOne(DEV, false);

        assertThat(report.node()).isEqualTo(DEV);
        assertThat(report.group().value()).isEqualTo("dev");
        assertThat(report.reachable()).isTrue();
        assertThat(report.status()).isEqualTo(HealthStatus.OK);
        assertThat(report.checkedAt()).as("the server's healthcheck clock")
            .isEqualTo(SERVER_TIME);
        assertThat(report.ready()).contains(true);
        assertThat(report.readyMessage()).contains("Ready to serve.");
        assertThat(report.maintenanceMode()).contains(false);
        assertThat(report.version()).contains("8.1.17");
        assertThat(report.systemInfo()).as("only with includeSystemInfo").isEmpty();
        assertThat(report.load()).contains(LOAD);
        assertThat(report.unavailable()).isEmpty();
        assertThat(report.error()).isEmpty();
        assertThat(report.warnings()).isEmpty();
        assertThat(monitoring(DEV).calls()).containsExactlyInAnyOrder("healthcheck", "ready",
            "systemInfo", "metrics");
        assertThat(detections).as("health never waits for a version detection (H1)")
            .hasValue(0);
    }

    @Test
    void includeSystemInfoAddsTheSystemInfo() {
        assertThat(checkOne(DEV, true).systemInfo()).contains(SYSTEM_INFO);
    }

    @Test
    void theFourCallsRunConcurrently() {
        // every call waits until all four have started; called one after the other, the first
        // call would never return
        CountDownLatch allStarted = new CountDownLatch(4);
        FakeMonitoring fake = monitoring(DEV);
        fake.confirmed = true;
        fake.barrier = allStarted;

        HealthReport report = checkOne(DEV, false);

        assertThat(allStarted.getCount()).isZero();
        assertThat(report.reachable()).isTrue();
        assertThat(report.ready()).contains(true);
        assertThat(report.version()).contains("8.1.17");
        assertThat(report.load()).isPresent();
        assertThat(report.unavailable()).isEmpty();
    }

    @Test
    void withUnconfirmedCredentialsMetricsFollowTheSystemInfo() {
        // Phase 3 review M2: wrong credentials cost one failed login, not two in parallel
        FakeMonitoring fake = monitoring(DEV);
        fake.confirmed = false;
        fake.systemInfo = () -> {
            sleepQuietly(200);
            throw error(ErrorCode.AUTH_FAILED, DEV, "INUBIT rejected the credentials (HTTP 401)");
        };
        fake.metrics = () -> {
            throw error(ErrorCode.AUTH_FAILED, DEV, "authentication failed recently; not"
                + " retried for 60 s to avoid account lockout");
        };

        HealthReport report = checkOne(DEV, false);

        List<String> events = fake.events();
        assertThat(events.indexOf("systemInfo:end")).isNotNegative()
            .isLessThan(events.indexOf("metrics:start"));
        assertThat(report.reachable()).isTrue();
        assertThat(report.unavailable()).extracting(UnavailablePart::part)
            .containsExactly(UnavailablePart.VERSION, UnavailablePart.LOAD);
    }

    @Test
    void healthcheckAndReadyDoNotWaitForTheAuthenticatedCalls() {
        FakeMonitoring fake = monitoring(DEV);
        CountDownLatch release = new CountDownLatch(1);
        fake.systemInfo = () -> {
            awaitQuietly(release);
            return SYSTEM_INFO;
        };
        CountDownLatch publicCallsDone = new CountDownLatch(2);
        fake.healthcheck = () -> {
            publicCallsDone.countDown();
            return new MonitoringPort.Healthcheck(200, HealthStatus.OK, Optional.of(false),
                Optional.of(SERVER_TIME), Optional.empty());
        };
        fake.ready = () -> {
            publicCallsDone.countDown();
            return new MonitoringPort.Readiness(true, Optional.empty());
        };
        Thread.ofVirtual().start(() -> {
            try {
                if (publicCallsDone.await(5, TimeUnit.SECONDS)) {
                    release.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        HealthReport report = checkOne(DEV, false);

        assertThat(report.version()).as("systemInfo was released by the public calls")
            .contains("8.1.17");
    }

    @Test
    void anUndetectedVersionLineGivesNoGatewayWarnings() {
        undetected.add(DEV);
        warnings.put(DEV, List.of("never shown"));

        HealthReport report = checkOne(DEV, false);

        assertThat(report.reachable()).isTrue();
        assertThat(report.warnings()).isEmpty();
        assertThat(detections).hasValue(0);
    }

    @Test
    void withoutServerTimestampTheMcpServerClockIsUsed() {
        monitoring(DEV).healthcheck = () -> new MonitoringPort.Healthcheck(200, HealthStatus.OK,
            Optional.of(false), Optional.empty(), Optional.empty());

        assertThat(checkOne(DEV, false).checkedAt()).isEqualTo(NOW);
    }

    // --- reachability ----------------------------------------------------------------------

    @Test
    void reachabilityIsDecidedByTheHealthcheck() {
        FakeMonitoring fake = monitoring(DEV);
        fake.healthcheck = () -> {
            throw error(ErrorCode.UNREACHABLE, DEV, "Cannot connect to dev/node1");
        };

        HealthReport report = checkOne(DEV, false);

        assertThat(report.reachable()).isFalse();
        assertThat(report.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(report.error()).get().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE);
            assertThat(error.node()).contains(DEV);
        });
        assertThat(report.checkedAt()).isEqualTo(NOW);
        // the other calls answered (fake), so their parts are still reported
        assertThat(report.ready()).contains(true);
        assertThat(report.version()).contains("8.1.17");
    }

    @Test
    void aHealthcheckAnswerWithoutHealthJsonIsReachableWithStatusUnavailable() {
        monitoring(DEV).healthcheck = () -> new MonitoringPort.Healthcheck(404,
            HealthStatus.UNKNOWN, Optional.empty(), Optional.empty(),
            Optional.of("the healthcheck answered HTTP 404 without the health JSON"));

        HealthReport report = checkOne(DEV, false);

        assertThat(report.reachable()).isTrue();
        assertThat(report.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(report.maintenanceMode()).isEmpty();
        assertThat(report.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailablePart.STATUS);
            assertThat(part.reason()).startsWith("UNEXPECTED_RESPONSE: ").contains("HTTP 404");
            assertThat(part.likelyCause()).get().asString().contains("baseUrl");
            assertThat(part.nextStep()).isPresent();
        });
    }

    // --- partial results (FR-008) ----------------------------------------------------------

    @Test
    void failedPartsAreUnavailableWithTheirReasonAndTheRestIsReported() {
        FakeMonitoring fake = monitoring(DEV);
        fake.ready = () -> {
            throw error(ErrorCode.UNEXPECTED_RESPONSE, DEV, "Unexpected HTTP 500 for GET ready");
        };
        fake.metrics = () -> {
            throw error(ErrorCode.AUTH_FAILED, DEV, "INUBIT rejected the credentials (HTTP 401)");
        };

        HealthReport report = checkOne(DEV, false);

        assertThat(report.reachable()).isTrue();
        assertThat(report.version()).contains("8.1.17");
        assertThat(report.ready()).isEmpty();
        assertThat(report.load()).isEmpty();
        assertThat(report.unavailable()).containsExactly(
            new UnavailablePart(UnavailablePart.READY,
                "UNEXPECTED_RESPONSE: Unexpected HTTP 500 for GET ready", Optional.of("cause"),
                Optional.of("next step")),
            new UnavailablePart(UnavailablePart.LOAD,
                "AUTH_FAILED: INUBIT rejected the credentials (HTTP 401)", Optional.of("cause"),
                Optional.of("next step")));
    }

    @Test
    void unlicensedMetricsAreMarkedAsSuch() {
        monitoring(DEV).metrics = () -> new MonitoringPort.Metrics.NotLicensed(
            "GET /ibis/rest/metrics answered HTTP 403");

        HealthReport report = checkOne(DEV, false);

        assertThat(report.load()).isEmpty();
        assertThat(report.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailablePart.LOAD);
            assertThat(part.reason())
                .isEqualTo("METRICS_NOT_LICENSED: GET /ibis/rest/metrics answered HTTP 403");
            assertThat(part.likelyCause()).get().asString().contains("license");
            assertThat(part.nextStep()).isPresent();
        });
        assertThat(report.ready()).contains(true);
    }

    @Test
    void notReadyIsReported() {
        monitoring(DEV).ready = () -> new MonitoringPort.Readiness(false,
            Optional.of("Not ready to serve."));

        HealthReport report = checkOne(DEV, false);

        assertThat(report.ready()).contains(false);
        assertThat(report.readyMessage()).contains("Not ready to serve.");
    }

    @Test
    void maintenanceModeIsStatedWithAWarning() {
        monitoring(DEV).healthcheck = () -> new MonitoringPort.Healthcheck(200, HealthStatus.OK,
            Optional.of(true), Optional.of(SERVER_TIME), Optional.empty());

        HealthReport report = checkOne(DEV, false);

        assertThat(report.maintenanceMode()).contains(true);
        assertThat(report.warnings()).singleElement().asString()
            .contains("maintenance mode", "not processing normally");
    }

    @Test
    void healthIsReportedWithoutSystemInfoAndTheGatewayWarningsAreCopied() {
        String gatewayWarning = "a warning of the gateway";
        warnings.put(DEV, List.of(gatewayWarning));
        monitoring(DEV).systemInfo = () -> {
            throw error(ErrorCode.AUTH_FAILED, DEV, "INUBIT rejected the credentials (HTTP 401)");
        };

        HealthReport report = checkOne(DEV, true);

        assertThat(report.reachable()).isTrue();
        assertThat(report.status()).isEqualTo(HealthStatus.OK);
        assertThat(report.ready()).contains(true);
        assertThat(report.load()).isPresent();
        assertThat(report.version()).isEmpty();
        assertThat(report.systemInfo()).isEmpty();
        assertThat(report.unavailable()).extracting(UnavailablePart::part)
            .containsExactly(UnavailablePart.VERSION, UnavailablePart.SYSTEM_INFO);
        assertThat(report.unavailable()).allSatisfy(part ->
            assertThat(part.reason()).startsWith("AUTH_FAILED: "));
        assertThat(report.warnings()).as("pinned V8_1: no version warning")
            .containsExactly(gatewayWarning);
    }

    @Test
    void systemInfoWithoutVersionMakesTheVersionUnavailable() {
        monitoring(DEV).systemInfo = () -> new SystemInfo(Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Map.of());

        HealthReport report = checkOne(DEV, false);

        assertThat(report.version()).isEmpty();
        assertThat(report.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailablePart.VERSION);
            assertThat(part.reason()).startsWith("UNEXPECTED_RESPONSE: ");
            assertThat(part.likelyCause()).isPresent();
            assertThat(part.nextStep()).isPresent();
        });
    }

    // --- feature 009: the version warning comes from the version gate (T013) ----------------

    private static final String UNVERIFIED_902 = "INUBIT 9.0.2 on dev/node1 is not verified for"
        + " monitoring; results may be incomplete";

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    private static SystemInfo systemInfoOf(String version) {
        return new SystemInfo(Optional.of(version), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Map.of());
    }

    private long systemInfoCalls(NodeId server) {
        return monitoring(server).calls().stream().filter("systemInfo"::equals).count();
    }

    @Test
    void aVersionOtherThan81AddsTheUnverifiedWarningDerivedFromThisRead() {
        // dev/node1 is pinned to V8_1: the warning follows what get_health read, not the pin
        monitoring(DEV).systemInfo = () -> systemInfoOf("9.0.2");

        HealthReport report = checkOne(DEV, false);

        assertThat(report.version()).contains("9.0.2");
        // T016: the read also contradicts the pin
        assertThat(report.warnings()).containsExactly(UNVERIFIED_902,
            contradiction("9.0.2"));
        assertThat(gateDetections).hasValue(0);
    }

    @Test
    void anAutoNodeIsReadOncePerCallAndWarnedAboutOnce() {
        versionLines.put(DEV, VersionSetting.AUTO);
        monitoring(DEV).systemInfo = () -> {
            // as the gateway factory's slot does for the first read of an AUTO node
            versions.detected(DEV, InubitVersion.parse("9.0.2").orElseThrow());
            return systemInfoOf("9.0.2");
        };

        HealthReport first = checkOne(DEV, false);
        assertThat(systemInfoCalls(DEV)).as("one /system/info per get_health").isEqualTo(1);
        HealthReport second = checkOne(DEV, false);

        assertThat(systemInfoCalls(DEV)).isEqualTo(2);
        assertThat(gateDetections).as("the gate never detects for get_health").hasValue(0);
        assertThat(detections).hasValue(0);
        assertThat(first.warnings()).as("no duplicate version warning")
            .containsExactly(UNVERIFIED_902);
        assertThat(second.warnings()).containsExactly(UNVERIFIED_902);
    }

    @Test
    void aFailedAutoDetectionGivesExactlyOneVersionWarning() {
        versionLines.put(DEV, VersionSetting.AUTO);
        monitoring(DEV).systemInfo = () -> {
            // as the gateway factory's slot reports a failed read of an AUTO node
            versions.detectionFailed(DEV, ErrorCode.AUTH_FAILED);
            throw error(ErrorCode.AUTH_FAILED, DEV, "INUBIT rejected the credentials (HTTP 401)");
        };

        HealthReport report = checkOne(DEV, false);

        assertThat(report.reachable()).isTrue();
        assertThat(report.version()).isEmpty();
        assertThat(report.warnings()).containsExactly("the INUBIT version of dev/node1 is"
            + " unknown (AUTH_FAILED at 2026-10-01T08:00:00Z); results are unverified");
        assertThat(report.warnings()).noneMatch(warning -> warning.contains("assuming 8.1"));
        assertThat(systemInfoCalls(DEV)).isEqualTo(1);
        assertThat(gateDetections).hasValue(0);
    }

    @Test
    void anAutoNodeOn81HasNoVersionWarning() {
        versionLines.put(DEV, VersionSetting.AUTO);
        monitoring(DEV).systemInfo = () -> {
            versions.detected(DEV, InubitVersion.parse("8.1.17").orElseThrow());
            return SYSTEM_INFO;
        };

        assertThat(checkOne(DEV, false).warnings()).isEmpty();
    }

    // --- feature 011, T007: monitoring access before contact (FR-001, research D-11) --------

    @Test
    void a61NodeIsRefusedBeforeAnyContactWhileAutoAnd81NodesAreReadAsBefore() {
        versionLines.put(DEV, VersionSetting.V6_1);
        withClient.add(DEV);
        versionLines.put(QA1, VersionSetting.AUTO);
        monitoring(QA1).systemInfo = () -> {
            versions.detected(QA1, InubitVersion.parse("8.1.17").orElseThrow());
            return SYSTEM_INFO;
        };
        // qa/node2 stays pinned to V8_1

        List<HealthReport> reports = service().check(Optional.empty(), false);

        HealthReport refused = reports.get(0);
        assertThat(refused.node()).isEqualTo(DEV);
        assertThat(refused.reachable()).isFalse();
        assertThat(refused.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(refused.error()).hasValueSatisfying(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
            assertThat(error.message()).isEqualTo("INUBIT 6.1 (versionLine V6_1) on dev/node1"
                + " does not support monitoring; use the Workbench, or another node with a"
                + " different INUBIT version; nothing was sent");
            assertThat(error.node()).contains(DEV);
        });
        assertThat(refused.client()).isEmpty();
        assertThat(refused.warnings()).isEmpty();
        // FR-001: no monitoring port, so no request; no client probe, so no StartCLI launch
        assertThat(monitorings).doesNotContainKey(DEV);
        assertThat(probeTimeouts).isEmpty();
        assertThat(credentialBounds).as("credentials prepared for the two other nodes only")
            .hasSize(2);

        for (HealthReport report : reports.subList(1, 3)) {
            assertThat(report.reachable()).isTrue();
            assertThat(report.version()).contains("8.1.17");
            assertThat(report.error()).isEmpty();
            assertThat(report.warnings()).isEmpty();
            assertThat(monitoring(report.node()).calls()).containsExactlyInAnyOrder(
                "healthcheck", "ready", "systemInfo", "metrics");
        }
        assertThat(gateDetections).hasValue(0);
    }

    @Test
    void aConcrete61PinIsRefusedBeforeAnyContact() {
        versionLines.put(DEV, VersionSetting.parse("6.1.0.89").orElseThrow());
        withClient.add(DEV);

        HealthReport report = checkOne(DEV, false);

        assertThat(report.error()).hasValueSatisfying(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
            assertThat(error.message()).contains("INUBIT 6.1.0.89", "dev/node1", "monitoring");
        });
        assertThat(monitorings).isEmpty();
        assertThat(probeTimeouts).isEmpty();
        assertThat(credentialBounds).isEmpty();
    }

    // --- feature 011, T027: get_health through StartCLI (FR-008, FR-009, research D-6) ------

    /**
     * 6.1 reached through StartCLI only, with monitoring and process diagnosis supported (as
     * once the recordings exist, T040); 8.1 fully supported as in production.
     */
    private static final CapabilityMatrix WITH_61 = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(6, 1, 6, 1),
            Map.of(Capability.MONITORING, SupportState.SUPPORTED,
                Capability.PROCESS_DIAGNOSIS, SupportState.SUPPORTED),
            Map.of(Capability.MONITORING, Set.of(Channel.CLI),
                Capability.PROCESS_DIAGNOSIS, Set.of(Channel.CLI)), true),
        new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
            Arrays.stream(Capability.values()).collect(Collectors.toMap(
                capability -> capability, capability -> SupportState.SUPPORTED)))), Map.of());
    private static final MemoryFigures MEMORY = new MemoryFigures(1210, 838, 2048);
    private static final ProcessCounts COUNTS = new ProcessCounts(3, 0, 12, 0);
    private static final List<UnavailablePart> NOT_ON_6_1 = List.of(
        new UnavailablePart(UnavailablePart.READY,
            "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 has no readiness check"),
        new UnavailablePart(UnavailablePart.THREADS,
            "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 reports no thread figures"),
        new UnavailablePart(UnavailablePart.BLOCKING_QUEUE,
            "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 reports no blocking queue"));
    private static final ClientVersion CLIENT_610 = new ClientVersion(version("6.1.0"),
        Optional.of(version("4.0.1")), Optional.of(version("6.1.0")));
    private static final String CLIENTS_VERSION = "the version 6.1.0.89 read on dev/node1 is the"
        + " StartCLI client's; the server version cannot be read";

    @TempDir
    Path temp;

    /** What one StartCLI session of a 6.1 node read: memory, counts and the 6.1 gaps. */
    private static CliHealthSnapshot snapshot(String version, boolean versionIsServer,
        boolean alive, boolean maintenance) {
        return new CliHealthSnapshot(alive, Optional.of(version), versionIsServer,
            Optional.of(maintenance), Optional.of(MEMORY), Optional.of(COUNTS), NOT_ON_6_1);
    }

    /** Pins dev/node1 to V6_1 with a StartCLI health port that answers with {@code answer}. */
    private FakeCliHealth pin61(Function<Duration, CliHealthSnapshot> answer) {
        versionLines.put(DEV, VersionSetting.V6_1);
        FakeCliHealth port = new FakeCliHealth(answer);
        cliPorts.put(DEV, port);
        return port;
    }

    private HealthReport checkOne61() {
        return checkOne61(service(WITH_61));
    }

    private static HealthReport checkOne61(HealthService service) {
        List<HealthReport> reports = service.check(Optional.of(DEV.value()), false);
        assertThat(reports).hasSize(1);
        return reports.get(0);
    }

    @Test
    void a61NodeIsReadFromOneStartCliSessionAndTheClientProbeWithoutRest() {
        FakeCliHealth port = pin61(budget -> snapshot("6.1.0.89", false, true, false));
        withClient.add(DEV);
        clients.put(DEV, () -> CLIENT_610);

        HealthReport report = checkOne61();

        assertThat(report.node()).isEqualTo(DEV);
        assertThat(report.reachable()).isTrue();
        assertThat(report.status()).isEqualTo(HealthStatus.OK);
        assertThat(report.checkedAt()).isEqualTo(NOW);
        assertThat(report.maintenanceMode()).contains(false);
        assertThat(report.version()).as("FR-006: the client's version is not the node's")
            .isEmpty();
        assertThat(report.load()).contains(LoadFigures.of(MEMORY));
        assertThat(report.processCounts()).contains(COUNTS);
        assertThat(report.ready()).isEmpty();
        assertThat(report.systemInfo()).isEmpty();
        assertThat(report.client()).contains(HealthReport.Client.of(CLIENT_610,
            ClientVersion.Comparison.NOT_COMPARABLE));
        assertThat(report.unavailable()).containsExactlyElementsOf(NOT_ON_6_1);
        assertThat(report.error()).isEmpty();
        assertThat(report.warnings()).containsExactly(CLIENTS_VERSION);
        assertThat(versions.state(DEV).observed()).as("FR-006: not observed").isEmpty();
        assertThat(port.budgets).hasSize(1);
        assertThat(probeTimeouts).as("the client probe of feature 009").hasSize(1);
        assertThat(credentialBounds).as("credentials before the session").hasSize(1);
        // no REST: no monitoring port, no gateway, no detection
        assertThat(monitorings).isEmpty();
        assertThat(detections).hasValue(0);
        assertThat(gateDetections).hasValue(0);
    }

    @Test
    void aVersionMarkedAsTheServersIsReportedAndObserved() {
        pin61(budget -> snapshot("6.1.0.89", true, true, false));

        HealthReport report = checkOne61();

        assertThat(report.version()).contains("6.1.0.89");
        assertThat(report.warnings()).isEmpty();
        assertThat(versions.state(DEV).observed().orElseThrow().value().raw())
            .isEqualTo("6.1.0.89");
        assertThat(versions.state(DEV).pinContradiction()).isFalse();
    }

    @Test
    void aNegativeAliveCheckIsErrorWhileTheOtherPartsAreStillReported() {
        pin61(budget -> snapshot("6.1.0.89", true, false, false));

        HealthReport report = checkOne61();

        assertThat(report.reachable()).as("StartCLI connected and logged in").isTrue();
        assertThat(report.status()).isEqualTo(HealthStatus.ERROR);
        assertThat(report.error()).isEmpty();
        assertThat(report.version()).contains("6.1.0.89");
        assertThat(report.maintenanceMode()).contains(false);
        assertThat(report.load()).contains(LoadFigures.of(MEMORY));
        assertThat(report.processCounts()).contains(COUNTS);
    }

    @Test
    void maintenanceModeGivesTheWarningOf81AndLeavesTheStatus() {
        pin61(budget -> snapshot("6.1.0.89", true, true, true));

        HealthReport report = checkOne61();

        assertThat(report.status()).isEqualTo(HealthStatus.OK);
        assertThat(report.maintenanceMode()).contains(true);
        assertThat(report.warnings()).containsExactly("dev/node1 is in maintenance mode and"
            + " therefore not processing normally");
    }

    @Test
    void countsTheSessionCouldNotReadAreListedAndLeaveTheStatus() {
        UnavailablePart counts = new UnavailablePart(UnavailablePart.PROCESS_COUNTS,
            "UNEXPECTED_RESPONSE: no count");
        pin61(budget -> new CliHealthSnapshot(true, Optional.of("6.1.0.89"), true,
            Optional.of(false), Optional.of(MEMORY), Optional.empty(), List.of(counts)));

        HealthReport report = checkOne61();

        assertThat(report.status()).isEqualTo(HealthStatus.OK);
        assertThat(report.processCounts()).isEmpty();
        assertThat(report.unavailable()).containsExactly(counts);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class,
        names = {"CLI_UNAVAILABLE", "AUTH_FAILED", "UNREACHABLE", "TLS_ERROR", "TIMEOUT"})
    void aSessionThatCouldNotRunIsUnknownWithItsError(ErrorCode code) {
        // a TIMEOUT before the budget is used up is the node's cliTimeout: its text stays
        pin61(budget -> {
            throw error(code, DEV, "the session failed");
        });

        HealthReport report = checkOne61();

        assertThat(report.reachable()).isFalse();
        assertThat(report.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(report.error()).hasValueSatisfying(error -> {
            assertThat(error.code()).isEqualTo(code);
            assertThat(error.message()).isEqualTo("the session failed");
        });
        assertThat(report.unavailable()).extracting(UnavailablePart::part).containsExactly(
            UnavailablePart.VERSION, UnavailablePart.LOAD, UnavailablePart.PROCESS_COUNTS);
        assertThat(report.unavailable()).extracting(UnavailablePart::reason)
            .containsOnly(code + ": the session failed");
        assertThat(report.maintenanceMode()).isEmpty();
        assertThat(report.load()).isEmpty();
        assertThat(report.processCounts()).isEmpty();
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void a61NodeWithoutStartCliIsCliUnavailableWhileTheOtherNodesReport() {
        // US1-4: dev/node1 has no StartCLI installation, so no client probe either
        pin61(budget -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
                "No CLI home is configured for dev/node1", "cliHome / cli.home is not set",
                "Set cliHome to the INUBIT client directory").withNode(DEV));
        });

        List<HealthReport> reports = service(WITH_61).check(Optional.empty(), false);

        HealthReport legacy = reports.get(0);
        assertThat(legacy.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(legacy.error()).hasValueSatisfying(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
            assertThat(error.nextStep()).contains("INUBIT client");
        });
        assertThat(legacy.client()).contains(HealthReport.Client.NOT_AVAILABLE);
        assertThat(probeTimeouts).isEmpty();
        for (HealthReport report : reports.subList(1, 3)) {
            assertThat(report.reachable()).isTrue();
            assertThat(report.status()).isEqualTo(HealthStatus.OK);
            assertThat(report.version()).contains("8.1.17");
            assertThat(report.error()).isEmpty();
        }
    }

    @Test
    void aServerVersionContradictingThe61PinIsReportedUntilA61ReadClearsIt() {
        // US1-7, FR-006: while contradicted, every tool but get_health is refused on the node
        FakeCliHealth port = pin61(budget -> snapshot("8.1.17", true, true, false));
        VersionGate gate = gateWithoutClients(versions, new FakeVersionDetection(versions),
            WITH_61);

        HealthReport contradicted = checkOne61();

        assertThat(contradicted.status()).isEqualTo(HealthStatus.OK);
        assertThat(contradicted.version()).contains("8.1.17");
        assertThat(contradicted.warnings()).containsExactly("versionLine V6_1 of dev/node1"
            + " contradicts INUBIT 8.1.17 read by get_health; every tool except get_health is"
            + " refused on dev/node1 until get_health reads a matching version or versionLine is"
            + " corrected and the server restarted");
        assertThat(versions.state(DEV).pinContradiction()).isTrue();
        assertThatThrownBy(() -> gate.admit(DEV, Capability.PROCESS_DIAGNOSIS,
            Capability.Kind.READ, Capability.PROCESS_DIAGNOSIS.channels()))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
                assertThat(e.error().message()).contains("V6_1", "8.1.17");
            });

        port.answer = budget -> snapshot("6.1.0.89", true, true, false);
        HealthReport cleared = checkOne61();

        assertThat(cleared.warnings()).isEmpty();
        assertThat(versions.state(DEV).pinContradiction()).isFalse();
        assertThat(gate.admit(DEV, Capability.PROCESS_DIAGNOSIS, Capability.Kind.READ,
            Capability.PROCESS_DIAGNOSIS.channels()).warnings())
            .as("find_processes is admitted again").isEmpty();
        assertThat(port.budgets).hasSize(2);
        assertThat(monitorings).isEmpty();
    }

    @Test
    void aPendingCredentialFetchStartsNoSession() {
        FakeCliHealth port = pin61(budget -> snapshot("6.1.0.89", true, true, false));
        credentials.put(DEV, bound -> CredentialState.PENDING);

        HealthReport report = checkOne61();

        assertThat(report.error()).get().extracting(ToolError::code)
            .isEqualTo(ErrorCode.TIMEOUT);
        assertThat(port.budgets).isEmpty();
        assertThat(credentialBounds).singleElement().satisfies(bound -> assertThat(bound)
            .as("within the budget of a single CLI-only node")
            .isGreaterThan(Duration.ofSeconds(13))
            .isLessThanOrEqualTo(Duration.ofMillis(14_100)));
    }

    @Test
    void aFailedCredentialFetchStartsNoSession() {
        FakeCliHealth port = pin61(budget -> snapshot("6.1.0.89", true, true, false));
        credentials.put(DEV, bound -> {
            throw error(ErrorCode.UNREACHABLE, DEV, "AWS Parameter Store cannot be reached");
        });

        HealthReport report = checkOne61();

        assertThat(report.error()).get().extracting(ToolError::code)
            .isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(port.budgets).isEmpty();
    }

    @Test
    void aRejectedLoginIsTriedOnceWithinSixtySecondsAndTheSecondReportSaysWhy() {
        // US1-6 with the real 6.1 adapter: the credential guard of its StartCLI runs
        versionLines.put(DEV, VersionSetting.V6_1);
        FakeV61Node node = new FakeV61Node(DEV, temp,
            FakeProcessLauncher.replaying("v6_1", "login_failed"), Duration.ofSeconds(10), clock);
        cliPorts.put(DEV, node.adapter());
        HealthService service = service(WITH_61);

        HealthReport first = checkOne61(service);
        clock.advance(Duration.ofSeconds(30));
        HealthReport second = checkOne61(service);

        assertThat(first.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(first.error()).get().extracting(ToolError::code)
            .isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(second.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(second.error()).hasValueSatisfying(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(error.message()).contains("authentication failed recently",
                "not retried");
        });
        assertThat(node.session.launchCount()).as("one login in 60 s").isEqualTo(1);
        assertThat(List.of(first, second).toString()).doesNotContain(FakeV61Node.PASSWORD);
    }

    @Test
    void aCallStartsAtMostThreeStartCliProcessesBesidesTheClientProbe() {
        // FR-009 with the real 6.1 adapter: the option probe (cached) and one session
        versionLines.put(DEV, VersionSetting.V6_1);
        withClient.add(DEV);
        clients.put(DEV, () -> CLIENT_610);
        FakeV61Node node = new FakeV61Node(DEV, temp,
            FakeProcessLauncher.replaying("v6_1", "health_session_ok"), Duration.ofSeconds(10),
            clock);
        cliPorts.put(DEV, node.adapter());
        HealthService service = service(WITH_61);

        HealthReport first = checkOne61(service);
        int firstLaunches = node.launches();
        HealthReport second = checkOne61(service);
        int secondLaunches = node.launches() - firstLaunches;

        assertThat(first.status()).isEqualTo(HealthStatus.OK);
        assertThat(first.processCounts()).contains(COUNTS);
        assertThat(first.load()).contains(LoadFigures.of(MEMORY));
        assertThat(first.warnings()).containsExactly(CLIENTS_VERSION);
        assertThat(second.status()).isEqualTo(HealthStatus.OK);
        assertThat(firstLaunches).isLessThanOrEqualTo(3).isEqualTo(2);
        assertThat(secondLaunches).as("the option probe is cached").isEqualTo(1);
        assertThat(probeTimeouts).as("the client probe, once, besides").hasSize(1);
    }

    @Test
    void aSingle61NodeGetsFifteenSecondsAndAnOverviewTenAtMost() {
        // FR-009: each bound less the fan-out grace, so the call ends within the bound
        FakeCliHealth port = pin61(budget -> snapshot("6.1.0.89", true, true, false));

        checkOne61();
        service(WITH_61).check(Optional.empty(), false);

        assertThat(HealthService.CLI_ONLY_SINGLE_NODE_BUDGET).isEqualTo(Duration.ofSeconds(15));
        assertThat(HealthService.OVERVIEW_BUDGET).isEqualTo(Duration.ofSeconds(10));
        assertThat(port.budgets).hasSize(2);
        assertThat(port.budgets.get(0)).as("single node")
            .isGreaterThan(Duration.ofSeconds(13)).isLessThanOrEqualTo(Duration.ofMillis(14_100));
        assertThat(port.budgets.get(1)).as("overview")
            .isGreaterThan(Duration.ofSeconds(8)).isLessThanOrEqualTo(Duration.ofMillis(9_100));
    }

    @Test
    void aHanging61SessionInAnOverviewIsTimeoutWithinTheBudgetWhileTheOtherNodesReport() {
        // scaled budgets: an overview of 2 s; the session ignores the cancellation
        CountDownLatch release = new CountDownLatch(1);
        pin61(budget -> {
            awaitUninterruptibly(release);
            return snapshot("6.1.0.89", true, true, false);
        });
        HealthService service = service(WITH_61, Duration.ofSeconds(3), Duration.ofSeconds(2));
        try {
            long start = System.nanoTime();
            List<HealthReport> reports = service.check(Optional.empty(), false);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
            HealthReport legacy = reports.get(0);
            assertThat(legacy.reachable()).isFalse();
            assertThat(legacy.status()).isEqualTo(HealthStatus.UNKNOWN);
            assertThat(legacy.error()).hasValueSatisfying(error -> {
                assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
                assertThat(error.message()).isEqualTo("The StartCLI health session of dev/node1"
                    + " did not finish within the get_health budget of 1.1 s");
                assertThat(error.nextStep()).contains("get_health for dev/node1 alone")
                    .doesNotContain("cliTimeout");
            });
            assertThat(legacy.unavailable()).extracting(UnavailablePart::part).containsExactly(
                UnavailablePart.VERSION, UnavailablePart.LOAD, UnavailablePart.PROCESS_COUNTS);
            assertThat(legacy.unavailable()).allSatisfy(part ->
                assertThat(part.reason()).startsWith("TIMEOUT: The StartCLI health session"));
            for (HealthReport report : reports.subList(1, 3)) {
                assertThat(report.status()).isEqualTo(HealthStatus.OK);
                assertThat(report.version()).contains("8.1.17");
            }
        } finally {
            release.countDown();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // --- feature 009: get_health observes the version of every node (T016, FR-012) ---------

    /** The warning about the pin V8_1 of dev/node1 contradicted by a read of {@code raw}. */
    private static String contradiction(String raw) {
        return "versionLine V8_1 of dev/node1 contradicts INUBIT " + raw + " read by get_health;"
            + " writes on dev/node1 are refused until get_health reads a matching version or"
            + " versionLine is corrected and the server restarted";
    }

    @Test
    void aPinnedNodeObservingAnotherMajorMinorIsContradictedUntilAMatchingRead() {
        // dev/node1 is pinned to V8_1
        monitoring(DEV).systemInfo = () -> systemInfoOf("9.0.7");

        HealthReport contradicted = checkOne(DEV, false);

        assertThat(contradicted.warnings()).containsExactly("INUBIT 9.0.7 on dev/node1 is not"
            + " verified for monitoring; results may be incomplete", contradiction("9.0.7"));
        assertThat(versions.state(DEV).pinContradiction()).isTrue();
        assertThat(versions.state(DEV).observed().orElseThrow().value().raw())
            .isEqualTo("9.0.7");

        monitoring(DEV).systemInfo = () -> SYSTEM_INFO;
        HealthReport matching = checkOne(DEV, false);

        assertThat(matching.warnings()).isEmpty();
        assertThat(versions.state(DEV).pinContradiction()).isFalse();
        assertThat(gateDetections).hasValue(0);
    }

    @Test
    void thePinWarningFollowsThisCallsReadNotTheFlagOfAnOverlappingCall() {
        // the warning takes only the setting and this call's read: the flag in NodeVersions may
        // already carry the read of another get_health call on the same node
        assertThat(HealthService.pinWarning(DEV, VersionSetting.V8_1, version("9.0.7")))
            .contains(contradiction("9.0.7"));
        assertThat(HealthService.pinWarning(DEV, VersionSetting.V8_1, version("8.1.18")))
            .isEmpty();
        assertThat(HealthService.pinWarning(DEV, VersionSetting.AUTO, version("9.0.7")))
            .isEmpty();
        assertThat(HealthService.pinWarning(DEV, VersionSetting.parse("8.1.17").orElseThrow(),
            version("8.1.18"))).get().asString().contains("in the patch only");
    }

    @Test
    void aPinnedPatchDifferenceOnlyWarns() {
        versionLines.put(DEV, VersionSetting.parse("8.1.17").orElseThrow());
        monitoring(DEV).systemInfo = () -> systemInfoOf("8.1.18");

        HealthReport report = checkOne(DEV, false);

        assertThat(report.warnings()).containsExactly("INUBIT 8.1.18 on dev/node1 differs from"
            + " its versionLine 8.1.17 in the patch only; writes stay allowed, but correct"
            + " versionLine of dev/node1");
        assertThat(versions.state(DEV).pinContradiction()).isFalse();
    }

    @Test
    void aMatchingConcretePinGivesNoWarning() {
        versionLines.put(DEV, VersionSetting.parse("8.1.17").orElseThrow());

        assertThat(checkOne(DEV, false).warnings()).isEmpty();
        assertThat(versions.state(DEV).observed()).isPresent();
    }

    @Test
    void anAutoNodeRecordsTheObservationWithoutContradiction() {
        versionLines.put(DEV, VersionSetting.AUTO);
        monitoring(DEV).systemInfo = () -> systemInfoOf("9.0.7");

        checkOne(DEV, false);

        assertThat(versions.state(DEV).observed().orElseThrow().value().raw())
            .isEqualTo("9.0.7");
        assertThat(versions.state(DEV).pinContradiction()).isFalse();
    }

    @Test
    void aFailedOrUnparsableReadRecordsNoObservation() {
        monitoring(DEV).systemInfo = () -> {
            throw error(ErrorCode.AUTH_FAILED, DEV, "INUBIT rejected the credentials (HTTP 401)");
        };
        monitoring(QA1).systemInfo = () -> systemInfoOf("unknown");

        checkOne(DEV, false);
        checkOne(QA1, false);

        assertThat(versions.state(DEV).observed()).isEmpty();
        assertThat(versions.state(QA1).observed()).isEmpty();
    }

    // --- client probe (feature 009, FR-002, FR-011) ----------------------------------------

    private static final String CLIENT_MISMATCH = "StartCLI client 8.1.17 on dev/node1 differs"
        + " from INUBIT 8.1.18 (mismatch); StartCLI-backed tools still run, but their results"
        + " may be unreliable";

    @Test
    void theClientIsProbedOnceWithinTheBudgetAndReportedWithItsRangeAndComparison() {
        withClient.add(DEV);
        versionLines.put(DEV, VersionSetting.parse("8.1.17").orElseThrow());

        HealthReport first = checkOne(DEV, false);
        HealthReport second = checkOne(DEV, false);

        for (HealthReport report : List.of(first, second)) {
            assertThat(report.client()).contains(new HealthReport.Client(Optional.of("8.1.17"),
                Optional.of("4.0.1"), Optional.of("8.1.17"), Optional.of("match"),
                Optional.empty()));
            assertThat(report.unavailable()).isEmpty();
            assertThat(report.warnings()).isEmpty();
        }
        assertThat(probeTimeouts).as("the second get_health reuses the result").singleElement()
            .satisfies(timeout -> assertThat(timeout).isPositive()
                .isLessThanOrEqualTo(TIMEOUT));
        assertThat(versions.state(DEV).client().orElseThrow().value()).isEqualTo(CLIENT_8117);
    }

    @Test
    void anOlderClientThanTheServerIsAMismatchWithAWarning() {
        withClient.add(DEV);
        monitoring(DEV).systemInfo = () -> systemInfoOf("8.1.18");

        HealthReport report = checkOne(DEV, false);

        assertThat(report.client()).get().satisfies(client -> {
            assertThat(client.version()).contains("8.1.17");
            assertThat(client.comparison()).contains("mismatch");
        });
        assertThat(report.warnings()).containsExactly(CLIENT_MISMATCH);
    }

    @Test
    void aServerOutsideTheClientRangeIsAMismatchWhoseStartCliToolsAreRefused() {
        // FR-011: an 8.1 client declares 4.0.1 to 8.1.17, so 9.0.7 lies outside its range
        withClient.add(DEV);
        versionLines.put(DEV, VersionSetting.AUTO);
        monitoring(DEV).systemInfo = () -> systemInfoOf("9.0.7");

        HealthReport report = checkOne(DEV, false);

        assertThat(report.client()).get().extracting(HealthReport.Client::comparison)
            .isEqualTo(Optional.of("mismatch"));
        assertThat(report.warnings()).contains("StartCLI client 8.1.17 of dev/node1 supports"
            + " INUBIT 4.0.1 to 8.1.17, which excludes INUBIT 9.0.7; StartCLI-backed tools are"
            + " refused on dev/node1 (UNSUPPORTED_VERSION) until a StartCLI client that"
            + " supports INUBIT 9.0 is installed");
        assertThat(report.warnings()).noneMatch(warning -> warning.contains("still run"));
    }

    @Test
    void anUnknownServerVersionIsNotComparableWithoutWarning() {
        withClient.add(DEV);
        versionLines.put(DEV, VersionSetting.AUTO);
        monitoring(DEV).systemInfo = () -> {
            throw error(ErrorCode.AUTH_FAILED, DEV, "INUBIT rejected the credentials (HTTP 401)");
        };

        HealthReport report = checkOne(DEV, false);

        assertThat(report.client()).get().extracting(HealthReport.Client::comparison)
            .isEqualTo(Optional.of("not_comparable"));
        assertThat(report.warnings()).noneMatch(warning -> warning.contains("StartCLI client"));
    }

    @Test
    void aNodeWithoutStartCliReportsTheClientAsNotAvailableAndRunsNoProbe() {
        HealthReport report = checkOne(DEV, false);

        assertThat(report.client()).contains(HealthReport.Client.NOT_AVAILABLE);
        assertThat(report.client().orElseThrow().state()).contains("not_available");
        assertThat(report.unavailable()).isEmpty();
        assertThat(probeTimeouts).isEmpty();
        assertThat(versions.state(DEV).client()).isEmpty();
        assertThat(versions.state(DEV).clientFailure()).isEmpty();
    }

    @Test
    void aFailedProbeIsUnavailableNotCachedAndRetriedAfterSixtySeconds() {
        withClient.add(DEV);
        clients.put(DEV, () -> {
            throw error(ErrorCode.CLI_UNAVAILABLE, DEV, "StartCLI did not report its version"
                + " (exit code 1)");
        });

        HealthReport failed = checkOne(DEV, false);

        assertThat(failed.client()).isEmpty();
        assertThat(failed.version()).contains("8.1.17");
        assertThat(failed.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailablePart.CLIENT);
            assertThat(part.reason()).isEqualTo("CLI_UNAVAILABLE: StartCLI did not report its"
                + " version (exit code 1)");
            assertThat(part.nextStep()).contains("next step");
        });
        assertThat(versions.state(DEV).clientFailure().orElseThrow().value())
            .isEqualTo(ErrorCode.CLI_UNAVAILABLE);

        clock.advance(Duration.ofSeconds(30));
        HealthReport backOff = checkOne(DEV, false);

        assertThat(probeTimeouts).as("no probe within 60 s of the failure").hasSize(1);
        assertThat(backOff.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailablePart.CLIENT);
            assertThat(part.reason()).isEqualTo("CLI_UNAVAILABLE: the StartCLI client probe"
                + " of dev/node1 failed at 2026-10-01T08:00:00Z; it is retried from"
                + " 2026-10-01T08:01:00Z");
        });

        clock.advance(Duration.ofSeconds(31));
        clients.remove(DEV);
        HealthReport retried = checkOne(DEV, false);

        assertThat(probeTimeouts).as("retried after the back-off").hasSize(2);
        assertThat(retried.unavailable()).isEmpty();
        assertThat(retried.client()).get().extracting(HealthReport.Client::version)
            .isEqualTo(Optional.of("8.1.17"));
        assertThat(versions.state(DEV).clientFailure()).isEmpty();
    }

    @Test
    void aHangingProbeIsNotYetDetectedWithinTheServerTimeoutPlusOneSecond() {
        withClient.add(DEV);
        CountDownLatch never = new CountDownLatch(1);
        clients.put(DEV, () -> {
            awaitQuietly(never);
            return CLIENT_8117;
        });

        long start = System.nanoTime();
        HealthReport report = checkOne(DEV, false);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(TIMEOUT.plus(SLACK));
        assertThat(report.reachable()).isTrue();
        assertThat(report.version()).contains("8.1.17");
        assertThat(report.load()).contains(LOAD);
        assertThat(report.client()).isEmpty();
        assertThat(report.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailablePart.CLIENT);
            assertThat(part.reason()).isEqualTo("TIMEOUT: not yet detected (timeout); the"
                + " StartCLI client of dev/node1 did not report its version within 1 s");
            assertThat(part.nextStep()).isPresent();
        });
        assertThat(versions.state(DEV).clientFailure().orElseThrow().value())
            .isEqualTo(ErrorCode.TIMEOUT);
    }

    @Test
    void aProbeTimeoutInTheBackOffIsStillNotYetDetected() {
        withClient.add(DEV);
        clients.put(DEV, () -> {
            throw error(ErrorCode.TIMEOUT, DEV, "StartCLI did not finish within 1 s");
        });
        checkOne(DEV, false);

        HealthReport report = checkOne(DEV, false);

        assertThat(probeTimeouts).hasSize(1);
        assertThat(report.unavailable()).singleElement().extracting(UnavailablePart::reason)
            .isEqualTo("TIMEOUT: not yet detected (timeout); the StartCLI client probe of"
                + " dev/node1 failed at 2026-10-01T08:00:00Z; it is retried from"
                + " 2026-10-01T08:01:00Z");
    }

    @Test
    void neitherTheClientReportNorItsUnavailableTextsExposeUrlsOrCredentials() {
        // FR-016: the StartCLI output excerpt of a failed probe is not copied into the report
        withClient.add(DEV);
        withClient.add(QA1);
        clients.put(DEV, () -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
                    "StartCLI did not report its version (exit code 1)", "cause", "next step")
                .withNode(DEV)
                .withExcerpt("login jdoe/s3cret-pw at https://inubit.example:8443/ibis"));
        });

        List<HealthReport> reports = service().check(Optional.empty(), true);

        assertThat(reports.get(0).unavailable()).extracting(UnavailablePart::part)
            .containsExactly(UnavailablePart.CLIENT);
        assertThat(reports.get(1).client()).isPresent();
        assertThat(reports.toString()).doesNotContain("inubit.example", "https://", "jdoe",
            "s3cret-pw");
    }

    // --- targets ---------------------------------------------------------------------------

    @Test
    void omittedTargetChecksAllServersInConfigOrder() {
        List<HealthReport> reports = service().check(Optional.empty(), false);

        assertThat(reports).extracting(HealthReport::node).containsExactly(DEV, QA1, QA2);
    }

    @Test
    void aStageChecksItsServers() {
        List<HealthReport> reports = service().check(Optional.of("qa"), false);

        assertThat(reports).extracting(HealthReport::node).containsExactly(QA1, QA2);
    }

    @Test
    void anUnknownTargetIsEnvironmentUnknownListingTheIds() {
        assertThatThrownBy(() -> service().check(Optional.of("staging/bogus"), false))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
                assertThat(e.error().message())
                    .contains("dev", "dev/node1", "qa", "qa/node1", "qa/node2");
            });
        assertThat(monitorings).isEmpty();
    }

    @Test
    void aGatewayFailureIsAReportOfThatServerOnly() {
        gatewayFailures.put(QA1, error(ErrorCode.TLS_ERROR, QA1, "The trust store is unusable"));

        List<HealthReport> reports = service().check(Optional.empty(), false);

        assertThat(reports).extracting(HealthReport::node).containsExactly(DEV, QA1, QA2);
        HealthReport failed = reports.get(1);
        assertThat(failed.reachable()).isFalse();
        assertThat(failed.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(failed.error()).get().extracting(ToolError::code)
            .isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(failed.checkedAt()).isEqualTo(NOW);
        assertThat(reports.get(0).reachable()).isTrue();
        assertThat(reports.get(2).reachable()).isTrue();
    }

    // --- deadlines (SC-002) ----------------------------------------------------------------

    @Test
    void aHangingPartIsATimeoutWithinTheServerTimeoutPlusOneSecond() {
        CountDownLatch never = new CountDownLatch(1);
        monitoring(DEV).metrics = () -> {
            awaitQuietly(never);
            return new MonitoringPort.Metrics.Available(LOAD);
        };

        long start = System.nanoTime();
        HealthReport report = checkOne(DEV, false);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(TIMEOUT.plus(SLACK));
        assertThat(report.reachable()).isTrue();
        assertThat(report.version()).contains("8.1.17");
        assertThat(report.load()).isEmpty();
        assertThat(report.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailablePart.LOAD);
            assertThat(part.reason()).isEqualTo("TIMEOUT: No answer from dev/node1 to the"
                + " metrics call within 1 s");
            assertThat(part.nextStep()).isPresent();
        });
    }

    @Test
    void aSerializedMetricsTimeoutReportsTheRemainingBudget() {
        // Phase 3 re-review N2: unconfirmed credentials run /metrics after /system/info, so
        // its share of the budget is what /system/info left over
        FakeMonitoring fake = monitoring(DEV);
        fake.confirmed = false;
        fake.systemInfo = () -> {
            sleepQuietly(600);
            return SYSTEM_INFO;
        };
        CountDownLatch never = new CountDownLatch(1);
        fake.metrics = () -> {
            awaitQuietly(never);
            return new MonitoringPort.Metrics.Available(LOAD);
        };

        HealthReport report = checkOne(DEV, false);

        assertThat(report.version()).contains("8.1.17");
        assertThat(report.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailablePart.LOAD);
            java.util.regex.Matcher reason = java.util.regex.Pattern.compile(
                "TIMEOUT: No answer from dev/node1 to the metrics call within the remaining"
                    + " (\\d+) ms of the 1 s budget \\(it ran after the system information"
                    + " call because the credentials are not confirmed yet\\)")
                .matcher(part.reason());
            assertThat(reason.matches()).as(part.reason()).isTrue();
            assertThat(Integer.parseInt(reason.group(1))).as("not the full budget")
                .isBetween(1, 450);
        });
    }

    @Test
    void aMetricsCallThatNeverStartedSaysItWaitedForTheSystemInfo() {
        FakeMonitoring fake = monitoring(DEV);
        fake.confirmed = false;
        CountDownLatch never = new CountDownLatch(1);
        fake.systemInfo = () -> {
            awaitQuietly(never);
            return SYSTEM_INFO;
        };

        HealthReport report = checkOne(DEV, false);

        assertThat(report.unavailable()).extracting(UnavailablePart::part)
            .containsExactly(UnavailablePart.VERSION, UnavailablePart.LOAD);
        assertThat(report.unavailable().get(1).reason()).isEqualTo("TIMEOUT: The metrics call"
            + " to dev/node1 did not start within 1 s: it waits for the system information"
            + " call while the credentials are not confirmed yet");
        assertThat(fake.calls()).as("no authenticated call after the deadline")
            .doesNotContain("metrics");
    }

    @Test
    void aHangingHealthcheckIsAnUnreachableTimeoutWhileOtherServersAreReported() {
        CountDownLatch never = new CountDownLatch(1);
        monitoring(QA1).healthcheck = () -> {
            awaitQuietly(never);
            throw new AssertionError("unreachable");
        };

        long start = System.nanoTime();
        List<HealthReport> reports = service().check(Optional.empty(), false);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(TIMEOUT.plus(SLACK));
        assertThat(reports).extracting(HealthReport::reachable).containsExactly(true, false,
            true);
        assertThat(reports.get(1).error()).get().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(error.message()).isEqualTo("No answer from qa/node1 to the"
                + " healthcheck within 1 s");
        });
    }

    @Test
    void aHangingGatewayStillGivesAReportWithinTheBound() {
        gatewayBlocks.put(QA2, new CountDownLatch(1));

        long start = System.nanoTime();
        List<HealthReport> reports = service().check(Optional.empty(), false);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(TIMEOUT.plus(SLACK));
        assertThat(reports).hasSize(3);
        assertThat(reports.get(2).reachable()).isFalse();
        assertThat(reports.get(2).error()).get().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(error.message()).isEqualTo("No answer from qa/node2 while preparing"
                + " the connection within 1 s");
        });
    }

    // --- feature 010 T014: credentials from AWS Parameter Store (R-9) ------------------------

    /** The answer of a fetch that does not end within the bound: waits for it, then PENDING. */
    private static CredentialState pendingAfter(Duration bound) {
        sleepQuietly(bound.toMillis());
        return CredentialState.PENDING;
    }

    @Test
    void pendingCredentialsAreATimeoutReportWithinTheBudgetWithoutAnyCallToThatServer() {
        credentials.put(QA1, HealthServiceTest::pendingAfter);

        long start = System.nanoTime();
        List<HealthReport> reports = service().check(Optional.empty(), false);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).as("SC-002").isLessThan(TIMEOUT.plus(SLACK));
        assertThat(reports).extracting(HealthReport::node).containsExactly(DEV, QA1, QA2);
        assertThat(reports).extracting(HealthReport::reachable).containsExactly(true, false,
            true);
        HealthReport pending = reports.get(1);
        assertThat(pending.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(pending.unavailable()).isEmpty();
        assertThat(pending.warnings()).isEmpty();
        assertThat(pending.error()).get().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(error.message()).isEqualTo("qa/node1: credentials not yet available"
                + " (still fetching from AWS Parameter Store); call get_health again shortly");
            assertThat(error.node()).contains(QA1);
            assertThat(error.nextStep()).isNotBlank();
        });
        assertThat(monitorings).as("neither monitoring() nor knownGateway() for qa/node1")
            .doesNotContainKey(QA1).containsKeys(DEV, QA2);
        assertThat(credentialBounds).as("each bound is what is left of the 1 s budget")
            .hasSize(3).allSatisfy(bound -> assertThat(bound)
                .isLessThanOrEqualTo(TIMEOUT).isGreaterThan(TIMEOUT.minusMillis(500)));
    }

    @Test
    void aFailedFetchIsAReportWithItsClassifiedError() {
        credentials.put(DEV, bound -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                "dev/node1: INUBIT credentials from AWS Parameter Store (eu-central-1) are"
                    + " unavailable: no AWS credentials", "cause", "next step"));
        });

        HealthReport report = checkOne(DEV, false);

        assertThat(report.reachable()).isFalse();
        assertThat(report.error()).get().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(error.message()).endsWith("no AWS credentials");
            assertThat(error.node()).as("the server id is added").contains(DEV);
        });
        assertThat(monitorings).isEmpty();
    }

    @Test
    void afterASlowFirstFetchTheNextCallReportsNormally() throws Exception {
        // a store that answers after 1.5 s, longer than the 1 s budget; the fetch continues
        CompletableFuture<Void> fetch = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean started =
            new java.util.concurrent.atomic.AtomicBoolean();
        credentials.put(DEV, bound -> {
            if (started.compareAndSet(false, true)) {
                CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS)
                    .execute(() -> fetch.complete(null));
            }
            try {
                fetch.get(bound.toNanos(), TimeUnit.NANOSECONDS);
                return CredentialState.READY;
            } catch (java.util.concurrent.TimeoutException e) {
                return CredentialState.PENDING;
            } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(e);
            }
        });

        long start = System.nanoTime();
        HealthReport first = checkOne(DEV, false);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(TIMEOUT.plus(SLACK));
        assertThat(first.reachable()).isFalse();
        assertThat(first.error()).get().extracting(ToolError::code)
            .isEqualTo(ErrorCode.TIMEOUT);
        assertThat(monitorings).isEmpty();

        fetch.get(5, TimeUnit.SECONDS);
        HealthReport next = checkOne(DEV, false);

        assertThat(next.reachable()).isTrue();
        assertThat(next.version()).contains("8.1.17");
        assertThat(next.load()).contains(LOAD);
        assertThat(next.error()).isEmpty();
    }

    // --- fakes -----------------------------------------------------------------------------

    private record FakeGateway(NodeId node, List<String> warnings, MonitoringPort monitoring)
        implements Gateway {

        @Override
        public AdapterLine adapterLine() {
            return AdapterLine.V8_1;
        }

        @Override
        public Optional<String> detectedVersion() {
            return Optional.empty();
        }

        @Override
        public ProcessQueryPort processes() {
            throw new AssertionError("get_health does not query processes");
        }

        @Override
        public LogPort logs() {
            throw new AssertionError("get_health does not query logs");
        }

        @Override
        public InventoryPort inventory() {
            throw new AssertionError("get_health does not read the inventory");
        }

        @Override
        public ProcessControlPort processControl() {
            throw new AssertionError("get_health does not control processes");
        }
    }

    /** Answers like a healthy 8.1.17 server unless a call is replaced. */
    private static final class FakeMonitoring implements MonitoringPort {

        volatile Supplier<Healthcheck> healthcheck = () -> new Healthcheck(200, HealthStatus.OK,
            Optional.of(false), Optional.of(SERVER_TIME), Optional.empty());
        volatile Supplier<Readiness> ready = () -> new Readiness(true,
            Optional.of("Ready to serve."));
        volatile Supplier<SystemInfo> systemInfo = () -> SYSTEM_INFO;
        volatile Supplier<Metrics> metrics = () -> new Metrics.Available(LOAD);
        volatile CountDownLatch barrier;
        volatile boolean confirmed = true;
        private final List<String> calls = new ArrayList<>();
        private final List<String> events = new ArrayList<>();

        synchronized List<String> calls() {
            return List.copyOf(calls);
        }

        synchronized List<String> events() {
            return List.copyOf(events);
        }

        @Override
        public boolean credentialsConfirmed() {
            return confirmed;
        }

        private <T> T call(String name, Supplier<T> supplier) {
            synchronized (this) {
                calls.add(name);
                events.add(name + ":start");
            }
            try {
                return barrierThen(supplier);
            } finally {
                synchronized (this) {
                    events.add(name + ":end");
                }
            }
        }

        private <T> T barrierThen(Supplier<T> supplier) {
            CountDownLatch latch = barrier;
            if (latch != null) {
                latch.countDown();
                try {
                    if (!latch.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the calls do not run concurrently");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return supplier.get();
        }

        @Override
        public Healthcheck healthcheck() {
            return call("healthcheck", healthcheck);
        }

        @Override
        public Readiness ready() {
            return call("ready", ready);
        }

        @Override
        public SystemInfo systemInfo() {
            return call("systemInfo", systemInfo);
        }

        @Override
        public Metrics metrics() {
            return call("metrics", metrics);
        }
    }

    /** A StartCLI health port that records each budget and answers with {@link #answer}. */
    private static final class FakeCliHealth implements CliHealthPort {

        volatile Function<Duration, CliHealthSnapshot> answer;
        final List<Duration> budgets = java.util.Collections.synchronizedList(new ArrayList<>());

        FakeCliHealth(Function<Duration, CliHealthSnapshot> answer) {
            this.answer = answer;
        }

        @Override
        public CliHealthSnapshot snapshot(Duration budget) {
            budgets.add(budget);
            return answer.apply(budget);
        }
    }
}
