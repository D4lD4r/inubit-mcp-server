package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.RepositoryArchive.RepositoryFile;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T006 (feature 005, research D-1): the archive of a repository import holds its entries
 * relative to {@code /Root/<owner>} (the probed import path) and never carries key material;
 * repository exports are read back into files.
 */
class RepositoryArchiveTest {

    private static byte[] cliFixture(String name) {
        try (InputStream in = RepositoryArchiveTest.class.getResourceAsStream(
            "/fixtures/v8_1/cli/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] metadata(String path) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Property name=\""
            + path.substring(path.lastIndexOf('/') + 1) + "\" type=\"RepositoryFile\""
            + " uuid=\"00000000-0000-0000-0000-000000000101\" path=\"" + path + "\""
            + " version=\"1.0\"><Description>Fixture</Description></Property>")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static RepositoryFile file(String path, String content) {
        return new RepositoryFile(path, metadata(path), content.getBytes(StandardCharsets.UTF_8));
    }

    private static ToolErrorException failure(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            return e;
        }
        throw new AssertionError("no ToolErrorException");
    }

    @Test
    void readsTheFilesOfARepositoryExport() {
        List<RepositoryFile> files = RepositoryArchive.read(cliFixture("export_repository.zip"));

        assertThat(files).extracting(RepositoryFile::path).containsExactly(
            "/Root/jdoe/xsd/order.xsd", "/Root/jdoe/xsd/release.xsl");
        RepositoryFile release = files.get(1);
        assertThat(new String(release.metadata(), StandardCharsets.UTF_8))
            .contains("version=\"1.1\"");
        assertThat(new String(release.content(), StandardCharsets.UTF_8))
            .contains("'v2'");
    }

    @Test
    void readsTheRepositoryOfAReleaseExport() {
        byte[] repository = ArtifactFixtures.entries(cliFixture("export_release.zip"))
            .get("Repository.zip");

        assertThat(RepositoryArchive.read(repository)).singleElement().satisfies(file -> {
            assertThat(file.path()).isEqualTo("/Root/jdoe/xsd/release.xsl");
            assertThat(new String(file.metadata(), StandardCharsets.UTF_8))
                .contains("tagName=\"TAG-01\"");
        });
        assertThat(RepositoryArchive.read(ArtifactFixtures.zip(Map.of()))).isEmpty();
    }

    @Test
    void buildsEntriesRelativeToTheOwnersRoot() {
        byte[] zip = RepositoryArchive.build("jdoe", List.of(
            file("/Root/jdoe/xsd/release.xsl", "<xsl/>"),
            file("/Root/jdoe/xsd/sub/order.xsd", "<xs/>"),
            file("/Root/jdoe/top.xsd", "<top/>")));

        Map<String, byte[]> entries = ArtifactFixtures.entries(zip);
        assertThat(entries.keySet()).containsExactly("top.xsd.xml", "top.xsd.dat", "xsd/",
            "xsd/release.xsl.xml", "xsd/release.xsl.dat", "xsd/sub/", "xsd/sub/order.xsd.xml",
            "xsd/sub/order.xsd.dat");
        assertThat(entries.get("xsd/release.xsl.dat")).asString(StandardCharsets.UTF_8)
            .isEqualTo("<xsl/>");
        assertThat(entries.get("xsd/release.xsl.xml")).asString(StandardCharsets.UTF_8)
            .contains("path=\"/Root/jdoe/xsd/release.xsl\"", "contentSize=\"6\"",
                "<Description>Fixture</Description>");
        assertThat(entries.keySet()).noneMatch(name -> name.startsWith("Root/"));
    }

    @Test
    void anExportReadBackBuildsTheSameFiles() {
        List<RepositoryFile> files = RepositoryArchive.read(cliFixture("export_repository.zip"));

        byte[] zip = RepositoryArchive.build("jdoe", files);

        assertThat(RepositoryArchive.readRelative("jdoe", zip))
            .usingRecursiveFieldByFieldElementComparator().isEqualTo(files);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/Root/other/x.xsd", "/Root/jdoe", "/Root/jdoe/", "/Root/jdoe/../x",
        "/Root/jdoe/./x", "/Root/jdoe//x", "Root/jdoe/x", "/Root/jdoe/a'b", "/Root/jdoe/a\\b",
        "/Root/jdoex/y.xsd"})
    void refusesPathsOutsideTheOwnersRoot(String path) {
        ToolErrorException e = failure(() -> RepositoryArchive.build("jdoe",
            List.of(new RepositoryFile(path, metadata(path), new byte[] {1}))));

        assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void refusesMetadataOfAnotherPathDuplicatesAndAnEmptyList() {
        assertThat(failure(() -> RepositoryArchive.build("jdoe", List.of(new RepositoryFile(
            "/Root/jdoe/a.xsd", metadata("/Root/jdoe/b.xsd"), new byte[] {1})))).error().code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(failure(() -> RepositoryArchive.build("jdoe", List.of(new RepositoryFile(
            "/Root/jdoe/a.xsd", "<Other/>".getBytes(StandardCharsets.UTF_8), new byte[] {1}))))
            .error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(failure(() -> RepositoryArchive.build("jdoe", List.of(
            file("/Root/jdoe/a.xsd", "1"), file("/Root/jdoe/a.xsd", "2")))).error().code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(failure(() -> RepositoryArchive.build("jdoe", List.of())).error().code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void neverCarriesKeyMaterial() {
        byte[] jks = {(byte) 0xfe, (byte) 0xed, (byte) 0xfe, (byte) 0xed, 0, 0, 0, 2};
        byte[] pem = ("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n")
            .getBytes(StandardCharsets.UTF_8);
        byte[] p12 = ArtifactFixtures.bytes("keystores/fixture-client.p12");

        for (RepositoryFile key : List.of(
            new RepositoryFile("/Root/jdoe/keys/a.xsd", metadata("/Root/jdoe/keys/a.xsd"), jks),
            new RepositoryFile("/Root/jdoe/keys/b.txt", metadata("/Root/jdoe/keys/b.txt"), pem),
            new RepositoryFile("/Root/jdoe/keys/c.bin", metadata("/Root/jdoe/keys/c.bin"), p12),
            file("/Root/jdoe/keys/client.p12", "not really a keystore"),
            // stage 1 review #1 (FR-015a): certificates are never deployed either
            file("/Root/jdoe/keys/partner.cer", "x"), file("/Root/jdoe/keys/partner.CRT", "x"),
            file("/Root/jdoe/keys/partner.der", "x"), file("/Root/jdoe/keys/partner.pem", "x"),
            file("/Root/jdoe/keys/chain.p7b", "x"), file("/Root/jdoe/keys/chain.p7c", "x"),
            file("/Root/jdoe/keys/code.spc", "x"),
            new RepositoryFile("/Root/jdoe/keys/d.txt", metadata("/Root/jdoe/keys/d.txt"),
                pem(CERTIFICATE, "CERTIFICATE")),
            new RepositoryFile("/Root/jdoe/keys/e.txt", metadata("/Root/jdoe/keys/e.txt"),
                pem(new byte[] {0x30, 0x03, 0x02, 0x01, 0x01}, "PKCS7")),
            new RepositoryFile("/Root/jdoe/keys/f.bin", metadata("/Root/jdoe/keys/f.bin"),
                CERTIFICATE))) {
            ToolErrorException e = failure(() -> RepositoryArchive.build("jdoe", List.of(
                file("/Root/jdoe/xsd/ok.xsd", "<xs/>"), key)));

            assertThat(e.error().code()).as(key.path()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(e.error().message()).contains(key.path()).contains("key material");
            assertThat(RepositoryArchive.isKeyMaterial(key.path(), key.content()))
                .as(key.path()).isTrue();
        }
    }

    private static final byte[] CERTIFICATE = certificate();

    private static byte[] certificate() {
        try {
            return de.dadecker.inubit.mcp.adapter.rest.TestCertificates.get()
                .selfsignedCertificate().getEncoded();
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] pem(byte[] der, String type) {
        return ("-----BEGIN " + type + "-----\n"
            + java.util.Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type
            + "-----\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void ordinaryFilesAreNoKeyMaterial() {
        assertThat(RepositoryArchive.isKeyMaterial("/Root/jdoe/xsd/order.xsd",
            "<xs:schema/>".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(RepositoryArchive.isKeyMaterial("/Root/jdoe/data/seq.bin",
            new byte[] {0x30, 0x03, 0x02, 0x01, 0x01})).isFalse();
        assertThat(RepositoryArchive.isKeyMaterial("/Root/jdoe/xsd/certificate-notes.txt",
            "a text about certificates".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void theMetadataStatesTheContentThatIsWrittenWithoutTag() {
        // stage 1 review #5: size and MD5 of the written content; no tag of the source
        String path = "/Root/jdoe/xsd/release.xsl";
        byte[] metadata = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Property"
            + " name=\"release.xsl\" type=\"RepositoryFile\" versionComment=\"a > b\""
            + " uuid=\"00000000-0000-0000-0000-000000000101\" path=\"" + path + "\""
            + " contentSize=\"999\" contentMD5=\"00000000000000000000000000000000\""
            + " tagName=\"TAG-01\" version=\"1.0\"><Description>Fixture</Description>"
            + "</Property>").getBytes(StandardCharsets.UTF_8);
        byte[] content = "<xsl/>".getBytes(StandardCharsets.UTF_8);

        String written = new String(ArtifactFixtures.entries(RepositoryArchive.build("jdoe",
            List.of(new RepositoryFile(path, metadata, content)))).get("xsd/release.xsl.xml"),
            StandardCharsets.UTF_8);

        assertThat(written).contains("contentSize=\"6\"",
            "contentMD5=\"" + md5(content) + "\"",
            "uuid=\"00000000-0000-0000-0000-000000000101\"", "versionComment=\"a > b\"",
            "<Description>Fixture</Description>").doesNotContain("tagName", "999");
    }

    private static String md5(byte[] content) {
        try {
            return String.format("%032x", new java.math.BigInteger(1,
                java.security.MessageDigest.getInstance("MD5").digest(content)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theInflationOfAnArchiveIsBounded() {
        // stage 1 review #4: a small ZIP must not inflate without limit
        byte[] zip = ArtifactFixtures.zip(java.util.Map.of("Root/jdoe/big.xsd.xml",
            metadata("/Root/jdoe/big.xsd"), "Root/jdoe/big.xsd.dat", new byte[4096]));

        ToolErrorException e = failure(() -> RepositoryArchive.read(zip, 1024));

        assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(e.error().message()).contains("too large");
        assertThat(RepositoryArchive.read(zip, 8192)).hasSize(1);
        assertThat(RepositoryArchive.MAX_ENTRY_BYTES).isEqualTo(64L << 20);
    }

    @Test
    void theFileNeverPrintsItsContent() {
        assertThat(file("/Root/jdoe/xsd/a.xsd", "secret-ish content"))
            .asString().contains("/Root/jdoe/xsd/a.xsd").doesNotContain("secret-ish");
    }
}
