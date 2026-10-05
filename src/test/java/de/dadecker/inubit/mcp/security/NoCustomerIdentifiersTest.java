package de.dadecker.inubit.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.dadecker.inubit.mcp.security.IdentifierLists.Kind;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T037 / SC-002 (research D-11): the repository guard. It scans the repository for the patterns
 * of three local identifier lists that name customer values and are therefore never committed:
 * the denylist (Java regular expressions) and the left-hand sides of the neutralize and the
 * synthetic map (Python regular expressions, see {@link PythonRegex}). Each list is read from its
 * environment variable, else from its git-ignored file in the repository root, else from the
 * default directory {@code ~/.config/inubit-mcp/} ({@code %APPDATA%\inubit-mcp\} on Windows), so
 * a plain local {@code mvn verify} runs the check. Without any list (CI) it is skipped
 * (docs/release-checks.md).
 *
 * <p>The scanner itself is tested with fictitious lists and temporary files. Findings name the
 * file, the line, the list and the rule number and show the match masked, never in full.
 */
class NoCustomerIdentifiersTest {

    @TempDir
    Path temp;

    private static IdentifierScanner denylist(String text) {
        return IdentifierScanner.of(IdentifierLists.parse(Kind.DENYLIST, text));
    }

    // --- the release check -------------------------------------------------------------------

    @Test
    void repositoryContainsNoCustomerIdentifiers() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        Map<String, String> environment = System.getenv();
        Path defaults = IdentifierLists.defaultDirectory(environment,
            System.getProperty("os.name"), System.getProperty("user.home"));
        IdentifierLists.Located lists = IdentifierLists.locateAll(environment, root, defaults);
        assumeTrue(lists.anyList(), lists.skipMessage());

        long start = System.nanoTime();
        IdentifierScanner scanner = lists.load();
        List<String> files = new ArrayList<>(IdentifierScanner.repositoryFiles(root));
        assertThat(files).as("files to scan").isNotEmpty();
        files.addAll(IdentifierScanner.builtJars(root));

        List<IdentifierScanner.Finding> findings = scanner.scan(root, files);
        System.out.printf("%s; %d files scanned in %d ms%n", lists.summary(scanner),
            files.size(), (System.nanoTime() - start) / 1_000_000);

