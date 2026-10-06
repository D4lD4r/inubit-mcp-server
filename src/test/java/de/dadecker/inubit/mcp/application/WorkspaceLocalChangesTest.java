package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.application.ExportHarness.diagramGroups;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.application.WorkspaceService.ExportResult;
import de.dadecker.inubit.mcp.domain.model.HistoryEntry;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T022 (clarification 1, FR-019): uncommitted changes in the workspace are recorded as
 * {@code local changes: <n> files} before the export entry; nothing is discarded.
 */
class WorkspaceLocalChangesTest {

    private static final String WORKFLOW = "dev/jdoe/workflows/GRP-01/Workflow-0001.xml";
    private static final String NOTES = "dev/jdoe/notes.txt";
    private static final String DRAFT = "dev/jdoe/workflows/GRP-01/Draft.xml";

    @TempDir
    Path root;

    private ExportHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ExportHarness(root);
        harness.artifacts.exports.put("GRP-01", ArtifactFixtures.bytes("grp-a.zip"));
    }

    private void write(String path, String content) throws IOException {
        Files.createDirectories(root.resolve(path).getParent());
        Files.writeString(root.resolve(path), content, StandardCharsets.UTF_8);
    }

    @Test
    void theFirstExportHasNoLocalChanges() {
        ExportResult first = harness.service().export(diagramGroups("jdoe", "GRP-01"));

        assertThat(first.localChanges()).isEmpty();
        assertThat(first.export().orElseThrow().changes())
            .contains(new PathChange(".gitignore", PathChange.Kind.ADDED));
    }

    @Test
    void editedAndAddedFilesAreCommittedBeforeTheExport() throws IOException {
        WorkspaceService service = harness.service();
        service.export(diagramGroups("jdoe", "GRP-01"));
        String exported = Files.readString(root.resolve(WORKFLOW));
        write(WORKFLOW, exported.replace("</Workflow>", "<!-- edited locally --></Workflow>"));
        write(NOTES, "review notes\n");
        write(DRAFT, "<Workflow/>\n");

        ExportResult result = service.export(diagramGroups("jdoe", "GRP-01"));

        assertThat(result.localChanges()).as("local changes recorded first").isPresent();
        HistoryEntry local = result.localChanges().orElseThrow();
        assertThat(local.message()).isEqualTo("local changes: 3 files");
        assertThat(local.changes()).containsExactlyInAnyOrder(
            new PathChange(WORKFLOW, PathChange.Kind.MODIFIED),
            new PathChange(NOTES, PathChange.Kind.ADDED),
            new PathChange(DRAFT, PathChange.Kind.ADDED));
        HistoryEntry export = result.export().orElseThrow();
        assertThat(export.changes()).containsExactlyInAnyOrder(
            new PathChange(WORKFLOW, PathChange.Kind.MODIFIED),
            new PathChange(DRAFT, PathChange.Kind.DELETED));
        assertThat(harness.log()).hasSize(3).startsWith(export.message(), local.message());

        // nothing is discarded: the edits stay in the history, the notes in the workspace
        assertThat(harness.git("show", local.commit() + ":" + WORKFLOW))
            .contains("<!-- edited locally -->");
        assertThat(harness.git("show", local.commit() + ":" + DRAFT)).contains("<Workflow/>");
        assertThat(root.resolve(NOTES)).hasContent("review notes");
        assertThat(Files.readString(root.resolve(WORKFLOW))).isEqualTo(exported);
    }

    @Test
    void theCountOfLocalChangesMatchesWhatIsCommitted() throws IOException {
        // review M4: with other local changes, the new .gitignore is part of the same entry
        write(NOTES, "notes written before the first export\n");

        ExportResult first = harness.service().export(diagramGroups("jdoe", "GRP-01"));

        HistoryEntry local = first.localChanges().orElseThrow();
        assertThat(local.changes()).containsExactlyInAnyOrder(
            new PathChange(NOTES, PathChange.Kind.ADDED),
            new PathChange(".gitignore", PathChange.Kind.ADDED));
        assertThat(local.message()).isEqualTo("local changes: " + local.changes().size()
            + " files");
    }

    @Test
    void aCleanWorkspaceRecordsNoLocalChanges() {
        WorkspaceService service = harness.service();
        service.export(diagramGroups("jdoe", "GRP-01"));

        ExportResult second = service.export(diagramGroups("jdoe", "GRP-01"));

        assertThat(second.localChanges()).isEmpty();
        assertThat(harness.log()).hasSize(1);
    }
}
