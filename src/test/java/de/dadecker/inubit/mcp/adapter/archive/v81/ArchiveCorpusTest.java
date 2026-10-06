package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Opt-in (review of T012): reads local, real export archives with {@link ArchiveReader}.
 * {@code INUBIT_MCP_ARCHIVE_CORPUS} lists ZIP files or directories (searched recursively for
 * {@code *.zip}), separated by the platform's path separator; the test is skipped when it is not
 * set. Empty files (a failed export), exports with history ({@code versionHistory.xml}) and
 * exports by tag ({@code usertags.xml}), both never requested by feature 003, are counted and
 * skipped. Only counts are printed — never names or
 * content of the archives.
 */
class ArchiveCorpusTest {

    static final String VARIABLE = "INUBIT_MCP_ARCHIVE_CORPUS";

    @Test
    void everyRealExportOfTheCorpusCanBeRead() throws IOException {
        String corpus = System.getenv(VARIABLE);
        Assumptions.assumeTrue(corpus != null && !corpus.isBlank(),
            VARIABLE + " is not set; the corpus test is skipped");
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
        ArchiveReader reader = new ArchiveReader();
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> failures = new TreeMap<>();
        for (Path archive : archives) {
            byte[] bytes = Files.readAllBytes(archive);
            if (bytes.length == 0) {
                counts.merge("skipped: empty file", 1, Integer::sum);
                continue;
            }
            Map<String, byte[]> entries = ArtifactFixtures.entries(bytes);
            if (entries.containsKey("versionHistory.xml")) {
                counts.merge("skipped: export with history", 1, Integer::sum);
                continue;
            }
            if (entries.containsKey("usertags.xml")) {
                counts.merge("skipped: export by tag", 1, Integer::sum);
                continue;
            }
            try {
                ExportArchive read = reader.read(bytes);
                counts.merge("read", 1, Integer::sum);
                counts.merge("workflows", read.workflowGroups().stream()
                    .mapToInt(g -> g.workflows().size()).sum(), Integer::sum);
                counts.merge("module files", read.moduleFiles().size(), Integer::sum);
                counts.merge("module index entries", read.moduleIndex().size(), Integer::sum);
                counts.merge("repository files", read.repository().size(), Integer::sum);
                // review I3: key material outside KeyStore properties (counts only)
                RedactionReport report = new SecretRedactor().redact(read).report();
                counts.merge("key material in other properties", report.counts()
                    .getOrDefault(SecretRedactor.Kind.KEY_MATERIAL, 0), Integer::sum);
                counts.merge("repository files with key material", report.counts()
                    .getOrDefault(SecretRedactor.Kind.REPOSITORY_KEY_MATERIAL, 0), Integer::sum);
                read.moduleFiles().keySet().forEach(name -> {
                    if (name.indexOf(' ') >= 0) {
                        counts.merge("module names with a space", 1, Integer::sum);
                    }
                    if (!name.chars().allMatch(c -> c < 128)) {
                        counts.merge("module names with non-ASCII characters", 1, Integer::sum);
                    }
                    if (!name.equals(name.toLowerCase(java.util.Locale.ROOT))) {
                        counts.merge("module names with upper-case letters", 1, Integer::sum);
                    }
                });
            } catch (ToolErrorException e) {
                failures.merge(category(e.error().message()), 1, Integer::sum);
            }
        }
        System.out.println("ArchiveCorpusTest: " + archives.size() + " archives, " + counts
            + ", failures " + failures);
        assertThat(failures).as("failures by kind (no names)").isEmpty();
    }

    /** The kind of a failure, without the entry or artifact names the message contains. */
    private static String category(String message) {
        for (String kind : List.of("without an entry in", "unexpected entry", "not well-formed",
            "too large", "unexpected element", "unexpected text", "leaves the archive", "twice",
            "two modules stored as", "has no", "not a readable ZIP", "does not start with",
            "unexpected directory entry")) {
            if (message.contains(kind)) {
                return kind;
            }
        }
        return "other";
    }
}
