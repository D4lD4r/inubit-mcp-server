package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.application.ExportHarness.DEV;
import static de.dadecker.inubit.mcp.application.ExportHarness.diagramGroups;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceInspector;
import de.dadecker.inubit.mcp.domain.model.ArtifactRef;
import de.dadecker.inubit.mcp.domain.model.ChangeSet;
import de.dadecker.inubit.mcp.domain.model.ChangedArtifact;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T010 (feature 004, research D-4, D-24, D-25): the change set of one scope, each artifact
 * against its own last server state, new artifacts against the scope's.
 */
class ChangeSetBuilderTest {

    private static final GroupId GROUP = DEV.group();
    private static final String OWNER = "jdoe";

    @TempDir
    Path root;

    private ExportHarness harness;
    private String exported;

    @BeforeEach
    void exportGrpA() {
        harness = new ExportHarness(root);
        harness.artifacts.exports.put("GRP-01", ArtifactFixtures.bytes("grp-a.zip"));
        harness.service().export(diagramGroups(OWNER, "GRP-01"));
        exported = harness.git("rev-parse", "HEAD").strip();
    }

    private ChangeSetBuilder builder() {
        return new ChangeSetBuilder(root, harness.history, new WorkspaceInspector());
    }

    private static ImportScope group() {
        return ImportScope.diagramGroup(GROUP, OWNER, "GRP-01");
    }

    private static String workflow(String diagramGroup, String name) {
        return slash(WorkspacePath.workflow(GROUP, OWNER, diagramGroup, name).toRelativePath());
    }

    /** The directory of module {@code name}, whatever its plugin type. */
    private String moduleDirectory(String name) throws IOException {
        try (Stream<Path> walk = Files.walk(root.resolve("dev/jdoe/modules"))) {
            return walk.filter(Files::isDirectory)
                .filter(dir -> dir.getFileName().toString().equals(name))
                .map(dir -> slash(root.relativize(dir))).findFirst().orElseThrow();
        }
    }

