package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.HealthReport;
import de.dadecker.inubit.mcp.domain.model.HealthStatus;
import de.dadecker.inubit.mcp.domain.model.LoadFigures;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
    private static final LoadFigures LOAD = LoadFigures.of(5500, 3716, 9216, 0, 999_999, 401, 0,
        200_000);

    private final Map<NodeId, FakeMonitoring> monitorings = new ConcurrentHashMap<>();
    private final Map<NodeId, List<String>> warnings = new HashMap<>();
    private final Set<NodeId> undetected = new HashSet<>();
    private final Map<NodeId, RuntimeException> gatewayFailures = new HashMap<>();
    private final Map<NodeId, CountDownLatch> gatewayBlocks = new HashMap<>();
    private final AtomicInteger detections = new AtomicInteger();

    private FakeMonitoring monitoring(NodeId server) {
        return monitorings.computeIfAbsent(server, s -> new FakeMonitoring());
    }

    private HealthService service() {
        GatewayFactory gateways = new GatewayFactory() {
            @Override
            public Gateway forServer(NodeId server) {
                detections.incrementAndGet(); // a blocking detection: health must not wait for it
                awaitQuietly(new CountDownLatch(1));
                throw new AssertionError("unreachable");
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
            public Optional<Gateway> knownGateway(NodeId server) {
                return undetected.contains(server) ? Optional.empty()
                    : Optional.of(new FakeGateway(server, warnings.getOrDefault(server,
                        List.of()), HealthServiceTest.this.monitoring(server)));
            }
        };
        return new HealthService(gateways, new TargetResolver(SERVERS), new FanOut(),
            server -> TIMEOUT, Clock.fixed(NOW, ZoneOffset.UTC));
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
        String detectionWarning = "INUBIT version could not be detected (AUTH_FAILED);"
            + " assuming 8.1";
        warnings.put(DEV, List.of(detectionWarning));
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
        assertThat(report.warnings()).containsExactly(detectionWarning);
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

    @Test
    void aVersionOtherThan81AddsAWarning() {
        monitoring(DEV).systemInfo = () -> new SystemInfo(Optional.of("9.0.2"),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Map.of());

        HealthReport report = checkOne(DEV, false);

        assertThat(report.version()).contains("9.0.2");
        assertThat(report.warnings()).singleElement().asString()
            .contains("9.0.2", "8.1");
    }

    @Test
    void theVersionWarningIsNotRepeatedWhenTheGatewayAlreadyWarns() {
        String unsupported = "INUBIT 9.0.2 is unsupported in this version; the 8.1 adapters are"
            + " used";
        warnings.put(DEV, List.of(unsupported));
        monitoring(DEV).systemInfo = () -> new SystemInfo(Optional.of("9.0.2"),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Map.of());

        assertThat(checkOne(DEV, false).warnings()).containsExactly(unsupported);
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
}
