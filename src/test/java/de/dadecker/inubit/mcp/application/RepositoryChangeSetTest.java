package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.archive.v81.V81ReleaseArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceInspector;
import de.dadecker.inubit.mcp.domain.model.ChangeSet;
import de.dadecker.inubit.mcp.domain.model.ChangedArtifact;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T020 (feature 008, research D-4, FR-010, FR-011, FR-011a, FR-017): the repository files of a
 * change set, by scope, and the refusals before anything is read from the node.
 *
 * <p>The workspace is {@code GRP-01} of {@code jdoe} plus a simulated repository export:
 * {@code Module-0001} (used by {@code Workflow-0001}) names {@code xsd/a.xsd}, which includes
 * {@code b.xsd}; {@code Module-0005} names {@code xsl/c.xsl}; nothing references
 * {@code other/d.xsd}.
 */
class RepositoryChangeSetTest {

    private static final GroupId GROUP = ImportHarness.DEV.group();
    private static final String OWNER = "jdoe";
    private static final String XSD = "application/x-xsd+xml";
    private static final String XSL = "application/xslt+xml";
    private static final int MIB = 1024 * 1024;

    @TempDir
    Path temp;

    private ImportHarness harness;
    private String exported;

    @BeforeEach
    void exportRepository() throws IOException {
        harness = ImportHarness.grpA(temp);
        reference("Module-0001", "/Root/jdoe/xsd/a.xsd");
        reference("Module-0005", "/Root/jdoe/xsl/c.xsl");
        harness.repositoryFile("xsd/a.xsd", schema("<xs:include schemaLocation=\"b.xsd\"/>"), XSD);
        harness.repositoryFile("xsd/b.xsd", schema(""), XSD);
        harness.repositoryFile("xsl/c.xsl", "<xsl:stylesheet version=\"2.0\" xmlns:xsl="
            + "\"http://www.w3.org/1999/XSL/Transform\"/>\n", XSL);
        harness.repositoryFile("other/d.xsd", schema(""), XSD);
        exported = serverState();
    }

    // --- helpers -----------------------------------------------------------------------------

    private ChangeSetBuilder builder() {
        return new ChangeSetBuilder(harness.root, harness.exports.history,
            new WorkspaceInspector(), new V81ReleaseArchives());
    }

    private static String schema(String body) {
        return "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">" + body
            + "</xs:schema>\n";
    }

    /** Lets module {@code module} name {@code repositoryPath} by URI. */
    private void reference(String module, String repositoryPath) {
        harness.edit(harness.moduleDirectory(module) + "/module.xml", "</Properties>",
            "<Property name=\"x.repository\">inubitrepository:" + repositoryPath
                + "</Property></Properties>");
    }

    /** Commits the workspace as a verified server state of the group, as an export does. */
    private String serverState() {
        harness.exports.history.commitAll("export dev/jdoe: repository",
            Map.of(VersionHistoryPort.SERVER_STATE, GROUP.value()));
        return harness.exports.git("rev-parse", "HEAD").strip();
    }

    private void commitLocalChanges() {
        harness.exports.history.commitAll("local changes: n files");
    }

    /** The workspace path of {@code /Root/jdoe/<relative>}. */
    private static String file(String relative) {
        return "dev/jdoe/repository/Root/jdoe/" + relative;
    }

    private void change(String relative) {
        harness.edit(file(relative), "</xs:schema>", "<xs:element name=\"x\"/></xs:schema>");
    }

    private void write(String workspacePath, byte[] content) throws IOException {
        Path target = harness.root.resolve(workspacePath);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    private static ImportScope paths(String... paths) {
        return ImportScope.repositoryPaths(GROUP, OWNER, List.of(paths));
    }

    private static ImportScope group() {
        return ImportScope.diagramGroup(GROUP, OWNER, "GRP-01");
    }

    private static List<String> names(List<ChangedArtifact> artifacts) {
        return artifacts.stream().map(ChangedArtifact::name).toList();
    }

    /** The refusal of {@code scope}; the builder never starts StartCLI. */
    private ToolError refusal(ImportScope scope) {
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> builder().build(scope));
        assertThat(e).as("expected a refusal").isNotNull();
        assertThat(harness.cli.launches()).isEmpty();
        return e.error();
    }

    // --- scopes ------------------------------------------------------------------------------

