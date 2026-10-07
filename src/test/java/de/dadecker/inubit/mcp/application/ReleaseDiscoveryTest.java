package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec;
import de.dadecker.inubit.mcp.adapter.archive.v81.V81ReleaseArchives;
import de.dadecker.inubit.mcp.domain.model.DeployMode;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T016 (feature 005, FR-009, research D-4): the release is the tag export of every source node,
 * rendered like a workspace export of the target group; the nodes must agree; a tag on no
 * diagram group is {@code NOT_FOUND}; a tagged version older than head is noted.
 */
class ReleaseDiscoveryTest {

    private static final String WORKFLOW = "int/jdoe/workflows/GRP-01/Workflow-0001.xml";
    private static final UUID AUDIT_ID = UUID.fromString("00000000-0000-0000-0000-00000000d016");

    @TempDir
    Path temp;

    private DeployHarness harness;

    @BeforeEach
    void setUp() throws IOException {
        harness = new DeployHarness(temp);
    }

    private ReleaseDiscovery discovery() {
        return new ReleaseDiscovery(harness::artifacts, new ArchiveCodec(),
            new V81ReleaseArchives(), harness.root);
    }

    private static DeployGuard.Admitted admitted(String tag) {
        return new DeployGuard.Admitted(new GroupId("int"), new GroupId("dev"),
            DeployMode.EXECUTE, List.of(), DeployHarness.TARGETS, DeployHarness.SOURCES, "jdoe",
            tag);
    }

    private ToolErrorException failure(String tag) {
        try {
            discovery().discover(admitted(tag), AUDIT_ID);
        } catch (ToolErrorException e) {
            return e;
        }
        throw new AssertionError("no failure");
    }

    private void releaseExports(String tag) {
        DeployHarness.SOURCES.forEach(node -> harness.exportRelease(node, tag));
    }

    @Test
    void theReleaseIsRenderedForTheTargetGroupWithoutTagTraces() {
        harness.tagged("GRP-01", "TAG-01");
        releaseExports("TAG-01");
        harness.exportGroup(DeployHarness.SOURCE);

        ReleaseDiscovery.Release release = discovery().discover(admitted("TAG-01"), AUDIT_ID);

        assertThat(release.diagramGroups()).containsExactly("GRP-01");
        assertThat(release.files()).containsKeys(WORKFLOW,
            "int/jdoe/repository/Root/jdoe/xsd/release.xsl");
        String workflow = new String(release.files().get(WORKFLOW), StandardCharsets.UTF_8);
        assertThat(workflow).contains("version=\"head\"").doesNotContain("tag=\"");
        assertThat(release.fingerprint()).startsWith("sha256:");
        assertThat(release.olderThanHead()).isEmpty();
        assertThat(release.tag()).isEqualTo("TAG-01");
        assertThat(harness.launches()).containsExactly(
            "dev/node1 export --exportWorkflowUser 'jdoe' --exportWorkflowType 'technical'"
                + " --exportWorkflowGroup '' --exportTag 'TAG-01' --exportFile '"
                + exportFile(0) + "'",
            "dev/node1 export --exportWorkflowUser 'jdoe' --exportWorkflowType 'technical'"
                + " --exportWorkflowGroup 'GRP-01' --exportFile '" + exportFile(1) + "'",
            "dev/node2 export --exportWorkflowUser 'jdoe' --exportWorkflowType 'technical'"
                + " --exportWorkflowGroup '' --exportTag 'TAG-01' --exportFile '"
                + exportFile(2) + "'");
        assertThat(release).asString().doesNotContain("<Workflow");
        harness.verifyComplete();
    }

    private String exportFile(int launch) {
        List<String> lines = harness.launches();
        String line = lines.get(launch);
        return line.substring(line.indexOf("--exportFile '") + 14, line.length() - 1);
    }

    @Test
    void aTagOnNoDiagramGroupIsNotFound() {
        releaseExports("TAG-99");

        ToolErrorException e = failure("TAG-99");

        assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(e.error().message()).contains("TAG-99");
        harness.verifyComplete();
    }

    @Test
    void sourceNodesThatHoldDifferentReleasesAreInconsistent() throws IOException {
        harness.servers.get(DeployHarness.SOURCE2).publishWorkflow("Workflow-0001",
            xml -> xml.replace("<ConnectionId>5</ConnectionId>", "<ConnectionId>7</ConnectionId>"));
        harness.tagged("GRP-01", "TAG-01");
        releaseExports("TAG-01");

        ToolErrorException e = failure("TAG-01");

        assertThat(e.error().code()).isEqualTo(ErrorCode.SOURCE_INCONSISTENT);
        assertThat(e.error().message()).contains("dev", "TAG-01", "Workflow-0001.xml",
            ".reports/deploy-" + AUDIT_ID + "/source.diff");
        Path report = harness.root.resolve(".reports/deploy-" + AUDIT_ID + "/source.diff");
        assertThat(Files.readString(report)).contains(WORKFLOW + ": differs between dev/node1"
            + " and dev/node2");
        harness.verifyComplete();
    }

    @Test
    void aTagOnOnlyOneSourceNodeIsInconsistent() throws IOException {
        harness.servers.get(DeployHarness.SOURCE).tag("GRP-01", "TAG-01");
        releaseExports("TAG-01");

        ToolErrorException e = failure("TAG-01");

        assertThat(e.error().code()).isEqualTo(ErrorCode.SOURCE_INCONSISTENT);
        assertThat(e.error().message()).contains("dev/node2", "no diagram group");
        harness.verifyComplete();
    }

    @Test
    void aTaggedVersionOlderThanHeadIsNoted() {
        harness.tagged("GRP-01", "TAG-01");
        DeployHarness.SOURCES.forEach(node -> harness.servers.get(node).publishWorkflow(
            "Workflow-0002", xml -> xml.replace("xPos=\"120\"", "xPos=\"130\"")));
        releaseExports("TAG-01");
        harness.exportGroup(DeployHarness.SOURCE);

        ReleaseDiscovery.Release release = discovery().discover(admitted("TAG-01"), AUDIT_ID);

        assertThat(release.olderThanHead()).containsExactly(
            "Workflow-0002 (GRP-01): tagged version 11, head 12");
    }

    @Test
    void checkinCommentsThatDifferBetweenSourceNodesAreNoInconsistency() {
        // the nodes of a group are imported separately; their comments may differ
        harness.servers.get(DeployHarness.SOURCE2).publishWorkflow("Workflow-0001",
            xml -> xml.replace("JD: Fixture", "JD: Fixture on node 2"));
        harness.tagged("GRP-01", "TAG-01");
        releaseExports("TAG-01");
        harness.exportGroup(DeployHarness.SOURCE);

        ReleaseDiscovery.Release release = discovery().discover(admitted("TAG-01"), AUDIT_ID);

        assertThat(release.diagramGroups()).containsExactly("GRP-01");
        harness.verifyComplete();
    }
}

