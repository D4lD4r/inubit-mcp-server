package de.dadecker.inubit.mcp.adapter.xslt;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.NameCodec;
import de.dadecker.inubit.mcp.domain.model.XsltRun;
import de.dadecker.inubit.mcp.domain.port.XsltPort.XsltRequest;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T031, opt-in (SC-005, research D-14): runs every {@code *.xsl} below the directory in
 * {@code INUBIT_MCP_XSLT_CORPUS} (real stylesheets, never part of the repository) — except its
 * {@code repository/} sub-directory (files, or the {@code Repository.zip} layout with
 * {@code <path>.dat}), which serves the {@code inubitrepository:} references — on
 * a minimal input ({@code <root/>}; required top-level parameters get an empty string, as the
 * corpus has neither the real messages nor the module's {@code xslt.params}). SC-005: at least
 * 95 % must compile and execute locally with every extension call served by a stand-in —
 * {@code OK}, or {@code XSLT_RUNTIME_ERROR} on the given input (never passed); every other one is
 * {@code NOT_TESTABLE} or {@code XSLT_STATIC_ERROR}. The shares of {@code OK} and of executable
 * runs are printed separately; a run stopped by the 60 s deadline counts as {@code TIMEOUT}, not as
 * executable. No {@code OK} run may use a stand-in with assumed behaviour
 * without the warning {@code XSLT_STANDIN_ASSUMED}.
 * Prints counts, error codes and the stand-ins used only — never names or content of the
 * stylesheets. Skipped when the variable is not set.
 */
class XsltCorpusTest {

    static final String VARIABLE = "INUBIT_MCP_XSLT_CORPUS";
    private static final String REPOSITORY = "repository";
    /** An XPath/XSLT error code such as {@code FORG0001} (codes only, never messages). */
    private static final Pattern ERROR_CODE =
        Pattern.compile("\\b[A-Z]{4}[0-9]{4}\\b");

    @TempDir
    Path root;

    /**
     * An empty string for every top-level {@code xsl:param required="yes"}: INUBIT passes the
     * module's {@code xslt.params}, which the corpus does not have (review M5).
     */
    private static Map<String, String> requiredParameters(Path stylesheet) {
        Map<String, String> parameters = new TreeMap<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Element top = factory.newDocumentBuilder().parse(stylesheet.toFile())
                .getDocumentElement();
            for (Node child = top.getFirstChild(); child != null;
                child = child.getNextSibling()) {
                if (child instanceof Element element
                    && "http://www.w3.org/1999/XSL/Transform".equals(element.getNamespaceURI())
                    && "param".equals(element.getLocalName())
                    && "yes".equals(element.getAttribute("required").strip())
                    && !element.getAttribute("name").contains(":")) {
                    parameters.put(element.getAttribute("name"), "");
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            // not parseable here: the run reports it
        }
        return parameters;
    }

    /** The INUBIT or JDK function a not-testable run misses; other names are never printed. */
    private static String inubitFunction(String message) {
        Matcher function = Pattern.compile(
            "(java:(?:com\\.inubit|java\\.)[\\w.]*) ([\\w-]+)").matcher(message);
        return function.find() ? " " + function.group(1) + "#" + function.group(2) : "";
    }

    @Test
    void atLeast95PercentOfTheCorpusRuns() throws IOException {
        String corpus = System.getenv(VARIABLE);
        Assumptions.assumeTrue(corpus != null && !corpus.isBlank(),
            VARIABLE + " is not set; the corpus test is skipped");
        Path source = Path.of(corpus.strip());
        // a temporary workspace: the repository of the owner "corpus" and one module each
        Path repository = source.resolve(REPOSITORY);
        try (Stream<Path> files = Files.walk(repository, FileVisitOption.FOLLOW_LINKS)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                // a Repository.zip layout: <path>.dat is the file, <path>.xml its metadata
                String name = file.getFileName().toString();
                if (name.endsWith(".xml") && Files.exists(file.resolveSibling(
                    name.substring(0, name.length() - 4) + ".dat"))) {
                    continue;
                }
                // stored like the workspace writer stores repository files (NameCodec)
                Path target = root.resolve("dev/corpus/repository");
                for (Path segment : repository.relativize(file)) {
                    String part = segment.toString();
                    if (segment.equals(repository.relativize(file).getFileName())
                        && part.endsWith(".dat")) {
                        part = part.substring(0, part.length() - 4);
                    }
                    target = target.resolve(NameCodec.encode(part));
                }
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        List<Path> stylesheets;
        try (Stream<Path> files = Files.walk(source, FileVisitOption.FOLLOW_LINKS)) {
            stylesheets = files.filter(f -> f.toString().endsWith(".xsl")
                && Files.isRegularFile(f) && !f.startsWith(repository)).sorted().toList();
        }
        Files.createDirectories(root.resolve("inputs"));
        Files.writeString(root.resolve("inputs/input.xml"), "<root/>");
        SaxonXsltRunner runner = new SaxonXsltRunner(root);
        Map<String, Integer> outcomes = new TreeMap<>();
        Map<String, Integer> standIns = new TreeMap<>();
        Map<String, Integer> codes = new TreeMap<>();
        int silentAssumptions = 0;
        for (int i = 0; i < stylesheets.size(); i++) {
            Path module = Path.of("dev/corpus/modules/XSLT Converter/S" + i
                + "/xslt.stylesheet.xsl");
            Files.createDirectories(root.resolve(module).getParent());
            Files.copy(stylesheets.get(i), root.resolve(module));
            XsltRun run = runner.run(new XsltRequest(module, Path.of("inputs/input.xml"),
                requiredParameters(root.resolve(module)), Optional.empty()));
            if (run.outcome() == XsltRun.Outcome.OK
                && run.standInsUsed().stream().anyMatch(InubitStandIns.ASSUMED::contains)
                && run.findings().stream().noneMatch(f -> f.code().equals(
                    "XSLT_STANDIN_ASSUMED"))) {
                silentAssumptions++;
            }
            // a run stopped by the deadline did not execute: counted on its own (stage-3 minor 3)
            boolean timedOut = run.findings().stream().anyMatch(f -> f.message().contains(
                "did not finish within"));
            outcomes.merge(timedOut ? "TIMEOUT" : XsltCoverageTest.label(run), 1, Integer::sum);
            if (run.outcome() != XsltRun.Outcome.OK) {
                run.findings().forEach(finding -> {
                    Matcher code = ERROR_CODE.matcher(finding.message());
                    codes.merge(finding.code() + (code.find() ? " " + code.group() : "")
                        + inubitFunction(finding.message()), 1, Integer::sum);
                });
            }
            run.standInsUsed().forEach(name -> standIns.merge(name, 1, Integer::sum));
        }
        int ok = outcomes.getOrDefault("OK", 0);
        int executable = ok + outcomes.getOrDefault("XSLT_RUNTIME_ERROR", 0);
        int total = Math.max(stylesheets.size(), 1);
        System.out.printf(Locale.ROOT, "XsltCorpusTest: %d stylesheets, outcomes %s, OK %.1f %%,"
            + " executable (OK + XSLT_RUNTIME_ERROR) %.1f %%, stand-ins used (stylesheets) %s,"
            + " other outcomes by code %s%n", stylesheets.size(), outcomes, 100.0 * ok / total,
            100.0 * executable / total, standIns, codes);

        assertThat(stylesheets).as("the corpus has stylesheets").isNotEmpty();
        assertThat(100.0 * executable / stylesheets.size())
            .as("SC-005: share of stylesheets that compile and execute locally")
            .isGreaterThanOrEqualTo(95.0);
        assertThat(silentAssumptions).as("OK runs on an assumed stand-in without the warning")
            .isZero();
        assertThat(outcomes.keySet()).as("no other outcome").isSubsetOf("OK", "NOT_TESTABLE",
            "XSLT_STATIC_ERROR", "XSLT_RUNTIME_ERROR", "TIMEOUT");
    }
}