    @Test
    void repositoryPathsTakeEveryChangedFileAtOrBelowThePaths() throws IOException {
        change("xsd/a.xsd");
        change("xsd/b.xsd");
        change("other/d.xsd");
        harness.edit(file("xsl/c.xsl"), "/>", "></xsl:stylesheet>");
        write(file("xsd/sub/new.xsd"), schema("").getBytes(StandardCharsets.UTF_8));
        write(file("xsdx/e.xsd"), schema("").getBytes(StandardCharsets.UTF_8));
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        commitLocalChanges();

        ChangeSet changes = builder().build(paths("xsd", "other/d.xsd"));

        assertThat(names(changes.repository())).containsExactly("/Root/jdoe/other/d.xsd",
            "/Root/jdoe/xsd/a.xsd", "/Root/jdoe/xsd/b.xsd", "/Root/jdoe/xsd/sub/new.xsd");
        assertThat(changes.repository().get(1)).satisfies(a -> {
            assertThat(a.kind()).isEqualTo(ChangedArtifact.Kind.MODIFIED);
            assertThat(a.base()).contains(exported);
            assertThat(a.paths()).containsExactly(file("xsd/a.xsd"));
        });
        assertThat(changes.repository().get(3)).satisfies(added -> {
            assertThat(added.kind()).isEqualTo(ChangedArtifact.Kind.NEW);
            assertThat(added.base()).isEmpty();
        });
        assertThat(changes.workflows()).isEmpty();
        assertThat(changes.modules()).isEmpty();
        assertThat(changes.notImported()).containsExactlyInAnyOrder(file("xsl/c.xsl"),
            file("xsdx/e.xsd"), harness.workflow("Workflow-0001"));
        assertThat(changes.baseCommit()).isEqualTo(exported);
    }

    @Test
    void aDiagramGroupTakesTheClosureOfEveryModuleItsWorkflowsUse() {
        // b.xsd is reached only through the unchanged a.xsd of the unchanged Module-0001
        change("xsd/b.xsd");
        change("other/d.xsd");
        commitLocalChanges();

        ChangeSet changes = builder().build(group());

        assertThat(names(changes.repository())).containsExactly("/Root/jdoe/xsd/b.xsd");
        assertThat(changes.repository().get(0).kind())
            .isEqualTo(ChangedArtifact.Kind.MODIFIED);
        assertThat(changes.notImported()).containsExactly(file("other/d.xsd"));
        // FR-011a: only repository files is a valid change set
        assertThat(changes.workflows()).isEmpty();
        assertThat(changes.modules()).isEmpty();
        assertThat(changes.isEmpty()).isFalse();
        assertThat(changes.modified()).containsExactly("/Root/jdoe/xsd/b.xsd");
    }

    @Test
    void aModuleScopeTakesTheClosureOfTheListedModules() {
        change("xsd/a.xsd");
        harness.edit(file("xsl/c.xsl"), "/>", "></xsl:stylesheet>");
        commitLocalChanges();

        ChangeSet changes = builder().build(ImportScope.modules(GROUP, OWNER,
            List.of(new ImportScope.Module("Module-0005", Optional.empty()))));

        assertThat(names(changes.repository())).containsExactly("/Root/jdoe/xsl/c.xsl");
        assertThat(changes.notImported()).containsExactly(file("xsd/a.xsd"));
        assertThat(changes.modules()).isEmpty();
    }

    @Test
    void aFileWithoutServerStateIsNewWhateverItsExtensionAndWithoutMetadata() throws IOException {
        // FR-017 amendment: the extension of a file to create is checked after the node read
        write(file("notes/readme.txt"), "plain text\n".getBytes(StandardCharsets.UTF_8));
        commitLocalChanges();

        ChangeSet changes = builder().build(paths("notes"));

        assertThat(changes.repository()).singleElement().satisfies(added -> {
            assertThat(added.name()).isEqualTo("/Root/jdoe/notes/readme.txt");
            assertThat(added.kind()).isEqualTo(ChangedArtifact.Kind.NEW);
            assertThat(added.base()).isEmpty();
        });
        assertThat(changes.created()).containsExactly("/Root/jdoe/notes/readme.txt");
    }

    @Test
    void aDeletionOutOfScopeIsNotImported() throws IOException {
        Files.delete(harness.root.resolve(file("other/d.xsd")));
        commitLocalChanges();

        ChangeSet changes = builder().build(group());

        assertThat(changes.isEmpty()).isTrue();
        assertThat(changes.notImported()).containsExactly(file("other/d.xsd"));
    }

    // --- refusals ----------------------------------------------------------------------------

