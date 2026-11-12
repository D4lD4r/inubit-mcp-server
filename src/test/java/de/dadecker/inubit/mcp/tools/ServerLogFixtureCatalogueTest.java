package de.dadecker.inubit.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequestTest;
import de.dadecker.inubit.mcp.adapter.cli.log.WebServiceLogClassifier;
import de.dadecker.inubit.mcp.adapter.cli.log.WebServiceLogClassifier.Downloaded;
import de.dadecker.inubit.mcp.adapter.cli.log.WebServiceLogClassifier.Failure;
import de.dadecker.inubit.mcp.adapter.cli.log.WebServiceLogClassifier.Outcome;
import de.dadecker.inubit.mcp.config.CredentialNames;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.config.StoreKind;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LogPresence;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T024 (feature 021, research D-21, D-22): the closed world of the server-log fixtures in
 * {@code fixtures/server-log/} as its {@code README.md} describes it. Every file is the root
 * {@code README.md}, an excerpt case {@code excerpt/<case>.{log,README}} or a case file
 * {@code <version>/<log>/<case>.<ext>} with the suffixes of its log; the case is the file name up
 * to its first dot. Every version case has an {@code .origin} whose first line is
 * {@code recorded <date> <version> neutralized} or {@code reconstructed <source>}. A trace
 * {@code .stdout} that confirms the download names the size of its {@code .log} in MiB, rounded to
 * the decimals it prints (8.1 none, 9.0 three; research "Spike findings"); a web service
 * {@code part log ok <bytes>} frame names the size of its {@code .log} in bytes. For every
 * (version, log) the production capability matrix says
 * {@code SUPPORTED} exactly when {@code <version>/<log>/ok.origin} starts with {@code recorded}.
 * T040: the 8.1 web service cases are classified as their names say. T048: the reconstructed 9.0
 * cases, classified as the 8.1 ones in the {@code REST_90} variant; while its {@code ok} case is
 * not recorded, the production matrix does not support a 9.0 log (FR-017). T051: the 9.0 trace
 * {@code ok} case is recorded.
 */
class ServerLogFixtureCatalogueTest {

    static final Path ROOT = Path.of("src/test/resources/fixtures/server-log");

    private static final Set<String> ROOT_FILES = Set.of("README.md");
    private static final Set<String> EXCERPT_SUFFIXES = Set.of(".log", ".README");
    /** The suffixes of a case per log directory (README "Layout"). */
    private static final Map<String, Set<String>> CASE_SUFFIXES = Map.of(
        "trace", Set.of(".stdout", ".stderr", ".exit", ".log", ".origin"),
        "webservice", Set.of(".frames", ".exit", ".log", ".origin"));
    /** The version directories and their (major, minor). */
    private static final Map<String, int[]> VERSIONS = Map.of("v8_1", new int[] {8, 1},
        "v9_0", new int[] {9, 0});
    private static final Map<String, Capability> LOGS = Map.of("trace",
        Capability.TRACE_LOG_READ, "webservice", Capability.WEBSERVICE_LOG_READ);
    private static final Pattern ORIGIN = Pattern.compile(
        "recorded \\d{4}-\\d{2}-\\d{2} \\d+\\.\\d+(?:\\.\\d+)* neutralized|reconstructed \\S.*");
    private static final Pattern TRACE_DATA =
        Pattern.compile("^Trace data \\((\\d+(?:\\.\\d+)?) MB\\) stored to: .+$");
    private static final Pattern PART_LOG_OK =
        Pattern.compile("^#QE1:[0-9a-f]{32} part log ok (\\d+)$");
    private static final long MIB = 1L << 20;

    @Test
    void everyFileBelongsToACaseWithAValidOrigin() throws IOException {
        assertThat(violations(ROOT)).isEmpty();
        assertThat(cases(ROOT)).as("cases").contains("v8_1/trace/ok", "v8_1/trace/empty",
            "v8_1/trace/missing", "v8_1/trace/login_failure", "v8_1/trace/forbidden",
            "v8_1/webservice/ok", "v8_1/webservice/empty", "v8_1/webservice/missing",
            "v8_1/webservice/forbidden", "v8_1/webservice/not_cluster_mode",
            "v8_1/webservice/api_missing", "v8_1/webservice/garbage_frames",
            "v9_0/trace/ok", "v9_0/trace/empty", "v9_0/trace/missing",
            "v9_0/trace/login_failure", "v9_0/trace/forbidden", "v9_0/webservice/ok",
            "v9_0/webservice/empty", "v9_0/webservice/missing", "v9_0/webservice/forbidden",
            "v9_0/webservice/not_cluster_mode", "v9_0/webservice/api_missing",
            "v9_0/webservice/garbage_frames");
    }

