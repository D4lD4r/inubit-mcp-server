package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.DeployMode;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stage 3 review B1, T025 (feature 005, US4, FR-023, FR-026): no import, tag or other writing
 * command ever reaches a package-only group, whatever its write settings.
 */
class PackageOnlyTest {

    static final String TAG = "TAG-01";

    @TempDir
    Path temp;

    DeployHarness harness;

    void harness() {
        try {
            harness = new DeployHarness(temp);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static DeployGuard.Request request(Optional<String> code) {
        return new DeployGuard.Request("prod", TAG, Optional.empty(), code,
            Optional.of("client/1.0"));
    }

    /** prod receives from int: the int nodes carry the tag and answer the release exports. */
    void reads() {
        DeployHarness.TARGETS.forEach(node -> harness.exportRelease(node, TAG));
        harness.exportGroup(DeployHarness.INT1);
        harness.exportGroup(DeployHarness.PROD);
    }

    DeploymentPreview preview() {
        DeployHarness.TARGETS.forEach(node -> harness.servers.get(node).tag("GRP-01", TAG));
        reads();
        return harness.deployService(List.of()).deploy(request(Optional.empty())).preview()
            .orElseThrow();
    }

    /** Only exports reached any node; nothing reached prod but exports. */
    void onlyExports() {
        assertThat(harness.launches()).allMatch(line -> line.matches("^[a-z0-9/-]+ export .*"));
        assertThat(harness.servers.get(DeployHarness.PROD).imported).isEmpty();
        assertThat(harness.servers.get(DeployHarness.PROD).repositoryImports).isEmpty();
        harness.verifyComplete();
    }

    @Test
    void aPackageOnlyTargetIsPreviewedLikeAnyOther() {
        harness();

        DeploymentPreview preview = preview();

        assertThat(preview.mode()).isEqualTo(DeployMode.PACKAGE_ONLY);
        assertThat(preview.plans()).extracting(plan -> plan.node().value())
            .containsExactly("prod/node1");
        assertThat(preview.confirmationCode()).isPresent();
        onlyExports();
    }

    @Test
    void theDeployerRefusesAPackageOnlyTargetAsDefenceInDepth() {
        harness();
        DeployGuard.Admitted admitted = new DeployGuard.Admitted(new GroupId("prod"),
            new GroupId("int"), DeployMode.PACKAGE_ONLY, List.of(),
            List.of(DeployHarness.PROD), DeployHarness.TARGETS, "jdoe", TAG);

        ToolErrorException e;
        try {
            harness.deployer(harness.audit::add).deploy(admitted, null, null, null,
                Optional.empty());
            throw new AssertionError("not refused");
        } catch (ToolErrorException refused) {
            e = refused;
        }

        assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(e.error().message()).contains("package-only");
        onlyExports();
    }

    @Test
    void aConfirmedPackageOnlyCallNeverWrites() {
        harness();
        DeploymentPreview preview = preview();

        ToolErrorException e;
        try {
            harness.deployService(List.of()).deploy(request(preview.confirmationCode()));
            throw new AssertionError("not refused");
        } catch (ToolErrorException refused) {
            e = refused;
        }

        assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(harness.audit.get(harness.audit.size() - 1).outcome())
            .isEqualTo(AuditOutcome.REFUSED);
        onlyExports();
    }

    static NodeId node(String id) {
        return NodeId.parse(id);
    }

    static AuditOutcome refused() {
        return AuditOutcome.REFUSED;
    }
}