    @Test
    void aDeletedFileInScopeIsRefused() throws IOException {
        Files.delete(harness.root.resolve(file("xsd/b.xsd")));
        commitLocalChanges();

        for (ImportScope scope : List.of(paths("xsd"), group())) {
            ToolError error = refusal(scope);
            assertThat(error.code()).as(scope.describe()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(error.message()).contains("deleting repository files is not supported",
                "/Root/jdoe/xsd/b.xsd");
        }
    }

    @Test
    void aFileBelowAnotherOwnersRootIsRefused() throws IOException {
        write("dev/jdoe/repository/Root/other/xsd/x.xsd",
            schema("").getBytes(StandardCharsets.UTF_8));
        commitLocalChanges();

        for (ImportScope scope : List.of(paths("xsd"), group())) {
            ToolError error = refusal(scope);
            assertThat(error.code()).as(scope.describe()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(error.message()).contains("/Root/other/xsd/x.xsd");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"keys/partner.p12", "certs/partner.cer"})
    void keyMaterialIsRefused(String path) throws IOException {
        write(file(path), "binary".getBytes(StandardCharsets.UTF_8));
        commitLocalChanges();

        ToolError error = refusal(paths(path));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("/Root/jdoe/" + path, "key material");
    }

    @Test
    void aSecretPlaceholderIsRefused() {
        harness.edit(file("xsd/a.xsd"), "</xs:schema>",
            "<xs:annotation><xs:appinfo>${secret:Password}</xs:appinfo></xs:annotation>"
                + "</xs:schema>");
        commitLocalChanges();

        ToolError error = refusal(group());

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("/Root/jdoe/xsd/a.xsd", "${secret:");
    }

    @Test
    void aFileOverTenMebibytesIsRefused() throws IOException {
        byte[] limit = new byte[10 * MIB];
        Arrays.fill(limit, (byte) 'x');
        write(file("big/data.xml"), limit);
        commitLocalChanges();
        assertThat(builder().build(paths("big")).repository()).hasSize(1);

        write(file("big/data.xml"), Arrays.copyOf(limit, limit.length + 1));
        commitLocalChanges();
        ToolError error = refusal(paths("big"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("/Root/jdoe/big/data.xml", "10 MiB");
    }

    @Test
    void moreThanFiftyModifiedFilesAreRefused() {
        for (int i = 0; i < 51; i++) {
            harness.repositoryFile("many/f" + i + ".xsd", schema(""), XSD);
        }
        serverState();
        for (int i = 0; i < 51; i++) {
            change("many/f" + i + ".xsd");
        }
        commitLocalChanges();

        ToolError error = refusal(paths("many"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("51", "50");
    }

    @Test
    void moreThanTwoHundredNewAndModifiedFilesAreRefused() throws IOException {
        for (int i = 0; i < 50; i++) {
            harness.repositoryFile("many/f" + i + ".xsd", schema(""), XSD);
        }
        serverState();
        for (int i = 0; i < 50; i++) {
            change("many/f" + i + ".xsd");
        }
        for (int i = 0; i < 150; i++) {
            write(file("many/n" + i + ".xsd"), schema("").getBytes(StandardCharsets.UTF_8));
        }
        commitLocalChanges();
        assertThat(builder().build(paths("many")).repository()).hasSize(200);

        write(file("many/n150.xsd"), schema("").getBytes(StandardCharsets.UTF_8));
        commitLocalChanges();
        ToolError error = refusal(paths("many"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("201", "200");
    }

    @ParameterizedTest
    @ValueSource(strings = {"xsd/a(1).xsd", "xsd/a%2Fb.xsd", "xsd/a%25.xsd"})
    void aPathTheCliRefusesIsRefused(String workspaceName) throws IOException {
        // NameCodec decodes these to a(1).xsd, "a/b.xsd" (one segment) and a%.xsd
        write(file(workspaceName), schema("").getBytes(StandardCharsets.UTF_8));
        commitLocalChanges();

        ToolError error = refusal(paths("xsd"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("StartCLI");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "not json", "null", "{}",
        "{\"metadata\": \"<Property\"}"})
    void aModifiedFileWithoutReadableMetadataMustBeExportedFirst(String record)
        throws IOException {
        Path meta = harness.root.resolve(WorkspacePath.repository(GROUP, OWNER,
            "/Root/jdoe/xsd/a.xsd").metaPath());
        if (record.equals("missing")) {
            Files.delete(meta);
        } else {
            Files.writeString(meta, record);
        }
        change("xsd/a.xsd");
        commitLocalChanges();

        ToolError error = refusal(paths("xsd/a.xsd"));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains("/Root/jdoe/xsd/a.xsd");
        assertThat(error.nextStep()).contains("export the path first");
    }
}
