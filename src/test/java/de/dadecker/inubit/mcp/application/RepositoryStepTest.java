package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.archive.v81.V81ReleaseArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceInspector;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.domain.model.ChangeSet;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.RepositoryUpload;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T022 (feature 008, research D-5, D-7, FR-012, FR-013, FR-014, FR-017): the node-state part of
 * {@link RepositoryStep}: the repository exports of the node (one per parent folder, every
 * StartCLI call scripted), the byte-for-byte classification, the conflict report, the
 * fingerprint, the uploads and the refusals after the node read.
 *
 * <p>The workspace is {@code GRP-01} of {@code jdoe} plus the exported repository files
 * {@code xsd/a.xsd}, {@code xsd/b.xsd} and {@code xsl/c.xsl}; the node holds them unchanged and
 * also {@code xsd/unrelated.xsd}, which the workspace does not know.
 */
class RepositoryStepTest {

    private static final GroupId GROUP = ImportHarness.DEV.group();
    private static final String OWNER = "jdoe";
    private static final String XSD = "application/x-xsd+xml";
    private static final String XSL = "application/xslt+xml";
    private static final String A = "/Root/jdoe/xsd/a.xsd";
    private static final String B = "/Root/jdoe/xsd/b.xsd";
    private static final String C = "/Root/jdoe/xsl/c.xsl";

    @TempDir
    Path temp;

    private ImportHarness harness;
    private final FakeServer node = new FakeServer();
    private final UUID auditId = UUID.randomUUID();

    @BeforeEach
    void exportRepository() throws IOException {
        harness = ImportHarness.grpA(temp);
        harness.repositoryFile("xsd/a.xsd", schema("a"), XSD);
        harness.repositoryFile("xsd/b.xsd", schema("b"), XSD);
        harness.repositoryFile("xsl/c.xsl", "<xsl:stylesheet version=\"2.0\" xmlns:xsl="
            + "\"http://www.w3.org/1999/XSL/Transform\"/>\n", XSL);
        harness.exports.history.commitAll("export dev/jdoe: repository",
            Map.of(VersionHistoryPort.SERVER_STATE, GROUP.value()));
        for (String path : List.of(A, B, C)) {
            node.putRepositoryFile(path, workspace(path));
        }
        node.putRepositoryFile("/Root/jdoe/xsd/unrelated.xsd", schema("unrelated"));
    }

    // --- helpers -----------------------------------------------------------------------------

    private RepositoryStep step() {
        return new RepositoryStep(harness.root, harness.exports.history,
            new WorkspaceInspector(), new V81ReleaseArchives(), nodeId -> harness.artifacts());
    }

    /** The change set of the repository paths, after committing the workspace edits. */
    private ChangeSet changes(String... relativePaths) {
        harness.exports.history.commitAll("local changes");
        return new ChangeSetBuilder(harness.root, harness.exports.history,
            new WorkspaceInspector(), new V81ReleaseArchives())
            .build(ImportScope.repositoryPaths(GROUP, OWNER, List.of(relativePaths)));
    }

    private RepositoryStep.NodeState nodeState(ChangeSet changes) {
        RepositoryStep.NodeState state = step().nodeState(ImportHarness.DEV, changes, auditId);
        harness.cli.verifyComplete();
        return state;
    }

