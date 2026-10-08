package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.application.ExportHarness.DEV;
import static de.dadecker.inubit.mcp.application.ExportHarness.diagramGroups;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceInspector;
import de.dadecker.inubit.mcp.domain.model.ChangeSet;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T015 (feature 004, research D-5, D-25): the fresh export of the scope against each artifact's
 * own base — changed on the server, in edit mode, or created there meanwhile is a CONFLICT with
 * a difference file; scope artifacts outside the change set count as well.
 */
class ConflictDetectorTest {

    private static final GroupId GROUP = DEV.group();
    private static final String OWNER = "jdoe";

    @TempDir
    Path root;

    private ExportHarness harness;
    private final List<String> moduleListCalls = new java.util.ArrayList<>();

    @BeforeEach
    void exportGrpA() {
        harness = new ExportHarness(root);
        harness.artifacts.exports.put("GRP-01", ArtifactFixtures.bytes("grp-a.zip"));
        harness.service().export(diagramGroups(OWNER, "GRP-01"));
        harness.artifacts.calls.clear();
    }

    private ConflictDetector detector() {
        return new ConflictDetector(root, harness.history, new ArchiveCodec(),
            node -> harness.artifacts, node -> inventory());
    }

    private InventoryPort inventory() {
        return new InventoryPort() {
            @Override
            public List<InventoryItem> listDiagrams(String owner) {
                throw new AssertionError("not used");
            }

            @Override
            public DiagramDetail diagramDetail(String owner, String name) {
                throw new AssertionError("not used");
            }

            @Override
            public DiagramMetadata diagramMetadata(String name) {
                throw new AssertionError("not used");
            }

            @Override
            public VersionHistory versionHistory(String owner, String type, String group) {
                throw new AssertionError("not used");
            }

            @Override
            public List<ModuleEntry> listModules(String owner) {
                moduleListCalls.add(owner);
                return List.of(module("Module-0001"), module("Module-0500"));
            }
        };
    }

