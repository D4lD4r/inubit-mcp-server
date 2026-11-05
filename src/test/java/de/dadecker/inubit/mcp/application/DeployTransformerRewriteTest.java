package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult.State;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Issue #55 for {@code deploy_release}: INUBIT 8.1.17 stores an {@code xslt.transformer} that
 * names the plain Saxon factory as a Saxon-EE factory of {@code com.saxonica.config} when a
 * module is imported. The verification of the deployment and of its rollback accept that
 * rewrite. The fixtures stand in {@code ProfessionalTransformerFactory} for the Saxon-EE factory.
 */
class DeployTransformerRewriteTest extends DeployExecution {

    private static final String PLAIN = ">net.sf.saxon.TransformerFactoryImpl<";
    private static final String SAXONICA = ">com.saxonica.config.ProfessionalTransformerFactory<";

    /** Module-0005 names the plain factory on every node; the targets rewrite it on import. */
    private DeploymentPreview plainPreview() {
        harness(false);
        Stream.concat(DeployHarness.SOURCES.stream(), DeployHarness.TARGETS.stream())
            .map(harness.servers::get)
            .forEach(server -> server.publishModule("Module-0005", xml -> xml.replace(SAXONICA,
                PLAIN)));
        DeployHarness.TARGETS.forEach(node -> harness.servers.get(node).saxonFactoryOnImport =
            SAXONICA.substring(1, SAXONICA.length() - 1));
        source(server -> { }, true);
        DeployHarness.TARGETS.forEach(this::nodeExports);
        return harness.deployService(List.of()).deploy(request(Optional.empty())).preview()
            .orElseThrow();
    }

    private String module(int node) {
        return new String(de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.entries(
            harness.servers.get(DeployHarness.TARGETS.get(node)).exportModule("XSLT Converter",
                "Module-0005").orElseThrow()).get("module/module-0005.xml"),
            java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void aDeploymentWhoseTransformerInubitRewritesIsVerified() {
        DeploymentPreview preview = plainPreview();
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);

        DeploymentResult result = execute(preview);

        assertThat(result.outcome()).as(result.toString())
            .isEqualTo(DeploymentResult.Outcome.EXECUTED);
        assertThat(result.nodes()).extracting(DeploymentResult.NodeOutcome::state)
            .containsExactly(State.DEPLOYED, State.DEPLOYED, State.DEPLOYED);
        assertThat(module(0)).as("the target rewrote it").contains(SAXONICA)
            .doesNotContain(PLAIN);
        harness.verifyComplete();
    }

    @Test
    void aReleaseThatDiffersOnlyInTheRewrittenTransformerIsUnchanged() {
        // the release keeps the plain factory, the targets hold the rewritten one: deploying
        // it again would change nothing, so the preview must not list Module-0005 as CHANGED
        DeploymentPreview first = plainPreview();
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);
        execute(first);
        List<Integer> imports = DeployHarness.TARGETS.stream()
            .map(node -> harness.servers.get(node).imported.size()).toList();

        DeploymentPreview second = preview();
        executeStart();
        DeployHarness.TARGETS.forEach(node -> {
            nodeExports(node);
            harness.tagVerified(node, "GRP-01", TAG);
        });
        DeploymentResult result = execute(second);

        assertThat(module(0)).contains(SAXONICA);
        assertThat(result.outcome()).as(result.toString())
            .isEqualTo(DeploymentResult.Outcome.EXECUTED);
        assertThat(result.nodes()).allSatisfy(node -> {
            assertThat(node.state()).isEqualTo(State.UNCHANGED);
            assertThat(node.imported()).isEmpty();
        });
        assertThat(DeployHarness.TARGETS.stream().map(node -> harness.servers.get(node)
            .imported.size()).toList()).isEqualTo(imports);
        harness.verifyComplete();
    }

    @Test
    void theRollbackOfAModuleWhoseTransformerInubitRewritesSucceeds() {
        DeploymentPreview preview = plainPreview();
        executeStart();
        deployed(DeployHarness.INT1);
        nodeExports(DeployHarness.INT2); // re-check
        harness.importRepositoryApplied(DeployHarness.INT2).importApplied(DeployHarness.INT2,
            MODULE_IMPORT);
        // another verify difference
        harness.cli.get(DeployHarness.INT2).then(spec -> harness.servers.get(
            DeployHarness.INT2).tamperNextImport = new String[] {"xslt_mode", "xslt_mode_X"});
        nodeExports(DeployHarness.INT2); // verification sees the tampered module
        harness.importApplied(DeployHarness.INT2, MODULE_IMPORT); // rollback from the backup
        nodeExports(DeployHarness.INT2); // verification of the rollback

        DeploymentResult result = execute(preview);

        assertThat(result.nodes().get(1).state()).as(result.toString())
            .isEqualTo(State.ROLLED_BACK);
        assertThat(result.nodes().get(1).failure()).get().satisfies(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
            assertThat(failure.step()).isEqualTo("verify");
        });
        assertThat(module(1)).doesNotContain("xslt_mode_X").contains(SAXONICA);
        harness.verifyComplete();
    }
}
