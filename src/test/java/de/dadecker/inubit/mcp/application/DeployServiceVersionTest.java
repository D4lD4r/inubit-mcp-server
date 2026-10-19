package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * T023 (feature 009, FR-014a, research D-11): {@code deploy_release} checks every source and
 * target node like a deployment write — detection, matrix, pin contradiction and the StartCLI
 * client — in the preview and again before the execution, and refuses the whole deployment
 * (audited {@code REFUSED}, nothing sent) if a node is refused or unknown or the nodes differ in
 * major.minor; admission warnings appear in the preview and the result.
 */
class DeployServiceVersionTest extends DeployExecution {

    private static final List<NodeId> NODES = List.of(DeployHarness.SOURCE,
        DeployHarness.SOURCE2, DeployHarness.INT1, DeployHarness.INT2, DeployHarness.INT3);
    private static final String WARNING = "StartCLI client 8.1.17 and INUBIT 8.1.18 on"
        + " int/node1 differ; StartCLI results may be unreliable";

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    private static VersionSetting pin(String raw) {
        return VersionSetting.parse(raw).orElseThrow();
    }

    private void sources(VersionSetting setting) {
        DeployHarness.SOURCES.forEach(node -> harness.versionLines.put(node, setting));
    }

    private void targets(VersionSetting setting) {
        DeployHarness.TARGETS.forEach(node -> harness.versionLines.put(node, setting));
    }

    /** Detection on every node of the deployment finds {@code source} or {@code target}. */
    private void detected(String source, String target) {
        sources(VersionSetting.AUTO);
        targets(VersionSetting.AUTO);
        DeployHarness.SOURCES.forEach(node -> harness.detection.answer(node, source));
        DeployHarness.TARGETS.forEach(node -> harness.detection.answer(node, target));
    }