    private static InventoryPort.ModuleEntry module(String name) {
        return new InventoryPort.ModuleEntry(new InventoryItem(DEV,
            de.dadecker.inubit.mcp.domain.model.InventoryKind.MODULE, name, "Assign", "Assign",
            OWNER, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            Optional.empty(), Optional.empty(),
            new de.dadecker.inubit.mcp.domain.model.ConnectorFlags(false, false, false),
            Optional.empty());
    }

    private static String workflow(String name) {
        return WorkspacePath.workflow(GROUP, OWNER, "GRP-01", name).toRelativePath().toString()
            .replace('\\', '/');
    }

    private ChangeSet editWorkflow(String name, String from, String to) throws IOException {
        Path file = root.resolve(workflow(name));
        String text = Files.readString(file);
        assertThat(text).contains(from);
        Files.writeString(file, text.replace(from, to));
        harness.history.commitAll("local changes: 1 files");
        return new ChangeSetBuilder(root, harness.history, new WorkspaceInspector())
            .build(ImportScope.diagramGroup(GROUP, OWNER, "GRP-01"));
    }

    private static byte[] serverChanged(String from, String to) {
        return ExportHarness.rewrite("grp-a.zip", "workflow/workflow.xml",
            xml -> xml.replace(from, to));
    }

    @Test
    void anUnchangedServerHasNoConflictAndHandsBackTheRawExportAndModuleList()
        throws IOException {
        ChangeSet changes = editWorkflow("Workflow-0001", "xPos=\"120\"", "xPos=\"140\"");

        ConflictDetector.Result result = detector().detect(DEV, changes, UUID.randomUUID());

        assertThat(harness.artifacts.calls).containsExactly("group jdoe GRP-01");
        assertThat(result.rawExports()).singleElement()
            .isEqualTo(ArtifactFixtures.bytes("grp-a.zip"));
        assertThat(result.fingerprint()).startsWith("sha256:");
        assertThat(result.targetModules()).contains("Module-0001", "Module-0500",
            "Module-0003");
        assertThat(moduleListCalls).containsExactly(OWNER);
    }

    @Test
    void aChangeOnTheServerOfAChangeSetArtifactIsAConflictWithADifferenceFile()
        throws IOException {
        ChangeSet changes = editWorkflow("Workflow-0001", "xPos=\"120\"", "xPos=\"140\"");
        harness.artifacts.exports.put("GRP-01", serverChanged("xPos=\"520\" yPos=\"130\"",
            "xPos=\"530\" yPos=\"130\""));
        UUID auditId = UUID.randomUUID();

        assertThatThrownBy(() -> detector().detect(DEV, changes, auditId))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CONFLICT);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().message()).contains("Workflow-0001", "changed on",
                    ".reports/conflict-" + auditId + ".diff");
            });
        String diff = Files.readString(root.resolve(".reports/conflict-" + auditId + ".diff"));
        assertThat(diff).contains(workflow("Workflow-0001"), "xPos=\"530\"", "xPos=\"520\"");
    }

    @Test
    void aChangeOnTheServerOfAnotherWorkflowOfTheScopeIsAConflictToo() throws IOException {
        ChangeSet changes = editWorkflow("Workflow-0001", "xPos=\"120\"", "xPos=\"140\"");
        harness.artifacts.exports.put("GRP-01", ExportHarness.rewrite("grp-a.zip",
            "workflow/workflow.xml", xml -> xml.replaceFirst(
                "(<WorkflowName>Workflow-0002</WorkflowName>(?s:.*?))xPos=\"120\"",
                "$1xPos=\"125\"")));

        assertThatThrownBy(() -> detector().detect(DEV, changes, UUID.randomUUID()))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CONFLICT);
                assertThat(e.error().message()).contains("Workflow-0002");
            });
    }

    @Test
    void aWorkflowOfTheChangeSetInEditModeIsAConflictNamingTheUser() throws IOException {
        // grp-a's Workflow-0002 is in edit mode (CheckoutUser jdoe)
        ChangeSet changes = editWorkflow("Workflow-0002", "xPos=\"120\"", "xPos=\"140\"");

        assertThatThrownBy(() -> detector().detect(DEV, changes, UUID.randomUUID()))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CONFLICT);
                assertThat(e.error().message()).contains("Workflow-0002", "edit mode",
                    "jdoe");
                assertThat(e.error().nextStep()).contains("Workbench");
            });
    }

    @Test
    void aNewArtifactThatExistsOnTheServerMeanwhileIsUpdatedOrNotSentIfIdentical()
        throws IOException {
        // 0.4.2: no conflict — e.g. created by an import that was rolled back
        String copy = Files.readString(root.resolve(workflow("Workflow-0001")))
            .replace("Workflow-0001", "Workflow-0100");
        Files.writeString(root.resolve(workflow("Workflow-0100")), copy);
        harness.history.commitAll("local changes: 1 files");
        ChangeSet changes = new ChangeSetBuilder(root, harness.history,
            new WorkspaceInspector()).build(ImportScope.diagramGroup(GROUP, OWNER, "GRP-01"));
        harness.artifacts.exports.put("GRP-01", ExportHarness.rewrite("grp-a.zip",
            "workflow/workflow.xml", xml -> xml.replace(
                "<WorkflowName>Workflow-0001</WorkflowName>",
                "<WorkflowName>Workflow-0100</WorkflowName>")));

        ChangeSet identical = detector().detect(DEV, changes, UUID.randomUUID()).changes();

        assertThat(identical.isEmpty()).isTrue();
        assertThat(identical.identicalNames()).containsExactly("Workflow-0100");

        harness.artifacts.exports.put("GRP-01", ExportHarness.rewrite("grp-a.zip",
            "workflow/workflow.xml", xml -> xml.replace(
                "<WorkflowName>Workflow-0001</WorkflowName>",
                "<WorkflowName>Workflow-0100</WorkflowName>").replaceFirst(
                    "(<WorkflowName>Workflow-0100</WorkflowName>(?s:.*?))xPos=\"120\"",
                    "$1xPos=\"125\"")));

        ChangeSet existing = detector().detect(DEV, changes, UUID.randomUUID()).changes();

        assertThat(existing.created()).isEmpty();
        assertThat(existing.modified()).containsExactly("Workflow-0100");
        assertThat(existing.existing()).containsExactly("Workflow-0100");
        assertThat(existing.identical()).isEmpty();
    }

    @Test
    void whatARollbackRewritesIsNoConflictWithReviewedContent() throws IOException {
        // 0.4.2: the rollback's check-in comment and last update are no change of content
        ChangeSet changes = editWorkflow("Workflow-0001", "xPos=\"120\"", "xPos=\"140\"");
        harness.artifacts.exports.put("GRP-01", ExportHarness.rewrite("grp-a.zip",
            "workflow/workflow.xml", xml -> xml.replaceAll("<CheckinComment>[^<]*"
                + "</CheckinComment>", "<CheckinComment>DefaultCommitCommentImport###Rollback"
                + " of import x###</CheckinComment>")));
        ConflictDetector reviewed = new ConflictDetector(root, harness.history,
            new ArchiveCodec(), node -> harness.artifacts, node -> inventory(),
            new de.dadecker.inubit.mcp.adapter.archive.v81.V81ImportArchives()::equivalent);

        assertThat(reviewed.detect(DEV, changes, UUID.randomUUID()).changes().modified())
            .containsExactly("Workflow-0001");
        assertThatThrownBy(() -> detector().detect(DEV, changes, UUID.randomUUID()))
            .isInstanceOf(ToolErrorException.class);
    }

    @Test
    void theFingerprintIgnoresTheGrowingCommentHistoryButNotContent() throws IOException {
        ChangeSet changes = editWorkflow("Workflow-0001", "xPos=\"120\"", "xPos=\"140\"");
        String first = detector().detect(DEV, changes, UUID.randomUUID()).fingerprint();
        harness.artifacts.exports.put("GRP-01", ExportHarness.grownComments());

        String grown = detector().detect(DEV, changes, UUID.randomUUID()).fingerprint();

        assertThat(grown).isEqualTo(first);
        assertThat(ConflictDetector.fingerprint(new java.util.TreeMap<>(java.util.Map.of(
            workflow("Workflow-0001"), "a".getBytes(StandardCharsets.UTF_8)))))
            .isNotEqualTo(first);
    }

    @Test
    void theDifferenceReportNamesDifferencesThatAreNotVisible() {
        // 0.4.2: "@@ line 16 @@" alone did not say that only the final line break differed
        StringBuilder diff = new StringBuilder();
        ConflictDetector.LineDiff.append(diff, "a.xsl", "intended", "server now",
            bytes("<a>\n</a>\n"), bytes("<a>\n</a>"));
        ConflictDetector.LineDiff.append(diff, "b.xsl", "intended", "server now",
            bytes("<a>\r\n</a>"), bytes("<a>\n</a>"));
        ConflictDetector.LineDiff.append(diff, "c.xsl", "intended", "server now",
            bytes("<a> \n</a>"), bytes("<a>\n</a>"));
        ConflictDetector.LineDiff.append(diff, "d.xml", "intended", "server now",
            bytes("<a/>"), null);

        assertThat(diff.toString())
            .contains("--- a.xsl (intended)", "+++ a.xsl (server now)",
                "\\ missing trailing newline (server now)")
            .contains("\\ line ends differ: CRLF (intended), LF (server now)")
            .contains("-<a> \n+<a>\n\\ the lines differ in trailing whitespace only")
            .contains("\\ the file is missing (server now)");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
