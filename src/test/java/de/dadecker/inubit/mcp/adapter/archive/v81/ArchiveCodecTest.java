package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.SyntheticKeystore;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort.PreparedExport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 008 T037 (research D-10, FR-009): {@link ArchiveCodec#prepareRepository} renders
 * repository exports ({@code export --exportRepositoryPath}, one archive per path) into the
 * workspace; its scope is the exported paths and their {@code .meta} mirrors only.
 */
class ArchiveCodecTest {

    private static final GroupId DEV = new GroupId("dev");
    private static final String REPOSITORY = "dev/jdoe/repository/Root/jdoe/";
    private static final String META = ".meta/" + REPOSITORY;

    private final ArchiveCodec codec = new ArchiveCodec();

    @TempDir
    Path root;

    private static byte[] cliFixture(String name) {
        try (InputStream in = ArchiveCodecTest.class.getResourceAsStream(
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

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(PreparedExport prepared, String path) {
        assertThat(prepared.files()).containsKey(path);
        return new String(prepared.files().get(path), StandardCharsets.UTF_8);
    }

    @Test
    void aFolderExportWritesEveryFileBelowItRecursivelyWithItsMetaRecord() {
        byte[] archive = cliFixture("export_repository_folder.zip");

        PreparedExport prepared = codec.prepareRepository(DEV, "jdoe", List.of(archive));

        Map<String, byte[]> exported = ArtifactFixtures.entries(archive);
        List<String> dat = exported.keySet().stream().filter(name -> name.endsWith(".dat"))
            .toList();
        assertThat(dat).hasSize(16);
        assertThat(prepared.files()).hasSize(2 * dat.size());
        for (String name : dat) {
            String path = name.substring("Root/jdoe/".length(), name.length() - ".dat".length());
            assertThat(prepared.files().get(REPOSITORY + path)).as(path)
                .isEqualTo(exported.get(name));
            assertThat(text(prepared, META + path + ".json")).as(path)
                .contains("\"metadata\"", "path=\\\"/Root/jdoe/" + path + "\\\"");
        }
        assertThat(prepared.files()).containsKeys(REPOSITORY + "xsd/P8/folder/sub/included.xsd",
            REPOSITORY + "xsd/P8/nested/inner-uri.xsl");
        assertThat(prepared.secretsReplaced()).isZero();
        assertThat(prepared.warnings()).isEmpty();
        assertThat(prepared.inEditMode()).isEmpty();
    }

    @Test
    void theScopeIsTheExportedPathsAndTheirMetaMirrorsOnly() {
        byte[] folder = cliFixture("export_repository_types.zip");
        byte[] file = repositoryExport(Map.of("/Root/jdoe/xsd/order.xsd",
            ArtifactFixtures.entries(cliFixture("export_repository.zip"))
                .get("Root/jdoe/xsd/order.xsd.dat")));

        PreparedExport prepared = codec.prepareRepository(DEV, "jdoe", List.of(folder, file));

        assertThat(prepared.scope()).containsExactlyInAnyOrder(
            REPOSITORY + "xsd/P8/types", META + "xsd/P8/types",
            REPOSITORY + "xsd/order.xsd", META + "xsd/order.xsd.json");
        assertThat(prepared.files().keySet()).allMatch(path -> prepared.scope().stream()
            .anyMatch(scope -> path.equals(scope) || path.startsWith(scope + "/")));
    }

    @Test
    void writingOverwritesTheExportedFilesAndTouchesNothingElse() throws IOException {
        // D-10: files missing on the node stay (overwritten, never deleted); no export record
        Path stale = root.resolve(REPOSITORY + "xsd/P8/types/probe.xsd");
        Path localOnly = root.resolve(REPOSITORY + "xsd/P8/types/local-only.xsd");
        Path module = root.resolve("dev/jdoe/modules/XSLT Converter/Module-0001/module.xml");
        for (Path path : List.of(stale, localOnly, module)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, "before");
        }
        byte[] archive = cliFixture("export_repository_types.zip");

        codec.prepareRepository(DEV, "jdoe", List.of(archive)).writeTo(root);

        assertThat(stale).hasBinaryContent(ArtifactFixtures.entries(archive)
            .get("Root/jdoe/xsd/P8/types/probe.xsd.dat"));
        assertThat(localOnly).hasContent("before");
        assertThat(module).hasContent("before");
        assertThat(root.resolve(".meta/dev/jdoe/exports")).doesNotExist();
    }

    @Test
    void aKeystoreIsWithheldWithAWarningWhileACertificateIsWritten() throws Exception {
        // US3-2: key material stays out as in every export; a certificate is written (D-10)
        SyntheticKeystore keystore = ArtifactFixtures.syntheticKeystores().stream()
            .filter(k -> k.file().endsWith(".p12")).findFirst().orElseThrow();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keystore.bytes()),
            keystore.storePassword().toCharArray());
        byte[] certificate = store.getCertificate(store.aliases().nextElement()).getEncoded();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("/Root/jdoe/keys/client.p12", keystore.bytes());
        files.put("/Root/jdoe/keys/partner.cer", certificate);

        PreparedExport prepared = codec.prepareRepository(DEV, "jdoe",
            List.of(repositoryExport(files)));

        assertThat(prepared.files()).doesNotContainKey(REPOSITORY + "keys/client.p12");
        assertThat(text(prepared, META + "keys/client.p12.json"))
            .contains("\"withheld\" : \"${secret:Repository/Root/jdoe/keys/client.p12}\"")
            .doesNotContain("contentMD5", "contentSize");
        assertThat(prepared.files().get(REPOSITORY + "keys/partner.cer")).isEqualTo(certificate);
        assertThat(prepared.warnings()).containsExactly("1 repository file(s) with key material"
            + " were not written; .meta holds a placeholder");
        assertThat(prepared.secretsReplaced()).isEqualTo(1);
        assertThat(prepared.scope()).containsExactlyInAnyOrder(REPOSITORY + "keys",
            META + "keys");
    }

    @Test
    void pathsThatDifferOnlyInCaseAreRefusedWithinOneExport() {
        // US3-5
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("/Root/jdoe/xsd/order.xsd", utf8("<a/>"));
        files.put("/Root/jdoe/xsd/ORDER.xsd", utf8("<b/>"));

        assertThatThrownBy(() -> codec.prepareRepository(DEV, "jdoe",
            List.of(repositoryExport(files)))).isInstanceOfSatisfying(ToolErrorException.class,
                e -> {
                    assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.error().message()).contains(REPOSITORY + "xsd/order.xsd",
                        REPOSITORY + "xsd/ORDER.xsd");
                });
    }

    @Test
    void pathsThatDifferOnlyInCaseAreRefusedAcrossExports() {
        byte[] lower = repositoryExport(Map.of("/Root/jdoe/xsd/order.xsd", utf8("<a/>")));
        byte[] upper = repositoryExport(Map.of("/Root/jdoe/XSD/order.xsd", utf8("<b/>")));

        assertThatThrownBy(() -> codec.prepareRepository(DEV, "jdoe", List.of(lower, upper)))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.error().message()).contains(REPOSITORY + "xsd/order.xsd",
                    REPOSITORY + "XSD/order.xsd");
            });
    }

    @Test
    void aFileOfAnotherOwnerIsNotWrittenButReported() {
        // as in every export: nothing below another owner's root
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("/Root/jdoe/xsd/order.xsd", utf8("<a/>"));
        files.put("/Root/other/xsd/order.xsd", utf8("<b/>"));

        PreparedExport prepared = codec.prepareRepository(DEV, "jdoe",
            List.of(repositoryExport(files)));

        assertThat(prepared.files()).containsOnlyKeys(REPOSITORY + "xsd/order.xsd",
            META + "xsd/order.xsd.json");
        assertThat(prepared.warnings()).containsExactly("1 repository file(s) of another owner"
            + " are part of the export and were not written");
        assertThat(prepared.scope()).containsExactlyInAnyOrder(REPOSITORY + "xsd/order.xsd",
            META + "xsd/order.xsd.json");
    }

    @Test
    void anUnreadableArchiveIsAnUnexpectedResponse() {
        assertThatThrownBy(() -> codec.prepareRepository(DEV, "jdoe",
            List.of(ArtifactFixtures.zip(Map.of("other/file.txt", utf8("x"))))))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(e.error().code())
                .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }
}
