package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.testing.FakeVersionDetection.gateWithoutClients;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.HealthReport;
import de.dadecker.inubit.mcp.domain.model.HealthStatus;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LoadFigures;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeSummary;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ClientSelectionPort;
import de.dadecker.inubit.mcp.domain.port.ClientVersionPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Feature 017 (T021, research D-6, FR-017): {@code get_health} reports the StartCLI client chosen
 * for the version this call read, probing it within the remaining budget when it is not the
 * client probed at the start.
 */
@Timeout(30)
class HealthServiceClientKeyTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    private static final InubitVersion V8_1_17 = version("8.1.17");
    private static final InubitVersion V9_0_7 = version("9.0.7");
    private static final ClientKey KEY_81 = new ClientKey("/opt/acme/startcli-8.1", "");
    private static final ClientKey KEY_90 = new ClientKey("/opt/acme/startcli-9.0", "");
    private static final ClientVersion CLIENT_81 = new ClientVersion(V8_1_17,
        Optional.of(version("4.0.1")), Optional.of(V8_1_17));
    private static final ClientVersion CLIENT_90 = new ClientVersion(V9_0_7,
        Optional.of(version("9.0.0")), Optional.of(V9_0_7));

    private final MutableClock clock = new MutableClock(NOW);
    private final NodeVersions versions = new NodeVersions(clock, node -> VersionSetting.AUTO,
        node -> Duration.ofMinutes(10));
    /** The version the next {@code /system/info} read returns. */
    private final AtomicReference<String> serverVersion = new AtomicReference<>("8.1.17");
    /** Each probe: the version it chose its client by. */
    private final List<Optional<InubitVersion>> probes = new CopyOnWriteArrayList<>();
    /** Runs at the start of each probe of the 8.1 client (e.g. blocks it). */
    private volatile Runnable before81 = () -> { };
    private volatile Duration timeout = Duration.ofSeconds(1);

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    private static ClientKey keyOf(Optional<InubitVersion> version) {
        return version.map(v -> v.major() >= 9 ? KEY_90 : KEY_81).orElse(ClientKey.NONE);
    }

    private final ClientSelectionPort selection = new ClientSelectionPort() {
        @Override
        public Optional<ClientKey> current(NodeId node, Optional<InubitVersion> version) {
            return Optional.of(keyOf(version));
        }

        @Override
        public boolean available(NodeId node, Optional<InubitVersion> version) {
            return true;
        }

        @Override
        public boolean anyCandidate(NodeId node) {
            return true;
        }

        @Override
        public Optional<String> warning(NodeId node, Optional<InubitVersion> version) {
            return Optional.empty();
        }

        @Override
        public NodeSummary.StartCli startCli(NodeId node, Optional<InubitVersion> version) {
            return NodeSummary.StartCli.none(version.isPresent());
        }
    };

    private final ClientVersionPort probe = (node, version, bound) -> {
        probes.add(version);
        ClientKey key = keyOf(version);
        if (key.equals(KEY_81)) {
            before81.run();
            if (bound.isZero()) {
                throw new AssertionError("no probe without budget");
            }
            return new ProbedClient(KEY_81, CLIENT_81);
        }
        return new ProbedClient(KEY_90, CLIENT_90);
    };

    private HealthService service() {
        GatewayFactory gateways = new GatewayFactory() {
            @Override
            public Gateway forServer(NodeId server) {
                throw new AssertionError("get_health never waits for a gateway");
            }

            @Override
            public InubitVersion detect(NodeId server) {
                throw new AssertionError("get_health never detects");
            }

            @Override
            public MonitoringPort monitoring(NodeId server) {
                return new Monitoring(serverVersion.get());
            }

            @Override
            public ProcessQueryPort processes(NodeId server) {
                throw new AssertionError("no processes here");
            }

            @Override
            public LogPort logs(NodeId server) {
                throw new AssertionError("no logs here");
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
            public Optional<Gateway> knownGateway(NodeId server) {
                return Optional.empty();
            }
        };
        return new HealthService(gateways, new TargetResolver(List.of(DEV)), new FanOut(),
            server -> timeout, Clock.fixed(NOW, ZoneOffset.UTC),
            gateWithoutClients(versions, gateways, CapabilityMatrix.production()), versions,
            probe, selection);
    }

    private HealthReport check() {
        return service().check(Optional.of(DEV.value()), false).get(0);
    }

    @Test
    void theClientOfAnUnchangedVersionIsReportedAsBefore() {
        versions.detected(DEV, V8_1_17);

        HealthReport report = check();

        assertThat(probes).containsExactly(Optional.of(V8_1_17));
        assertThat(report.client()).contains(HealthReport.Client.of(CLIENT_81,
            ClientVersion.Comparison.MATCH));
        assertThat(report.unavailable()).isEmpty();
    }

    @Test
    void aReadOfAnotherVersionProbesItsClientWithinTheBudget() {
        versions.detected(DEV, V8_1_17);
        serverVersion.set("9.0.7");

        HealthReport report = check();

        assertThat(probes).containsExactly(Optional.of(V8_1_17), Optional.of(V9_0_7));
        assertThat(report.client()).contains(HealthReport.Client.of(CLIENT_90,
            ClientVersion.Comparison.MATCH));
        assertThat(report.unavailable()).isEmpty();
        assertThat(report.warnings()).noneMatch(warning -> warning.contains("StartCLI"));
        assertThat(versions.state(DEV).clientFor(KEY_81)).isPresent();
        assertThat(versions.state(DEV).clientFor(KEY_90)).isPresent();
    }

    @Test
    void aKnownResultOfTheEarlierClientIsNotReportedForTheNewVersion() {
        versions.detected(DEV, V8_1_17);
        versions.clientProbed(DEV, new ProbedClient(KEY_81, CLIENT_81));
        serverVersion.set("9.0.7");

        HealthReport report = check();

        assertThat(probes).containsExactly(Optional.of(V9_0_7));
        assertThat(report.client()).contains(HealthReport.Client.of(CLIENT_90,
            ClientVersion.Comparison.MATCH));
    }

    @Test
    void withoutBudgetLeftTheNewClientIsNotCheckedYet() {
        timeout = Duration.ofMillis(300);
        versions.detected(DEV, V8_1_17);
        serverVersion.set("9.0.7");
        // the first probe uses up the budget it was given
        before81 = () -> sleepQuietly(timeout.toMillis());

        HealthReport report = check();

        assertThat(probes).containsExactly(Optional.of(V8_1_17));
        assertThat(report.client()).isEmpty();
        assertThat(report.unavailable()).containsExactly(new UnavailablePart(
            UnavailablePart.CLIENT,
            "the StartCLI client for INUBIT 9.0.7 on dev/node1 has not been checked yet",
            Optional.empty(), Optional.of("Call get_health again")));
        assertThat(versions.state(DEV).clientFor(KEY_81)).as("the first probe is kept")
            .isPresent();
    }

    @Test
    void overlappingCallsEachReportTheClientOfTheirOwnRead() throws Exception {
        timeout = Duration.ofSeconds(10);
        versions.detected(DEV, V8_1_17);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        before81 = () -> {
            if (first.getAndSet(false)) {
                awaitQuietly(release);
            }
        };

        CompletableFuture<HealthReport> early = CompletableFuture.supplyAsync(this::check);
        awaitObserved(V8_1_17);
        serverVersion.set("9.0.7");
        HealthReport late = check();
        release.countDown();
        HealthReport earlier = early.get(10, TimeUnit.SECONDS);

        assertThat(versions.effectiveVersion(DEV)).contains(V9_0_7);
        assertThat(earlier.version()).contains("8.1.17");
        assertThat(earlier.client()).contains(HealthReport.Client.of(CLIENT_81,
            ClientVersion.Comparison.MATCH));
        assertThat(earlier.warnings()).noneMatch(warning -> warning.contains("StartCLI"));
        assertThat(late.version()).contains("9.0.7");
        assertThat(late.client()).contains(HealthReport.Client.of(CLIENT_90,
            ClientVersion.Comparison.MATCH));
        assertThat(late.warnings()).noneMatch(warning -> warning.contains("StartCLI"));
    }

    private void awaitObserved(InubitVersion version) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!versions.state(DEV).observed().map(observed -> observed.value().equals(version))
            .orElse(false)) {
            assertThat(System.nanoTime() - end).as("observed in time").isNegative();
            Thread.sleep(5);
        }
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
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Healthy, ready, the scripted version and load. */
    private static final class Monitoring implements MonitoringPort {

        private final String version;

        Monitoring(String version) {
            this.version = version;
        }

        @Override
        public Healthcheck healthcheck() {
            return new Healthcheck(200, HealthStatus.OK, Optional.of(false), Optional.of(NOW),
                Optional.empty());
        }

        @Override
        public Readiness ready() {
            return new Readiness(true, Optional.empty());
        }

        @Override
        public SystemInfo systemInfo() {
            return new SystemInfo(Optional.of(version), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Map.of());
        }

        @Override
        public Metrics metrics() {
            return new Metrics.Available(LoadFigures.of(5500, 3716, 9216, 0, 999_999, 401, 0,
                200_000));
        }

        @Override
        public boolean credentialsConfirmed() {
            return true;
        }
    }
}
