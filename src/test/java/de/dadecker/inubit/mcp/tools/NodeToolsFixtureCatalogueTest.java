package de.dadecker.inubit.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Detail;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.DetailsOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Member;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.MembersOk;
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
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T019 (feature 024, research D-26, D-27): the closed world of the node-tool fixtures in
 * {@code fixtures/node-tools/} as its {@code README.md} describes it. Every file is a root file
 * ({@code README.md}, {@code io-message-ref.vectors}) or a case file
 * {@code <version>/<tool>/<case>.<ext>} with the suffixes of its tool; the case is the file name up
 * to its first dot. Every case has an {@code .origin} whose first line is
 * {@code recorded <date> <version> neutralized}, its version in the line of its version directory,
 * or {@code reconstructed <source>}. For every (version, capability) the production capability
 * matrix says {@code SUPPORTED} exactly when all cases D-27 requires for it are recorded and have
 * their data files: {@code ok} for the cluster nodes, {@code listing} and {@code read_text} for the
 * I/O log, {@code all} for the node configuration, {@code set_ok} and {@code reset_ok} for the log
 * level. The 8.1 cluster-nodes cases parse as the helper protocol
 * (contracts/helper-protocol-delta.md) and as their names say.
 */
class NodeToolsFixtureCatalogueTest {

    static final Path ROOT = Path.of("src/test/resources/fixtures/node-tools");

    /** The fixed nonce of the {@code .frames} fixtures, as in the 020 and 021 fixtures. */
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final Set<String> ROOT_FILES = Set.of("README.md", "io-message-ref.vectors");
    /** The version directories and their (major, minor). */
    private static final Map<String, int[]> VERSIONS = Map.of("v8_1", new int[] {8, 1},
        "v9_0", new int[] {9, 0});

    /**
     * A tool directory: its capability, the suffixes of its cases (README "Layout") and the cases
     * that must be recorded before the matrix supports the capability (D-27).
     */
    private record Tool(Capability capability, Set<String> suffixes, List<Required> required) {
    }

    /** A case D-27 requires recorded and the data files it must have besides its .origin. */
    private record Required(String name, Set<String> files) {
    }

    private static final Set<String> HELPER_FILES = Set.of(".frames", ".exit");
    private static final Set<String> STARTCLI_FILES = Set.of(".stdout", ".stderr", ".exit");
    private static final Map<String, Tool> TOOLS = Map.of(
        "cluster-nodes", new Tool(Capability.CLUSTER_NODES_READ,
            Set.of(".frames", ".exit", ".origin"), List.of(new Required("ok", HELPER_FILES))),
        "io-log", new Tool(Capability.IO_LOG_READ,
            Set.of(".frames", ".exit", ".list", ".message", ".origin"),
            List.of(new Required("listing", Set.of(".frames", ".exit", ".list")),
                new Required("read_text", Set.of(".frames", ".exit", ".list", ".message")))),
        "node-config", new Tool(Capability.NODE_CONFIG_READ,
            Set.of(".frames", ".exit", ".origin"), List.of(new Required("all", HELPER_FILES))),
        "log-level", new Tool(Capability.LOG_LEVEL_WRITE,
            Set.of(".stdout", ".stderr", ".exit", ".origin"),
            List.of(new Required("set_ok", STARTCLI_FILES),
                new Required("reset_ok", STARTCLI_FILES))));
    /** Groups 1 and 2: major and minor of a recorded version. */
    private static final Pattern ORIGIN = Pattern.compile("recorded \\d{4}-\\d{2}-\\d{2}"
        + " (\\d+)\\.(\\d+)(?:\\.\\d+)* neutralized|reconstructed \\S.*");

    @Test
    void everyFileBelongsToACaseWithAValidOrigin() throws IOException {
        assertThat(violations(ROOT)).isEmpty();
        assertThat(cases(ROOT)).as("cases").contains("v8_1/cluster-nodes/ok",
            "v8_1/cluster-nodes/single", "v8_1/cluster-nodes/details",
            "v8_1/cluster-nodes/details_truncated", "v8_1/cluster-nodes/details_unknown",
            "v8_1/cluster-nodes/details_error", "v8_1/cluster-nodes/unknown_status",
            "v8_1/cluster-nodes/api_missing", "v8_1/cluster-nodes/garbage");
    }

