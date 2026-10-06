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
