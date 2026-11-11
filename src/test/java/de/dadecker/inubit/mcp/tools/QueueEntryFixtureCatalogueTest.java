package de.dadecker.inubit.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T026, T032 (feature 020, research D-18, D-20): the closed world of the queue-entry fixtures in
 * {@code fixtures/queue-entry/}. Every file lies in {@code v8_1/} or {@code v9_0/} (or is the
 * root {@code README.md}) and belongs to a case, named by the file name up to its first dot; every
 * case has an {@code .origin} whose first line is {@code recorded …} or {@code reconstructed …}
 * and a {@code .README}, except the queueLog answers {@code rows_<case>} (T012), which are a
 * {@code .json} and an {@code .origin} only. A case with frames has its exit code and the other
 * way round, and its frames use the fixed nonce; a {@code start} frame names the helper variant
 * of its directory ({@code SOAP_81} in {@code v8_1/}, {@code REST_90} in {@code v9_0/}). 9.0 is
 * {@code SUPPORTED} for reading queue entries in the capability matrix exactly when
 * {@code v9_0/error_entry.origin} starts with {@code recorded} (FR-019).
 */
class QueueEntryFixtureCatalogueTest {

    static final Path ROOT = Path.of("src/test/resources/fixtures/queue-entry");

    /** The fixed nonce of every frame in the fixtures; a replay replaces it. */
    static final String FIXTURE_NONCE = "0123456789abcdef0123456789abcdef";

    private static final Set<String> ROOT_FILES = Set.of("README.md");
    private static final Set<String> VERSIONS = Set.of("v8_1", "v9_0");
    private static final String ROWS_PREFIX = "rows_";
    /** The files of a content case (research D-18); the case name is the part before them. */
    private static final Set<String> CASE_SUFFIXES = Set.of(".data", ".wfsp", ".frames",
        ".exit", ".rows.json", ".origin", ".README");
    private static final Set<String> ROWS_SUFFIXES = Set.of(".json", ".origin");
    private static final Pattern ORIGIN = Pattern.compile("(?:reconstructed|recorded) .+");
    private static final Pattern FRAME = Pattern.compile("#QE1:([^ ]*) .*");
    private static final Pattern START = Pattern.compile("#QE1:[^ ]* start ([^ ]*).*");
    /** The helper variant whose frames a version directory holds. */
    private static final Map<String, String> VARIANTS = Map.of("v8_1", "SOAP_81",
        "v9_0", "REST_90");

    @Test
    void everyFileBelongsToACaseWithItsOriginAndReadme() throws IOException {
        assertThat(violations(ROOT)).isEmpty();
        assertThat(cases(ROOT)).as("cases").contains("v8_1/not_found_file",
            "v8_1/rows_two_errors", "v8_1/secrets_everywhere", "v9_0/error_entry",
            "v9_0/missing_workflow", "v9_0/not_found_file");
    }

    @Test
    void nineZeroIsSupportedExactlyWhenItsErrorEntryIsRecorded() throws IOException {
        SupportState state = CapabilityMatrix.production().stateOfLine(9, OptionalInt.of(0),
            Capability.QUEUE_ENTRY_READ);

        assertThat(state).as("9.0 enabled with its recording (T038)")
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(nineZeroViolation(ROOT, state)).isEmpty();
    }