    /** T040, T048: {@code ok} and {@code empty} are the log of INUBIT node {@code node1} in .log. */
    @ParameterizedTest
    @CsvSource({"v8_1, ok", "v8_1, empty", "v9_0, ok", "v9_0, empty"})
    void aWebServiceDownloadCaseIsTheLogOfNode1(String version, String name) throws IOException {
        Path log = ROOT.resolve(version + "/webservice/" + name + ".log");

        assertThat(classifyWebService(version, name))
            .isEqualTo(new Downloaded("node1", size(log), LogPresence.PRESENT));
    }

    /** T040, T048: every failing web service case fails with the code its name stands for. */
    @ParameterizedTest
    @CsvSource({"v8_1, missing, NOT_FOUND", "v8_1, forbidden, FORBIDDEN",
        "v8_1, not_cluster_mode, UNEXPECTED_RESPONSE", "v8_1, api_missing, CLI_UNAVAILABLE",
        "v8_1, garbage_frames, UNEXPECTED_RESPONSE", "v9_0, missing, NOT_FOUND",
        "v9_0, forbidden, FORBIDDEN", "v9_0, not_cluster_mode, UNEXPECTED_RESPONSE",
        "v9_0, api_missing, CLI_UNAVAILABLE", "v9_0, garbage_frames, UNEXPECTED_RESPONSE"})
    void aWebServiceFailureCaseFailsWithTheCodeOfItsName(String version, String name,
        ErrorCode code) throws IOException {
        assertThat(classifyWebService(version, name)).isInstanceOfSatisfying(Failure.class,
            failure -> assertThat(failure.error().code()).isEqualTo(code));
    }

    /**
     * T048 (FR-017, D-22): until the 9.0 web service recording (T052) every 9.0 web service case
     * is reconstructed and the production matrix does not support the 9.0 web service log.
     */
    @Test
    void every90WebServiceCaseIsReconstructedAndTheProductionMatrixDoesNotSupportIt()
        throws IOException {
        List<String> cases = cases(ROOT).stream()
            .filter(name -> name.startsWith("v9_0/webservice/")).toList();

        assertThat(cases).isNotEmpty().allSatisfy(name ->
            assertThat(firstLine(ROOT.resolve(name + ".origin"))).startsWith("reconstructed "));
        assertThat(CapabilityMatrix.production().stateOfLine(9, OptionalInt.of(0),
            Capability.WEBSERVICE_LOG_READ)).isEqualTo(SupportState.UNSUPPORTED);
    }

    /**
     * T051 (FR-017, D-22): the 9.0 trace {@code ok} case is the neutralized T049 recording; the
     * other 9.0 trace cases stay reconstructed, as the recording did not observe them.
     */
    @Test
    void the90TraceOkCaseIsRecordedAndTheProductionMatrixSupportsIt() throws IOException {
        assertThat(firstLine(ROOT.resolve("v9_0/trace/ok.origin")))
            .isEqualTo("recorded 2026-11-12 9.0.10 neutralized");
        assertThat(cases(ROOT).stream().filter(name -> name.startsWith("v9_0/trace/"))
            .filter(name -> !name.equals("v9_0/trace/ok")).toList())
            .isNotEmpty().allSatisfy(name -> assertThat(firstLine(ROOT.resolve(name + ".origin")))
                .startsWith("reconstructed "));
        assertThat(CapabilityMatrix.production().stateOfLine(9, OptionalInt.of(0),
            Capability.TRACE_LOG_READ)).isEqualTo(SupportState.SUPPORTED);
    }

    @Test
    void theMatrixSupportsALogExactlyWhenItsOkCaseIsRecorded() {
        assertThat(matrixViolations(ROOT, CapabilityMatrix.production())).isEmpty();
    }

