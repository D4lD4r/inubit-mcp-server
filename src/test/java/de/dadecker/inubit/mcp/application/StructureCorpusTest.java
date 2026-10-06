package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceInspector;
import de.dadecker.inubit.mcp.adapter.xslt.SaxonXsltRunner;
import de.dadecker.inubit.mcp.domain.model.CheckFinding;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SC-004 on real data, opt-in like {@code ArchiveCorpusTest} ({@code INUBIT_MCP_ARCHIVE_CORPUS}):
 * every readable diagram-group export of the corpus is rendered into its own temporary workspace
 * and checked with {@code verifyOnServer: false}; unchanged real exports must have no structural
 * ERROR (SC-004; an XML or XSD error of a module is a genuine defect and only counted). Archives
 * whose file name says {@code bad} or {@code miss}, or that lie below a path of
 * {@code INUBIT_MCP_ARCHIVE_CORPUS_MODIFIED} (deliberately modified variants, e.g. import
 * experiments), are counted separately. Prints counts per severity and finding code only —
 * never names, paths or messages. Skipped when the variable is not set.
 */
class StructureCorpusTest {

    static final String VARIABLE = "INUBIT_MCP_ARCHIVE_CORPUS";
    /** Files or directories of deliberately modified variants (e.g. import experiments). */
    static final String MODIFIED_VARIABLE = "INUBIT_MCP_ARCHIVE_CORPUS_MODIFIED";

    @TempDir
    Path temp;

    /** The owner of the first workflow of an export ({@code UserOrUserGroupName}). */
    private static String owner(byte[] archive) {
        java.util.regex.Matcher owner = java.util.regex.Pattern.compile(
            "<UserOrUserGroupName>([^<]+)</UserOrUserGroupName>").matcher(new String(
                ArtifactFixtures.entries(archive).get("workflow/workflow.xml"),
                java.nio.charset.StandardCharsets.UTF_8));
        return owner.find() ? owner.group(1).strip() : "corpus";
    }

    @Test
    void unchangedRealWorkflowsHaveNoStructuralError() throws IOException {
        String corpus = System.getenv(VARIABLE);
        Assumptions.assumeTrue(corpus != null && !corpus.isBlank(),
            VARIABLE + " is not set; the structure corpus test is skipped");
        List<Path> archives = new ArrayList<>();
        for (String entry : corpus.split(File.pathSeparator)) {
            Path path = Path.of(entry.strip());
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    files.filter(f -> f.toString().endsWith(".zip") && Files.isRegularFile(f))
                        .sorted().forEach(archives::add);
                }
            } else if (Files.isRegularFile(path)) {
                archives.add(path);
            }
        }
        List<Path> modified = new ArrayList<>();
        String variants = System.getenv(MODIFIED_VARIABLE);
        if (variants != null && !variants.isBlank()) {
            for (String entry : variants.split(File.pathSeparator)) {
                modified.add(Path.of(entry.strip()));
            }
        }
        Map<String, Integer> real = new TreeMap<>();
        Map<String, Integer> broken = new TreeMap<>();
        Map<String, Integer> archivesChecked = new TreeMap<>();
        for (int i = 0; i < archives.size(); i++) {
            Path archive = archives.get(i);
            String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean deliberatelyBroken = name.contains("bad") || name.contains("miss")
                || modified.stream().anyMatch(archive::startsWith);
            byte[] bytes = Files.readAllBytes(archive);
            if (bytes.length == 0 || ArtifactFixtures.entries(bytes).keySet().stream()
                .noneMatch(entry -> entry.equals("workflow/workflow.xml"))) {
                archivesChecked.merge("skipped: no workflows", 1, Integer::sum);
                continue;
            }
            Path root = Files.createDirectories(temp.resolve("w" + i));
            try {
                // the archive's own owner, so that its repository references resolve as in a
                // real workspace (review M1)
                new ArchiveCodec().prepare(new GroupId("dev"), owner(bytes), List.of(bytes))
                    .writeTo(root);
            } catch (ToolErrorException e) {
                archivesChecked.merge("skipped: not processable", 1, Integer::sum);
                continue;
            }
            ArtifactCheckService checks = new ArtifactCheckService(root, new WorkspaceInspector(),
                new SaxonXsltRunner(root), group -> Optional.empty(), node -> {
                    throw new AssertionError("no server lookups");
                }, node -> Optional.empty(), ResultLimiter.withDefaults(), Clock.systemUTC());
            Map<String, Integer> counts = deliberatelyBroken ? broken : real;
            for (CheckFinding finding : checks.checkPaths(List.of("dev"), false)) {
                counts.merge(finding.severity() + " " + finding.check() + " " + finding.code(),
                    1, Integer::sum);
            }
            archivesChecked.merge(deliberatelyBroken ? "broken variants" : "real exports", 1,
                Integer::sum);
        }
        System.out.println("StructureCorpusTest: " + archives.size() + " archives "
            + archivesChecked + "; real exports " + real + "; broken variants " + broken);

        assertThat(real.keySet()).as("SC-004: no structural ERROR on unchanged real exports")
            .noneMatch(key -> key.startsWith("ERROR STRUCTURE "));
    }
}
