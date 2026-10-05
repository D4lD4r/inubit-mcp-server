package de.dadecker.inubit.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T037 / SC-002 (research D-11): the repository guard. The denylist names the customer values it
 * looks for, so it is never committed: it is read from {@code INUBIT_MCP_DENYLIST} or the
 * git-ignored {@code .denylist} in the repository root, and the repository scan is skipped
 * without one (release check, docs/release-checks.md).
 *
 * <p>The scanner itself is tested with a synthetic denylist and temporary files. Findings name
 * the file, the line and the pattern number, never the matched text.
 */
class NoCustomerIdentifiersTest {

    @TempDir
    Path temp;

    // --- the release check -------------------------------------------------------------------

    @Test
    void repositoryContainsNoDenylistedIdentifiers() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        Optional<Path> denylist = DenylistScanner.locateDenylist(System.getenv(), root);
        assumeTrue(denylist.isPresent(),
            "no denylist configured (INUBIT_MCP_DENYLIST or .denylist): release check skipped");

        DenylistScanner scanner = DenylistScanner.fromFile(denylist.get());
        List<String> files = new ArrayList<>(DenylistScanner.repositoryFiles(root));
        assertThat(files).as("files to scan").isNotEmpty();
        files.addAll(DenylistScanner.builtJars(root));

        List<DenylistScanner.Finding> findings = scanner.scan(root, files);

