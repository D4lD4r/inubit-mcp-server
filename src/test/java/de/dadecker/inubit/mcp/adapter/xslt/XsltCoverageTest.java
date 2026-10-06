package de.dadecker.inubit.mcp.adapter.xslt;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.XsltRun;
import de.dadecker.inubit.mcp.domain.port.XsltPort.XsltRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T031 (SC-005 on the committed fixtures): every stylesheet of the fixtures — the documents of
 * {@code xslt/} and the stylesheets of the XSLT Converter modules of the exports — has the
 * outcome listed in the {@code xslt-coverage} block of {@code fixtures/artifacts/README.md};
 * none is falsely {@code OK}.
 */
class XsltCoverageTest {

    private static final String BLOCK = "```xslt-coverage\n";
    private static final String FIXTURE_MODULES = "dev/OWNERS/modules/XSLT Converter/";
    /** Where each export goes: group and owner. */
    private static final Map<String, String[]> EXPORTS = Map.of(
        "grp-a.zip", new String[] {"dev", "jdoe"}, "grp-b.zip", new String[] {"dev", "OWNERS"},
        "module-one.zip", new String[] {"int", "OWNERS"},
        "module-smime.zip", new String[] {"int", "OWNERS"});

    @TempDir
    Path root;

    /** The listed outcome per stylesheet id ({@code xslt/<file>} or {@code <zip> <module>}). */
    static Map<String, String> listed() {
        String readme = new String(ArtifactFixtures.bytes("README.md"), StandardCharsets.UTF_8);
        int start = readme.indexOf(BLOCK);
        assertThat(start).as("README.md has an xslt-coverage block").isNotNegative();
        int end = readme.indexOf("```", start + BLOCK.length());
        Map<String, String> listed = new LinkedHashMap<>();
        for (String line : readme.substring(start + BLOCK.length(), end).split("\n")) {
            if (!line.isBlank()) {
                String[] fields = line.split("\t");
                assertThat(fields).as(line).hasSize(2);
                listed.put(fields[0], fields[1]);
            }
        }
        return listed;
    }

    /** The outcome label of a run: OK, NOT_TESTABLE, XSLT_STATIC_ERROR or XSLT_RUNTIME_ERROR. */
    static String label(XsltRun run) {
        return switch (run.outcome()) {
            case OK -> "OK";
            case NOT_TESTABLE -> "NOT_TESTABLE";
            case ERROR -> run.findings().stream().anyMatch(f -> f.code().equals(
                "XSLT_STATIC_ERROR")) ? "XSLT_STATIC_ERROR" : "XSLT_RUNTIME_ERROR";
        };
    }

    private void put(String path, byte[] content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    /** Every fixture stylesheet in a workspace, by id. */
    private Map<String, Path> workspace() throws IOException {
        Map<String, Path> stylesheets = new TreeMap<>();
        for (String name : List.of("plain", "standins", "unknown-extension", "syntax-error",
            "repository-import", "common")) {
            String path = FIXTURE_MODULES + "Fixture-" + name + "/xslt.stylesheet.xsl";
            put(path, ArtifactFixtures.bytes("xslt/" + name + ".xsl"));
            stylesheets.put("xslt/" + name + ".xsl", Path.of(path));
        }
        put("dev/OWNERS/repository/Root/OWNERS/xsl/common.xsl",
            ArtifactFixtures.bytes("xslt/common.xsl"));
        put("inputs/input.xml", ArtifactFixtures.bytes("xslt/input.xml"));
        for (Map.Entry<String, String[]> export : new TreeMap<>(EXPORTS).entrySet()) {
            Path exportRoot = root.resolve(".exports").resolve(export.getKey());
            new ArchiveCodec().prepare(new GroupId(export.getValue()[0]), export.getValue()[1],
                List.of(ArtifactFixtures.bytes(export.getKey()))).writeTo(exportRoot);
            try (Stream<Path> files = Files.walk(exportRoot)) {
                for (Path file : files.filter(f -> f.getFileName().toString()
                    .equals("xslt.stylesheet.xsl")).toList()) {
                    Path relative = exportRoot.relativize(file);
                    put(relative.toString(), Files.readAllBytes(file));
                    stylesheets.put(export.getKey() + " " + relative.getParent().getFileName(),
                        relative);
                }
            }
        }
        return stylesheets;
    }

    @Test
    void everyFixtureStylesheetHasItsListedOutcome() throws IOException {
        Map<String, String> listed = listed();
        Map<String, Path> stylesheets = workspace();
        SaxonXsltRunner runner = new SaxonXsltRunner(root);

        assertThat(new TreeSet<>(listed.keySet())).as("the README lists every stylesheet")
            .isEqualTo(new TreeSet<>(stylesheets.keySet()));
        Map<String, String> outcomes = new TreeMap<>();
        stylesheets.forEach((id, path) -> outcomes.put(id, label(runner.run(new XsltRequest(
            path, Path.of("inputs/input.xml"), Map.of(), Optional.empty())))));

        assertThat(outcomes).isEqualTo(new TreeMap<>(listed));
        assertThat(outcomes).as("tested on Saxon-HE although the module names Saxon-EE")
            .containsEntry("grp-a.zip Module-0001", "OK");
    }
}
