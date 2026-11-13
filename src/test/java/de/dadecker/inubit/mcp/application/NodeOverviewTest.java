package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeSummary;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.TestClientSelection;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * Feature 012 (FR-004, research D-2): {@link NodeOverview} names the two halves of the former
 * capability "diagnosis" as {@code process_diagnosis} and {@code log_diagnosis}. Feature 018
 * (FR-016): partner management is listed as {@code partner_management}.
 */
class NodeOverviewTest {

    private static final Set<Capability> DIAGNOSIS =
        EnumSet.of(Capability.PROCESS_DIAGNOSIS, Capability.LOG_DIAGNOSIS);

    @Test
    void bothDiagnosisCapabilitiesAreListedByTheirOwnName() {
        NodeSummary.Capabilities capabilities = capabilities(CapabilityMatrix.production());

        assertThat(capabilities.usable()).containsExactly("process_diagnosis", "log_diagnosis");
        assertThat(capabilities.notUsable()).isEmpty();
    }

    @Test
    void anUnsupportedDiagnosisIsRefusedByItsOwnNameAndLabel() {
        Map<Capability, SupportState> unsupported = Map.of(
            Capability.PROCESS_DIAGNOSIS, SupportState.UNSUPPORTED,
            Capability.LOG_DIAGNOSIS, SupportState.UNSUPPORTED);
        CapabilityMatrix matrix = new CapabilityMatrix(
            List.of(new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1), unsupported)),
            unsupported);

        NodeSummary.Capabilities capabilities = capabilities(matrix);

        assertThat(capabilities.usable()).isEmpty();
        assertThat(capabilities.notUsable()).extracting(NodeSummary.NotUsable::capability)
            .containsExactly("process_diagnosis", "log_diagnosis");
        assertThat(capabilities.notUsable()).extracting(NodeSummary.NotUsable::reason)
            .satisfiesExactly(
                reason -> assertThat(reason).endsWith(" does not support process diagnosis yet"),
                reason -> assertThat(reason).endsWith(" does not support log diagnosis yet"));
    }

    /** Feature 020 (T003, D-6): the capability's name in {@code list_nodes}. */
    @Test
    void queueEntryReadIsListedAsQueueEntryRead() {
        NodeSummary.Capabilities capabilities = capabilities(CapabilityMatrix.production(),
            EnumSet.of(Capability.QUEUE_ENTRY_READ));

        assertThat(capabilities.usable()).containsExactly("queue_entry_read");
        assertThat(capabilities.notUsable()).isEmpty();
    }

    /**
     * Feature 021 (T009, D-6): the names in {@code list_nodes}, refused until recorded; T031: the
     * reason names the matrix note as the gate's refusal does; T033, T046, T051, T052: the 8.1
     * and 9.0 trace and web service logs are recorded and usable, so the note is shown with a
     * test declaration that still carries it.
     */
    @Test
    void theServerLogReadsAreListedByTheirNamesAndRefusedBeforeTheirRecordings() {
        NodeSummary.Capabilities capabilities = capabilities(CapabilityMatrix.production(),
            EnumSet.of(Capability.TRACE_LOG_READ, Capability.WEBSERVICE_LOG_READ));

        assertThat(capabilities.usable()).containsExactly("trace_log_read", "webservice_log_read");
        assertThat(capabilities.notUsable()).isEmpty();
        NodeSummary.Capabilities v90 = version(VersionSetting.V9_0,
            EnumSet.of(Capability.TRACE_LOG_READ, Capability.WEBSERVICE_LOG_READ)).capabilities();
        assertThat(v90.usable()).containsExactly("trace_log_read", "webservice_log_read");
        assertThat(v90.notUsable()).isEmpty();

        CapabilityMatrix beforeTheRecording = new CapabilityMatrix(List.of(
            new CapabilityMatrix.Entry(new VersionRange(9, 0, 9, 0),
                Map.of(Capability.TRACE_LOG_READ, SupportState.SUPPORTED), Map.of(), false,
                Map.of(Capability.WEBSERVICE_LOG_READ, "not yet verified"))), Map.of());
        assertThat(version(beforeTheRecording, VersionSetting.V9_0,
                EnumSet.of(Capability.TRACE_LOG_READ, Capability.WEBSERVICE_LOG_READ))
            .capabilities().notUsable()).extracting(NodeSummary.NotUsable::reason)
            .containsExactly("INUBIT 9.0 on dev/node1: downloading the web service log is not yet"
                + " verified for INUBIT 9.0");
    }

    /**
     * Feature 024 (T004, D-4): the names in {@code list_nodes}, refused until recorded (8.1
     * cluster nodes since T025); listed only where the configuration allows them, which Wiring
     * ties to the group's opt-in and a client home (T023, T030, T036, T054).
     */
    @Test
    void theNodeToolsAreListedByTheirNamesOnlyWhereAllowedAndRefusedBeforeTheirRecordings() {
        Set<Capability> nodeTools = EnumSet.of(Capability.CLUSTER_NODES_READ,
            Capability.IO_LOG_READ, Capability.NODE_CONFIG_READ, Capability.LOG_LEVEL_WRITE);
        BiFunction<NodeId, Boolean, Set<Capability>> withClientOnly =
            (id, withClient) -> withClient ? nodeTools : Set.of();
        NodeSummary node = TestNodeConfig.node().versionLine(VersionSetting.V8_1).build()
            .summary(path -> false, false);
        NodeVersions versions = new NodeVersions(new MutableClock(Instant.EPOCH),
            id -> VersionSetting.V8_1, id -> Duration.ofMinutes(5));

        NodeSummary.Capabilities withoutClient = new NodeOverview(List.of(node), versions,
            CapabilityMatrix.production(), withClientOnly, TestClientSelection.none()).nodes()
            .getFirst().version().orElseThrow().capabilities();
        NodeSummary.Capabilities withClient = new NodeOverview(List.of(node), versions,
            CapabilityMatrix.production(), withClientOnly, TestClientSelection.of(id -> true))
            .nodes().getFirst().version().orElseThrow().capabilities();

        assertThat(withoutClient.usable()).isEmpty();
        assertThat(withoutClient.notUsable()).as("not allowed, so not listed").isEmpty();
        assertThat(withClient.usable()).containsExactly("cluster_nodes_read");
        assertThat(withClient.notUsable()).extracting(NodeSummary.NotUsable::capability)
            .containsExactly("io_log_read", "node_config_read", "log_level_write");
        assertThat(withClient.notUsable()).extracting(NodeSummary.NotUsable::reason)
            .containsExactly(
                "INUBIT 8.1 on dev/node1: reading module input/output logging is not yet verified"
                    + " for INUBIT 8.1",
                "INUBIT 8.1 on dev/node1: reading node configuration values is not yet verified"
                    + " for INUBIT 8.1",
                "INUBIT 8.1 on dev/node1: changing temporary log levels is not yet verified for"
                    + " INUBIT 8.1");
    }

    @Test
    void partnerManagementIsListedByItsNameWithTheStateOfTheVersionLine() {
        Set<Capability> partners = EnumSet.of(Capability.PARTNER_MANAGEMENT);

        NodeSummary.Version v81 = version(VersionSetting.V8_1, partners);
        assertThat(v81.capabilities().usable()).containsExactly("partner_management");
        assertThat(v81.status()).isEqualTo("supported");

        NodeSummary.Version v90 = version(VersionSetting.V9_0, partners);
        assertThat(v90.capabilities().usable()).as("runs with a warning")
            .containsExactly("partner_management");
        assertThat(v90.capabilities().notUsable()).isEmpty();
        assertThat(v90.status()).as("unverified, so not fully supported")
            .isEqualTo("partially_supported");

        NodeSummary.Version v61 = version(VersionSetting.V6_1, partners);
        assertThat(v61.capabilities().usable()).isEmpty();
        assertThat(v61.capabilities().notUsable()).singleElement().satisfies(refused -> {
            assertThat(refused.capability()).isEqualTo("partner_management");
            assertThat(refused.reason()).endsWith(" does not support partner management");
        });
    }

    @Test
    void everyListedNodeHasItsStartCliChoiceWhichTheStartupSummaryLacks() {
        // feature 017 (FR-015, research D-18): always present in list_nodes; final for a pin
        NodeSummary node = TestNodeConfig.node().versionLine(VersionSetting.V8_1).build()
            .summary(path -> false, false);
        NodeVersions versions = new NodeVersions(new MutableClock(Instant.EPOCH),
            id -> VersionSetting.V8_1, id -> Duration.ofMinutes(5));

        NodeSummary listed = new NodeOverview(List.of(node), versions,
            CapabilityMatrix.production(), (id, withClient) -> DIAGNOSIS,
            TestClientSelection.none()).nodes().getFirst();

        assertThat(node.startCli()).isEmpty();
        assertThat(listed.startCli()).contains(NodeSummary.StartCli.none(true));
    }

    /** The capabilities of a {@code V8_1} node that allows both diagnosis capabilities only. */
    private static NodeSummary.Capabilities capabilities(CapabilityMatrix matrix) {
        return capabilities(matrix, DIAGNOSIS);
    }

    /** The capabilities of a {@code V8_1} node that allows {@code allowed} only. */
    private static NodeSummary.Capabilities capabilities(CapabilityMatrix matrix,
        Set<Capability> allowed) {
        return version(matrix, VersionSetting.V8_1, allowed).capabilities();
    }

    /** The version block of a node on {@code line} that allows {@code allowed} only. */
    private static NodeSummary.Version version(VersionSetting line, Set<Capability> allowed) {
        return version(CapabilityMatrix.production(), line, allowed);
    }

    private static NodeSummary.Version version(CapabilityMatrix matrix, VersionSetting line,
        Set<Capability> allowed) {
        NodeVersions versions = new NodeVersions(new MutableClock(Instant.EPOCH),
            id -> line, id -> Duration.ofMinutes(5));
        NodeSummary node = TestNodeConfig.node().versionLine(line).build()
            .summary(path -> false, false);
        return new NodeOverview(List.of(node), versions, matrix, (id, withClient) -> allowed,
            TestClientSelection.none()).nodes()
            .getFirst().version().orElseThrow();
    }
}