    @Test
    void theMatrixSupportsACapabilityExactlyWhenItsRequiredCasesAreRecorded() {
        assertThat(matrixViolations(ROOT, CapabilityMatrix.production())).isEmpty();
    }

    /**
     * Each 8.1 cluster-nodes case: its exit code, its frames (runs of one frame type as
     * {@code Type*n}) and the parse problem; a {@code members ok} or {@code details ok} count is
     * the number of {@code member} or {@code detail} frames before it.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "ok | 0 | Start LoginOk NodeOk Member*2 MembersOk End | -",
        "single | 0 | Start LoginOk NodeOk Member MembersOk End | -",
        "details | 0 | Start LoginOk NodeOk Member*2 MembersOk Detail*9 DetailsOk End | -",
        "details_truncated | 0 | Start LoginOk NodeOk Member*2 MembersOk Detail*500 DetailsOk"
            + " End | -",
        "details_unknown | 0 | Start LoginOk NodeOk Member*2 MembersOk DetailsUnknown End | -",
        "details_error | 0 | Start LoginOk NodeOk Member*2 MembersOk DetailsError End | -",
        "unknown_status | 0 | Start LoginOk NodeOk Member*2 MembersOk End | -",
        "api_missing | 4 | ApiMissing | -",
        "garbage | 0 | Start LoginOk NodeOk | UNKNOWN_FRAME_TYPE"})
    void aClusterNodesCaseParsesAsItsNameSays(String name, int exit, String summary,
        String problem) throws IOException {
        Path dir = ROOT.resolve("v8_1/cluster-nodes");
        HelperFrames frames = HelperFrames.parse(NONCE,
            Files.readString(dir.resolve(name + ".frames"), StandardCharsets.UTF_8));

        assertThat(Integer.parseInt(firstLine(dir.resolve(name + ".exit")))).isEqualTo(exit);
        assertThat(summary(frames.frames()) + " | "
            + frames.problem().map(Enum::name).orElse("-")).isEqualTo(summary + " | " + problem);
        int members = 0;
        int details = 0;
        for (HelperFrames.Frame frame : frames.frames()) {
            switch (frame) {
                case Member member -> members++;
                case MembersOk ok -> assertThat(ok.count()).as("members ok").isEqualTo(members);
                case Detail detail -> details++;
                case DetailsOk ok -> assertThat(ok.count()).as("details ok").isEqualTo(details);
                default -> { }
            }
        }
    }

    /**
     * The {@code details} case carries an address, a path and a secret-named value for the masking
     * of D-21; {@code details_truncated} hit the helper's cap of 500 entries and has one value cut
     * at the helper's 4,000 characters.
     */
    @Test
    void theDetailsCasesCarryAMaskableValueAndACutValue() throws IOException {
        assertThat(details("details")).noneMatch(Detail::cut).extracting(Detail::value)
            .contains("10.0.0.11:7800", "zone-a")
            .anySatisfy(value -> assertThat(value).startsWith("/opt/inubit/"))
            .anySatisfy(value -> assertThat(value).containsPattern("(?i)password=\\S"));
        assertThat(details("details_truncated")).filteredOn(Detail::cut).singleElement()
            .satisfies(detail -> assertThat(detail.value()).hasSize(4000));
        assertThat(HelperFrames.parse(NONCE, Files.readString(
                ROOT.resolve("v8_1/cluster-nodes/details_truncated.frames"),
                StandardCharsets.UTF_8)).frames())
            .contains(new DetailsOk(500, true));
    }

    private static List<Detail> details(String name) throws IOException {
        return HelperFrames.parse(NONCE, Files.readString(
                ROOT.resolve("v8_1/cluster-nodes/" + name + ".frames"), StandardCharsets.UTF_8))
            .frames().stream().filter(Detail.class::isInstance).map(Detail.class::cast).toList();
    }