        assertThat(findings).as("customer identifiers (file:line: list rule n: masked match);"
            + " <name of scanned file #n> is line n of 'git ls-files --cached --others"
            + " --exclude-standard | LC_ALL=C sort -u' followed by target/*.jar; <name of entry"
            + " #k> is line k of 'unzip -Z1 <archive>' (see docs/release-checks.md)").isEmpty();
    }

    // --- the scanner -------------------------------------------------------------------------

    @Test
    void reportsFileLineListAndRuleWithAMaskedMatch() throws IOException {
        Files.writeString(temp.resolve("a.txt"), "clean\nsome Foocorp value\nclean\n");
        Files.createDirectories(temp.resolve("sub"));
        Files.writeString(temp.resolve("sub/b.md"), "host bar-17.internal\n");
        IdentifierScanner scanner = denylist("""
            # comment: fictitious customer
            (?i)foocorp

            bar-\\d+\\.internal
            """);

        List<IdentifierScanner.Finding> findings =
            scanner.scan(temp, List.of("a.txt", "sub/b.md"));

        assertThat(findings).extracting(IdentifierScanner.Finding::toString).containsExactly(
            "a.txt:2: denylist rule 1: Fo…(7)", "sub/b.md:1: denylist rule 2: ba…(15)");
        assertThat(findings.toString()).doesNotContainIgnoringCase("foocorp")
            .doesNotContain("bar-17");
    }

    @Test
    void masksShowAtMostTwoCharactersAndTheLength() {
        assertThat(IdentifierScanner.mask("Globex-Billing")).isEqualTo("Gl…(14)");
        assertThat(IdentifierScanner.mask("acme12")).isEqualTo("ac…(6)");
        assertThat(IdentifierScanner.mask("acme")).isEqualTo("a…(4)");
        assertThat(IdentifierScanner.mask("abc")).isEqualTo("a…(3)");
        assertThat(IdentifierScanner.mask("ab")).isEqualTo("…(2)");
        assertThat(IdentifierScanner.mask("😀😀😀xyz"))
            .as("code points, not chars").isEqualTo("😀😀…(6)");
    }

    @Test
    void combinesTheDenylistAndBothMapsAndLabelsEachFinding() throws IOException {
        Files.writeString(temp.resolve("a.xml"), """
            <m name="Globex Billing-Import" group="Acme"/>
            <host>foo.corp.invalid</host> FooCorp
            <g>Acme</g> the Acme call
            """);
        IdentifierScanner scanner = IdentifierScanner.of(
            IdentifierLists.parse(Kind.DENYLIST, "(?i)foocorp\n"),
            IdentifierLists.parse(Kind.NEUTRALIZE_MAP,
                "foo\\.corp\\.invalid\tinubit-dev-1.example.test\n(?i)foocorp\tacme\n"),
            IdentifierLists.parse(Kind.SYNTHETIC_MAP, """
                # Generated by tools/synthesize_names.py (fictitious)
                (?<![\\w-])Globex\\ Billing\\-Import(?![\\w-])\tWorkflow-0001
                (?<=[\\">])Acme(?=[\\"<])\tGRP-01
                """));

        assertThat(scanner.ruleCount()).isEqualTo(5);
        assertThat(scanner.scan(temp, List.of("a.xml"))).extracting(Object::toString)
            .containsExactly(
                "a.xml:1: synthetic-map rule 1: Gl…(21)",
                "a.xml:1: synthetic-map rule 2: A…(4)",
                "a.xml:2: denylist rule 1: Fo…(7)",
                "a.xml:2: neutralize-map rule 1: fo…(16)",
                "a.xml:2: neutralize-map rule 2: Fo…(7)",
                "a.xml:3: synthetic-map rule 2: A…(4)");
    }

    @Test
    void reportsEveryMatchingRuleOfALineOnce() throws IOException {
        Files.writeString(temp.resolve("a.txt"), "foo foo bar\n");
        IdentifierScanner scanner = denylist("foo\nbar\nbaz\n");

        assertThat(scanner.scan(temp, List.of("a.txt"))).extracting(Object::toString)
            .containsExactly(
                "a.txt:1: denylist rule 1: f…(3)", "a.txt:1: denylist rule 2: b…(3)");
    }

    @Test
    void rulesWithoutALiteralAreAppliedToo() throws IOException {
        Files.writeString(temp.resolve("a.txt"), "x\nhost-42 or acme\n");
        IdentifierScanner scanner = denylist("[a-z]+-\\d+\nacme|globex\n");

        assertThat(scanner.scan(temp, List.of("a.txt"))).extracting(Object::toString)
            .containsExactly(
                "a.txt:2: denylist rule 1: ho…(7)", "a.txt:2: denylist rule 2: a…(4)");
    }

    @Test
    void patternsAreCaseSensitiveUnlessMarked() throws IOException {
        Files.writeString(temp.resolve("a.txt"), "FOO\nBar\n");
        IdentifierScanner scanner = denylist("foo\n(?i)bar\n");

        assertThat(scanner.scan(temp, List.of("a.txt"))).extracting(Object::toString)
            .containsExactly("a.txt:2: denylist rule 2: B…(3)");
    }

    @Test
    void anAllowlistedValueIsNotReportedButLongerMatchesAre() throws IOException {
        Files.writeString(temp.resolve("a.txt"), "<g>Initech</g>\n<g>InitechX</g>\n");
        IdentifierScanner scanner = IdentifierScanner.of(IdentifierLists.parse(
                Kind.SYNTHETIC_MAP, "(?<=[\\\">])Initech\\w*(?=[\\\"<])\tGRP-01\n"))
            .allowing(Set.of("Initech"));

        assertThat(scanner.scan(temp, List.of("a.txt"))).extracting(Object::toString)
            .containsExactly("a.txt:2: synthetic-map rule 1: In…(8)");
    }

    @Test
    void anAllowlistedValueDoesNotHideAnotherMatchOfTheSameRuleOnTheLine() throws IOException {
        Files.writeString(temp.resolve("a.txt"), "<g>Initech</g> <g>Acme</g>\n");
        IdentifierScanner scanner = IdentifierScanner.of(IdentifierLists.parse(
                Kind.SYNTHETIC_MAP, "(?<=>)(?:Initech|Acme)(?=<)\tGRP-01\n"))
            .allowing(Set.of("Initech"));

        assertThat(scanner.scan(temp, List.of("a.txt"))).extracting(Object::toString)
            .containsExactly("a.txt:1: synthetic-map rule 1: A…(4)");
    }

    @Test
    void anAllowlistedMatchDoesNotHideAnOverlappingMatchOfTheSameRule() throws IOException {
        Files.writeString(temp.resolve("a.txt"), "Initech-Xy\n");
        IdentifierScanner scanner = IdentifierScanner.of(IdentifierLists.parse(
                Kind.SYNTHETIC_MAP, "Initech|nitech\\-Xy\tGRP-01\n"))
            .allowing(Set.of("Initech"));

        assertThat(scanner.scan(temp, List.of("a.txt"))).extracting(Object::toString)
            .containsExactly("a.txt:1: synthetic-map rule 1: ni…(9)");
    }

    @Test
    void anArchiveThatIsNoZipIsScannedAsText() throws IOException {
        Files.writeString(temp.resolve("broken.zip"), "not a zip\nFooCorp\n");
        Files.writeString(temp.resolve("broken.jar"), "FooCorp\n");

        assertThat(denylist("(?i)foocorp\n").scan(temp, List.of("broken.jar", "broken.zip")))
            .extracting(Object::toString).containsExactly(
                "broken.jar:1: denylist rule 1: Fo…(7)",
                "broken.zip:2: denylist rule 1: Fo…(7)");
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
        IdentifierScanner scanner = denylist("(?i)foocorp\n");

        assertThat(scanner.scan(temp, List.of("fixtures/sample.zip"))).extracting(Object::toString)
            .containsExactly("fixtures/sample.zip!dir/model.xml:2: denylist rule 1: FO…(7)");
    }

    @Test
    void skipsBinaryFiles() throws IOException {
        Files.write(temp.resolve("blob.p12"), new byte[] {1, 0, 'f', 'o', 'o', 0, 2});
        Files.writeString(temp.resolve("text.txt"), "foo\n");
        IdentifierScanner scanner = denylist("foo\n");

        assertThat(scanner.scan(temp, List.of("blob.p12", "text.txt")))
            .extracting(Object::toString).containsExactly("text.txt:1: denylist rule 1: f…(3)");
    }

    @Test
    void reportsMatchingFileNamesByIndexOnly() throws IOException {
        Files.writeString(temp.resolve("clean.txt"), "x\n");
        Files.writeString(temp.resolve("data_foocorp.xml"), "x\n");
        IdentifierScanner scanner = denylist("(?i)foocorp\n");

        List<IdentifierScanner.Finding> findings =
            scanner.scan(temp, List.of("clean.txt", "data_foocorp.xml"));

        assertThat(findings).extracting(Object::toString)
            .containsExactly("<name of scanned file #2>: denylist rule 1: fo…(7)");
    }

    @Test
    void contentFindingsOfAMatchingFileNameAreReportedUnderTheRedactedName() throws IOException {
        Files.writeString(temp.resolve("data_foocorp.xml"), "x\n<a>FooCorp</a>\n");
        IdentifierScanner scanner = denylist("(?i)foocorp\n");

        List<IdentifierScanner.Finding> findings =
            scanner.scan(temp, List.of("data_foocorp.xml"));

        assertThat(findings).extracting(Object::toString).containsExactly(
            "<name of scanned file #1>: denylist rule 1: fo…(7)",
            "<name of scanned file #1>:2: denylist rule 1: Fo…(7)");
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
        IdentifierScanner scanner = denylist("(?i)foocorp\n");

        List<IdentifierScanner.Finding> findings = scanner.scan(temp, List.of("sample.zip"));

        assertThat(findings).extracting(Object::toString).containsExactly(
            "sample.zip!<name of entry #2>: denylist rule 1: fo…(7)",
            "sample.zip!<name of entry #2>:1: denylist rule 1: FO…(7)");
        assertThat(findings.toString()).doesNotContainIgnoringCase("foocorp");
    }

    @Test
    void ignoresListedFilesThatNoLongerExist() throws IOException {
        assertThat(denylist("foo\n").scan(temp, List.of("deleted.txt"))).isEmpty();
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
        IdentifierScanner scanner = denylist("(?i)foocorp\n");

        List<String> jars = IdentifierScanner.builtJars(temp);

        assertThat(jars).containsExactly("target/app-1.0.jar");
        assertThat(scanner.scan(temp, jars)).extracting(Object::toString).containsExactly(
            "target/app-1.0.jar!<name of entry #1>: denylist rule 1: fo…(7)",
            "target/app-1.0.jar!META-INF/app.properties:1: denylist rule 1: Fo…(7)");
        assertThat(IdentifierScanner.builtJars(temp.resolve("nothing-here"))).isEmpty();
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
        Files.writeString(temp.resolve(".identifier-allowlist"), "x");

        assertThat(IdentifierScanner.walkTree(temp))
            .containsExactly("README.md", "src/A.java");
    }

    @Test
    void manyRulesStayFast() throws IOException {
        StringBuilder map = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            map.append("(?<![\\w-])Globex\\-Name\\-").append(i).append("(?![\\w-])\tWorkflow-")
                .append(i).append('\n');
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 50_000; i++) {
            text.append("<m name=\"Workflow-").append(i).append("\" group=\"GRP-01\"/>\n");
        }
        text.append("<m name=\"Globex-Name-1999\"/>\n");
        Files.writeString(temp.resolve("big.xml"), text);
        IdentifierScanner scanner =
            IdentifierScanner.of(IdentifierLists.parse(Kind.SYNTHETIC_MAP, map.toString()));

        long start = System.nanoTime();
        List<IdentifierScanner.Finding> findings = scanner.scan(temp, List.of("big.xml"));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(findings).extracting(Object::toString)
            .containsExactly("big.xml:50001: synthetic-map rule 2000: Gl…(16)");
        assertThat(millis).as("2,000 rules over 50,000 lines (ms)").isLessThan(3_000);
    }
}
