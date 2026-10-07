package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ArtifactClass;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T028 (feature 005, SC-007): the preview of a release with 5 diagram groups, 20 workflows and
 * 100 modules (80 of them run by the workflows and thus in the release) for a target group with
 * 2 nodes, and its execution, on {@link DeployHarness} with {@link FakeServer#synthetic}. The
 * fake answers instantly, so the measured time is this server's own work (archives, rendering,
 * checks, plans, verification) — the part SC-007 budgets besides INUBIT's export time.
 */
@Timeout(120)
class DeployTimingTest {

    private static final String TAG = "REL-SC007";
    private static final List<NodeId> TARGETS = List.of(DeployHarness.INT1, DeployHarness.INT2);
    private static final List<String> GROUPS = List.of("GRP-01", "GRP-02", "GRP-03", "GRP-04",
        "GRP-05");
    private static final Duration BUDGET = Duration.ofSeconds(20);
    private static final String MODULE_IMPORT =
        "--importModule --importUser 'jdoe' --returnProtocol";

    @TempDir
    Path temp;

    private DeployHarness harness;

    private void reads() {
        DeployHarness.SOURCES.forEach(node -> harness.exportRelease(node, TAG));
        GROUPS.forEach(group -> harness.exportGroup(DeployHarness.SOURCE, group));
        TARGETS.forEach(this::targetExports);
    }

    private void targetExports(NodeId node) {
        GROUPS.forEach(group -> harness.exportGroup(node, group));
    }

    private static DeployGuard.Request request(Optional<String> code) {
        return new DeployGuard.Request("int", TAG, Optional.empty(), code, Optional.empty());
    }

    @Test
    void previewAndExecutionOfAnSc007ReleaseStayWellBelowTheBudget() throws IOException {
        harness = new DeployHarness(temp, node -> FakeServer.synthetic(5, 4, 20),
            List.of(DeployHarness.SOURCE, DeployHarness.SOURCE2, DeployHarness.INT1,
                DeployHarness.INT2));
        for (NodeId source : DeployHarness.SOURCES) {
            FakeServer server = harness.servers.get(source);
            server.moduleTypes().keySet().forEach(name -> server.publishModule(name, text ->
                text.replace("IsModuleTemplate", "IsModuleTemplateX")));
            GROUPS.forEach(group -> server.tag(group, TAG));
        }
        reads();

        long start = System.nanoTime();
        DeploymentPreview preview = harness.deployService(List.of()).deploy(request(
            Optional.empty())).preview().orElseThrow();
        Duration previewTime = Duration.ofNanos(System.nanoTime() - start);

        assertThat(preview.executable()).as(preview.toString()).isTrue();
        assertThat(preview.diagramGroups()).containsExactlyElementsOf(GROUPS);
        assertThat(preview.plans()).hasSize(2).allSatisfy(plan -> {
            assertThat(plan.counts()).containsEntry(ArtifactClass.CHANGED, 80)
                .containsEntry(ArtifactClass.UNCHANGED, 20);
        });
        reads();
        TARGETS.forEach(node -> {
            targetExports(node); // re-check
            harness.importApplied(node, MODULE_IMPORT);
            targetExports(node); // verification
            GROUPS.forEach(group -> harness.tagMoved(node, group, TAG));
            GROUPS.forEach(group -> harness.exportHistory(node, group));
        });

        start = System.nanoTime();
        DeploymentResult result = harness.deployService(List.of()).deploy(request(
            preview.confirmationCode())).result().orElseThrow();
        Duration executeTime = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.outcome()).as(result.toString())
            .isEqualTo(DeploymentResult.Outcome.EXECUTED);
        assertThat(result.nodes()).allSatisfy(node -> assertThat(node.imported()).hasSize(80));
        System.out.printf("SC-007 harness timing: preview %d ms, execute %d ms%n",
            previewTime.toMillis(), executeTime.toMillis());
        assertThat(previewTime).isLessThan(BUDGET);
        assertThat(executeTime).isLessThan(BUDGET);
        harness.verifyComplete();
    }
}
