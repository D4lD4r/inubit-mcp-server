package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T001 (feature 025, research D-11, spec FR-015): the 7.4 fixture folder has its {@code README.md}
 * with a {@code ## Recorded cases} section, {@code RECONSTRUCTED.txt} and {@code DERIVED.txt};
 * every line of the three lists has its format, every listed case has its three files, and every
 * {@code <case>.stdout} of the folder is in exactly one of the three lists.
 */
class V74FixturesShapeTest {

    static final Path FOLDER = Path.of("src/test/resources/fixtures/v7_4/cli");
    static final String RECORDED_HEADING = "## Recorded cases";

    /** {@code RECONSTRUCTED.txt}: {@code <case> <capability>}. */
    private static final Pattern RECONSTRUCTED_LINE = Pattern.compile("^([a-z0-9_]+) ([a-z_]+)$");
    /** {@code DERIVED.txt}: {@code <case> <source-case> <capability> <edit…>}. */
    private static final Pattern DERIVED_LINE =
        Pattern.compile("^([a-z0-9_]+) ([a-z0-9_]+) ([a-z_]+) (\\S.*)$");
    /** README, section {@code ## Recorded cases}: one list item {@code - <case> <source>}. */
    private static final Pattern RECORDED_LINE = Pattern.compile("^- ([a-z0-9_]+) ([a-z0-9_]+)$");

    @Test
    void theFolderHasItsReadmeAndBothLists() {
        for (String file : List.of("README.md", "RECONSTRUCTED.txt", "DERIVED.txt")) {
            assertThat(FOLDER.resolve(file)).as(file).isRegularFile();
        }
    }

    @Test
    void theReadmeHasTheRecordedCasesSection() throws IOException {
        assertThat(Files.readAllLines(FOLDER.resolve("README.md"))).contains(RECORDED_HEADING);
    }

    @Test
    void theFolderMatchesItsLists() throws IOException {
        assertThat(violations(FOLDER)).isEmpty();
    }

    @Test
    void violationsReportBadLinesMissingFilesAndCasesNotInExactlyOneList(@TempDir Path folder)
        throws IOException {
        Files.writeString(folder.resolve("README.md"), """
            # Fixtures

            ## Recorded cases

            One case per line.

            - rec attachment
            - rec

            ## Next

            - after the section x
            """);
        Files.writeString(folder.resolve("RECONSTRUCTED.txt"), """
            # comment
            rec monitoring
            missing monitoring
            Bad Line
            """);
        Files.writeString(folder.resolve("DERIVED.txt"), """
            # comment
            der rec monitoring maintenance switched on
            der rec
            """);
        for (String name : List.of("rec", "der", "stray")) {
            for (String suffix : List.of(".stdout", ".stderr", ".exit")) {
                Files.writeString(folder.resolve(name + suffix), "");
            }
        }

        assertThat(violations(folder)).containsExactlyInAnyOrder(
            "README.md: bad line: - rec",
            "RECONSTRUCTED.txt: bad line: Bad Line",
            "DERIVED.txt: bad line: der rec",
            "missing.stdout missing",
            "missing.stderr missing",
            "missing.exit missing",
            "rec.stdout is in 2 lists",
            "stray.stdout is in 0 lists");
    }

    /** Every rule of this class the folder breaks, one message per finding. */
    static List<String> violations(Path folder) throws IOException {
        List<String> violations = new ArrayList<>();
        Set<String> reconstructed = cases("RECONSTRUCTED.txt",
            listLines(folder.resolve("RECONSTRUCTED.txt")), RECONSTRUCTED_LINE, violations);
        Set<String> derived = cases("DERIVED.txt", listLines(folder.resolve("DERIVED.txt")),
            DERIVED_LINE, violations);
        Set<String> recorded = cases("README.md", recordedLines(folder), RECORDED_LINE, violations);
        Set<String> listed = new LinkedHashSet<>(reconstructed);
        listed.addAll(derived);
        listed.addAll(recorded);
        for (String name : listed) {
            for (String suffix : List.of(".stdout", ".stderr", ".exit")) {
                if (!Files.isRegularFile(folder.resolve(name + suffix))) {
                    violations.add(name + suffix + " missing");
                }
            }
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.sorted().toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".stdout")) {
                    continue;
                }
                String name = fileName.substring(0, fileName.length() - ".stdout".length());
                long lists = Stream.of(reconstructed, derived, recorded)
                    .filter(list -> list.contains(name)).count();
                if (lists != 1) {
                    violations.add(fileName + " is in " + lists + " lists");
                }
            }
        }
        return violations;
    }

    /** The lines of a list file without blank lines and {@code #} comments. */
    static List<String> listLines(Path file) throws IOException {
        return Files.readAllLines(file).stream()
            .map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();
    }

    /** The list items ({@code - …}) of the README's {@code ## Recorded cases} section. */
    static List<String> recordedLines(Path folder) throws IOException {
        List<String> items = new ArrayList<>();
        boolean inSection = false;
        for (String line : Files.readAllLines(folder.resolve("README.md"))) {
            if (line.startsWith("## ")) {
                inSection = line.strip().equals(RECORDED_HEADING);
            } else if (inSection && line.startsWith("- ")) {
                items.add(line.strip());
            }
        }
        return items;
    }

    /** The case names of the lines that match {@code format}; every other line is a violation. */
    private static Set<String> cases(String file, List<String> lines, Pattern format,
        List<String> violations) {
        Set<String> cases = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher entry = format.matcher(line);
            if (entry.matches()) {
                cases.add(entry.group(1));
            } else {
                violations.add(file + ": bad line: " + line);
            }
        }
        return cases;
    }
}