    private void edit(String path, String from, String to) throws IOException {
        Path file = root.resolve(path);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).contains(from);
        Files.writeString(file, text.replace(from, to), StandardCharsets.UTF_8);
    }

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void commitLocalChanges() {
        harness.history.commitAll("local changes: n files");
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    @Test
    void anUnchangedScopeIsAnEmptyChangeSetOnItsBase() {
        ChangeSet changes = builder().build(group());

        assertThat(changes.isEmpty()).isTrue();
        assertThat(changes.baseCommit()).isEqualTo(exported);
        assertThat(changes.notImported()).isEmpty();
    }

    @Test
    void anEditedWorkflowIsModifiedAgainstItsOwnBase() throws IOException {
        edit(workflow("GRP-01", "Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        commitLocalChanges();

        ChangeSet changes = builder().build(group());

        assertThat(changes.workflows()).singleElement().satisfies(w -> {
            assertThat(w.ref()).isEqualTo(ArtifactRef.workflow(GROUP, OWNER, "GRP-01",
                "Workflow-0001"));
            assertThat(w.kind()).isEqualTo(ChangedArtifact.Kind.MODIFIED);
            assertThat(w.base()).contains(exported);
            assertThat(w.paths()).containsExactly(workflow("GRP-01", "Workflow-0001"));
        });
        assertThat(changes.modules()).isEmpty();
        assertThat(changes.modified()).containsExactly("Workflow-0001");
        assertThat(changes.created()).isEmpty();
    }

    @Test
    void onlyTheChangedModulesOfChangedWorkflowsAreInTheScope() throws IOException {
        edit(workflow("GRP-01", "Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        String module0001 = moduleDirectory("Module-0001");
        String module0005 = moduleDirectory("Module-0005");
        Files.writeString(root.resolve(module0001 + "/module.xml"), Files.readString(
            root.resolve(module0001 + "/module.xml")).replaceFirst("</Properties>",
                "<Property name=\"x.added\">1</Property></Properties>"));
        Files.writeString(root.resolve(module0005 + "/module.xml"), Files.readString(
            root.resolve(module0005 + "/module.xml")).replaceFirst("</Properties>",
                "<Property name=\"x.added\">1</Property></Properties>"));
        commitLocalChanges();

        ChangeSet changes = builder().build(group());

        assertThat(changes.modules()).singleElement().satisfies(m -> {
            assertThat(m.ref().name()).isEqualTo("Module-0001");
            assertThat(m.ref().pluginType()).isPresent();
            assertThat(m.kind()).isEqualTo(ChangedArtifact.Kind.MODIFIED);
            assertThat(m.base()).contains(exported);
            assertThat(m.paths()).contains(module0001 + "/module.xml",
                module0001 + "/index.xml");
        });
        assertThat(changes.notImported()).containsExactly(module0005 + "/module.xml");
    }

    @Test
    void newWorkflowsAndModulesAreCreatedOnTheScopeBase() throws IOException {
        // review M4: a new artifact has no base of its own; the scope's is used
        String copy = Files.readString(root.resolve(workflow("GRP-01", "Workflow-0001")))
            .replace("Workflow-0001", "Workflow-0100").replace("Module-0001", "Module-0100");
        write(workflow("GRP-01", "Workflow-0100"), copy);
        String source = moduleDirectory("Module-0001");
        String target = source.replace("Module-0001", "Module-0100");
        write(target + "/module.xml", Files.readString(root.resolve(source + "/module.xml")));
        write(target + "/index.xml", Files.readString(root.resolve(source + "/index.xml"))
            .replace("Module-0001", "Module-0100"));
        commitLocalChanges();

        ChangeSet changes = builder().build(group());

        assertThat(changes.baseCommit()).isEqualTo(exported);
        assertThat(changes.created()).containsExactly("Workflow-0100", "Module-0100");
        assertThat(changes.modified()).isEmpty();
        assertThat(changes.workflows()).singleElement().satisfies(w -> {
            assertThat(w.kind()).isEqualTo(ChangedArtifact.Kind.NEW);
            assertThat(w.base()).isEmpty();
        });
        assertThat(changes.modules()).singleElement().satisfies(m ->
            assertThat(m.paths()).containsExactly(target + "/index.xml",
                target + "/module.xml"));
    }

    @Test
    void aNewModuleWithoutIndexEntryIsInvalid() throws IOException {
        String copy = Files.readString(root.resolve(workflow("GRP-01", "Workflow-0001")))
            .replace("Module-0001", "Module-0100");
        Files.writeString(root.resolve(workflow("GRP-01", "Workflow-0001")), copy);
        String target = moduleDirectory("Module-0001").replace("Module-0001", "Module-0100");
        write(target + "/module.xml", "<Properties/>\n");
        commitLocalChanges();

        assertThatThrownBy(() -> builder().build(group()))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.error().message()).contains("Module-0100", "index.xml");
            });
    }

    @Test
    void aDeletedWorkflowOrModuleFileIsRefused() throws IOException {
        Files.delete(root.resolve(workflow("GRP-01", "Workflow-0001")));
        commitLocalChanges();

        assertThatThrownBy(() -> builder().build(group()))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.error().message()).contains("deleting artifacts is not supported",
                    "Workflow-0001");
                assertThat(e.error().nextStep()).contains("restore the file",
                    "delete it in the Workbench");
            });
    }

    @Test
    void aDeletedFileOfAReferencedModuleIsRefused() throws IOException {
        edit(workflow("GRP-01", "Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        Files.delete(root.resolve(moduleDirectory("Module-0001") + "/index.xml"));
        commitLocalChanges();

        assertThatThrownBy(() -> builder().build(group()))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                e.error().message()).contains("deleting artifacts is not supported",
                    "Module-0001"));
    }

    @Test
    void aChangeBelowTheRepositoryIsRefused() throws IOException {
        write("dev/jdoe/repository/Root/jdoe/xsd/new.xsd", "<xs:schema/>\n");
        commitLocalChanges();

        assertThatThrownBy(() -> builder().build(group()))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.error().message()).contains("repository");
            });
    }

    @Test
    void changesOutsideTheScopeAreListedAsNotImported() throws IOException {
        write(workflow("GRP-09", "Workflow-0900"), "<Workflow/>\n");
        edit(workflow("GRP-01", "Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        commitLocalChanges();

        ChangeSet changes = builder().build(group());

        assertThat(changes.modified()).containsExactly("Workflow-0001");
        assertThat(changes.notImported()).containsExactly(workflow("GRP-09", "Workflow-0900"));
    }

    @Test
    void anEditThatWasUndoneIsNoChange() throws IOException {
        String file = workflow("GRP-01", "Workflow-0001");
        edit(file, "xPos=\"120\"", "xPos=\"140\"");
        commitLocalChanges();
        edit(file, "xPos=\"140\"", "xPos=\"120\"");
        commitLocalChanges();

        assertThat(builder().build(group()).isEmpty()).isTrue();
    }

    @Test
    void aModuleScopeTakesOnlyTheNamedModules() throws IOException {
        String module0005 = moduleDirectory("Module-0005");
        Files.writeString(root.resolve(module0005 + "/module.xml"), Files.readString(
            root.resolve(module0005 + "/module.xml")).replaceFirst("</Properties>",
                "<Property name=\"x.added\">1</Property></Properties>"));
        edit(workflow("GRP-01", "Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        commitLocalChanges();

        ChangeSet changes = builder().build(ImportScope.modules(GROUP, OWNER,
            List.of(new ImportScope.Module("Module-0005", Optional.empty()),
                new ImportScope.Module("Module-0006", Optional.empty()))));

        assertThat(changes.workflows()).isEmpty();
        assertThat(changes.modified()).containsExactly("Module-0005");
        assertThat(changes.notImported()).containsExactly(workflow("GRP-01", "Workflow-0001"));
        assertThat(changes.baseCommit()).isEqualTo(exported);
    }

    @Test
    void aNamedModuleThatIsNotInTheWorkspaceIsInvalid() {
        assertThatThrownBy(() -> builder().build(ImportScope.modules(GROUP, OWNER,
            List.of(new ImportScope.Module("Module-9999", Optional.of("Assign"))))))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.error().message()).contains("Module-9999");
            });
    }

    @Test
    void aScopeThatWasNeverExportedMustBeExportedFirst() throws IOException {
        write(workflow("GRP-02", "Workflow-0200"), "<Workflow/>\n");
        commitLocalChanges();

        assertThatThrownBy(() -> builder().build(ImportScope.diagramGroup(GROUP, OWNER,
            "GRP-02"))).isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                assertThat(e.error().nextStep()).contains("export the scope first");
            });
    }
}
