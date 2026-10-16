package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.application.ExportHarness.DEV;
import static de.dadecker.inubit.mcp.application.ExportHarness.diagramGroups;
import static de.dadecker.inubit.mcp.application.ExportHarness.repositoryPaths;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.SyntheticKeystore;
import de.dadecker.inubit.mcp.application.WorkspaceService.ExportRequest;
import de.dadecker.inubit.mcp.application.WorkspaceService.ExportResult;
import de.dadecker.inubit.mcp.application.WorkspaceService.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.HistoryEntry;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature 008 T036 (research D-10, FR-009, US3): {@link WorkspaceService#export} of repository
 * paths, on a real git history and the real archive codec with a fake StartCLI.
 */
class WorkspaceServiceRepositoryExportTest {

    private static final String REPOSITORY = "dev/jdoe/repository/Root/jdoe/";
    private static final String META = ".meta/" + REPOSITORY;

    @TempDir
    Path root;

    private ExportHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ExportHarness(root);
        harness.artifacts.exports.put("/Root/jdoe/xsd", cliFixture("export_repository_folder.zip"));
        harness.artifacts.exports.put("/Root/jdoe/xsd/P8/types",
            cliFixture("export_repository_types.zip"));
    }

    private static byte[] cliFixture(String name) {
        try (InputStream in = WorkspaceServiceRepositoryExportTest.class.getResourceAsStream(
            "/fixtures/v8_1/cli/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A repository export of {@code files} (repository path to content), in the export shape. */
    private static byte[] repositoryExport(Map<String, byte[]> files) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        files.forEach((path, content) -> {
            entries.put(path.substring(1) + ".xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<Property name=\"" + path.substring(path.lastIndexOf('/') + 1) + "\""
                + " type=\"RepositoryFile\" uuid=\"00000000-0000-0000-0000-000000000301\""
                + " path=\"" + path + "\" contentSize=\"" + content.length + "\""
                + " contentMD5=\"00000000000000000000000000000000\" version=\"1.0\">"
                + "<Description>Fixture</Description></Property>")
                .getBytes(StandardCharsets.UTF_8));
            entries.put(path.substring(1) + ".dat", content);
        });
        return ArtifactFixtures.zip(entries);
    }

    /** A repository export of {@code count} files {@code <folder>/f<i>.xml}, each {@code content}. */
    private static byte[] folderOf(String folder, int count, byte[] content) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        IntStream.range(0, count).forEach(i -> files.put(folder + "/f" + i + ".xml", content));
        return repositoryExport(files);
    }

    private static ToolErrorException refusal(Runnable call, ErrorCode code) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            assertThat(e.error().code()).as(e.error().message()).isEqualTo(code);
            return e;
        }
        throw new AssertionError("expected " + code);
    }

    /** The files of {@code snapshot} outside the workspace sub-trees {@code scopes}. */
    private static SortedMap<String, String> outside(SortedMap<String, String> snapshot,
        String... scopes) {
        SortedMap<String, String> rest = new TreeMap<>(snapshot);
        rest.keySet().removeIf(path -> Arrays.stream(scopes).anyMatch(scope -> path.equals(scope)
            || path.startsWith(scope + "/")));
        return rest;
    }

    @Test
    void aFolderIsExportedRecursively() {
        ExportResult result = harness.service().export(repositoryPaths("jdoe", "xsd"));

        assertThat(harness.artifacts.calls).containsExactly("repository /Root/jdoe/xsd");
        Map<String, byte[]> exported = ArtifactFixtures.entries(
            cliFixture("export_repository_folder.zip"));
        List<String> dat = exported.keySet().stream().filter(name -> name.endsWith(".dat"))
            .toList();
        assertThat(dat).hasSize(16);
        for (String name : dat) {
            String path = name.substring("Root/jdoe/".length(), name.length() - ".dat".length());
            assertThat(root.resolve(REPOSITORY + path)).as(path)
                .hasBinaryContent(exported.get(name));
            assertThat(root.resolve(META + path + ".json")).as(path).isRegularFile();
        }
        assertThat(root.resolve(REPOSITORY + "xsd/P8/folder/sub/included.xsd")).isRegularFile();
        assertThat(result.localChanges()).isEmpty();
        assertThat(result.secretsReplaced()).isZero();
        assertThat(result.warnings()).isEmpty();
        assertThat(harness.history.status()).isEmpty();
    }

    @Test
    void theExportIsOneHistoryEntryWithTheServerState() {
        ExportResult result = harness.service().export(repositoryPaths("jdoe", "xsd/P8/types",
            "xsd"));

        assertThat(harness.artifacts.calls).containsExactly("repository /Root/jdoe/xsd/P8/types",
            "repository /Root/jdoe/xsd");
        HistoryEntry entry = result.export().orElseThrow();
        assertThat(entry.message()).isEqualTo("export dev/node1: repository xsd/P8/types, xsd ("
            + entry.changes().size() + " files)");
        assertThat(entry.changes()).extracting(PathChange::path).contains(
            REPOSITORY + "xsd/P8/types/probe.xsd", META + "xsd/P8/types/probe.xsd.json",
            REPOSITORY + "xsd/probe.xsl");
        assertThat(harness.log()).containsExactly(entry.message());
        assertThat(harness.git("log", "-1", "--format=%(trailers:key=Server-State,valueonly)")
            .strip()).isEqualTo("dev");
    }

    @Test
    void anyMissingPathFailsTheWholeCallNamingEveryMissingPathAndWritesNothing() {
        WorkspaceService service = harness.service();
        service.export(repositoryPaths("jdoe", "xsd/P8/types"));
        SortedMap<String, String> before = harness.snapshot();
        harness.artifacts.calls.clear();

        ToolErrorException e = refusal(() -> service.export(repositoryPaths("jdoe", "gone",
            "xsd", "also/gone")), ErrorCode.NOT_FOUND);

        assertThat(e.error().message()).contains("/Root/jdoe/gone", "/Root/jdoe/also/gone")
            .doesNotContain("/Root/jdoe/xsd");
        assertThat(e.error().node()).contains(DEV);
        assertThat(harness.artifacts.calls).as("every path is tried").containsExactly(
            "repository /Root/jdoe/gone", "repository /Root/jdoe/xsd",
            "repository /Root/jdoe/also/gone");
        assertThat(harness.snapshot()).isEqualTo(before);
        assertThat(harness.log()).hasSize(1);
        assertThat(harness.history.status()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/Root/jdoe/xsd", "/xsd", "", "/", ".", "..", "xsd/../..",
        "xsd/./P8", "xsd//P8", "xsd%2FP8"})
    void anAbsoluteEmptyRootOrDotPathIsInvalidInput(String path) {
        ToolErrorException e = refusal(() -> harness.service().export(repositoryPaths("jdoe",
            "xsd", path)), ErrorCode.INVALID_INPUT);

        assertThat(e.error().message()).contains(path);
        assertThat(harness.artifacts.calls).isEmpty();
        assertThat(root.resolve(".git")).doesNotExist();
    }

    @Test
    void moreThanTwentyPathsAreInvalidInput() {
        String[] paths = IntStream.range(0, 21).mapToObj(i -> "p" + i).toArray(String[]::new);

        ToolErrorException e = refusal(() -> repositoryPaths("jdoe", paths),
            ErrorCode.INVALID_INPUT);

        assertThat(e.error().message()).contains("20");
    }

    @Test
    void exactlyOneOfDiagramGroupsModulesOrRepositoryPathsIsRequired() {
        List<Runnable> calls = List.of(
            () -> new ExportRequest(DEV, "jdoe", List.of("GRP-01"), List.of(), List.of("xsd")),
            () -> new ExportRequest(DEV, "jdoe", List.of(),
                List.of(new ModuleRef("Module-0001", Optional.empty())), List.of("xsd")),
            () -> new ExportRequest(DEV, "jdoe", List.of(), List.of(), List.of()));

        for (Runnable call : calls) {
            assertThat(refusal(call, ErrorCode.INVALID_INPUT).error().message())
                .isEqualTo("Give exactly one of diagramGroups, modules or repositoryPaths");
        }
    }

    @Test
    void moreThan500FilesInTotalAreRefusedBeforeAnythingIsWritten() {
        WorkspaceService service = harness.service();
        service.export(repositoryPaths("jdoe", "xsd/P8/types"));
        SortedMap<String, String> before = harness.snapshot();
        byte[] content = "<a/>".getBytes(StandardCharsets.UTF_8);
        harness.artifacts.exports.put("/Root/jdoe/a", folderOf("/Root/jdoe/a", 300, content));
        harness.artifacts.exports.put("/Root/jdoe/b", folderOf("/Root/jdoe/b", 201, content));

        ToolErrorException e = refusal(() -> service.export(repositoryPaths("jdoe", "a", "b")),
            ErrorCode.INVALID_INPUT);

        assertThat(e.error().message()).contains("501 files", "0.0 MiB",
            "at most 500 files and 100 MiB");
        assertThat(e.error().nextStep()).contains("narrower");
        assertThat(e.error().node()).contains(DEV);
        assertThat(harness.snapshot()).isEqualTo(before);
        assertThat(harness.log()).hasSize(1);
    }

    @Test
    void exactly500FilesAreExported() {
        byte[] content = "<a/>".getBytes(StandardCharsets.UTF_8);
        harness.artifacts.exports.put("/Root/jdoe/a", folderOf("/Root/jdoe/a", 500, content));

        ExportResult result = harness.service().export(repositoryPaths("jdoe", "a"));

        assertThat(result.export()).isPresent();
        assertThat(root.resolve(REPOSITORY + "a/f499.xml")).hasBinaryContent(content);
    }

    @Test
    void moreThan100MiBAreRefusedBeforeAnythingIsWritten() {
        WorkspaceService service = harness.service();
        service.export(repositoryPaths("jdoe", "xsd/P8/types"));
        SortedMap<String, String> before = harness.snapshot();
        byte[] mebibyte = new byte[1 << 20];
        harness.artifacts.exports.put("/Root/jdoe/big", folderOf("/Root/jdoe/big", 101,
            mebibyte));

        ToolErrorException e = refusal(() -> service.export(repositoryPaths("jdoe", "big")),
            ErrorCode.INVALID_INPUT);

        assertThat(e.error().message()).contains("101 files", "101.0 MiB",
            "at most 500 files and 100 MiB");
        assertThat(harness.snapshot()).isEqualTo(before);
        assertThat(harness.log()).hasSize(1);
    }

    @Test
    void requestedPathsThatDifferOnlyInCaseAreInvalidInput() {
        ToolErrorException e = refusal(() -> harness.service().export(repositoryPaths("jdoe",
            "xsd", "XSD")), ErrorCode.INVALID_INPUT);

        assertThat(e.error().message()).contains("xsd", "XSD");
        assertThat(harness.artifacts.calls).isEmpty();
    }

    @Test
    void exportedFilesThatDifferOnlyInCaseAreInvalidInputAndNothingIsWritten() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("/Root/jdoe/mixed/order.xsd", "<a/>".getBytes(StandardCharsets.UTF_8));
        files.put("/Root/jdoe/mixed/ORDER.xsd", "<b/>".getBytes(StandardCharsets.UTF_8));
        harness.artifacts.exports.put("/Root/jdoe/mixed", repositoryExport(files));
        WorkspaceService service = harness.service();
        service.export(repositoryPaths("jdoe", "xsd/P8/types"));
        SortedMap<String, String> before = harness.snapshot();

        ToolErrorException e = refusal(() -> service.export(repositoryPaths("jdoe", "mixed")),
            ErrorCode.INVALID_INPUT);

        assertThat(e.error().message()).contains(REPOSITORY + "mixed/order.xsd",
            REPOSITORY + "mixed/ORDER.xsd");
        assertThat(e.error().node()).contains(DEV);
        assertThat(harness.snapshot()).isEqualTo(before);
        assertThat(harness.log()).hasSize(1);
    }

    @Test
    void aKeystoreIsWithheldWithAWarningAndACertificateIsWritten() throws Exception {
        SyntheticKeystore keystore = ArtifactFixtures.syntheticKeystores().stream()
            .filter(k -> k.file().endsWith(".p12")).findFirst().orElseThrow();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keystore.bytes()),
            keystore.storePassword().toCharArray());
        byte[] certificate = store.getCertificate(store.aliases().nextElement()).getEncoded();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("/Root/jdoe/keys/client.p12", keystore.bytes());
        files.put("/Root/jdoe/keys/partner.cer", certificate);
        harness.artifacts.exports.put("/Root/jdoe/keys", repositoryExport(files));

        ExportResult result = harness.service().export(repositoryPaths("jdoe", "keys"));

        assertThat(root.resolve(REPOSITORY + "keys/client.p12")).doesNotExist();
        assertThat(root.resolve(META + "keys/client.p12.json")).content()
            .contains("${secret:Repository/Root/jdoe/keys/client.p12}");
        assertThat(root.resolve(REPOSITORY + "keys/partner.cer")).hasBinaryContent(certificate);
        assertThat(result.warnings()).containsExactly("1 repository file(s) with key material"
            + " were not written; .meta holds a placeholder");
        assertThat(result.secretsReplaced()).isEqualTo(1);
    }

    @Test
    void uncommittedEditsAreCommittedFirstAndLocalFilesStay() throws IOException {
        WorkspaceService service = harness.service();
        service.export(repositoryPaths("jdoe", "xsd"));
        Path edited = root.resolve(REPOSITORY + "xsd/probe.xsd");
        byte[] exported = Files.readAllBytes(edited);
        Files.writeString(edited, "<edited/>");
        Path created = root.resolve(REPOSITORY + "xsd/P8/new.xsd");
        Files.writeString(created, "<new/>");

        ExportResult result = service.export(repositoryPaths("jdoe", "xsd"));

        HistoryEntry local = result.localChanges().orElseThrow();
        assertThat(local.message()).isEqualTo("local changes: 2 files");
        assertThat(local.changes()).containsExactlyInAnyOrder(
            new PathChange(REPOSITORY + "xsd/probe.xsd", PathChange.Kind.MODIFIED),
            new PathChange(REPOSITORY + "xsd/P8/new.xsd", PathChange.Kind.ADDED));
        assertThat(result.export().orElseThrow().changes()).containsExactly(
            new PathChange(REPOSITORY + "xsd/probe.xsd", PathChange.Kind.MODIFIED));
        assertThat(harness.log()).hasSize(3).element(1).isEqualTo(local.message());
        assertThat(edited).hasBinaryContent(exported);
        assertThat(created).as("overwritten, never deleted").hasContent("<new/>");
    }

    @Test
    void workspaceFilesOutsideTheExportedPathsAreUntouched() {
        harness.artifacts.exports.put("GRP-01", ArtifactFixtures.bytes("grp-a.zip"));
        WorkspaceService service = harness.service();
        service.export(diagramGroups("jdoe", "GRP-01"));
        service.export(repositoryPaths("jdoe", "xsd"));
        SortedMap<String, String> before = harness.snapshot();
        Map<String, byte[]> types = new LinkedHashMap<>(ArtifactFixtures.entries(
            cliFixture("export_repository_types.zip")));
        types.put("Root/jdoe/xsd/P8/types/probe.xsd.dat",
            "<changed/>".getBytes(StandardCharsets.UTF_8));
        harness.artifacts.exports.put("/Root/jdoe/xsd/P8/types", ArtifactFixtures.zip(types));

        ExportResult result = service.export(repositoryPaths("jdoe", "xsd/P8/types"));

        String scope = REPOSITORY + "xsd/P8/types";
        String metaScope = META + "xsd/P8/types";
        assertThat(result.export().orElseThrow().changes()).containsExactly(
            new PathChange(scope + "/probe.xsd", PathChange.Kind.MODIFIED));
        assertThat(outside(harness.snapshot(), scope, metaScope))
            .isEqualTo(outside(before, scope, metaScope))
            .containsKeys(REPOSITORY + "xsd/P8/untyped/probe.xsd",
                "dev/jdoe/workflows/GRP-01/Workflow-0001.xml");
    }
}