    @Test
    void aMissingOriginFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("queue-entry"));
        Files.delete(copy.resolve("v8_1/binary_zip.origin"));
        Files.delete(copy.resolve("v8_1/rows_empty.origin"));

        assertThat(violations(copy)).containsExactlyInAnyOrder("v8_1/binary_zip: no .origin",
            "v8_1/rows_empty: no .origin");
    }

    @Test
    void anOriginWithoutProvenanceFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("queue-entry"));
        Files.writeString(copy.resolve("v8_1/too_large.origin"), "synthetic\nrecorded 9.0.7\n");

        assertThat(violations(copy)).containsExactly("v8_1/too_large: the first line of"
            + " .origin is neither 'recorded …' nor 'reconstructed …'");
    }

    @Test
    void aMissingReadmeFailsExceptForTheRowsAnswers(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("queue-entry"));
        Files.delete(copy.resolve("v8_1/cutoff_tag.README"));
        Files.delete(copy.resolve("v9_0/missing_workflow.README"));

        assertThat(violations(copy)).containsExactlyInAnyOrder("v8_1/cutoff_tag: no .README",
            "v9_0/missing_workflow: no .README");
    }

    @Test
    void aFileOfNoCaseFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("queue-entry"));
        Files.writeString(copy.resolve("notes.txt"), "x\n");
        Files.writeString(copy.resolve("v8_1/binary_zip.zip"), "x\n");
        Files.writeString(copy.resolve("v8_1/rows_empty.README"), "x\n");
        Files.writeString(copy.resolve("v8_1/stray.wfsp"), "x\n");
        Files.createDirectories(copy.resolve("v9_1"));
        Files.writeString(copy.resolve("v9_1/error_entry.origin"), "recorded 9.1.0\n");
        Files.createDirectories(copy.resolve("v8_1/nested"));
        Files.writeString(copy.resolve("v8_1/nested/a.origin"), "reconstructed x\n");

        assertThat(violations(copy)).containsExactlyInAnyOrder(
            "notes.txt belongs to no case",
            "v8_1/binary_zip.zip belongs to no case",
            "v8_1/nested/a.origin belongs to no case",
            "v8_1/rows_empty.README belongs to no case",
            "v9_1/error_entry.origin belongs to no case",
            "v8_1/stray: no .origin",
            "v8_1/stray: no .README");
    }

    @Test
    void framesWithoutExitCodeOrWithAnotherNonceFail(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("queue-entry"));
        Files.delete(copy.resolve("v8_1/latin1_text.exit"));
        Files.delete(copy.resolve("v8_1/many_variables.frames"));
        Path frames = copy.resolve("v8_1/long_values.frames");
        Files.writeString(frames, Files.readString(frames).replace(FIXTURE_NONCE,
            "fedcba9876543210fedcba9876543210"));

        assertThat(violations(copy)).containsExactlyInAnyOrder(
            "v8_1/latin1_text: .frames without .exit",
            "v8_1/long_values: a frame without the fixed nonce",
            "v8_1/many_variables: .exit without .frames");
    }

    @Test
    void aStartFrameOfTheOtherVersionFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("queue-entry"));
        Path frames90 = copy.resolve("v9_0/not_found_file.frames");
        Files.writeString(frames90, Files.readString(frames90).replace(" start REST_90",
            " start SOAP_81"));
        Path frames81 = copy.resolve("v8_1/binary_zip.frames");
        Files.writeString(frames81, Files.readString(frames81).replace(" start SOAP_81",
            " start REST_90"));

        assertThat(violations(copy)).containsExactlyInAnyOrder(
            "v8_1/binary_zip: a start frame not naming SOAP_81",
            "v9_0/not_found_file: a start frame not naming REST_90");
    }

    @Test
    void theNineZeroRuleFailsInBothDirections(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("queue-entry"));
        Files.delete(copy.resolve("v9_0/error_entry.origin"));

        assertThat(nineZeroViolation(copy, SupportState.SUPPORTED)).contains("9.0 is SUPPORTED"
            + " for QUEUE_ENTRY_READ, but v9_0/error_entry.origin does not start with"
            + " 'recorded'");
        Files.writeString(copy.resolve("v9_0/error_entry.origin"),
            "reconstructed from the helper protocol\n");
        assertThat(nineZeroViolation(copy, SupportState.SUPPORTED)).isPresent();
        Files.writeString(copy.resolve("v9_0/error_entry.origin"),
            "recorded 2026-11-10 9.0.10 neutralized\n");
        assertThat(nineZeroViolation(copy, SupportState.SUPPORTED)).isEmpty();
        assertThat(nineZeroViolation(copy, SupportState.UNSUPPORTED)).contains("9.0 is"
            + " UNSUPPORTED for QUEUE_ENTRY_READ, but v9_0/error_entry.origin starts with"
            + " 'recorded'; enable 9.0 in the capability matrix in the same change");
    }

    /** The cases below {@code root} as {@code <version>/<case>}, sorted. */
    static Set<String> cases(Path root) throws IOException {
        return new TreeSet<>(filesByCase(root, new ArrayList<>()).keySet());
    }

    /** Every violation of the rules of the class description. */
    static List<String> violations(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        Map<String, Set<String>> cases = filesByCase(root, violations);
        cases.forEach((name, suffixes) -> {
            boolean rows = name.substring(name.indexOf('/') + 1).startsWith(ROWS_PREFIX);
            if (!suffixes.contains(".origin")) {
                violations.add(name + ": no .origin");
            } else if (!ORIGIN.matcher(firstLine(root.resolve(name + ".origin"))).matches()) {
                violations.add(name + ": the first line of .origin is neither 'recorded …' nor"
                    + " 'reconstructed …'");
            }
            if (!rows && !suffixes.contains(".README")) {
                violations.add(name + ": no .README");
            }
            if (suffixes.contains(".frames") && !suffixes.contains(".exit")) {
                violations.add(name + ": .frames without .exit");
            }
            if (suffixes.contains(".exit") && !suffixes.contains(".frames")) {
                violations.add(name + ": .exit without .frames");
            }
            if (suffixes.contains(".frames") && !fixedNonce(root.resolve(name + ".frames"))) {
                violations.add(name + ": a frame without the fixed nonce");
            }
            String variant = VARIANTS.get(name.substring(0, name.indexOf('/')));
            if (suffixes.contains(".frames")
                && !startFramesName(root.resolve(name + ".frames"), variant)) {
                violations.add(name + ": a start frame not naming " + variant);
            }
        });
        return violations;
    }

    /**
     * Empty if the matrix state of 9.0 and the provenance of {@code v9_0/error_entry.origin} fit
     * (D-20), else the violation.
     */
    static Optional<String> nineZeroViolation(Path root, SupportState state) throws IOException {
        Path origin = root.resolve("v9_0/error_entry.origin");
        boolean recorded = Files.isRegularFile(origin) && firstLine(origin).startsWith("recorded ");
        if (state == SupportState.SUPPORTED && !recorded) {
            return Optional.of("9.0 is SUPPORTED for QUEUE_ENTRY_READ, but"
                + " v9_0/error_entry.origin does not start with 'recorded'");
        }
        if (state != SupportState.SUPPORTED && recorded) {
            return Optional.of("9.0 is " + state + " for QUEUE_ENTRY_READ, but"
                + " v9_0/error_entry.origin starts with 'recorded'; enable 9.0 in the capability"
                + " matrix in the same change");
        }
        return Optional.empty();
    }

    /**
     * The suffixes of each case ({@code <version>/<case>}); files outside the layout go to
     * {@code violations}.
     */
    private static Map<String, Set<String>> filesByCase(Path root, List<String> violations)
        throws IOException {
        Map<String, Set<String>> cases = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (ROOT_FILES.contains(relative)) {
                    continue;
                }
                String[] parts = relative.split("/");
                String fileName = parts[parts.length - 1];
                int dot = fileName.indexOf('.');
                String name = dot < 0 ? fileName : fileName.substring(0, dot);
                String suffix = dot < 0 ? "" : fileName.substring(dot);
                Set<String> allowed = name.startsWith(ROWS_PREFIX) ? ROWS_SUFFIXES
                    : CASE_SUFFIXES;
                if (parts.length != 2 || !VERSIONS.contains(parts[0]) || name.isEmpty()
                    || !allowed.contains(suffix)) {
                    violations.add(relative + " belongs to no case");
                    continue;
                }
                cases.computeIfAbsent(parts[0] + "/" + name, key -> new TreeSet<>()).add(suffix);
            }
        }
        return cases;
    }

    private static boolean fixedNonce(Path frames) {
        return lines(frames).stream()
            .map(FRAME::matcher)
            .filter(Matcher::matches)
            .allMatch(frame -> frame.group(1).equals(FIXTURE_NONCE));
    }

    /** Every {@code start} frame names {@code variant}; a case without one passes. */
    private static boolean startFramesName(Path frames, String variant) {
        return lines(frames).stream()
            .map(START::matcher)
            .filter(Matcher::matches)
            .allMatch(start -> start.group(1).equals(variant));
    }

    private static String firstLine(Path file) {
        List<String> lines = lines(file);
        return lines.isEmpty() ? "" : lines.get(0);
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
        return to;
    }
}
