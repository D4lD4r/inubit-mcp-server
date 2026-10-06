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
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T031, opt-in (SC-005, research D-14): runs every {@code *.xsl} below the directory in
 * {@code INUBIT_MCP_XSLT_CORPUS} (real stylesheets, never part of the repository) — except its
 * {@code repository/} sub-directory (files, or the {@code Repository.zip} layout with
 * {@code <path>.dat}), which serves the {@code inubitrepository:} references — on
 * a minimal input ({@code <root/>}, no {@code xslt.params}). At least 95 % must run locally:
 * {@code OK}, or compiled and stopped by a dynamic error of the minimal input
 * ({@code XSLT_RUNTIME_ERROR}, e.g. a required template parameter or a type error — the corpus
 * has neither the real messages nor the module parameters); every other one is
 * {@code NOT_TESTABLE} or {@code XSLT_STATIC_ERROR}. The share of {@code OK} runs is printed.
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
        for (int i = 0; i < stylesheets.size(); i++) {
            Path module = Path.of("dev/corpus/modules/XSLT Converter/S" + i
                + "/xslt.stylesheet.xsl");
            Files.createDirectories(root.resolve(module).getParent());
            Files.copy(stylesheets.get(i), root.resolve(module));
            XsltRun run = runner.run(new XsltRequest(module, Path.of("inputs/input.xml"),
                Map.of(), Optional.empty()));
            outcomes.merge(XsltCoverageTest.label(run), 1, Integer::sum);
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
        System.out.println("XsltCorpusTest: " + stylesheets.size() + " stylesheets, outcomes "
            + outcomes + ", OK " + (stylesheets.isEmpty() ? 0 : 100.0 * ok / stylesheets.size())
            + " %, stand-ins used (stylesheets) " + standIns + ", other outcomes by code "
            + codes);

        assertThat(stylesheets).as("the corpus has stylesheets").isNotEmpty();
        int ran = ok + outcomes.getOrDefault("XSLT_RUNTIME_ERROR", 0);
        assertThat(100.0 * ran / stylesheets.size())
            .as("SC-005: share of stylesheets that compile and run locally")
            .isGreaterThanOrEqualTo(95.0);
        assertThat(outcomes.keySet()).as("no other outcome").isSubsetOf("OK", "NOT_TESTABLE",
            "XSLT_STATIC_ERROR", "XSLT_RUNTIME_ERROR");
    }
}
