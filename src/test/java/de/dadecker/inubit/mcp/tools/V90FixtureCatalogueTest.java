package de.dadecker.inubit.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.testing.V90Cases;
import de.dadecker.inubit.mcp.testing.V90Cases.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T035 (feature 017, research D-10, SC-006): the closed world of the INUBIT 9.0 fixtures. The
 * catalogue of {@code tools/record-fixtures.sh} for {@code RECORD_INUBIT_LINE=9.0} (its
 * {@code V90_CASES}, which {@code --list} prints, see {@link RecordFixturesCatalogueTest}) and the
 * cases of {@code fixtures/v9_0/NOT_RECORDABLE.txt} together are exactly the fixture cases on
 * disk, each with its {@code .origin}, and exactly the cases the 9.0 contract tests load through
 * {@link V90Cases}, in both directions. The non-recordable set is asserted exactly, so no exemption
 * can be added silently; its feature-022 administration cases must be recorded and neutralized.
 */
class V90FixtureCatalogueTest {

    static final Path ROOT = Path.of("src/test/resources/fixtures/v9_0");

    /** The exemptions of research D-10: write successes and REST 403/500/503. */
    private static final Set<String> D10_EXEMPTIONS = Set.of("import_created", "tag_ok",
        "processErrorStart_ok", "kill_ok", "rest_forbidden", "rest_internal_error",
        "rest_unavailable");

    /**
     * The feature-022 administration recordings (research D-26; T024, T028b): recorded once by
     * their live task, not by the recorder, and neutralized.
     */
    private static final Set<String> ADMINISTRATION = Set.of("admin-license",
        "admin-configurations", "admin-users", "admin-settings-session",
        "admin-settings-threads-refused", "admin-settings-privileged-percentage-refused",
        "admin-settings-privileged-list-refused", "admin-settings-db-tolerance",
        "admin-settings-date", "admin-settings-license-days", "admin-settings-license-seconds",
        "admin-users-session", "admin-users-user", "admin-users-finger",
        "admin-users-user-capitalized", "admin-users-finger-capitalized",
        "admin-users-user-underscore", "admin-users-finger-no-name", "admin-users-msglist-empty",
        "admin-users-who", "admin-users-nwho");

    private static final Set<String> NOT_RECORDABLE = Stream.concat(D10_EXEMPTIONS.stream(),
        ADMINISTRATION.stream()).collect(Collectors.toUnmodifiableSet());

    /** The only files of the fixture root that are not a case. */
    private static final Set<String> ROOT_FILES = Set.of("README.md", "NOT_RECORDABLE.txt");

    @Test
    void theNotRecordableCasesAreTheD10ExemptionsAndTheAdminRecordings() throws IOException {
        Map<String, String> reasons = notRecordable(ROOT);

        assertThat(reasons.keySet()).containsExactlyInAnyOrderElementsOf(NOT_RECORDABLE);
        assertThat(reasons).allSatisfy((name, reason) ->
            assertThat(reason).as(name).hasSizeGreaterThan(20));
        ADMINISTRATION.forEach(name -> assertThat(V90Cases.origin(name)).as(name)
            .matches("recorded \\d{4}-\\d{2}-\\d{2} 9\\.0\\.\\d+ neutralized"));
    }

    @Test
    void catalogueAndNotRecordableAreTheRegisteredCases() throws IOException {
        Set<String> catalogue = new TreeSet<>(RecordFixturesCatalogueTest.v90CasesOfTheScript());

        assertThat(catalogue).doesNotContainAnyElementsOf(NOT_RECORDABLE);
        Set<String> all = new TreeSet<>(catalogue);
        all.addAll(NOT_RECORDABLE);
        assertThat(all).containsExactlyElementsOf(V90Cases.names());
        for (String name : catalogue) {
            Kind recorded = RecordFixturesCatalogueTest.kind(RecordFixturesCatalogueTest.arm(name))
                .equals("rest") ? Kind.REST : Kind.CLI;
            assertThat(V90Cases.CASES.get(name)).as(name).isEqualTo(recorded);
        }
    }

    @Test
    void theFixtureFilesAreExactlyTheRegisteredCasesWithTheirOrigin() throws IOException {
        assertThat(violations(ROOT, V90Cases.CASES)).isEmpty();
        V90Cases.names().forEach(name -> assertThat(V90Cases.origin(name))
            .matches("(reconstructed|recorded) .+"));
    }

    @Test
    void aMissingOriginFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("v9_0"));
        Files.delete(copy.resolve("rest/ready.origin"));

        assertThat(violations(copy, V90Cases.CASES))
            .containsExactly("ready: no rest/ready.origin");
    }

    @Test
    void aFixtureOfNoRegisteredCaseFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("v9_0"));
        Files.writeString(copy.resolve("cli/ps_error.stdout"), "x\n");
        Files.writeString(copy.resolve("rest/version.origin"), "recorded 2026-01-01 9.0.7\n");
        Map<String, Kind> withoutHelp = new LinkedHashMap<>(V90Cases.CASES);
        withoutHelp.remove("help");

        assertThat(violations(copy, withoutHelp)).containsExactlyInAnyOrder(
            "ps_error: cli/ps_error.stdout belongs to no registered case",
            "version: rest/version.origin belongs to no registered case",
            "help: cli/help.exit belongs to no registered case",
            "help: cli/help.origin belongs to no registered case",
            "help: cli/help.stderr belongs to no registered case",
            "help: cli/help.stdout belongs to no registered case");
    }

    /** {@code <case> <reason>} per line; blank lines and {@code #} comments are skipped. */
    static Map<String, String> notRecordable(Path root) throws IOException {
        Map<String, String> reasons = new LinkedHashMap<>();
        for (String line : Files.readAllLines(root.resolve("NOT_RECORDABLE.txt"))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.strip().split("\\s+", 2);
            assertThat(reasons.put(parts[0], parts.length > 1 ? parts[1] : ""))
                .as("duplicate " + parts[0]).isNull();
        }
        return reasons;
    }

    /**
     * Every registered case has its origin in the directory of its kind, and every file below
     * {@code root} belongs to a registered case of that directory (or is a root file).
     */
    static List<String> violations(Path root, Map<String, Kind> cases) throws IOException {
        List<String> violations = new ArrayList<>();
        cases.forEach((name, kind) -> {
            if (!Files.isRegularFile(root.resolve(kind.directory() + name + ".origin"))) {
                violations.add(name + ": no " + kind.directory() + name + ".origin");
            }
        });
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (ROOT_FILES.contains(relative)) {
                    continue;
                }
                String fileName = file.getFileName().toString();
                String name = fileName.substring(0, fileName.indexOf('.') < 0
                    ? fileName.length() : fileName.indexOf('.'));
                Kind kind = cases.get(name);
                if (kind == null || !relative.equals(kind.directory() + fileName)) {
                    violations.add(name + ": " + relative + " belongs to no registered case");
                }
            }
        }
        return violations;
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
