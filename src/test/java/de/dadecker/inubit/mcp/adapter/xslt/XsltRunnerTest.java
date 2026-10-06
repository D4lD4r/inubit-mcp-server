package de.dadecker.inubit.mcp.adapter.xslt;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.domain.model.CheckFinding;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Severity;
import de.dadecker.inubit.mcp.domain.model.XsltRun;
import de.dadecker.inubit.mcp.domain.model.XsltRun.Outcome;
import de.dadecker.inubit.mcp.domain.port.XsltPort.XsltRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T030 (research D-11, FR-029 – FR-033): stylesheet runs on Saxon-HE with the stand-ins, output
 * only below {@code .tests/}, no access outside the workspace.
 */
class XsltRunnerTest {

    private static final String MODULES = "dev/OWNERS/modules/XSLT Converter/";
    private static final String INPUT = "inputs/input.xml";

    @TempDir
    Path root;
    @TempDir
    Path outside;

    private SaxonXsltRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        for (String name : new String[] {"plain", "standins", "unknown-extension",
            "syntax-error", "repository-import"}) {
            put(MODULES + "Module-" + name + "/xslt.stylesheet.xsl", fixture(name + ".xsl"));
        }
        put("dev/OWNERS/repository/Root/OWNERS/xsl/common.xsl", fixture("common.xsl"));
        put(INPUT, fixture("input.xml"));
        runner = new SaxonXsltRunner(root);
    }

    private static String fixture(String name) {
        return new String(ArtifactFixtures.bytes("xslt/" + name), StandardCharsets.UTF_8);
    }

    private void put(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private XsltRun run(String module, Optional<Instant> now) {
        return runner.run(new XsltRequest(Path.of(MODULES + "Module-" + module
            + "/xslt.stylesheet.xsl"), Path.of(INPUT), Map.of(), now));
    }

    private String output(XsltRun run) throws IOException {
        return Files.readString(root.resolve(run.output().orElseThrow()));
    }

    @Test
    void aPlainStylesheetRunsAndWritesBelowTests() throws IOException {
        XsltRun run = run("plain", Optional.empty());

        assertThat(run.outcome()).isEqualTo(Outcome.OK);
        assertThat(run.output()).contains(".tests/dev/OWNERS/Module-plain/input.xml.out");
        assertThat(output(run)).contains("<invoice order=\"A-1001\">",
            "<customer>Acme Corporation</customer>", "<total currency=\"EUR\">25.25</total>");
        assertThat(run.standInsUsed()).isEmpty();
        assertThat(run.findings()).isEmpty();
    }

    @Test
    void theStandInsAreDeterministicAndReported() throws IOException {
        XsltRun first = run("standins", Optional.empty());
        String once = output(first);
        XsltRun second = run("standins", Optional.empty());

        assertThat(first.outcome()).isEqualTo(Outcome.OK);
        assertThat(output(second)).isEqualTo(once);
        assertThat(once).contains("<id>00000000-0000-0000-0000-000000000000</id>",
            "<orderDate>06.10.2026</orderDate>", "<createdAt>2000-01-01 00:00:00</createdAt>");
        assertThat(first.standInsUsed()).containsExactly("Formatter.changeDateFormat",
            "Formatter.getDateTime", "Misc.guid", "Thread.sleep", "UUID.randomUUID");
        assertThat(first.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("XSLT_STANDINS_USED");
            assertThat(finding.severity()).isEqualTo(Severity.INFO);
            assertThat(finding.message()).contains("Misc.guid");
        });
        assertThat(output(run("standins", Optional.of(Instant.parse(
            "2026-10-06T12:34:56Z"))))).contains("<createdAt>2026-10-06 12:34:56</createdAt>");
    }

    @Test
    void assumedStandInsAndFallbacksAreAWarning() throws IOException {
        // review I2: OK, but not silently resting on an invented result
        put(MODULES + "Module-assumed/xslt.stylesheet.xsl", """
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:Misc="java:com.inubit.ibis.xsltext.Misc"
                xmlns:Formatter="java:com.inubit.ibis.xsltext.Formatter">
              <xsl:output method="text"/>
              <xsl:template match="/">
                <xsl:value-of select="Misc:encode('abc'),
                    Formatter:changeDateFormat('soon', 'yyyy-MM-dd|dd.MM.yyyy')"/>
              </xsl:template>
            </xsl:stylesheet>
            """);

        XsltRun run = run("assumed", Optional.empty());

        assertThat(run.outcome()).isEqualTo(Outcome.OK);
        assertThat(run.findings()).extracting(CheckFinding::code)
            .containsExactly("XSLT_STANDINS_USED", "XSLT_STANDIN_ASSUMED");
        assertThat(run.findings().get(1)).satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.message()).contains("Misc.encode",
                "Formatter.changeDateFormat: the date could not be read and was kept");
        });
    }

    @Test
    void aFunctionWithoutDocumentedBehaviourIsNotTestable() throws IOException {
        put(MODULES + "Module-difference/xslt.stylesheet.xsl", """
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:Formatter="java:com.inubit.ibis.xsltext.Formatter">
              <xsl:template match="/">
                <d><xsl:value-of select="Formatter:calculateDateDifference('a', 'b', 'c', 'd',
                    'e', 'f', 'g', 'h', 'i', 'j', 'k')"/></d>
              </xsl:template>
            </xsl:stylesheet>
            """);

        XsltRun run = run("difference", Optional.empty());

        assertThat(run.outcome()).isEqualTo(Outcome.NOT_TESTABLE);
        assertThat(run.findings().get(0).message()).contains("calculateDateDifference");
    }

    @Test
    void randomNumbersAreDeterministicOrNotTestable() throws IOException {
        // review M4 (clarification 4): a seed gives the same numbers, no seed cannot be tested
        put(MODULES + "Module-seeded/xslt.stylesheet.xsl", """
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
              <xsl:output method="text"/>
              <xsl:template match="/">
                <xsl:value-of select="random-number-generator('seed')?number,
                    random-number-generator(42)?permute(1 to 10)" separator="|"/>
              </xsl:template>
            </xsl:stylesheet>
            """);
        put(MODULES + "Module-unseeded/xslt.stylesheet.xsl", """
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
              <xsl:import href="../Module-seeded/xslt.stylesheet.xsl"/>
              <xsl:output method="text"/>
              <xsl:template match="/">
                <xsl:value-of select="random-number-generator ( )?number"/>
              </xsl:template>
            </xsl:stylesheet>
            """);

        XsltRun seeded = run("seeded", Optional.empty());
        XsltRun unseeded = run("unseeded", Optional.empty());

        assertThat(seeded.outcome()).isEqualTo(Outcome.OK);
        assertThat(output(run("seeded", Optional.empty()))).isEqualTo(output(seeded));
        assertThat(unseeded.outcome()).isEqualTo(Outcome.NOT_TESTABLE);
        assertThat(unseeded.findings().get(0).message())
            .contains("random-number-generator() without a seed");
    }

    @Test
    void anUnknownExtensionIsNotTestableNeverOk() {
        XsltRun run = run("unknown-extension", Optional.empty());

        assertThat(run.outcome()).isEqualTo(Outcome.NOT_TESTABLE);
        assertThat(run.output()).isEmpty();
        assertThat(run.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("XSLT_NOT_TESTABLE");
            assertThat(finding.message()).contains("java:com.example.fixture.UnknownTool",
                "validate");
        });
    }

    @Test
    void aStaticErrorNamesLineAndColumn() {
        XsltRun run = run("syntax-error", Optional.empty());

        assertThat(run.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(run.findings()).isNotEmpty().allSatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("XSLT_STATIC_ERROR");
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.check()).isEqualTo(CheckFinding.Check.XSLT);
        });
        assertThat(run.findings().get(0).location()).hasValueSatisfying(location ->
            assertThat(location).startsWith("7:"));
    }

    @Test
    void repositoryImportsResolveFromTheWorkspace() throws IOException {
        XsltRun run = run("repository-import", Optional.empty());

        assertThat(run.outcome()).isEqualTo(Outcome.OK);
        assertThat(output(run)).contains("<invoice order=\"ORDER-A-1001\"/>");
    }

    @Test
    void repositoryNamesAreDecodedAndEncodedLikeTheWorkspace() throws IOException {
        // the writer stores repository paths with NameCodec; references may be %-escaped
        put("dev/OWNERS/repository/Root/OWNERS/xsl/" + de.dadecker.inubit.mcp.domain.model
            .NameCodec.encode("shared lib.xsl"), fixture("common.xsl"));
        put(MODULES + "Module-spaces/xslt.stylesheet.xsl", fixture("repository-import.xsl")
            .replace("xsl/common.xsl", "xsl/shared%20lib.xsl"));

        XsltRun run = run("spaces", Optional.empty());

        assertThat(run.outcome()).isEqualTo(Outcome.OK);
        assertThat(output(run)).contains("ORDER-A-1001");
    }

    @Test
    void aDocumentTypeDeclarationIsNotTestableLocally() throws IOException {
        // DTDs are never read (no external entities); that is not an error of the stylesheet
        put(MODULES + "Module-doctype/xslt.stylesheet.xsl", "<!DOCTYPE xsl:stylesheet [\n"
            + "  <!ENTITY nbsp \"&#160;\">\n]>\n" + fixture("plain.xsl")
                .replaceFirst("<\\?xml[^>]*>\\s*", ""));

        XsltRun run = run("doctype", Optional.empty());

        assertThat(run.outcome()).isEqualTo(Outcome.NOT_TESTABLE);
        assertThat(run.findings()).singleElement().satisfies(finding ->
            assertThat(finding.message()).contains("document type declaration"));
    }

    static final String ENDLESS = """
        <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
          <xsl:template match="/">
            <xsl:iterate select="1 to 500000000">
              <xsl:param name="n" select="0"/>
              <xsl:next-iteration><xsl:with-param name="n" select="$n + 1"/></xsl:next-iteration>
            </xsl:iterate>
          </xsl:template>
        </xsl:stylesheet>
        """;

    @Test
    void aRunThatDoesNotFinishInTimeIsAnError() throws IOException {
        // review I1: the check holds the workspace lock while the stylesheet runs
        assertThat(SaxonXsltRunner.DEADLINE).isEqualTo(java.time.Duration.ofSeconds(60));
        put(MODULES + "Module-endless/xslt.stylesheet.xsl", ENDLESS);
        SaxonXsltRunner limited = new SaxonXsltRunner(root, java.time.Duration.ofSeconds(1));
        long start = System.nanoTime();

        XsltRun run = limited.run(new XsltRequest(Path.of(MODULES
            + "Module-endless/xslt.stylesheet.xsl"), Path.of(INPUT), Map.of(), Optional.empty()));

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
            .isLessThan(java.time.Duration.ofSeconds(20));
        assertThat(run.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(run.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("XSLT_RUNTIME_ERROR");
            assertThat(finding.message()).contains("did not finish within 1 s");
        });
        assertThat(root.resolve(".tests/dev/OWNERS/Module-endless/input.xml.out"))
            .doesNotExist();
    }

    static final String FLOOD = """
        <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
          <xsl:output method="text"/>
          <xsl:template match="/">
            <xsl:for-each select="1 to 400000000">
              <xsl:value-of select="'fixture output line ', ., '&#10;'"/>
            </xsl:for-each>
          </xsl:template>
        </xsl:stylesheet>
        """;

    private static long files(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.count();
        }
    }

    @Test
    void anAbandonedRunWritesNothingMoreAndANewRunIsUnaffected() throws Exception {
        // stage-3 minor 1: no shared temporary file, no writing after the deadline
        put(MODULES + "Module-flood/xslt.stylesheet.xsl", FLOOD);
        XsltRequest request = new XsltRequest(Path.of(MODULES
            + "Module-flood/xslt.stylesheet.xsl"), Path.of(INPUT), Map.of(), Optional.empty());
        Path directory = root.resolve(".tests/dev/OWNERS/Module-flood");

        XsltRun abandoned = new SaxonXsltRunner(root, java.time.Duration.ofSeconds(1))
            .run(request);
        assertThat(abandoned.outcome()).isEqualTo(Outcome.ERROR);
        put(MODULES + "Module-flood/xslt.stylesheet.xsl", fixture("plain.xsl"));
        XsltRun next = runner.run(request);
        Thread.sleep(2000);

        assertThat(next.outcome()).isEqualTo(Outcome.OK);
        String written = output(next);
        assertThat(written).contains("<total currency=\"EUR\">25.25</total>")
            .as("nothing of the abandoned run").doesNotContain("fixture output line");
        Thread.sleep(1000);
        assertThat(output(next)).as("unchanged afterwards").isEqualTo(written);
        assertThat(files(directory)).as("only the new output, no temporary file").isEqualTo(1);
    }

    @Test
    void theOutputIsCapped() throws IOException {
        put(MODULES + "Module-flood/xslt.stylesheet.xsl", FLOOD.replace("400000000", "1000"));

        XsltRun run = new SaxonXsltRunner(root, java.time.Duration.ofSeconds(30), 1024)
            .run(new XsltRequest(Path.of(MODULES + "Module-flood/xslt.stylesheet.xsl"),
                Path.of(INPUT), Map.of(), Optional.empty()));

        assertThat(run.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(run.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("XSLT_RUNTIME_ERROR");
            assertThat(finding.message()).contains("the output exceeds 1024 bytes");
        });
        assertThat(files(root.resolve(".tests/dev/OWNERS/Module-flood"))).isZero();
        assertThat(SaxonXsltRunner.MAX_OUTPUT_BYTES).isEqualTo(64L << 20);
    }

    @Test
    void theServersEnvironmentAndSystemPropertiesStayHidden() throws IOException {
        // review C1: the server environment holds the INUBIT credential variables
        String path = System.getenv("PATH");
        String home = System.getProperty("user.home");
        assertThat(path).as("the probe needs a non-empty PATH").isNotBlank();
        put(MODULES + "Module-host/xslt.stylesheet.xsl", """
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
              <xsl:output method="text"/>
              <xsl:template match="/">
                <xsl:value-of select="'env=' || string(environment-variable('PATH')),
                    'count=' || count(available-environment-variables()),
                    'home=' || system-property('user.home'),
                    'java=' || system-property('java.version')" separator="|"/>
              </xsl:template>
            </xsl:stylesheet>
            """);
        put(MODULES + "Module-leak/xslt.stylesheet.xsl", """
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
              <xsl:template match="/">
                <xsl:sequence select="error((), 'leak ' || environment-variable('PATH')
                    || ' ' || system-property('user.home'))"/>
              </xsl:template>
            </xsl:stylesheet>
            """);

        XsltRun host = run("host", Optional.empty());
        XsltRun leak = run("leak", Optional.empty());

        assertThat(host.outcome()).isEqualTo(Outcome.OK);
        assertThat(output(host)).isEqualTo("env=|count=0|home=|java=");
        assertThat(leak.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(leak.findings()).isNotEmpty().allSatisfy(finding -> assertThat(
            finding.message()).doesNotContain(path).doesNotContain(home));
        assertThat(output(run("standins", Optional.empty())))
            .as("the integrated stand-ins still work").contains("00000000-0000");
    }

    @Test
    void secondaryResultDocumentsAreNotTestableAndNeverWritten() throws IOException {
        // review M1/C1: no secondary output at all, not even through a link in .tests/
        Path directory = Files.createDirectories(root.resolve(".tests/dev/OWNERS/Module-link"));
        Files.createSymbolicLink(directory.resolve("away"), outside);
        put(MODULES + "Module-link/xslt.stylesheet.xsl", """
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
              <xsl:template match="/">
                <r/>
                <xsl:result-document href="away/written.xml"><x/></xsl:result-document>
              </xsl:template>
            </xsl:stylesheet>
            """);

        XsltRun run = run("link", Optional.empty());

        assertThat(outside.resolve("written.xml")).doesNotExist();
        assertThat(run.outcome()).as("secondary results are not available locally")
            .isEqualTo(Outcome.NOT_TESTABLE);
        assertThat(run.findings().get(0).message()).contains("xsl:result-document");
    }

    @Test
    void nothingOutsideTheWorkspaceIsReadOrWritten() throws IOException {
        Files.writeString(outside.resolve("secret.xml"), "<secret>outside</secret>");
        String file = outside.resolve("secret.xml").toUri().toString();
        put(MODULES + "Module-escape/xslt.stylesheet.xsl", """
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
              <xsl:template match="/">
                <r>
                  <a><xsl:value-of select="doc-available('%1$s')"/></a>
                  <b><xsl:value-of select="unparsed-text-available('%1$s')"/></b>
                  <c><xsl:value-of select="doc-available('https://example.test/x.xml')"/></c>
                  <d><xsl:value-of select="doc-available('../../../../../inputs/input.xml')"/></d>
                </r>
                <xsl:result-document href="%2$s"><x/></xsl:result-document>
              </xsl:template>
            </xsl:stylesheet>
            """.formatted(file, outside.resolve("written.xml").toUri()));

        XsltRun run = run("escape", Optional.empty());

        assertThat(run.outcome()).isNotEqualTo(Outcome.OK);
        assertThat(outside.resolve("written.xml")).doesNotExist();
        put(MODULES + "Module-read/xslt.stylesheet.xsl", """
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
              <xsl:output method="text"/>
              <xsl:template match="/">
                <xsl:value-of select="doc-available('%s'), unparsed-text-available('%s'),
                    doc-available('https://example.test/x.xml'),
                    doc-available('../../../../../inputs/input.xml')" separator="|"/>
              </xsl:template>
            </xsl:stylesheet>
            """.formatted(file, file));
        XsltRun read = run("read", Optional.empty());
        assertThat(read.outcome()).isEqualTo(Outcome.OK);
        assertThat(output(read)).isEqualTo("false|false|false|true");
        try (Stream<Path> written = Files.walk(root)) {
            assertThat(written.filter(Files::isRegularFile).map(root::relativize)
                .map(Path::toString).filter(p -> p.endsWith(".out")))
                .allMatch(p -> p.startsWith(".tests/"));
        }
    }
}
