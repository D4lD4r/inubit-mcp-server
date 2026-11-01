package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.testing.FakeVersionDetection.gateWithoutClients;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.HealthReport;
import de.dadecker.inubit.mcp.domain.model.HealthStatus;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.CliHealthPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T027 (feature 011, FR-009, research D-6): {@code get_health} for one 6.1 node returns within
 * its budget, also when the StartCLI session hangs; the real {@code V61HealthAdapter} runs over
 * a fake StartCLI ({@link FakeV61Node}). The bound of a single CLI-only node is scaled from 15 s
 * to {@link #SINGLE} so that the test is quick; {@code HealthServiceTest} checks that the
 * production bound gives the session at most 15 s less the fan-out grace.
 */
@Timeout(30)
class V61HealthTimingTest {

    private static final NodeId LEGACY = NodeId.parse("legacy/node1");
    /** The scaled bound of a call for one CLI-only node; its session gets 1.1 s. */
    private static final Duration SINGLE = Duration.ofSeconds(2);
    private static final Duration CLI_TIMEOUT = Duration.ofSeconds(10);
    private static final CapabilityMatrix WITH_61 = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(6, 1, 6, 1),
            Map.of(Capability.MONITORING, SupportState.SUPPORTED),
            Map.of(Capability.MONITORING, Set.of(Channel.CLI)), true)), Map.of());
    private static final ClientVersion CLIENT_610 = new ClientVersion(version("6.1.0"),
        Optional.of(version("4.0.1")), Optional.of(version("6.1.0")));

    @TempDir
    Path temp;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    /** {@code get_health} for legacy/node1 alone, with the client probe of feature 009. */
    private HealthReport check(FakeV61Node node) {
        NodeVersions versions = new NodeVersions(clock, id -> VersionSetting.V6_1,
            id -> Duration.ofMinutes(10));
        GatewayFactory gateways = new CliOnlyGateways(node.adapter());
        HealthService service = new HealthService(gateways, new TargetResolver(List.of(LEGACY)),
            new FanOut(), id -> Duration.ofSeconds(5), clock,
            gateWithoutClients(versions, gateways, WITH_61), versions,
            (id, timeout) -> CLIENT_610, id -> true, SINGLE, Duration.ofSeconds(1));
        List<HealthReport> reports = service.check(Optional.of(LEGACY.value()), false);
        assertThat(reports).hasSize(1);
        return reports.get(0);
    }

    private static Duration timed(Runnable call) {
        long start = System.nanoTime();
        call.run();
        return Duration.ofNanos(System.nanoTime() - start);
    }

    private static void assertBudgetTimeout(HealthReport report) {
        assertThat(report.reachable()).isFalse();
        assertThat(report.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(report.error()).hasValueSatisfying(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(error.message()).isEqualTo("The StartCLI health session of legacy/node1"
                + " did not finish within the get_health budget of 1.1 s");
            assertThat(error.nextStep()).doesNotContain("cliTimeout");
        });
        assertThat(report.unavailable()).extracting(UnavailablePart::part).containsExactly(
            UnavailablePart.VERSION, UnavailablePart.LOAD, UnavailablePart.PROCESS_COUNTS);
        assertThat(report.unavailable()).allSatisfy(part ->
            assertThat(part.reason()).startsWith("TIMEOUT: The StartCLI health session"));
        assertThat(report.client()).as("the probe finished in time").isPresent();
    }

    @Test
    void aSessionThatFinishesInTimeIsReportedInFull() {
        FakeV61Node node = new FakeV61Node(LEGACY, temp,
            FakeProcessLauncher.replaying("v6_1", "health_session_ok"), CLI_TIMEOUT, clock);

        HealthReport[] report = new HealthReport[1];
        Duration elapsed = timed(() -> report[0] = check(node));

        assertThat(elapsed).isLessThan(SINGLE);
        assertThat(report[0].status()).isEqualTo(HealthStatus.OK);
        assertThat(report[0].processCounts()).isPresent();
    }

    @Test
    void aHangingSessionIsStoppedAtTheBudgetAndReportedAsTimeout() {
        FakeV61Node node = new FakeV61Node(LEGACY, temp,
            FakeProcessLauncher.of("", "", 0).hanging(), CLI_TIMEOUT, clock);

        HealthReport[] report = new HealthReport[1];
        Duration elapsed = timed(() -> report[0] = check(node));

        assertThat(elapsed).isLessThan(SINGLE);
        assertBudgetTimeout(report[0]);
        assertThat(node.session.launchCount()).isEqualTo(1);
        assertThat(node.session.last().destroyed()).as("the runner ended the session").isTrue();
    }

    @Test
    void aSessionThatIgnoresItsTerminationStillLeavesTheCallWithinTheBudget() {
        // the runner would wait 2 s more for the kill; the call does not
        FakeV61Node node = new FakeV61Node(LEGACY, temp,
            FakeProcessLauncher.of("", "", 0).ignoringDestroy(), CLI_TIMEOUT, clock);

        HealthReport[] report = new HealthReport[1];
        Duration elapsed = timed(() -> report[0] = check(node));

        assertThat(elapsed).isLessThan(SINGLE);
        assertBudgetTimeout(report[0]);
    }

    @Test
    void aCliTimeoutShorterThanTheBudgetKeepsTheRunnersAdvice() {
        FakeV61Node node = new FakeV61Node(LEGACY, temp,
            FakeProcessLauncher.of("", "", 0).hanging(), Duration.ofMillis(300), clock);

        HealthReport[] report = new HealthReport[1];
        Duration elapsed = timed(() -> report[0] = check(node));

        assertThat(elapsed).isLessThan(SINGLE);
        assertThat(report[0].status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(report[0].error()).hasValueSatisfying(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(error.message()).contains("300 ms");
            assertThat(error.nextStep()).contains("raise cliTimeout");
        });
    }

    /** The gateways of a node reached through StartCLI only: anything else is a failure. */
    private record CliOnlyGateways(CliHealthPort health) implements GatewayFactory {

        @Override
        public Optional<CliHealthPort> cliHealth(NodeId server) {
            return Optional.of(health);
        }

        @Override
        public Gateway forServer(NodeId server) {
            throw new AssertionError("no gateway for a CLI-only node");
        }

        @Override
        public InubitVersion detect(NodeId server) {
            throw new AssertionError("no detection for a CLI-only node");
        }

        @Override
        public MonitoringPort monitoring(NodeId server) {
            throw new AssertionError("no REST monitoring for a CLI-only node");
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
            throw new AssertionError("get_health does not read the inventory");
        }

        @Override
        public ProcessControlPort processControl(NodeId server) {
            throw new AssertionError("get_health does not control processes");
        }

        @Override
        public Optional<Gateway> knownGateway(NodeId server) {
            throw new AssertionError("no gateway for a CLI-only node");
        }
    }
}