        assertThat(findings).as("customer identifiers (file:line pattern #n); <name of scanned"
            + " file #n> is line n of 'git ls-files --cached --others --exclude-standard"
            + " | LC_ALL=C sort -u' followed by target/*.jar; <name of entry #k> is line k of"
            + " 'unzip -Z1 <archive>' (see docs/release-checks.md)").isEmpty();
    }

    // --- the scanner -------------------------------------------------------------------------

    @Test
    void reportsFileLineAndPatternNumberWithoutTheMatchedText() throws IOException {
        Files.writeString(temp.resolve("a.txt"), "clean\nsome Foocorp value\nclean\n");
        Files.createDirectories(temp.resolve("sub"));
        Files.writeString(temp.resolve("sub/b.md"), "host bar-17.internal\n");
        DenylistScanner scanner = DenylistScanner.parse("""
            # comment: fictitious customer
            (?i)foocorp

            bar-\\d+\\.internal
            """);

        List<DenylistScanner.Finding> findings = scanner.scan(temp, List.of("a.txt", "sub/b.md"));

        assertThat(findings).extracting(DenylistScanner.Finding::toString)
            .containsExactly("a.txt:2 pattern #1", "sub/b.md:1 pattern #2");
        assertThat(findings.toString()).doesNotContainIgnoringCase("foocorp")
            .doesNotContain("bar-17");
    }

    @Test
    void reportsEveryMatchingPatternOfALineOnce() throws IOException {
        Files.writeString(temp.resolve("a.txt"), "foo foo bar\n");
        DenylistScanner scanner = DenylistScanner.parse("foo\nbar\nbaz\n");

        assertThat(scanner.scan(temp, List.of("a.txt"))).extracting(Object::toString)
            .containsExactly("a.txt:1 pattern #1", "a.txt:1 pattern #2");
    }

    @Test
    void patternsAreCaseSensitiveUnlessMarked() throws IOException {
        Files.writeString(temp.resolve("a.txt"), "FOO\nBar\n");
        DenylistScanner scanner = DenylistScanner.parse("foo\n(?i)bar\n");

        assertThat(scanner.scan(temp, List.of("a.txt"))).extracting(Object::toString)
            .containsExactly("a.txt:2 pattern #2");
    }

    @Test
    void scansTheTextEntriesOfZipArchives() throws IOException {
        Path zip = temp.resolve("fixtures/sample.zip");
        Files.createDirectories(zip.getParent());
        try (OutputStream out = Files.newOutputStream(zip);
             ZipOutputStream archive = new ZipOutputStream(out)) {
            archive.putNextEntry(new ZipEntry("dir/clean.xml"));
            archive.write("<a/>\n".getBytes(StandardCharsets.UTF_8));
            archive.putNextEntry(new ZipEntry("dir/model.xml"));
            archive.write("<a>\n<owner>FOOCORP</owner>\n</a>\n".getBytes(StandardCharsets.UTF_8));
            archive.putNextEntry(new ZipEntry("image.bin"));
            archive.write(new byte[] {0, 'F', 'O', 'O', 'C', 'O', 'R', 'P', 0});
        }
        DenylistScanner scanner = DenylistScanner.parse("(?i)foocorp\n");

        assertThat(scanner.scan(temp, List.of("fixtures/sample.zip"))).extracting(Object::toString)
            .containsExactly("fixtures/sample.zip!dir/model.xml:2 pattern #1");
    }

    @Test
    void skipsBinaryFiles() throws IOException {
        Files.write(temp.resolve("blob.p12"), new byte[] {1, 0, 'f', 'o', 'o', 0, 2});
        Files.writeString(temp.resolve("text.txt"), "foo\n");
        DenylistScanner scanner = DenylistScanner.parse("foo\n");

        assertThat(scanner.scan(temp, List.of("blob.p12", "text.txt"))).extracting(Object::toString)
            .containsExactly("text.txt:1 pattern #1");
    }

    @Test
    void reportsMatchingFileNamesByIndexOnly() throws IOException {
        Files.writeString(temp.resolve("clean.txt"), "x\n");
        Files.writeString(temp.resolve("data_foocorp.xml"), "x\n");
        DenylistScanner scanner = DenylistScanner.parse("(?i)foocorp\n");

        List<DenylistScanner.Finding> findings =
            scanner.scan(temp, List.of("clean.txt", "data_foocorp.xml"));

        assertThat(findings).extracting(Object::toString)
            .containsExactly("<name of scanned file #2> pattern #1");
    }

    @Test
    void contentFindingsOfAMatchingFileNameAreReportedUnderTheRedactedName() throws IOException {
        Files.writeString(temp.resolve("data_foocorp.xml"), "x\n<a>FooCorp</a>\n");
        DenylistScanner scanner = DenylistScanner.parse("(?i)foocorp\n");

        List<DenylistScanner.Finding> findings = scanner.scan(temp, List.of("data_foocorp.xml"));

        assertThat(findings).extracting(Object::toString).containsExactly(
            "<name of scanned file #1> pattern #1", "<name of scanned file #1>:2 pattern #1");
        assertThat(findings.toString()).doesNotContainIgnoringCase("foocorp");
    }

    @Test
    void contentFindingsOfAMatchingZipEntryNameAreReportedUnderTheRedactedEntry()
            throws IOException {
        Path zip = temp.resolve("sample.zip");
        try (OutputStream out = Files.newOutputStream(zip);
             ZipOutputStream archive = new ZipOutputStream(out)) {
            archive.putNextEntry(new ZipEntry("clean.txt"));
            archive.write("ok\n".getBytes(StandardCharsets.UTF_8));
            archive.putNextEntry(new ZipEntry("dir/foocorp.xml"));
            archive.write("<a>FOOCORP</a>\n".getBytes(StandardCharsets.UTF_8));
        }
        DenylistScanner scanner = DenylistScanner.parse("(?i)foocorp\n");

        List<DenylistScanner.Finding> findings = scanner.scan(temp, List.of("sample.zip"));

        assertThat(findings).extracting(Object::toString).containsExactly(
            "sample.zip!<name of entry #2> pattern #1",
            "sample.zip!<name of entry #2>:1 pattern #1");
        assertThat(findings.toString()).doesNotContainIgnoringCase("foocorp");
    }

    @Test
    void ignoresListedFilesThatNoLongerExist() throws IOException {
        DenylistScanner scanner = DenylistScanner.parse("foo\n");

        assertThat(scanner.scan(temp, List.of("deleted.txt"))).isEmpty();
    }

    @Test
    void anInvalidPatternIsRejectedByNumberWithoutItsText() {
        assertThatThrownBy(() -> DenylistScanner.parse("# c\nok\n(unclosed-secret\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pattern #2")
            .hasMessageNotContaining("unclosed-secret");
    }

    @Test
    void aDenylistWithoutPatternsIsRejected() {
        assertThatThrownBy(() -> DenylistScanner.parse("# only comments\n\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no patterns");
    }

    // --- denylist location -------------------------------------------------------------------

    @Test
    void theEnvironmentVariableWinsOverTheRepositoryFile() throws IOException {
        Path fromEnv = Files.writeString(temp.resolve("env-denylist.txt"), "foo\n");
        Files.writeString(temp.resolve(".denylist"), "bar\n");

        assertThat(DenylistScanner.locateDenylist(
                Map.of("INUBIT_MCP_DENYLIST", fromEnv.toString()), temp))
            .contains(fromEnv);
    }

    @Test
    void theRepositoryFileIsUsedWhenTheVariableIsUnset() throws IOException {
        Path local = Files.writeString(temp.resolve(".denylist"), "bar\n");

        assertThat(DenylistScanner.locateDenylist(Map.of(), temp)).contains(local);
        assertThat(DenylistScanner.locateDenylist(Map.of("INUBIT_MCP_DENYLIST", " "), temp))
            .contains(local);
    }

    @Test
    void aVariableNamingAMissingFileIsAnErrorNotAFallback() throws IOException {
        Files.writeString(temp.resolve(".denylist"), "bar\n");
        Path missing = temp.resolve("missing.txt");

        assertThatThrownBy(() -> DenylistScanner.locateDenylist(
                Map.of("INUBIT_MCP_DENYLIST", missing.toString()), temp))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("INUBIT_MCP_DENYLIST")
            .hasMessageContaining(missing.toString());
    }

    @Test
    void builtJarsAreScannedForEntryNamesAndTextEntries() throws IOException {
        Path jar = temp.resolve("target/app-1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(temp.resolve("target/notes.txt"), "FooCorp\n");
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream archive = new ZipOutputStream(out)) {
            archive.putNextEntry(new ZipEntry("com/foocorp/App.class"));
            archive.write(new byte[] {(byte) 0xCA, (byte) 0xFE, 0, 0});
            archive.putNextEntry(new ZipEntry("META-INF/app.properties"));
            archive.write("vendor=FooCorp\n".getBytes(StandardCharsets.UTF_8));
        }
        DenylistScanner scanner = DenylistScanner.parse("(?i)foocorp\n");

        List<String> jars = DenylistScanner.builtJars(temp);

        assertThat(jars).containsExactly("target/app-1.0.jar");
        assertThat(scanner.scan(temp, jars)).extracting(Object::toString).containsExactly(
            "target/app-1.0.jar!<name of entry #1> pattern #1",
            "target/app-1.0.jar!META-INF/app.properties:1 pattern #1");
        assertThat(DenylistScanner.builtJars(temp.resolve("nothing-here"))).isEmpty();
    }

    @Test
    void withoutADenylistNothingIsLocated() {
        assertThat(DenylistScanner.locateDenylist(Map.of(), temp)).isEmpty();
    }

    @Test
    void repositoryFilesComeFromTheTreeWhenGitIsNotAvailable() throws IOException {
        Files.createDirectories(temp.resolve("src"));
        Files.createDirectories(temp.resolve("target"));
        Files.createDirectories(temp.resolve(".git"));
        Files.writeString(temp.resolve("src/A.java"), "x");
        Files.writeString(temp.resolve("README.md"), "x");
        Files.writeString(temp.resolve("target/B.class"), "x");
        Files.writeString(temp.resolve(".git/config"), "x");
        Files.writeString(temp.resolve(".denylist"), "x");
        Files.writeString(temp.resolve(".neutralize-map"), "x");
        Files.writeString(temp.resolve(".synthetic-map"), "x");

        assertThat(DenylistScanner.walkTree(temp))
            .containsExactly("README.md", "src/A.java");
    }
}
