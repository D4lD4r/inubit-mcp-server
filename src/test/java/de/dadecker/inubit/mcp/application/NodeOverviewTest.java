package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.NodeSummary;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Feature 011 (FR-004, research D-2): {@link NodeOverview} names the two halves of the former
 * capability "diagnosis" as {@code process_diagnosis} and {@code log_diagnosis}.
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

    /** The capabilities of a {@code V8_1} node that allows both diagnosis capabilities only. */
    private static NodeSummary.Capabilities capabilities(CapabilityMatrix matrix) {
        NodeVersions versions = new NodeVersions(new MutableClock(Instant.EPOCH),
            id -> VersionSetting.V8_1, id -> Duration.ofMinutes(5));
        NodeSummary node = TestNodeConfig.node().versionLine(VersionSetting.V8_1).build()
            .summary(path -> false, false);
        return new NodeOverview(List.of(node), versions, matrix, id -> DIAGNOSIS).nodes()
            .getFirst().version().orElseThrow().capabilities();
    }
}