    /** A matrix where every capability is supported on 8.1 and 8.2 (and on nothing else). */
    private static CapabilityMatrix supported81And82(SupportState unknown) {
        Map<Capability, SupportState> all = new EnumMap<>(Capability.class);
        Map<Capability, SupportState> otherwise = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            all.put(capability, SupportState.SUPPORTED);
            otherwise.put(capability, capability.kind() == Capability.Kind.READ
                ? SupportState.UNVERIFIED : unknown);
        }
        return new CapabilityMatrix(List.of(new CapabilityMatrix.Entry(
            new VersionRange(8, 1, 8, 2), all)), otherwise);
    }

    /**
     * The preview is refused with {@code UNSUPPORTED_VERSION}, audited {@code REFUSED}, before
     * any StartCLI run or REST read.
     */
    private ToolError refusedPreview() {
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> harness.deployService(List.of()).preview(request(Optional.empty())));
        assertThat(e).as("refused").isNotNull();
        ToolError error = e.error();
        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).startsWith("deploy_release: ").endsWith("; nothing was sent");
        assertThat(audit()).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.step()).isEqualTo(AuditRecord.Step.PREVIEW);
            assertThat(record.node()).isEqualTo("int");
            assertThat(record.reason()).get().asString().startsWith("UNSUPPORTED_VERSION: ");
        });
        assertThat(harness.launches()).isEmpty();
        assertThat(harness.restCalls).isEmpty();
        harness.verifyComplete();
        return error;
    }

    @Test
    void aDeploymentFrom81_17To81_18RunsWithoutVersionWarnings() {
        harness(false);
        sources(pin("8.1.17"));
        targets(pin("8.1.18"));
        DeploymentPreview preview = preview();
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);

        DeploymentResult result = execute(preview);

        assertThat(preview.executable()).isTrue();
        assertThat(preview.warnings()).isEmpty();
        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.EXECUTED);
        assertThat(result.warnings()).noneMatch(warning -> warning.contains("INUBIT"));
        assertThat(harness.detection.requests()).isEmpty();
        harness.verifyComplete();
    }

    @Test
    void aDeploymentFrom81To90IsRefusedAfterOnlyTheDetections() {
        harness(false);
        detected("8.1.17", "9.0.7");

        ToolError error = refusedPreview();

        assertThat(error.message()).contains("INUBIT 9.0.7 on int/node1 does not support"
            + " deployment yet", "different INUBIT minor versions (8.1, 9.0)");
        assertThat(error.nextStep()).contains("same INUBIT minor version");
        assertThat(harness.detection.requests()).containsExactlyInAnyOrderElementsOf(NODES);
    }

    @Test
    void oneMessageNamesEveryNodeWithItsVersionAndEachRefusedNodesCause() {
        harness(false);
        sources(pin("8.1.17"));
        harness.versionLines.put(DeployHarness.INT1, VersionSetting.AUTO);
        harness.detection.answer(DeployHarness.INT1, "9.0.7");
        harness.versionLines.put(DeployHarness.INT2, VersionSetting.AUTO);
        harness.detection.fail(DeployHarness.INT2, ErrorCode.AUTH_FAILED);
        harness.versionLines.put(DeployHarness.INT3, VersionSetting.V9_X);

        ToolError error = refusedPreview();

        assertThat(error.message()).isEqualTo("deploy_release: deployment from dev into int"
            + " refused (the version check refused int/node1, int/node2, int/node3; the nodes"
            + " run different INUBIT minor versions (8.1, 9.0)). Sources: dev/node1 INUBIT"
            + " 8.1.17, dev/node2 INUBIT 8.1.17. Targets: int/node1 INUBIT 9.0.7 (refused:"
            + " INUBIT 9.0.7 on int/node1 does not support deployment yet), int/node2 INUBIT"
            + " version unknown (refused: Deployment refused: the INUBIT version of int/node2"
            + " is unknown (AUTH_FAILED at 2026-10-07T10:00:00Z)), int/node3 INUBIT 9.x"
            + " (versionLine V9_X) (refused: INUBIT 9.x (versionLine V9_X) on int/node3 does"
            + " not support deployment yet); nothing was sent");
        assertThat(error.likelyCause()).contains("same minor version");
        assertThat(error.nextStep()).contains("same INUBIT minor version",
            "versionLine of int/node2");
        assertThat(harness.detection.requests()).containsExactly(DeployHarness.INT1,
            DeployHarness.INT2);
    }

    @Test
    void anUnknownSourceIsRefused() {
        harness(false);
        targets(pin("8.1.17"));
        harness.versionLines.put(DeployHarness.SOURCE, VersionSetting.AUTO);
        harness.detection.fail(DeployHarness.SOURCE, ErrorCode.AUTH_FAILED);

        ToolError error = refusedPreview();

        assertThat(error.message()).contains("(the version check refused dev/node1)",
            "dev/node1 INUBIT version unknown (refused: Deployment refused: the INUBIT version"
                + " of dev/node1 is unknown (AUTH_FAILED at 2026-10-07T10:00:00Z))")
            .doesNotContain("different INUBIT minor versions");
    }

    @Test
    void aTargetPinnedToV9xCountsAsUnknownAndIsRefused() {
        harness(false);
        harness.versionLines.put(DeployHarness.INT2, VersionSetting.V9_X);

        ToolError error = refusedPreview();

        assertThat(error.message()).contains("(the version check refused int/node2)",
            "int/node2 INUBIT 9.x (versionLine V9_X) (refused:", "int/node1 INUBIT 8.1,");
        assertThat(harness.detection.requests()).isEmpty();
    }

    @Test
    void aNodeOfUnknownVersionIsRefusedEvenWhenTheMatrixAdmitsIt() {
        // FR-014a: V9_X gives no minor version; a matrix supporting unknown versions does not
        // make it comparable
        harness(false);
        harness.matrix = supported81And82(SupportState.SUPPORTED);
        harness.versionLines.put(DeployHarness.INT3, VersionSetting.V9_X);

        ToolError error = refusedPreview();

        assertThat(error.message()).contains("(the INUBIT version of int/node3 is unknown)",
            "int/node3 INUBIT 9.x (versionLine V9_X); nothing was sent")
            .doesNotContain("refused:");
        assertThat(error.nextStep()).contains("same INUBIT minor version");
    }

    @Test
    void sourcesOfDifferentMinorVersionsAreRefusedAlthoughEachIsSupported() {
        harness(false);
        harness.matrix = supported81And82(SupportState.UNSUPPORTED);
        harness.versionLines.put(DeployHarness.SOURCE, pin("8.1.17"));
        harness.versionLines.put(DeployHarness.SOURCE2, pin("8.2.1"));
        targets(pin("8.1.17"));

        ToolError error = refusedPreview();

        assertThat(error.message()).contains("(the nodes run different INUBIT minor versions"
            + " (8.1, 8.2))", "dev/node2 INUBIT 8.2.1. Targets:").doesNotContain("refused:");
    }

    @Test
    void aVersionChangedBetweenPreviewAndExecuteIsRefusedAtExecute() {
        harness(false);
        detected("8.1.17", "8.1.17");
        DeploymentPreview preview = preview();
        int launches = harness.launches().size();
        int restCalls = harness.restCalls.size();
        harness.detection.answer(DeployHarness.INT2, "9.0.7");
        harness.clock.advance(Duration.ofMinutes(11)); // FR-010: older than the bound

        ToolError error = failure(preview).error();

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).contains("int/node2 INUBIT 9.0.7 (refused:");
        assertThat(audit()).last().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.step()).isEqualTo(AuditRecord.Step.EXECUTE);
        });
        assertThat(harness.launches()).hasSize(launches);
        assertThat(harness.restCalls).hasSize(restCalls);
        assertThat(harness.detection.requests()).hasSize(2 * NODES.size());
        harness.verifyComplete();
    }

    @Test
    void aClientWhoseRangeExcludesTheNodesMinorVersionIsRefused() {
        harness(false);
        sources(pin("8.1.17"));
        targets(pin("8.1.17"));
        harness.versions.clientProbed(DeployHarness.INT1, new ClientVersion(version("8.0.5"),
            Optional.of(version("4.0.1")), Optional.of(version("8.0.5"))));

        ToolError error = refusedPreview();

        assertThat(error.message()).contains("int/node1 INUBIT 8.1.17 (refused: StartCLI client"
            + " 8.0.5 of int/node1 supports INUBIT 4.0.1 to 8.0.5, which excludes INUBIT"
            + " 8.1.17; deployment via StartCLI refused)")
            .doesNotContain("different INUBIT minor versions");
        assertThat(error.nextStep()).contains("Install a StartCLI client")
            .doesNotContain("same INUBIT minor version");
    }

    @Test
    void aClientPatchMismatchIsAWarningInThePreviewAndTheResult() {
        harness(false);
        sources(pin("8.1.17"));
        targets(pin("8.1.18"));
        harness.versions.clientProbed(DeployHarness.INT1, new ClientVersion(version("8.1.17"),
            Optional.of(version("4.0.1")), Optional.of(version("8.1.17"))));
        DeploymentPreview preview = preview();
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);

        DeploymentResult result = execute(preview);

        assertThat(preview.executable()).isTrue();
        assertThat(preview.warnings()).containsExactly(WARNING);
        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.EXECUTED);
        assertThat(result.warnings()).contains(WARNING);
        harness.verifyComplete();
    }
}