    @Test
    void aMissingOriginFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("node-tools"));
        Files.delete(copy.resolve("v8_1/cluster-nodes/single.origin"));

        assertThat(violations(copy)).containsExactly("v8_1/cluster-nodes/single: no .origin");
    }

    @Test
    void anOriginOfAnInvalidFormatFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("node-tools"));
        Files.writeString(copy.resolve("v8_1/cluster-nodes/details.origin"), "synthetic\n");
        Files.writeString(copy.resolve("v8_1/cluster-nodes/ok.origin"), "recorded 8.1.17\n");
        Files.writeString(copy.resolve("v8_1/cluster-nodes/single.origin"), "reconstructed\n");

        String invalid = ": the first line of .origin is neither 'recorded <date> <version>"
            + " neutralized' nor 'reconstructed <source>'";
        assertThat(violations(copy)).containsExactly("v8_1/cluster-nodes/details" + invalid,
            "v8_1/cluster-nodes/ok" + invalid, "v8_1/cluster-nodes/single" + invalid);
    }

    @Test
    void aFileOfNoCaseFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("node-tools"));
        Files.writeString(copy.resolve("notes.txt"), "x\n");
        Files.writeString(copy.resolve("v8_1/cluster-nodes/ok.stdout"), "x\n");
        Files.writeString(copy.resolve("v8_1/ok.origin"), "reconstructed x\n");
        Files.createDirectories(copy.resolve("v9_1/cluster-nodes"));
        Files.writeString(copy.resolve("v9_1/cluster-nodes/ok.origin"), "reconstructed x\n");
        Files.createDirectories(copy.resolve("v8_1/cache"));
        Files.writeString(copy.resolve("v8_1/cache/ok.origin"), "reconstructed x\n");
        Files.createDirectories(copy.resolve("v8_1/log-level"));
        Files.writeString(copy.resolve("v8_1/log-level/set_ok.frames"), "x\n");
        Files.writeString(copy.resolve("v8_1/cluster-nodes/stray.exit"), "0\n");

        assertThat(violations(copy)).containsExactlyInAnyOrder(
            "notes.txt belongs to no case",
            "v8_1/cache/ok.origin belongs to no case",
            "v8_1/cluster-nodes/ok.stdout belongs to no case",
            "v8_1/log-level/set_ok.frames belongs to no case",
            "v8_1/ok.origin belongs to no case",
            "v9_1/cluster-nodes/ok.origin belongs to no case",
            "v8_1/cluster-nodes/stray: no .origin");
    }

    @Test
    void theMatrixRuleFailsInBothDirections(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("node-tools"));
        assertThat(matrixViolation(copy, "v8_1", "cluster-nodes", SupportState.SUPPORTED))
            .isEqualTo("8.1 is SUPPORTED for CLUSTER_NODES_READ, but these required cases are not"
                + " recorded: v8_1/cluster-nodes/ok");
        assertThat(matrixViolation(copy, "v8_1", "cluster-nodes", SupportState.UNSUPPORTED))
            .isNull();

        Files.writeString(copy.resolve("v8_1/cluster-nodes/ok.origin"),
            "recorded 2026-11-12 8.1.17 neutralized\n");
        assertThat(matrixViolation(copy, "v8_1", "cluster-nodes", SupportState.SUPPORTED))
            .isNull();
        assertThat(matrixViolation(copy, "v8_1", "cluster-nodes", SupportState.UNSUPPORTED))
            .isEqualTo("8.1 is UNSUPPORTED for CLUSTER_NODES_READ, but its required cases are"
                + " recorded (v8_1/cluster-nodes/ok); enable it in the capability matrix in the"
                + " same change");
        assertThat(matrixViolation(copy, "v8_1", "cluster-nodes", SupportState.UNVERIFIED))
            .startsWith("8.1 is UNVERIFIED for CLUSTER_NODES_READ, but its required cases");

        // the log level needs both of its cases recorded; the other cases of a tool do not count
        Files.createDirectories(copy.resolve("v9_0/log-level"));
        for (String name : List.of("set_ok", "reset_ok")) {
            for (String suffix : List.of(".stdout", ".stderr", ".exit")) {
                Files.writeString(copy.resolve("v9_0/log-level/" + name + suffix), "x\n");
            }
        }
        Files.writeString(copy.resolve("v9_0/log-level/set_ok.origin"),
            "recorded 2026-11-12 9.0.10 neutralized\n");
        Files.writeString(copy.resolve("v9_0/log-level/forbidden.origin"),
            "recorded 2026-11-12 9.0.10 neutralized\n");
        assertThat(matrixViolation(copy, "v9_0", "log-level", SupportState.UNSUPPORTED)).isNull();
        assertThat(matrixViolation(copy, "v9_0", "log-level", SupportState.SUPPORTED))
            .isEqualTo("9.0 is SUPPORTED for LOG_LEVEL_WRITE, but these required cases are not"
                + " recorded: v9_0/log-level/reset_ok");
        Files.writeString(copy.resolve("v9_0/log-level/reset_ok.origin"),
            "recorded 2026-11-12 9.0.10 neutralized\n");
        assertThat(matrixViolation(copy, "v9_0", "log-level", SupportState.SUPPORTED)).isNull();
        assertThat(matrixViolation(copy, "v9_0", "log-level", SupportState.UNSUPPORTED))
            .isEqualTo("9.0 is UNSUPPORTED for LOG_LEVEL_WRITE, but its required cases are"
                + " recorded (v9_0/log-level/set_ok, v9_0/log-level/reset_ok); enable it in the"
                + " capability matrix in the same change");
    }

    @Test
    void aRecordedRequiredCaseWithoutItsDataFilesDoesNotCount(@TempDir Path temp)
        throws IOException {
        Path copy = copy(ROOT, temp.resolve("node-tools"));
        Files.writeString(copy.resolve("v8_1/cluster-nodes/ok.origin"),
            "recorded 2026-11-12 8.1.17 neutralized\n");
        Files.delete(copy.resolve("v8_1/cluster-nodes/ok.frames"));

        assertThat(matrixViolation(copy, "v8_1", "cluster-nodes", SupportState.SUPPORTED))
            .isEqualTo("8.1 is SUPPORTED for CLUSTER_NODES_READ, but these required cases are not"
                + " recorded: v8_1/cluster-nodes/ok (no .frames)");
        assertThat(matrixViolation(copy, "v8_1", "cluster-nodes", SupportState.UNSUPPORTED))
            .isNull();

        // io-log read_text needs its .list and .message as well
        Files.createDirectories(copy.resolve("v9_0/io-log"));
        for (String file : List.of("listing.origin", "read_text.origin")) {
            Files.writeString(copy.resolve("v9_0/io-log/" + file),
                "recorded 2026-11-12 9.0.10 neutralized\n");
        }
        for (String file : List.of("listing.frames", "listing.exit", "listing.list",
            "read_text.frames", "read_text.exit")) {
            Files.writeString(copy.resolve("v9_0/io-log/" + file), "x\n");
        }
        assertThat(matrixViolation(copy, "v9_0", "io-log", SupportState.SUPPORTED))
            .isEqualTo("9.0 is SUPPORTED for IO_LOG_READ, but these required cases are not"
                + " recorded: v9_0/io-log/read_text (no .list, .message)");
    }

    @Test
    void aRecordedOriginOfAnotherVersionLineFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("node-tools"));
        Files.writeString(copy.resolve("v8_1/cluster-nodes/ok.origin"),
            "recorded 2026-11-12 9.0.10 neutralized\n");
        Files.writeString(copy.resolve("v8_1/cluster-nodes/single.origin"),
            "recorded 2026-11-12 8.10.2 neutralized\n");
        Files.writeString(copy.resolve("v8_1/cluster-nodes/details.origin"),
            "recorded 2026-11-12 8.1 neutralized\n");

        assertThat(violations(copy)).containsExactly(
            "v8_1/cluster-nodes/ok: .origin names version 9.0, but the case is in v8_1",
            "v8_1/cluster-nodes/single: .origin names version 8.10, but the case is in v8_1");
    }

    /** The cases below {@code root} as {@code <version>/<tool>/<case>}, sorted. */
    static Set<String> cases(Path root) throws IOException {
        return new TreeSet<>(filesByCase(root, new ArrayList<>()).keySet());
    }

    /** Every violation of the layout and provenance rules of the class description. */
    static List<String> violations(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        filesByCase(root, violations).forEach((name, suffixes) -> {
            if (!suffixes.contains(".origin")) {
                violations.add(name + ": no .origin");
                return;
            }
            Matcher origin = ORIGIN.matcher(firstLine(root.resolve(name + ".origin")));
            if (!origin.matches()) {
                violations.add(name + ": the first line of .origin is neither 'recorded <date>"
                    + " <version> neutralized' nor 'reconstructed <source>'");
                return;
            }
            String directory = name.substring(0, name.indexOf('/'));
            int[] line = VERSIONS.get(directory);
            if (origin.group(1) != null && (Integer.parseInt(origin.group(1)) != line[0]
                || Integer.parseInt(origin.group(2)) != line[1])) {
                violations.add(name + ": .origin names version " + origin.group(1) + "."
                    + origin.group(2) + ", but the case is in " + directory);
            }
        });
        return violations;
    }

    /** Every (version, capability) whose matrix state and recorded cases do not fit (D-27). */
    static List<String> matrixViolations(Path root, CapabilityMatrix matrix) {
        List<String> violations = new ArrayList<>();
        for (String version : new TreeSet<>(VERSIONS.keySet())) {
            int[] line = VERSIONS.get(version);
            for (String tool : new TreeSet<>(TOOLS.keySet())) {
                String violation = matrixViolation(root, version, tool, matrix.stateOfLine(line[0],
                    OptionalInt.of(line[1]), TOOLS.get(tool).capability()));
                if (violation != null) {
                    violations.add(violation);
                }
            }
        }
        return violations;
    }

    /** The violation of one (version, tool) in {@code state}; {@code null} if they fit. */
    static String matrixViolation(Path root, String version, String tool, SupportState state) {
        List<String> required = new ArrayList<>();
        List<String> notRecorded = new ArrayList<>();
        for (Required each : TOOLS.get(tool).required()) {
            String name = version + "/" + tool + "/" + each.name();
            required.add(name);
            Path origin = root.resolve(name + ".origin");
            List<String> missing = new TreeSet<>(each.files()).stream()
                .filter(suffix -> !Files.isRegularFile(root.resolve(name + suffix))).toList();
            if (!Files.isRegularFile(origin) || !firstLine(origin).startsWith("recorded ")) {
                notRecorded.add(name);
            } else if (!missing.isEmpty()) {
                notRecorded.add(name + " (no " + String.join(", ", missing) + ")");
            }
        }
        int[] line = VERSIONS.get(version);
        String subject = line[0] + "." + line[1] + " is " + state + " for "
            + TOOLS.get(tool).capability();
        if (state == SupportState.SUPPORTED && !notRecorded.isEmpty()) {
            return subject + ", but these required cases are not recorded: "
                + String.join(", ", notRecorded);
        }
        if (state != SupportState.SUPPORTED && notRecorded.isEmpty()) {
            return subject + ", but its required cases are recorded (" + String.join(", ", required)
                + "); enable it in the capability matrix in the same change";
        }
        return null;
    }

    /** The simple class names of {@code frames}, a run of {@code n > 1} as {@code Type*n}. */
    private static String summary(List<HelperFrames.Frame> frames) {
        List<String> runs = new ArrayList<>();
        String type = null;
        int count = 0;
        for (HelperFrames.Frame frame : frames) {
            String name = frame.getClass().getSimpleName();
            if (!name.equals(type) && type != null) {
                runs.add(count > 1 ? type + "*" + count : type);
                count = 0;
            }
            type = name;
            count++;
        }
        if (type != null) {
            runs.add(count > 1 ? type + "*" + count : type);
        }
        return String.join(" ", runs);
    }

    /**
     * The suffixes of each case {@code <version>/<tool>/<case>}; files outside the layout go to
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
                if (parts.length != 3 || !VERSIONS.containsKey(parts[0])
                    || !TOOLS.containsKey(parts[1]) || name.isEmpty()
                    || !TOOLS.get(parts[1]).suffixes().contains(suffix)) {
                    violations.add(relative + " belongs to no case");
                    continue;
                }
                cases.computeIfAbsent(parts[0] + "/" + parts[1] + "/" + name,
                    key -> new TreeSet<>()).add(suffix);
            }
        }
        return cases;
    }

    private static String firstLine(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return lines.isEmpty() ? "" : lines.get(0);
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