    private ToolError refusal(ChangeSet changes) {
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> step().nodeState(ImportHarness.DEV, changes, auditId));
        assertThat(e).as("expected a refusal").isNotNull();
        return e.error();
    }

    private static String schema(String name) {
        return "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n  <xs:element name=\""
            + name + "\"/>\n</xs:schema>\n";
    }

    /** The workspace path of {@code /Root/jdoe/…}. */
    private static String file(String repositoryPath) {
        return WorkspacePath.repository(GROUP, OWNER, repositoryPath).toRelativePath().toString()
            .replace('\\', '/');
    }

    private String workspace(String repositoryPath) {
        return harness.read(file(repositoryPath));
    }

    /** Edits the exported schema {@code repositoryPath} in the workspace. */
    private void change(String repositoryPath) {
        harness.edit(file(repositoryPath), "</xs:schema>",
            "  <xs:element name=\"added\"/>\n</xs:schema>");
    }

    /** Creates {@code repositoryPath} in the workspace, without a {@code .meta} record. */
    private void create(String repositoryPath, String content) {
        harness.write(file(repositoryPath), content);
    }

    /**
     * The next StartCLI call is the repository export of {@code path} from {@link #node}; a
     * path the node does not have fails like the recorded {@code Path not found}.
     */
    private void exported(String path) {
        harness.cli.expect("export --exportRepositoryPath '" + path + "'").answering(spec -> {
            Optional<byte[]> zip = node.exportRepository(path);
            if (zip.isEmpty()) {
                return new ScriptedProcessLauncher.Reply(
                    FakeProcessLauncher.fixtureText("export_repository_not_found.stdout"),
                    FakeProcessLauncher.fixtureText("export_repository_not_found.stderr")
                        .replace("//ibis:Root/jdoe/xsd/no-such-dir", "//ibis:"
                            + path.substring(1)), 1);
            }
            ImportHarness.write(ScriptedProcessLauncher.exportFile(spec), zip.get());
            return new ScriptedProcessLauncher.Reply(DeployHarness.REPOSITORY_OK, "", 0);
        });
    }

    /** The next StartCLI call exports {@code path} into a file over the CLI's archive bound. */
    private void exportedTooLarge(String path) {
        harness.cli.expect("export --exportRepositoryPath '" + path + "'").then(spec -> {
            try (RandomAccessFile out = new RandomAccessFile(
                ScriptedProcessLauncher.exportFile(spec).toFile(), "rw")) {
                out.setLength(CliExportRunner.MAX_ARCHIVE_BYTES + 1); // sparse
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).replying(DeployHarness.REPOSITORY_OK, "", 0);
    }

    private static Map<String, RepositoryUpload> uploads(RepositoryStep.NodeState state) {
        return state.uploads().stream().collect(Collectors.toMap(RepositoryUpload::path,
            Function.identity()));
    }

    private static String sha256(String content) throws NoSuchAlgorithmException {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    // --- exports -----------------------------------------------------------------------------

    @Test
    void theNodeIsReadWithOneExportPerDistinctParentFolder() {
        change(A);
        change(B);
        harness.edit(file(C), "/>", "><xsl:template match=\"/\"/></xsl:stylesheet>");
        create("/Root/jdoe/xsd/new.xsd", schema("new"));
        ChangeSet changes = changes("xsd", "xsl");
        exported("/Root/jdoe/xsd");
        exported("/Root/jdoe/xsl");

        RepositoryStep.NodeState state = nodeState(changes);

        assertThat(harness.cli.launches()).hasSize(2);
        assertThat(state.rawExports()).hasSize(2);
        assertThat(state.files()).as("only the files of the change set, not unrelated.xsd")
            .containsOnlyKeys(A, B, C);
    }

    @Test
    void aFolderTheNodeDoesNotHaveMeansEveryFileBelowItIsAbsent() {
        create("/Root/jdoe/fresh/x.xsd", schema("x"));
        create("/Root/jdoe/fresh/y.xsd", schema("y"));
        ChangeSet changes = changes("fresh");
        exported("/Root/jdoe/fresh");

        RepositoryStep.NodeState state = nodeState(changes);

        assertThat(harness.cli.launches()).hasSize(1);
        assertThat(state.changes().created()).containsExactly("/Root/jdoe/fresh/x.xsd",
            "/Root/jdoe/fresh/y.xsd");
        assertThat(state.files()).isEmpty();
        assertThat(state.rawExports()).isEmpty();
        assertThat(state.fingerprint()).isEmpty();
    }

    @Test
    void aFolderOverTheReadLimitsFallsBackToOneExportPerFile() {
        change(A);
        create("/Root/jdoe/xsd/new.xsd", schema("new"));
        ChangeSet changes = changes("xsd");
        exportedTooLarge("/Root/jdoe/xsd");
        exported(A);
        exported("/Root/jdoe/xsd/new.xsd");

        RepositoryStep.NodeState state = nodeState(changes);

        assertThat(harness.cli.launches()).hasSize(3);
        assertThat(state.changes().modified()).containsExactly(A);
        assertThat(state.changes().created()).containsExactly("/Root/jdoe/xsd/new.xsd");
        assertThat(state.files()).containsOnlyKeys(A);
        assertThat(state.rawExports()).hasSize(1);
    }

    @Test
    void aFileDirectlyBelowTheOwnersRootIsExportedOnItsOwn() {
        create("/Root/jdoe/top.xsd", schema("top"));
        ChangeSet changes = changes("top.xsd");
        exported("/Root/jdoe/top.xsd");

        RepositoryStep.NodeState state = nodeState(changes);

        assertThat(harness.cli.launches()).hasSize(1);
        assertThat(state.changes().created()).containsExactly("/Root/jdoe/top.xsd");
    }

    // --- classification ----------------------------------------------------------------------

    @Test
    void eachFileIsClassifiedByteForByteAgainstTheNode() {
        change(A);
        create("/Root/jdoe/xsd/new.xsd", schema("new"));
        create("/Root/jdoe/xsd/same.xsd", schema("same"));
        node.putRepositoryFile("/Root/jdoe/xsd/same.xsd", schema("same"));
        create("/Root/jdoe/xsl/other.xsl", "<xsl:stylesheet version=\"2.0\"/>\n");
        node.putRepositoryFile("/Root/jdoe/xsl/other.xsl", "<xsl:stylesheet version=\"3.0\"/>\n");
        ChangeSet changes = changes("xsd", "xsl");
        exported("/Root/jdoe/xsd");
        exported("/Root/jdoe/xsl");

        ChangeSet classified = nodeState(changes).changes();

        assertThat(classified.created()).as("NEW, absent on the node")
            .containsExactly("/Root/jdoe/xsd/new.xsd");
        assertThat(classified.identicalNames()).as("NEW, identical on the node")
            .containsExactly("/Root/jdoe/xsd/same.xsd");
        assertThat(classified.existing()).as("NEW, other content on the node")
            .containsExactly("/Root/jdoe/xsl/other.xsl");
        assertThat(classified.modified()).as("MODIFIED with node == base, and the existing one")
            .containsExactlyInAnyOrder(A, "/Root/jdoe/xsl/other.xsl");
        assertThat(classified.repository()).hasSize(3);
    }

    @Test
    void aModifiedFileThatChangedOnTheNodeIsAConflictWithAReport() throws IOException {
        change(A);
        change(B);
        node.putRepositoryFile(A, schema("colleague"));
        ChangeSet changes = changes("xsd");
        exported("/Root/jdoe/xsd");

        ToolError error = refusal(changes);

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.node()).contains(ImportHarness.DEV);
        String report = ".reports/conflict-" + auditId + ".diff";
        assertThat(error.message()).contains(A).doesNotContain(B).contains(report);
        String diff = Files.readString(harness.root.resolve(report));
        assertThat(diff).contains(A).contains("+  <xs:element name=\"colleague\"/>")
            .contains("-  <xs:element name=\"a\"/>").doesNotContain(B);
    }

    @Test
    void aModifiedFileTheNodeNoLongerHasIsAConflict() throws IOException {
        change(A);
        ChangeSet changes = changes("xsd/a.xsd");
        // the folder was deleted on the node
        harness.cli.expect("export --exportRepositoryPath '/Root/jdoe/xsd'").replying(
            FakeProcessLauncher.fixtureText("export_repository_not_found.stdout"),
            FakeProcessLauncher.fixtureText("export_repository_not_found.stderr")
                .replace("/no-such-dir", ""), 1);

        ToolError error = refusal(changes);

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(Files.readString(harness.root.resolve(".reports/conflict-" + auditId
            + ".diff"))).contains("the file is missing");
    }

    @Test
    void keyMaterialOnTheNodeIsNeverWrittenToTheConflictReport() throws IOException {
        change(A);
        String key = "-----BEGIN PRIVATE KEY-----\nMIIBVQIBADANBgkqhkiG9w0BAQEFAASCAT8wggE7\n"
            + "-----END PRIVATE KEY-----\n";
        node.putRepositoryFile(A, key);
        ChangeSet changes = changes("xsd/a.xsd");
        exported("/Root/jdoe/xsd");

        ToolError error = refusal(changes);

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        String diff = Files.readString(harness.root.resolve(".reports/conflict-" + auditId
            + ".diff"));
        assertThat(diff).contains(A).doesNotContain("PRIVATE KEY").doesNotContain("MIIB");
    }

    // --- fingerprint and uploads -------------------------------------------------------------

    @Test
    void theFingerprintIsTheSortedHashesOfTheNodesFilesOfTheChangeSet() throws Exception {
        change(B);
        change(A);
        create("/Root/jdoe/xsd/new.xsd", schema("new"));
        ChangeSet changes = changes("xsd");
        exported("/Root/jdoe/xsd");

        RepositoryStep.NodeState state = nodeState(changes);

        assertThat(state.fingerprint()).isEqualTo("repository:" + A + "=" + sha256(
            node.repositoryContent(A).orElseThrow()) + "\nrepository:" + B + "=" + sha256(
                node.repositoryContent(B).orElseThrow()));
    }

    @Test
    void eachFileToSendGetsTheMetadataItsKindCallsFor() {
        change(A);
        create("/Root/jdoe/xsd/new.xsd", schema("new"));
        create("/Root/jdoe/xsd/same.xsd", schema("same"));
        node.putRepositoryFile("/Root/jdoe/xsd/same.xsd", schema("same"));
        create("/Root/jdoe/xsd/held.xsd", schema("mine"));
        node.putRepositoryFile("/Root/jdoe/xsd/held.xsd", schema("theirs"));
        ChangeSet changes = changes("xsd");
        exported("/Root/jdoe/xsd");

        Map<String, RepositoryUpload> uploads = uploads(nodeState(changes));

        assertThat(uploads).containsOnlyKeys(A, "/Root/jdoe/xsd/new.xsd",
            "/Root/jdoe/xsd/held.xsd");
        assertThat(new String(uploads.get(A).content(), StandardCharsets.UTF_8))
            .isEqualTo(workspace(A));
        assertThat(uploads.get(A).metadata()).as("MODIFIED: the .meta metadata").hasValueSatisfying(
            xml -> assertThat(xml).contains("contentType=\"" + XSD + "\"")
                .contains("Workspace file"));
        assertThat(uploads.get("/Root/jdoe/xsd/new.xsd").metadata()).as("create: synthesized")
            .isEmpty();
        assertThat(uploads.get("/Root/jdoe/xsd/held.xsd").metadata())
            .as("EXISTING: the node's metadata (research D-7)").hasValueSatisfying(
                xml -> assertThat(xml).contains("path=\"/Root/jdoe/xsd/held.xsd\"")
                    .contains("contentType=\"application/octet-stream\"")
                    .contains("<Description>Fixture file</Description>"));
    }

    @Test
    void aModifiedFileWhoseMetaRecordTurnedToNullMustBeExportedFirst() throws IOException {
        change(A);
        ChangeSet changes = changes("xsd/a.xsd");
        Files.writeString(harness.root.resolve(WorkspacePath.repository(GROUP, OWNER, A)
            .metaPath()), "null");
        exported("/Root/jdoe/xsd");

        ToolError error = refusal(changes);

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.nextStep()).contains("export the path first");
    }

    // --- refusals after the node read --------------------------------------------------------

    @Test
    void identicalFilesDoNotCountTowardsTheLimitOfFiftyFilesToSend() {
        for (int i = 0; i < 60; i++) {
            String path = String.format("/Root/jdoe/bulk/f%02d.xsd", i);
            create(path, schema("f" + i));
            if (i < 15) {
                node.putRepositoryFile(path, schema("f" + i));
            }
        }
        ChangeSet changes = changes("bulk");
        exported("/Root/jdoe/bulk");

        RepositoryStep.NodeState state = nodeState(changes);

        assertThat(state.changes().created()).hasSize(45);
        assertThat(state.changes().identicalNames()).hasSize(15);
        assertThat(state.uploads()).hasSize(45);
    }

    @Test
    void moreThanFiftyFilesToSendAreRefusedBeforeAnythingIsSent() {
        for (int i = 0; i < 51; i++) {
            create(String.format("/Root/jdoe/bulk/f%02d.xsd", i), schema("f" + i));
        }
        ChangeSet changes = changes("bulk");
        exported("/Root/jdoe/bulk");

        ToolError error = refusal(changes);

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("51").contains("50");
        assertThat(harness.cli.launches()).hasSize(1);
    }

    @Test
    void aFileToCreateWithAnUnsupportedExtensionIsRefusedNamingTheSupportedOnes() {
        create("/Root/jdoe/xsd/notes.txt", "notes\n");
        ChangeSet changes = changes("xsd");
        exported("/Root/jdoe/xsd");

        ToolError error = refusal(changes);

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("/Root/jdoe/xsd/notes.txt")
            .contains(".xsd, .xsl, .xslt, .wsdl, .xml");
        assertThat(harness.cli.launches()).hasSize(1);
    }

    @Test
    void aNewFileWithAnUnsupportedExtensionThatTheNodeHoldsIsUpdatedNotRefused() {
        create("/Root/jdoe/xsd/legacy.txt", "mine\n");
        node.putRepositoryFile("/Root/jdoe/xsd/legacy.txt", "theirs\n");
        ChangeSet changes = changes("xsd");
        exported("/Root/jdoe/xsd");

        RepositoryStep.NodeState state = nodeState(changes);

        assertThat(state.changes().existing()).containsExactly("/Root/jdoe/xsd/legacy.txt");
        assertThat(uploads(state).get("/Root/jdoe/xsd/legacy.txt").metadata()).isPresent();
    }
}
