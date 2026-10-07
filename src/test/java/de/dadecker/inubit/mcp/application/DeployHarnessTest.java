package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.RepositoryArchive;
import de.dadecker.inubit.mcp.adapter.archive.v81.RepositoryArchive.RepositoryFile;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T010 (feature 005, research D-16): the deployment harness — one {@link FakeInubit} per node
 * behind the real 8.1 adapters — answers tag (release) exports with the tagged versions and the
 * referenced repository files, repository exports and imports with versions and relative entries,
 * and applies the active-flag option of a workflow import.
 */
class DeployHarnessTest {

    @TempDir
    Path temp;

    private DeployHarness harness;

    @BeforeEach
    void setUp() throws IOException {
        harness = new DeployHarness(temp);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void hasTheSourceThreeTargetNodesAndAPackageOnlyNode() {
        assertThat(harness.servers.keySet()).containsExactly(DeployHarness.SOURCE,
            DeployHarness.INT1, DeployHarness.INT2, DeployHarness.INT3, DeployHarness.PROD);
        assertThat(DeployHarness.TARGETS).containsExactly(DeployHarness.INT1, DeployHarness.INT2,
            DeployHarness.INT3);
        assertThat(harness.root.resolve(".git")).doesNotExist();
        assertThat(harness.audit).isEmpty();
    }

    @Test
    void theReleaseExportHoldsTheTaggedStateWithTheTaggedRepositoryFile() {
        FakeInubit source = harness.servers.get(DeployHarness.SOURCE);
        harness.tagMoved(DeployHarness.SOURCE, "TAG-01");
        harness.tags(DeployHarness.SOURCE).tag("TAG-01", DeployHarness.GROUP, DeployHarness.OWNER);
        source.putRepositoryFile(DeployHarness.RELEASE_XSL, "<xsl:stylesheet v2/>");
        source.changeWorkflows(xml -> xml.replace("xPos=\"120\"", "xPos=\"999\""));
        harness.exportRelease(DeployHarness.SOURCE, "TAG-01");

        byte[] release = harness.artifacts(DeployHarness.SOURCE).exportRelease(
            DeployHarness.OWNER, "TAG-01");

        Map<String, byte[]> entries = ArtifactFixtures.entries(release);
        assertThat(entries).containsKeys("usertags.xml", "workflow/workflow.xml",
            "module/module.xml", "Repository.zip");
        String workflows = text(entries.get("workflow/workflow.xml"));
        assertThat(workflows).contains("<WorkflowGroupName>GRP-01</WorkflowGroupName>",
            "@@@Tag: TAG-01@@@", "tag=\"TAG-01\"").doesNotContain("version=\"head\"")
            .doesNotContain("xPos=\"999\"");
        assertThat(RepositoryArchive.read(entries.get("Repository.zip"))).singleElement()
            .satisfies(file -> {
                assertThat(file.path()).isEqualTo(DeployHarness.RELEASE_XSL);
                assertThat(text(file.metadata())).contains("tagName=\"TAG-01\"",
                    "version=\"1.0\"");
                assertThat(text(file.content())).isEqualTo(DeployHarness.RELEASE_XSL_V1);
            });
        assertThat(source.repositoryVersion(DeployHarness.RELEASE_XSL)).contains("1.1");
        harness.verifyComplete();
    }

    @Test
    void anUnknownTagIsNotFound() {
        harness.exportRelease(DeployHarness.SOURCE, "TAG-99");

        assertThatThrownBy(() -> harness.artifacts(DeployHarness.SOURCE).exportRelease(
            DeployHarness.OWNER, "TAG-99")).isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND));
        harness.verifyComplete();
    }

    @Test
    void repositoryImportsCreateAndVersionFilesFromRelativeEntries() {
        FakeInubit target = harness.servers.get(DeployHarness.INT1);
        RepositoryFile file = RepositoryArchive.read(ArtifactFixtures.entries(
            harness.servers.get(DeployHarness.SOURCE).exportWorkflowGroup())
            .get("Repository.zip")).get(0);
        harness.exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL)
            .importRepositoryApplied(DeployHarness.INT1)
            .importRepositoryApplied(DeployHarness.INT1)
            .exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);

        assertThatThrownBy(() -> harness.artifacts(DeployHarness.INT1).exportRepository(
            DeployHarness.RELEASE_XSL)).isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND));
        byte[] archive = RepositoryArchive.build(DeployHarness.OWNER, List.of(file));
        harness.imports(DeployHarness.INT1).importRepository(archive, DeployHarness.OWNER);
        String uuid = target.repositoryUuid(DeployHarness.RELEASE_XSL).orElseThrow();
        harness.imports(DeployHarness.INT1).importRepository(archive, DeployHarness.OWNER);
        List<RepositoryFile> exported = RepositoryArchive.read(harness.artifacts(
            DeployHarness.INT1).exportRepository(DeployHarness.RELEASE_XSL));

        assertThat(target.repositoryVersion(DeployHarness.RELEASE_XSL)).contains("1.1");
        assertThat(target.repositoryUuid(DeployHarness.RELEASE_XSL)).contains(uuid);
        assertThat(uuid).isNotEqualTo("00000000-0000-0000-0000-000000000101");
        assertThat(exported).singleElement().satisfies(back -> {
            assertThat(back.content()).isEqualTo(file.content());
            assertThat(text(back.metadata())).contains("version=\"1.1\"",
                "versionComment=\"DefaultCommitCommentImport@@@DefaultCommitCommentImport@@@")
                .doesNotContain("tagName=");
        });
        assertThat(target.repositoryImports).hasSize(2);
        harness.verifyComplete();
    }

    @Test
    void theActiveFlagOfAWorkflowImportIsApplied() {
        FakeInubit target = harness.servers.get(DeployHarness.INT2);
        byte[] export = target.exportWorkflowGroup();
        harness.importApplied(DeployHarness.INT2, "--importWorkflow --importWorkflowActive"
            + " --importUser 'jdoe' --returnProtocol");

        harness.imports(DeployHarness.INT2).importArchive(export,
            ImportPort.Mode.WORKFLOW_ACTIVE, DeployHarness.OWNER);

        assertThat(target.workflowXml()).contains("<IsActive>true</IsActive>")
            .doesNotContain("<IsActive>false</IsActive>");
        assertThat(target.importFlags).containsExactly(Boolean.TRUE);
        harness.verifyComplete();
    }

    @Test
    void aTargetWithoutTheDiagramGroupAnswersNotFoundUntilItIsImported() throws IOException {
        DeployHarness empty = new DeployHarness(temp.resolve("empty"), true);
        FakeInubit target = empty.servers.get(DeployHarness.INT3);
        byte[] release = empty.servers.get(DeployHarness.SOURCE).exportWorkflowGroup();
        empty.exportGroup(DeployHarness.INT3)
            .importApplied(DeployHarness.INT3, "--importWorkflow --importWorkflowInactive"
                + " --importUser 'jdoe' --returnProtocol")
            .exportGroup(DeployHarness.INT3);

        assertThatThrownBy(() -> empty.artifacts(DeployHarness.INT3).exportWorkflowGroup(
            DeployHarness.OWNER, DeployHarness.GROUP)).isInstanceOfSatisfying(
                ToolErrorException.class, e -> assertThat(e.error().code())
                    .isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(target.hasModule("Module-0001")).isFalse();
        empty.imports(DeployHarness.INT3).importArchive(release,
            ImportPort.Mode.WORKFLOW_INACTIVE, DeployHarness.OWNER);
        String workflows = text(ArtifactFixtures.entries(empty.artifacts(DeployHarness.INT3)
            .exportWorkflowGroup(DeployHarness.OWNER, DeployHarness.GROUP))
            .get("workflow/workflow.xml"));

        assertThat(workflows).contains("<WorkflowName>Workflow-0001</WorkflowName>",
            "<WorkflowName>Workflow-0002</WorkflowName>");
        assertThat(target.hasModule("Module-0001")).isTrue();
        empty.verifyComplete();
    }

    @Test
    void aLaunchThatWasNotScriptedFailsOnItsOwnNode() {
        assertThatThrownBy(() -> harness.artifacts(DeployHarness.INT2).exportRelease(
            DeployHarness.OWNER, "TAG-01")).isInstanceOf(AssertionError.class);
        assertThatThrownBy(harness::verifyComplete).isInstanceOf(AssertionError.class)
            .hasMessageContaining("int/node2");
    }
}