    /** T033 (FR-017, D-22): the 8.1 trace {@code ok} case is the neutralized T021 recording. */
    @Test
    void the81TraceOkCaseIsRecordedAndTheProductionMatrixSupportsIt() {
        assertThat(firstLine(ROOT.resolve("v8_1/trace/ok.origin")))
            .isEqualTo("recorded 2026-11-11 8.1.17 neutralized");
        assertThat(CapabilityMatrix.production().stateOfLine(8, OptionalInt.of(1),
            Capability.TRACE_LOG_READ)).isEqualTo(SupportState.SUPPORTED);
    }

    /**
     * T046 (FR-017, D-22): the 8.1 web service {@code ok} case is the neutralized T034 recording;
     * the other web service cases stay reconstructed, as the spike did not observe them.
     */
    @Test
    void the81WebServiceOkCaseIsRecordedAndTheProductionMatrixSupportsIt() {
        assertThat(firstLine(ROOT.resolve("v8_1/webservice/ok.origin")))
            .isEqualTo("recorded 2026-11-12 8.1.17 neutralized");
        assertThat(firstLine(ROOT.resolve("v8_1/webservice/missing.origin")))
            .startsWith("reconstructed ");
        assertThat(firstLine(ROOT.resolve("v8_1/webservice/empty.origin")))
            .startsWith("reconstructed ");
        assertThat(CapabilityMatrix.production().stateOfLine(8, OptionalInt.of(1),
            Capability.WEBSERVICE_LOG_READ)).isEqualTo(SupportState.SUPPORTED);
    }

