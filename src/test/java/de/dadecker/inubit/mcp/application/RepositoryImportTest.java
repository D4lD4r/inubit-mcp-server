package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportPreview;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.ImportArchivePort;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import de.dadecker.inubit.mcp.infra.AuditLog;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 008: a development import whose change set holds repository files.
 *
 * <p>T023 (research D-5 step 3): the confirmation code is bound to the repository files of the
 * node (their fingerprint joins the server fingerprint) and of the workspace (their content is
 * part of the workspace hash). The workspace is {@code GRP-01} of {@code jdoe} with the exported
 * schema {@code xsd/a.xsd}, which the node holds unchanged; {@code Workflow-0001} is changed and
 * so is {@code Module-0003}, which it uses, to reference {@code a.xsd} (changed as well) and the
 * new {@code b.xsd}. The node uses server confirmation, so the first call is a preview; every
 * StartCLI call is scripted.
 *
 * <p>T024 (FR-004, research D-5 step 5): on a node whose {@code development.repository} is
 * {@code READ_ONLY} (every harness node starts so; the T023 tests use {@code WRITE}), a change
 * set with a repository file to send is refused as a whole with {@code REPOSITORY_WRITE_DENIED}:
 * at once if a file is MODIFIED in the workspace, after the node read if a NEW file is to be
 * created or modified; a change set whose repository files are all identical is not refused.
 *
 * <p>T025 (FR-012, research D-6): a repository file to send always needs a server code, also on
 * a node with {@code development.confirmation: CLIENT}; the code is redeemed after the node read,
 * and always if the change set holds repository files. Until T031 the confirmed call stops at the
 * interim execute guard of T020+T021, so these tests see that guard instead of an import.
 *
 * <p>T026 (FR-012, research D-5 step 4, edge cases "Shared use" and "Reference chains"): the
 * preview lists the repository files by their full path, names up to 20 workspace artifacts
 * outside the scope that use each file to send (all of them in a report), writes the line
 * differences between the node and the workspace of every file to modify, and warns of
 * references that leave the owner's repository root and of references on the node that the
 * workspace cannot show.
 *
 * <p>T027 (FR-019, research D-11): every audit record of such a call lists each repository file
 * as {@code repository.<n>} with its operation, full path and the hash of its workspace content,
 * never the content. Until T031 the records of a call that sends repository files end at the
 * interim execute guard.
 *
 * <p>T028 (FR-005, contracts/mcp-tools-delta.md "import_artifacts"): a request with
 * {@code repositoryPaths} covers the changed files at or below the paths; the paths are part of
 * what the code confirms, a malformed path, a second scope or a tag is {@code INVALID_INPUT}
 * before anything is sent.
 */
@Timeout(60)
class RepositoryImportTest {

    private static final String A = "/Root/jdoe/xsd/a.xsd";
    private static final String B = "/Root/jdoe/xsd/b.xsd";
    private static final String C = "/Root/jdoe/xsd/c.xsd";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    /** The audit log of the T027 tests, outside the workspace. */
    @TempDir
    Path auditDirectory;

    private ImportHarness harness;
    private ImportService service;
    private final FakeServer node = new FakeServer();

    @BeforeEach
    void setUp() throws IOException {
        harness = ImportHarness.grpA(temp);
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        harness.repositoryFile("xsd/a.xsd", schema("a"), "application/x-xsd+xml");
        harness.exports.history.commitAll("export dev/jdoe: repository",
            Map.of(VersionHistoryPort.SERVER_STATE, ImportHarness.DEV.group().value()));
        node.putRepositoryFile(A, schema("a"));
        harness.edit(harness.moduleDirectory("Module-0003") + "/module.xml", "</Properties>",
            "<Property name=\"x.a\">inubitrepository:" + A + "</Property>"
                + "<Property name=\"x.b\">inubitrepository:" + B + "</Property></Properties>");
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.edit(file(A), "</xs:schema>", "  <xs:element name=\"added\"/>\n</xs:schema>");
        harness.write(file(B), schema("b"));
        service = harness.repositoryWrite(ImportHarness.DEV, true).service();
    }

    /** The service on {@code dev/node1} with {@code development.repository: READ_ONLY}. */
    private ImportService readOnly() {
        return harness.repositoryWrite(ImportHarness.DEV, false).service();
    }

    /** Takes the edit of {@code a.xsd} back: it equals its exported base again. */
    private void revertA() {
        harness.edit(file(A), "  <xs:element name=\"added\"/>\n</xs:schema>", "</xs:schema>");
    }

    private static String schema(String name) {
        return "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n  <xs:element name=\""
            + name + "\"/>\n</xs:schema>\n";
    }

    private static String file(String repositoryPath) {
        return WorkspacePath.repository(ImportHarness.DEV.group(), "jdoe", repositoryPath)
            .toRelativePath().toString().replace('\\', '/');
    }

    /** The node read of one call: the diagram group, then the repository folder. */
    private void nodeRead() {
        harness.exportGroup().exportRepository(node, "/Root/jdoe/xsd");
    }