    @Test
    void aMissingOriginFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("server-log"));
        Files.delete(copy.resolve("v8_1/trace/missing.origin"));

        assertThat(violations(copy)).containsExactly("v8_1/trace/missing: no .origin");
    }

    @Test
    void anOriginOfAnInvalidFormatFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("server-log"));
        Files.writeString(copy.resolve("v8_1/trace/empty.origin"), "synthetic\n");
        Files.writeString(copy.resolve("v8_1/trace/forbidden.origin"), "recorded 8.1.17\n");
        Files.writeString(copy.resolve("v8_1/trace/ok.origin"), "reconstructed\n");

        assertThat(violations(copy)).containsExactly(
            "v8_1/trace/empty: the first line of .origin is neither 'recorded <date> <version>"
                + " neutralized' nor 'reconstructed <source>'",
            "v8_1/trace/forbidden: the first line of .origin is neither 'recorded <date>"
                + " <version> neutralized' nor 'reconstructed <source>'",
            "v8_1/trace/ok: the first line of .origin is neither 'recorded <date> <version>"
                + " neutralized' nor 'reconstructed <source>'");
    }

    @Test
    void aFileOfNoCaseFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("server-log"));
        Files.writeString(copy.resolve("notes.txt"), "x\n");
        Files.writeString(copy.resolve("v8_1/trace/ok.frames"), "x\n");
        Files.writeString(copy.resolve("v8_1/ok.origin"), "reconstructed x\n");
        Files.createDirectories(copy.resolve("v9_1/trace"));
        Files.writeString(copy.resolve("v9_1/trace/ok.origin"), "reconstructed x\n");
        Files.createDirectories(copy.resolve("v8_1/system"));
        Files.writeString(copy.resolve("v8_1/system/ok.origin"), "reconstructed x\n");
        Files.writeString(copy.resolve("excerpt/crlf.origin"), "reconstructed x\n");
        Files.writeString(copy.resolve("v8_1/trace/stray.log"), "x\n");

        assertThat(violations(copy)).containsExactlyInAnyOrder(
            "excerpt/crlf.origin belongs to no case",
            "notes.txt belongs to no case",
            "v8_1/ok.origin belongs to no case",
            "v8_1/system/ok.origin belongs to no case",
            "v8_1/trace/ok.frames belongs to no case",
            "v9_1/trace/ok.origin belongs to no case",
            "v8_1/trace/stray: no .origin");
    }

    @Test
    void aTraceMbValueThatDoesNotMatchItsLogFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("server-log"));
        Files.write(copy.resolve("v8_1/trace/empty.log"), new byte[(int) (MIB / 2)]);
        Path stdout = copy.resolve("v8_1/trace/ok.stdout");
        Files.writeString(stdout, Files.readString(stdout).replace("Trace data (0 MB)",
            "Trace data (71 MB)"));
        Path stdout90 = copy.resolve("v9_0/trace/ok.stdout");
        Files.writeString(stdout90, Files.readString(stdout90).replace("Trace data (0.004 MB)",
            "Trace data (0.005 MB)"));

        assertThat(violations(copy)).containsExactlyInAnyOrder(
            "v8_1/trace/empty: .stdout names 0 MB, but .log has " + MIB / 2 + " bytes (1 MiB)",
            "v8_1/trace/ok: .stdout names 71 MB, but .log has "
                + Files.size(copy.resolve("v8_1/trace/ok.log")) + " bytes (0 MiB)",
            "v9_0/trace/ok: .stdout names 0.005 MB, but .log has "
                + Files.size(copy.resolve("v9_0/trace/ok.log")) + " bytes (0.004 MiB)");
    }

    @Test
    void aWebServiceByteCountThatDoesNotMatchItsLogFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("server-log"));
        Files.writeString(copy.resolve("v8_1/webservice/empty.log"), "x\n");

        assertThat(violations(copy)).containsExactly(
            "v8_1/webservice/empty: .frames names 0 bytes, but .log has 2 bytes");
    }

    @Test
    void theMatrixRuleFailsInBothDirections(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("server-log"));
        Files.writeString(copy.resolve("v8_1/trace/ok.origin"),
            "recorded 2026-11-12 8.1.17 neutralized\n");

        assertThat(matrixViolation(copy, "v8_1", "trace", SupportState.UNSUPPORTED))
            .contains("8.1 is UNSUPPORTED for TRACE_LOG_READ, but v8_1/trace/ok.origin starts"
                + " with 'recorded'; enable it in the capability matrix in the same change");
        assertThat(matrixViolation(copy, "v8_1", "trace", SupportState.SUPPORTED)).isEmpty();

        Files.writeString(copy.resolve("v8_1/trace/ok.origin"), "reconstructed x\n");
        assertThat(matrixViolation(copy, "v8_1", "trace", SupportState.UNSUPPORTED)).isEmpty();
        assertThat(matrixViolation(copy, "v8_1", "trace", SupportState.SUPPORTED))
            .contains("8.1 is SUPPORTED for TRACE_LOG_READ, but v8_1/trace/ok.origin does not"
                + " start with 'recorded'");
        assertThat(matrixViolation(copy, "v9_0", "webservice", SupportState.SUPPORTED))
            .contains("9.0 is SUPPORTED for WEBSERVICE_LOG_READ, but"
                + " v9_0/webservice/ok.origin does not start with 'recorded'");
    }

    /** The cases below {@code root} as {@code <version>/<log>/<case>}, sorted. */
    static Set<String> cases(Path root) throws IOException {
        return new TreeSet<>(filesByCase(root, new ArrayList<>()).keySet());
    }

    /** Every violation of the layout, provenance and MB rules of the class description. */
    static List<String> violations(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        filesByCase(root, violations).forEach((name, suffixes) -> {
            if (!suffixes.contains(".origin")) {
                violations.add(name + ": no .origin");
            } else if (!ORIGIN.matcher(firstLine(root.resolve(name + ".origin"))).matches()) {
                violations.add(name + ": the first line of .origin is neither 'recorded <date>"
                    + " <version> neutralized' nor 'reconstructed <source>'");
            }
            if (suffixes.contains(".stdout") && suffixes.contains(".log")) {
                mbViolation(root, name).ifPresent(violations::add);
            }
            if (suffixes.contains(".frames") && suffixes.contains(".log")) {
                bytesViolation(root, name).ifPresent(violations::add);
            }
        });
        return violations;
    }

    /** Every (version, log) whose matrix state and {@code ok.origin} do not fit (D-22). */
    static List<String> matrixViolations(Path root, CapabilityMatrix matrix) {
        List<String> violations = new ArrayList<>();
        for (String version : new TreeSet<>(VERSIONS.keySet())) {
            int[] line = VERSIONS.get(version);
            for (String log : new TreeSet<>(LOGS.keySet())) {
                matrixViolation(root, version, log,
                    matrix.stateOfLine(line[0], OptionalInt.of(line[1]), LOGS.get(log)))
                    .ifPresent(violations::add);
            }
        }
        return violations;
    }

    /** The violation of one (version, log) in {@code state}; empty if they fit. */
    static Optional<String> matrixViolation(Path root, String version, String log,
        SupportState state) {
        Path origin = root.resolve(version + "/" + log + "/ok.origin");
        boolean recorded = Files.isRegularFile(origin) && firstLine(origin).startsWith("recorded ");
        int[] line = VERSIONS.get(version);
        String subject = line[0] + "." + line[1] + " is " + state + " for " + LOGS.get(log);
        String file = version + "/" + log + "/ok.origin";
        if (state == SupportState.SUPPORTED && !recorded) {
            return Optional.of(subject + ", but " + file + " does not start with 'recorded'");
        }
        if (state != SupportState.SUPPORTED && recorded) {
            return Optional.of(subject + ", but " + file + " starts with 'recorded'; enable it"
                + " in the capability matrix in the same change");
        }
        return Optional.empty();
    }

    /**
     * The MB value of a trace confirmation that is not the {@code .log} size in MiB, rounded to
     * the decimals of the value.
     */
    private static Optional<String> mbViolation(Path root, String name) {
        for (String line : lines(root.resolve(name + ".stdout"))) {
            Matcher matcher = TRACE_DATA.matcher(line);
            if (matcher.matches()) {
                long size = size(root.resolve(name + ".log"));
                BigDecimal named = new BigDecimal(matcher.group(1));
                BigDecimal mib = BigDecimal.valueOf(size).divide(BigDecimal.valueOf(MIB),
                    named.scale(), RoundingMode.HALF_UP);
                if (named.compareTo(mib) != 0) {
                    return Optional.of(name + ": .stdout names " + named
                        + " MB, but .log has " + size + " bytes (" + mib + " MiB)");
                }
            }
        }
        return Optional.empty();
    }

    /** The byte count of a {@code part log ok} frame that is not the {@code .log} size. */
    private static Optional<String> bytesViolation(Path root, String name) {
        for (String line : lines(root.resolve(name + ".frames"))) {
            Matcher matcher = PART_LOG_OK.matcher(line);
            if (matcher.matches()) {
                long size = size(root.resolve(name + ".log"));
                long named = Long.parseLong(matcher.group(1));
                if (named != size) {
                    return Optional.of(name + ": .frames names " + named
                        + " bytes, but .log has " + size + " bytes");
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The web service case {@code name} of {@code version} replayed through the classifier
     * (research D-4), for a request in the version's variant ({@code SOAP_81}, {@code REST_90}).
     */
    private static Outcome classifyWebService(String version, String name) throws IOException {
        Path frames = ROOT.resolve(version + "/webservice/" + name + ".frames");
        int exit = Integer.parseInt(firstLine(ROOT.resolve(version + "/webservice/" + name
            + ".exit")));
        CliResult result = new CliResult(exit, Files.readString(frames, StandardCharsets.UTF_8),
            "", Duration.ofSeconds(1), false, 1 << 20);
        SecretLabels labels = new SecretLabels.Store(
            new CredentialNames("INUBIT_ACME", NodeId.parse("test/node1")), StoreKind.KEYCHAIN);
        int[] line = VERSIONS.get(version);
        String variant = line[0] == 9 ? "REST_90" : "SOAP_81";
        return new WebServiceLogClassifier(new SecretScrubber(), labels).classify(
            HelperRequestTest.serverLogRequest(r -> r.variant = variant), result,
            Path.of("/opt/acme/inubit-" + line[0] + "." + line[1] + "/client"),
            new MajorMinor(line[0], line[1]));
    }

    /**
     * The suffixes of each case ({@code <version>/<log>/<case>} or {@code excerpt/<case>});
     * files outside the layout go to {@code violations}. Excerpt cases are documented by their
     * {@code .README} and carry no {@code .origin}, so they are left out of the result.
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
                if (parts.length == 2 && parts[0].equals("excerpt") && !name.isEmpty()
                    && EXCERPT_SUFFIXES.contains(suffix)) {
                    continue;
                }
                if (parts.length != 3 || !VERSIONS.containsKey(parts[0])
                    || !CASE_SUFFIXES.containsKey(parts[1]) || name.isEmpty()
                    || !CASE_SUFFIXES.get(parts[1]).contains(suffix)) {
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

    private static long size(Path file) {
        try {
            return Files.size(file);
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