    /**
     * The service on {@code dev/node1} with {@code development.confirmation: CLIENT} and
     * {@code development.repository: WRITE} (T025).
     */
    private ImportService client() {
        harness.confirmation = WritePolicy.Confirmation.CLIENT;
        return harness.service();
    }

    private ImportPreview preview() {
        return preview(service);
    }

    private ImportPreview preview(ImportService on) {
        ImportPreview preview = challenge(on);
        assertThat(preview.create()).as("b.xsd is absent on the node").contains(B);
        assertThat(preview.modify()).containsExactly("Workflow-0001", "Module-0003", A);
        return preview;
    }

    /** The first call on {@code on}: the node read, then the preview. */
    private ImportPreview challenge(ImportService on) {
        nodeRead();
        ImportService.Response response = on.importArtifacts(harness.group("Schemas"));
        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.Challenge.class);
        return ((ImportService.Response.Challenge) response).preview();
    }

    private ToolError confirmedRefusal(ImportPreview preview) {
        return confirmedRefusal(service, preview.confirmationCode());
    }

    private ToolError confirmedRefusal(ImportService on, String code) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> on.importArtifacts(ImportHarness.confirmed(harness.group("Schemas"), code)));
        assertThat(exception).as("expected a refusal").isNotNull();
        harness.cli.verifyComplete();
        assertThat(harness.inubit.imported).as("nothing was imported").isEmpty();
        assertThat(node.repositoryImports).as("nothing was imported").isEmpty();
        assertThat(harness.audit("import_artifacts")).last().satisfies(record ->
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
        return exception.error();
    }

    @Test
    void aRepositoryFileCreatedOnTheNodeAfterThePreviewIsAConflict() {
        // b.xsd is NEW: the node read classifies it without a conflict of its own, only the
        // fingerprint of the preview tells that the node changed
        ImportPreview preview = preview();
        node.putRepositoryFile(B, schema("someone else's b"));
        nodeRead();

        ToolError error = confirmedRefusal(preview);

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).contains("after the preview", "nothing was sent");
    }

    @Test
    void aRepositoryFileChangedOnTheNodeAfterThePreviewIsAConflict() {
        ImportPreview preview = preview();
        node.putRepositoryFile(A, schema("changed on the node"));
        nodeRead();

        ToolError error = confirmedRefusal(preview);

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).contains(A, ".reports/conflict-");
    }

    @Test
    void aRepositoryFileEditedInTheWorkspaceAfterThePreviewNeedsANewPreview() {
        // only the repository file changes: its content is part of the workspace hash
        ImportPreview preview = preview();
        harness.edit(file(B), "name=\"b\"", "name=\"b2\"");
        nodeRead();

        ToolError error = confirmedRefusal(preview);

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(error.message()).contains("workspace changed", "preview again");
        assertThat(harness.cli.launches())
            .as("the node read of both calls: T025 redeems after the node read (D-6)")
            .hasSize(4);
    }

    // --- T024: the switch (FR-004) ---------------------------------------------------------

    /** The refusal of the call on a read-only node: nothing sent, audited with the switch. */
    private ToolError denied(ImportService readOnly) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> readOnly.importArtifacts(harness.group("Schemas")));
        assertThat(exception).as("expected a refusal").isNotNull();
        harness.cli.verifyComplete();
        assertThat(harness.inubit.imported).as("nothing was imported").isEmpty();
        assertThat(node.repositoryImports).as("nothing was imported").isEmpty();
        assertThat(exception.error().code()).isEqualTo(ErrorCode.REPOSITORY_WRITE_DENIED);
        assertThat(exception.error().message()).contains("nothing was sent");
        assertThat(harness.audit("import_artifacts")).last().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.reason()).hasValueSatisfying(reason -> assertThat(reason)
                .startsWith("REPOSITORY_WRITE_DENIED: development.repository is READ_ONLY on "
                    + ImportHarness.DEV));
        });
        return exception.error();
    }

    @Test
    void aModifiedRepositoryFileOnAReadOnlyNodeIsRefusedBeforeAnyCliLaunch() {
        // a.xsd is MODIFIED: it differs from its base, so it is sent whatever the node holds
        ToolError error = denied(readOnly());

        assertThat(harness.cli.launches()).as("not even the node read").isEmpty();
        assertThat(error.message()).contains(A, B);
    }

    @Test
    void aNewRepositoryFileToCreateOnAReadOnlyNodeIsRefusedAfterTheNodeRead() {
        revertA();
        ImportService readOnly = readOnly();
        nodeRead();

        ToolError error = denied(readOnly);

        assertThat(harness.cli.launches()).as("only the node read").hasSize(2);
        assertThat(error.message()).contains(B).doesNotContain(A);
    }

    @Test
    void aNewRepositoryFileToModifyOnAReadOnlyNodeIsRefusedAfterTheNodeRead() {
        // b.xsd is on the node with other content: modify and existing
        revertA();
        node.putRepositoryFile(B, schema("someone else's b"));
        ImportService readOnly = readOnly();
        nodeRead();

        ToolError error = denied(readOnly);

        assertThat(harness.cli.launches()).as("only the node read").hasSize(2);
        assertThat(error.message()).contains(B).doesNotContain(A);
    }

    @Test
    void aDiagramGroupImportWithOneReferencedChangedFileIsRefusedAsAWhole() throws IOException {
        // only a.xsd is referenced and changed; the workflows are not imported without it, and it
        // does not go to notImported instead
        harness.edit(harness.moduleDirectory("Module-0003") + "/module.xml",
            "<Property name=\"x.b\">inubitrepository:" + B + "</Property>", "");
        Files.delete(harness.root.resolve(file(B)));

        ToolError error = denied(readOnly());

        assertThat(error.message()).contains(A).doesNotContain(B);
        assertThat(harness.audit("import_artifacts")).last().satisfies(record ->
            assertThat(record.inputs().get("changeSet"))
                .isEqualTo("Workflow-0001, Module-0003, " + A));
    }

    @Test
    void aChangeSetWhoseRepositoryFilesAreAllIdenticalIsNotRefused() {
        // b.xsd is identical on the node and not sent; the workflow and the module are imported
        revertA();
        node.putRepositoryFile(B, schema("b"));
        harness.confirmation = WritePolicy.Confirmation.CLIENT;
        ImportService readOnly = readOnly();
        nodeRead();
        harness.importApplied().exportGroup();

        ImportService.Response response = readOnly.importArtifacts(harness.group("Schemas"));

        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        WriteOutcome outcome = ((ImportService.Response.Completed) response).outcome();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly("Workflow-0001", "Module-0003");
        assertThat(outcome.identical()).hasValueSatisfying(names -> assertThat(names)
            .containsExactly(B));
        assertThat(outcome.notImported()).doesNotContain(B);
        assertThat(node.repositoryImports).as("b.xsd is not sent").isEmpty();
    }

    // --- T025: the server code is always required (FR-012, research D-6) --------------------

    /** Records every build of an artifact archive (the archive port wrapped). */
    private List<ImportArchivePort.Build> recordBuilds() {
        List<ImportArchivePort.Build> builds = new CopyOnWriteArrayList<>();
        harness.archives = port -> (ImportArchivePort) Proxy.newProxyInstance(
            ImportArchivePort.class.getClassLoader(), new Class<?>[] {ImportArchivePort.class},
            (proxy, method, args) -> {
                if (method.getName().equals("assemble")) {
                    builds.add((ImportArchivePort.Build) args[0]);
                }
                try {
                    return method.invoke(port, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        return builds;
    }

    @Test
    void aClientNodeGetsAServerCodeForARepositoryFileToSendAndRunsOnlyWithIt() {
        List<ImportArchivePort.Build> builds = recordBuilds();
        ImportService client = client();

        ImportPreview preview = preview(client);

        assertThat(harness.inubit.imported).as("nothing was imported").isEmpty();
        assertThat(node.repositoryImports).as("nothing was imported").isEmpty();
        assertThat(harness.audit("import_artifacts")).last().satisfies(record ->
            assertThat(record.outcome()).isEqualTo(AuditOutcome.CHALLENGE_ISSUED));
        assertThat(builds).singleElement().satisfies(build -> assertThat(build.files().keySet())
            .as("the preview's artifact archive holds no repository file")
            .noneMatch(path -> path.contains(file(A)) || path.contains(file(B))));

        // with the code: redeemed after the node read, bound to inputs, workspace and node; the
        // call reaches the execute step, where the interim guard of T020+T021 still refuses
        // repository files until T031 sends them
        nodeRead();
        ToolError error = confirmedRefusal(client, preview.confirmationCode());

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("does not import yet");
        assertThat(harness.audit("import_artifacts")).last().satisfies(record ->
            assertThat(record.step()).isEqualTo(AuditRecord.Step.EXECUTE));
    }

    @Test
    void aCallWithoutRepositoryFilesKeepsClientConfirmation() {
        // Module-0003 no longer references the schemas: they are changed outside the scope
        harness.edit(harness.moduleDirectory("Module-0003") + "/module.xml",
            "<Property name=\"x.a\">inubitrepository:" + A + "</Property>"
                + "<Property name=\"x.b\">inubitrepository:" + B + "</Property></Properties>",
            "</Properties>");
        ImportService client = client();
        harness.exportGroup().importApplied().exportGroup();

        ImportService.Response response = client.importArtifacts(harness.group("Schemas"));

        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        WriteOutcome outcome = ((ImportService.Response.Completed) response).outcome();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly("Workflow-0001");
        assertThat(outcome.notImported()).contains(file(A), file(B));
        assertThat(node.repositoryImports).isEmpty();
    }

    @Test
    void onAClientNodeAWorkspaceEditAfterThePreviewNeedsANewPreview() {
        ImportService client = client();
        ImportPreview preview = preview(client);
        harness.edit(file(B), "name=\"b\"", "name=\"b2\"");
        nodeRead();

        ToolError error = confirmedRefusal(client, preview.confirmationCode());

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(error.message()).contains("workspace changed", "preview again");
    }

    @Test
    void onAClientNodeANodeChangeAfterThePreviewIsAConflict() {
        ImportService client = client();
        ImportPreview preview = preview(client);
        node.putRepositoryFile(B, schema("someone else's b"));
        nodeRead();

        ToolError error = confirmedRefusal(client, preview.confirmationCode());

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).contains("after the preview", "nothing was sent");
    }

    @Test
    void aRepositoryOnlyChangeSetGetsAPreview() throws IOException {
        // FR-011a: Module-0003 references the schemas in its server state, only they changed
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"140\"", "xPos=\"120\"");
        revertA();
        Files.delete(harness.root.resolve(file(B)));
        harness.exports.history.commitAll("export dev/jdoe: GRP-01",
            Map.of(VersionHistoryPort.SERVER_STATE, ImportHarness.DEV.group().value()));
        harness.inubit.changeModule("Module-0003", xml -> xml.replace("</Properties>",
            "<Property name=\"x.a\">inubitrepository:" + A + "</Property>"
                + "<Property name=\"x.b\">inubitrepository:" + B + "</Property></Properties>"));
        harness.edit(file(A), "</xs:schema>", "  <xs:element name=\"added\"/>\n</xs:schema>");
        harness.write(file(B), schema("b"));
        List<ImportArchivePort.Build> builds = recordBuilds();
        ImportService on = harness.service();
        nodeRead();

        ImportService.Response response = on.importArtifacts(harness.group("Schemas"));

        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.Challenge.class);
        ImportPreview preview = ((ImportService.Response.Challenge) response).preview();
        assertThat(preview.create()).containsExactly(B);
        assertThat(preview.modify()).containsExactly(A);
        assertThat(builds).as("no artifact archive for repository files alone").isEmpty();
    }

    @Test
    void onAClientNodeChangedWorkflowsWithIdenticalRepositoryFilesRunAtOnce() {
        revertA();
        node.putRepositoryFile(B, schema("b"));
        ImportService client = client();
        nodeRead();
        harness.importApplied().exportGroup();

        ImportService.Response response = client.importArtifacts(harness.group("Schemas"));

        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        WriteOutcome outcome = ((ImportService.Response.Completed) response).outcome();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly("Workflow-0001", "Module-0003");
        assertThat(node.repositoryImports).as("b.xsd is identical and not sent").isEmpty();
        assertThat(harness.audit("import_artifacts")).noneMatch(record ->
            record.outcome() == AuditOutcome.CHALLENGE_ISSUED);
    }

    @Test
    void onAClientNodeAnUnknownCodeIsRefusedEvenIfNoRepositoryFileIsLeftToSend() {
        // the change set holds b.xsd, identical on the node: the code is redeemed, not ignored
        revertA();
        node.putRepositoryFile(B, schema("b"));
        ImportService client = client();
        nodeRead();

        ToolError error = confirmedRefusal(client, "AAAAAAAAAAAAAAAAAAAAAA");

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    @Test
    void onAClientNodeARedeemedCodeStillBindsTheNodeStateIfNothingIsLeftToSend() {
        // b.xsd is created by someone else, identical to the workspace, after the preview
        revertA();
        ImportService client = client();
        nodeRead();
        ImportService.Response response = client.importArtifacts(harness.group("Schemas"));
        assertThat(response).isInstanceOf(ImportService.Response.Challenge.class);
        String code = ((ImportService.Response.Challenge) response).preview().confirmationCode();
        node.putRepositoryFile(B, schema("b"));
        nodeRead();

        ToolError error = confirmedRefusal(client, code);

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).contains("after the preview");
    }

    @Test
    void aRedeemedCodeProceedsWithoutARepositoryImportIfNothingIsLeftToSend() {
        // server confirmation: the preview of an all-identical change set, then the execution
        revertA();
        node.putRepositoryFile(B, schema("b"));
        nodeRead();
        ImportService.Response first = service.importArtifacts(harness.group("Schemas"));
        assertThat(first).isInstanceOf(ImportService.Response.Challenge.class);
        String code = ((ImportService.Response.Challenge) first).preview().confirmationCode();
        nodeRead();
        harness.importApplied().exportGroup();

        ImportService.Response second = service.importArtifacts(ImportHarness.confirmed(
            harness.group("Schemas"), code));

        harness.cli.verifyComplete();
        assertThat(second).isInstanceOf(ImportService.Response.Completed.class);
        WriteOutcome outcome = ((ImportService.Response.Completed) second).outcome();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.identical()).hasValueSatisfying(names -> assertThat(names)
            .containsExactly(B));
        assertThat(node.repositoryImports).isEmpty();
    }

    // --- T026: the preview of repository files (FR-012, research D-5 step 4) ------------------

    /** Lets module {@code module} name {@code repositoryPath} by URI in property {@code name}. */
    private void reference(String module, String name, String repositoryPath) {
        harness.edit(harness.moduleDirectory(module) + "/module.xml", "</Properties>",
            "<Property name=\"" + name + "\">inubitrepository:" + repositoryPath
                + "</Property></Properties>");
    }

    /** The content of the only report file whose name starts with {@code prefix}. */
    private String report(String prefix) throws IOException {
        try (Stream<Path> files = Files.list(harness.root.resolve(".reports"))) {
            List<Path> found = files.filter(file -> file.getFileName().toString()
                .startsWith(prefix)).toList();
            assertThat(found).as(prefix).hasSize(1);
            return Files.readString(found.get(0));
        }
    }

    @Test
    void thePreviewListsRepositoryFilesByFullPathAndWritesTheNodeDiff() {
        // a.xsd is MODIFIED, b.xsd is on the node with other content (modify and existing), the
        // new c.xsd is identical on the node
        node.putRepositoryFile(B, schema("someone else's b"));
        node.putRepositoryFile(C, schema("c"));
        harness.write(file(C), schema("c"));
        reference("Module-0003", "x.c", C);

        ImportPreview preview = challenge(service);

        assertThat(preview.create()).isEmpty();
        assertThat(preview.modify()).containsExactly("Workflow-0001", "Module-0003", A, B);
        assertThat(preview.existing()).containsExactly(B);
        assertThat(preview.identical()).containsExactly(C);
        assertThat(preview.repositoryDiff()).hasValueSatisfying(path -> {
            assertThat(path).matches("\\.reports/repository-[0-9a-f-]{36}\\.diff");
            assertThat(harness.read(path))
                .contains("--- " + A + " (on dev/node1)", "+++ " + A + " (workspace)",
                    "+  <xs:element name=\"added\"/>")
                .contains("--- " + B + " (on dev/node1)",
                    "-  <xs:element name=\"someone else's b\"/>", "+  <xs:element name=\"b\"/>")
                .doesNotContain(C);
        });
    }

    @Test
    void aFileToCreateHasNoDiffAndNoNoteOnReferencesOnTheNode() {
        // only b.xsd is sent, and the node does not hold it
        revertA();

        ImportPreview preview = challenge(service);

        assertThat(preview.create()).containsExactly(B);
        assertThat(preview.repositoryDiff()).isEmpty();
        assertThat(preview.repositoryReferences()).hasValue(List.of());
        assertThat(preview.warnings()).isEmpty();
        assertThat(harness.root.resolve(".reports")).doesNotExist();
    }

    @Test
    void thePreviewNamesTwentyArtifactsOutsideTheScopeThatUseAFileAndReportsAll()
        throws IOException {
        // US1 scenario 11: 25 schemas outside the scope include b.xsd; Mapping-0007 of the
        // diagram group GRP-02 names a.xsd
        for (int i = 1; i <= 25; i++) {
            harness.write(file(String.format("/Root/jdoe/other/s%02d.xsd", i)), "<xs:schema"
                + " xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:include"
                + " schemaLocation=\"../xsd/b.xsd\"/></xs:schema>\n");
        }
        harness.write(WorkspacePath.workflow(ImportHarness.DEV.group(), "jdoe", "GRP-02",
            "Mapping-0007").toRelativePath().toString(), "<Workflow><Property>"
            + "inubitrepository:" + A + "</Property></Workflow>\n");

        ImportPreview preview = preview();

        assertThat(preview.repositoryReferences()).hasValueSatisfying(references -> {
            assertThat(references).extracting(ImportPreview.RepositoryReferences::path)
                .containsExactly(A, B);
            assertThat(references.get(0).artifacts()).containsExactly("Mapping-0007 (GRP-02)");
            assertThat(references.get(0).truncated()).isFalse();
            assertThat(references.get(1).artifacts()).hasSize(20)
                .startsWith("/Root/jdoe/other/s01.xsd").endsWith("/Root/jdoe/other/s20.xsd");
            assertThat(references.get(1).truncated()).isTrue();
        });
        String report = report("repository-references-");
        for (int i = 1; i <= 25; i++) {
            assertThat(report).contains(String.format("/Root/jdoe/other/s%02d.xsd", i));
        }
        assertThat(report).contains(A, B, "Mapping-0007 (GRP-02)");
        assertThat(preview.warnings()).hasValueSatisfying(warnings -> assertThat(warnings)
            .anySatisfy(warning -> assertThat(warning)
                .contains(A + " (1)", B + " (25)", ".reports/repository-references-")));
    }

    @Test
    void thePreviewWarnsOfReferencesOutsideTheOwnersRootAndOfReferencesOnTheNode() {
        // Module-0003 also names a schema of another owner: not followed, not imported
        reference("Module-0003", "x.other", "/Root/other/x.xsd");

        ImportPreview preview = preview();

        assertThat(preview.warnings()).hasValueSatisfying(warnings -> assertThat(warnings)
            .anySatisfy(warning -> assertThat(warning).contains(
                harness.moduleDirectory("Module-0003") + "/module.xml", "/Root/other/x.xsd",
                "outside the owner's repository root /Root/jdoe/", "not followed"))
            .anySatisfy(warning -> assertThat(warning)
                .contains(A, "on dev/node1", "cannot be listed").doesNotContain(B)));
    }

    @Test
    void artifactsOfAnotherOwnerOfTheGroupAreNamedWithTheirOwner() throws IOException {
        // bob's workflow names a.xsd; bob's schema y.xsd includes b.xsd, and bob's workflow
        // Mapping-0009 names y.xsd: it uses b.xsd through y.xsd
        harness.write(WorkspacePath.workflow(ImportHarness.DEV.group(), "bob", "GRP-02",
            "Mapping-0008").toRelativePath().toString(), "<Workflow><Property>"
            + "inubitrepository:" + A + "</Property></Workflow>\n");
        harness.write(WorkspacePath.repository(ImportHarness.DEV.group(), "bob",
            "/Root/bob/y.xsd").toRelativePath().toString(), "<xs:schema"
            + " xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:include"
            + " schemaLocation=\"inubitrepository:" + B + "\"/></xs:schema>\n");
        harness.write(WorkspacePath.workflow(ImportHarness.DEV.group(), "bob", "GRP-02",
            "Mapping-0009").toRelativePath().toString(), "<Workflow><Property>"
            + "inubitrepository:/Root/bob/y.xsd</Property></Workflow>\n");

        ImportPreview preview = preview();

        assertThat(preview.repositoryReferences()).hasValueSatisfying(references -> {
            assertThat(references).extracting(ImportPreview.RepositoryReferences::path)
                .containsExactly(A, B);
            assertThat(references.get(0).artifacts())
                .containsExactly("Mapping-0008 (GRP-02, owner bob)");
            assertThat(references.get(1).artifacts()).containsExactly(
                "/Root/bob/y.xsd (owner bob)", "Mapping-0009 (GRP-02, owner bob)");
        });
        assertThat(report("repository-references-")).contains(
            "Mapping-0008 (GRP-02, owner bob)", "/Root/bob/y.xsd (owner bob)",
            "Mapping-0009 (GRP-02, owner bob)");
    }

    @Test
    void aReferenceOutsideTheOwnersRootIsReportedAlsoIfTheRepositoryFilesAreAllIdentical() {
        // FR-011: b.xsd is identical on the node and not sent; Module-0003 still names a schema
        // of another owner, which the closure did not follow
        revertA();
        node.putRepositoryFile(B, schema("b"));
        reference("Module-0003", "x.other", "/Root/other/x.xsd");
        ImportService client = client();
        nodeRead();
        harness.importApplied().exportGroup();

        ImportService.Response response = client.importArtifacts(harness.group("Schemas"));

        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        WriteOutcome outcome = ((ImportService.Response.Completed) response).outcome();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.warnings())
            .anySatisfy(warning -> assertThat(warning).contains("/Root/other/x.xsd",
                "outside the owner's repository root /Root/jdoe/", "not followed"))
            .noneSatisfy(warning -> assertThat(warning).contains("cannot be listed"));
    }

    @Test
    void thePreviewOfAllIdenticalRepositoryFilesWarnsOfAReferenceOutsideTheOwnersRoot() {
        revertA();
        node.putRepositoryFile(B, schema("b"));
        reference("Module-0003", "x.other", "/Root/other/x.xsd");

        ImportPreview preview = challenge(service);

        assertThat(preview.identical()).containsExactly(B);
        assertThat(preview.repositoryReferences()).isEmpty();
        assertThat(preview.repositoryDiff()).isEmpty();
        assertThat(preview.warnings()).hasValueSatisfying(warnings -> assertThat(warnings)
            .singleElement().satisfies(warning -> assertThat(warning)
                .contains("/Root/other/x.xsd", "not followed")));
    }

    // --- T027: the audit of repository files (FR-019, research D-11) ---------------------------

    /**
     * The records of {@code import_artifacts} as the audit log writes them, one JSON object per
     * line; no line holds content of a repository file.
     */
    private List<JsonNode> auditLines() throws IOException {
        AuditLog log = new AuditLog(auditDirectory, new SecretScrubber());
        harness.audit("import_artifacts").forEach(log::append);
        List<JsonNode> lines = new ArrayList<>();
        try (Stream<Path> files = Files.list(auditDirectory)) {
            for (Path file : files.sorted().toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    assertThat(line).as("no content of a repository file")
                        .doesNotContain("xs:schema", "xs:element");
                    lines.add(JSON.readTree(line));
                }
            }
        }
        return lines;
    }

    /** The inputs {@code repository.1}, {@code repository.2}, … of one record, in this order. */
    private static List<String> repositoryInputs(JsonNode record) {
        List<String> values = new ArrayList<>();
        for (int n = 1; record.path("inputs").has("repository." + n); n++) {
            values.add(record.path("inputs").path("repository." + n).asString());
        }
        return values;
    }

    /** {@code sha256:} and the first 16 hex chars of the workspace content of a file. */
    private String hash(String repositoryPath) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(harness.root.resolve(file(repositoryPath)))))
                .substring(0, 16);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void thePreviewAndTheConfirmedCallAuditEveryRepositoryFileWithOperationAndHash()
        throws IOException {
        // until T031 the confirmed call stops at the interim execute guard (EXECUTE, REFUSED)
        ImportPreview preview = preview();
        nodeRead();
        assertThat(confirmedRefusal(preview).message()).contains("does not import yet");

        List<JsonNode> records = auditLines();

        assertThat(records).extracting(record -> record.path("outcome").asString())
            .containsExactly("CHALLENGE_ISSUED", "REFUSED");
        assertThat(records.get(1).path("step").asString()).isEqualTo("EXECUTE");
        assertThat(records).allSatisfy(record -> assertThat(repositoryInputs(record))
            .containsExactly("modify " + A + " " + hash(A), "create " + B + " " + hash(B)));
    }

    @Test
    void aRefusalBeforeTheNodeReadAuditsTheRepositoryFilesOfTheWorkspace() throws IOException {
        denied(readOnly());

        assertThat(auditLines()).singleElement().satisfies(record -> {
            assertThat(record.path("outcome").asString()).isEqualTo("REFUSED");
            assertThat(repositoryInputs(record))
                .containsExactly("modify " + A + " " + hash(A), "create " + B + " " + hash(B));
        });
    }

    @Test
    void aRefusalAfterTheNodeReadAuditsTheOperationTheNodeReadFound() throws IOException {
        // b.xsd is NEW in the workspace and on the node with other content: existing
        revertA();
        node.putRepositoryFile(B, schema("someone else's b"));
        ImportService readOnly = readOnly();
        nodeRead();

        denied(readOnly);

        assertThat(auditLines()).singleElement().satisfies(record -> assertThat(
            repositoryInputs(record)).containsExactly("existing " + B + " " + hash(B)));
    }

    @Test
    void anExecutionWithoutARepositoryFileToSendAuditsTheIdenticalOnes() throws IOException {
        // the preview of an all-identical change set, then its execution (FR-017: not sent)
        revertA();
        node.putRepositoryFile(B, schema("b"));
        nodeRead();
        String code = ((ImportService.Response.Challenge) service.importArtifacts(
            harness.group("Schemas"))).preview().confirmationCode();
        nodeRead();
        harness.importApplied().exportGroup();
        service.importArtifacts(ImportHarness.confirmed(harness.group("Schemas"), code));
        harness.cli.verifyComplete();

        List<JsonNode> records = auditLines();

        assertThat(records).extracting(record -> record.path("outcome").asString())
            .startsWith("CHALLENGE_ISSUED").endsWith("EXECUTED");
        assertThat(records).allSatisfy(record -> assertThat(repositoryInputs(record))
            .containsExactly("identical " + B + " " + hash(B)));
    }

    @Test
    void aPathTooLongForTheInputLimitIsShortenedButKeepsOperationAndHash() throws IOException {
        String longPath = "/Root/jdoe/" + "d".repeat(200) + "/" + "e".repeat(200) + "/"
            + "f".repeat(150) + ".xsd";
        harness.write(file(longPath), schema("long"));
        reference("Module-0003", "x.long", longPath);

        denied(readOnly());

        assertThat(auditLines()).singleElement().satisfies(record -> {
            List<String> inputs = repositoryInputs(record);
            assertThat(inputs).hasSize(3)
                .allSatisfy(value -> assertThat(value).hasSizeLessThanOrEqualTo(500));
            assertThat(inputs).filteredOn(value -> value.startsWith("create /Root/jdoe/ddd"))
                .singleElement().satisfies(value -> assertThat(value).hasSize(500)
                    .endsWith("… " + hash(longPath)));
        });
    }

    // --- T028: the repositoryPaths scope (FR-005) ----------------------------------------------

    @Test
    void aRepositoryPathsImportPreviewsTheFilesBelowThePathsAndAuditsItsScope() {
        // a.xsd and b.xsd are below xsd; the changed workflow and module are out of the scope,
        // and Module-0003 uses both files from outside it
        harness.exportRepository(node, "/Root/jdoe/xsd");

        ImportService.Response response = service.importArtifacts(
            harness.repositoryPaths("Schemas", "xsd"));

        harness.cli.verifyComplete();
        assertThat(harness.cli.launches()).as("only the node read of the folder").hasSize(1);
        assertThat(response).isInstanceOf(ImportService.Response.Challenge.class);
        ImportPreview preview = ((ImportService.Response.Challenge) response).preview();
        assertThat(preview.scope()).isEqualTo("repository paths xsd");
        assertThat(preview.create()).containsExactly(B);
        assertThat(preview.modify()).containsExactly(A);
        assertThat(preview.notImported()).contains(harness.workflow("Workflow-0001"),
            harness.moduleDirectory("Module-0003") + "/module.xml");
        String module = "Module-0003 (" + harness.moduleDirectory("Module-0003").split("/")[3]
            + " module)";
        assertThat(preview.repositoryReferences()).hasValueSatisfying(references -> {
            assertThat(references).extracting(ImportPreview.RepositoryReferences::path)
                .containsExactly(A, B);
            assertThat(references).allSatisfy(reference ->
                assertThat(reference.artifacts()).containsExactly(module));
        });
        assertThat(preview.warnings()).hasValueSatisfying(warnings -> assertThat(warnings)
            .anySatisfy(warning -> assertThat(warning).contains(A + " (1)"))
            .anySatisfy(warning -> assertThat(warning).contains(A, "cannot be listed")));
        assertThat(harness.audit("import_artifacts")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.CHALLENGE_ISSUED);
            assertThat(record.inputs()).containsEntry("scope", "repository paths xsd");
        });
    }

    @Test
    void otherRepositoryPathsWithTheCodeOfThePreviewAreConfirmationInvalid() {
        // the same files, named by other paths: the code confirms the paths, not just the files
        harness.exportRepository(node, "/Root/jdoe/xsd");
        String code = ((ImportService.Response.Challenge) service.importArtifacts(
            harness.repositoryPaths("Schemas", "xsd"))).preview().confirmationCode();
        harness.exportRepository(node, "/Root/jdoe/xsd");

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> service.importArtifacts(ImportHarness.confirmed(harness.repositoryPaths(
                "Schemas", "xsd/a.xsd", "xsd/b.xsd"), code)));

        assertThat(exception).as("expected a refusal").isNotNull();
        harness.cli.verifyComplete();
        assertThat(exception.error().code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(exception.error().message()).contains("other inputs");
        assertThat(node.repositoryImports).isEmpty();
        assertThat(harness.audit("import_artifacts")).last().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.inputs()).containsEntry("scope",
                "repository paths xsd/a.xsd, xsd/b.xsd");
        });
    }

    @Test
    void aTagWithRepositoryPathsIsInvalidInputBeforeAnythingIsSent() {
        ImportService.ImportRequest paths = harness.repositoryPaths("Schemas", "xsd");

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> service.importArtifacts(new ImportService.ImportRequest(paths.node(),
                paths.owner(), paths.diagramGroup(), paths.modules(), paths.repositoryPaths(),
                paths.reason(), paths.confirmationCode(), paths.mcpClient(),
                Optional.of("REL-1"))));

        assertThat(exception).as("expected a refusal").isNotNull();
        assertThat(exception.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(exception.error().message()).contains("diagram group", "repository paths");
        assertThat(harness.cli.launches()).isEmpty();
        assertThat(harness.audit("import_artifacts")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.inputs()).containsEntry("scope", "repository paths xsd")
                .containsEntry("tag", "REL-1");
        });
    }

    @Test
    void pathsTheServerRefusesAndASecondScopeAreInvalidInputBeforeAnythingIsSent() {
        // FR-005: relative to the owner's root and normalized; the schema pattern lets some pass
        List<List<String>> refused = new ArrayList<>();
        for (String path : List.of("", ".", "..", "../jdoe/xsd", "xsd/../xsd", "xsd/./a.xsd",
            "/xsd", "/Root/jdoe/xsd", "xsd//a.xsd", "xsd/", "xsd%2Fa.xsd", "xsd\\a.xsd")) {
            refused.add(List.of(path));
        }
        refused.add(List.of("xsd", "xsd"));
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= 50; i++) {
            many.add("xsd/s" + i + ".xsd");
        }
        refused.add(many);
        List<ImportService.ImportRequest> requests = new ArrayList<>();
        refused.forEach(paths -> requests.add(new ImportService.ImportRequest(
            ImportHarness.DEV.value(), Optional.empty(), Optional.empty(),
            List.of(), paths, "Schemas", Optional.empty(), Optional.empty(),
            Optional.empty())));
        requests.add(new ImportService.ImportRequest(ImportHarness.DEV.value(),
            Optional.empty(), Optional.of("GRP-01"), List.of(),
            List.of("xsd"), "Schemas", Optional.empty(), Optional.empty(),
            Optional.empty()));
        requests.add(new ImportService.ImportRequest(ImportHarness.DEV.value(),
            Optional.empty(), Optional.empty(), List.of(
                new ImportScope.Module("Module-0003",
                    Optional.empty())), List.of("xsd"), "Schemas",
            Optional.empty(), Optional.empty(), Optional.empty()));

        for (ImportService.ImportRequest request : requests) {
            ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
                () -> service.importArtifacts(request));
            assertThat(exception).as(request.repositoryPaths().toString()).isNotNull();
            assertThat(exception.error().code()).as(request.repositoryPaths().toString())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        }
        assertThat(harness.cli.launches()).isEmpty();
        assertThat(harness.audit("import_artifacts")).hasSize(requests.size())
            .allSatisfy(record -> assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
    }
}
