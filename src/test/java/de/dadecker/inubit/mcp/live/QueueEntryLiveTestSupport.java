package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.fail;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.adapter.cli.queue.WfspParser;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRow;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent.PartResult;
import de.dadecker.inubit.mcp.domain.model.RawVariable;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import tools.jackson.databind.JsonNode;

/**
 * The offline parts of {@link QueueEntryLiveTest} (feature 020, research D-21), tested by
 * {@link QueueEntryLiveTestSupportTest}: the entry variable, the record directory and its
 * owner-only files, the rows-before/after check, the log check and the recording of the
 * queue-entry helper. Failure messages name variables and fields, never entry content.
 */
final class QueueEntryLiveTestSupport {

    static final String ENTRY_VARIABLE = "INUBIT_LIVE_QUEUE_ENTRY";
    static final String RECORD_DIR_VARIABLE = "INUBIT_LIVE_RECORD_DIR";
    /** The fictitious file name of the negative case: an existing workflow, no such file. */
    static final String NOT_FOUND_FILE_NAME = "Module-0000(0)@0@00000000";
    /**
     * Returned values from this length are checked as substrings; shorter ones (closing tags,
     * numbers, flags) also occur in unrelated log text.
     */
    static final int MIN_CHECKED_CHARS = 10;
    /**
     * Unmasked variable values from this length up to {@link #MIN_CHECKED_CHARS} are checked as
     * tokens; masked values shorter than this are checked as tokens, longer ones as substrings.
     */
    static final int MIN_TOKEN_CHARS = 4;

    /** The {@code processId} pattern of the {@code read_queue_entry} input schema. */
    private static final Pattern PROCESS_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final String ENTRY_FORM = ENTRY_VARIABLE + " must be <processId>[/<fileName>]";

    private QueueEntryLiveTestSupport() {
    }

    /** The entry named by {@code INUBIT_LIVE_QUEUE_ENTRY}; the file name is optional. */
    record Entry(String processId, Optional<String> fileName) {
    }

    /** {@code INUBIT_LIVE_QUEUE_ENTRY}; skips the live test without it. */
    static Entry entry(Map<String, String> environment) {
        String value = environment.get(ENTRY_VARIABLE);
        Assumptions.assumeTrue(value != null && !value.isBlank(), ENTRY_VARIABLE + " is not set"
            + " (<processId>[/<fileName>] of an existing ERROR entry); the queue entry live test"
            + " is skipped");
        int slash = value.indexOf('/');
        String processId = slash < 0 ? value : value.substring(0, slash);
        if (!PROCESS_ID.matcher(processId).matches()) {
            fail(ENTRY_FORM + " with a decimal processId (1 to 19 digits, no leading zero)");
        }
        if (slash < 0) {
            return new Entry(processId, Optional.empty());
        }
        String fileName = value.substring(slash + 1);
        if (!QueueEntryRow.isValidFileName(fileName)) {
            fail(ENTRY_FORM + "; the fileName must have 1 to 255 characters without /, \\ or"
                + " control characters, as read_queue_entry accepts it");
        }
        return new Entry(processId, Optional.of(fileName));
    }

    /**
     * {@code INUBIT_LIVE_RECORD_DIR} as a real path (its nearest existing ancestor resolved, links
     * and letter case included, plus the rest); empty without it. The recording holds raw entry
     * content, so a relative path, one with {@code ..} segments, one inside {@code repository}
     * (also resolved) and one inside any git working tree are refused.
     */
    static Optional<Path> recordDirectory(Map<String, String> environment, Path repository)
        throws IOException {
        String value = environment.get(RECORD_DIR_VARIABLE);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(privateDirectory(RECORD_DIR_VARIABLE, value, repository,
            "the recording holds raw entry content"));
    }

    /**
     * {@code value} of {@code variable} as a real path, refused as {@link #recordDirectory}
     * describes; {@code holds} ends the refusal and says what the directory receives (also used
     * by {@link ServerLogLiveTestSupport}, feature 021).
     */
    static Path privateDirectory(String variable, String value, Path repository, String holds)
        throws IOException {
        Path directory = Path.of(value);
        if (!directory.isAbsolute()) {
            fail(variable + " must be an absolute path to a private directory outside"
                + " the repository");
        }
        for (Path name : directory) {
            if (name.toString().equals("..")) {
                fail(variable + " must not contain .. segments: they would hide where"
                    + " the files land");
            }
        }
        Path existing = directory;
        while (!Files.exists(existing)) {
            existing = existing.getParent();
        }
        Path real = existing.toRealPath().resolve(existing.relativize(directory)).normalize();
        if (real.startsWith(repository.toRealPath())) {
            fail(variable + " must be outside the repository: " + holds);
        }
        if (GitFreeTempDir.insideGitTree(real)) {
            fail(variable + " must not be inside a git working tree: " + holds);
        }
        return real;
    }

    /**
     * Creates {@code directory} and a new owner-only directory of this run below it. Without
     * POSIX permissions or Windows access lists nothing is created.
     */
    static Path runDirectory(PrivateFileSystem files, Path directory) throws IOException {
        return runDirectory(files, directory, "queue-entry-");
    }

    /** As {@link #runDirectory(PrivateFileSystem, Path)} with the run directory {@code prefix}. */
    static Path runDirectory(PrivateFileSystem files, Path directory, String prefix)
        throws IOException {
        try {
            files.createDirectories(directory);
            return files.createTempDirectory(directory, prefix);
        } catch (UnsupportedOperationException e) {
            throw new AssertionError(RECORD_DIR_VARIABLE + " needs owner-only files (POSIX"
                + " permissions or a Windows access list), which this file system does not offer;"
                + " nothing is recorded", e);
        }
    }

    /**
     * Writes {@code helper} into {@code run} as the case files of research D-18:
     * {@code <name>.frames} (its stdout), {@code .exit}, and the raw {@code .data} and
     * {@code .wfsp} it wrote; each owner-only.
     */
    static void record(PrivateFileSystem files, Path run, String name, HelperRun helper)
        throws IOException {
        write(files, run.resolve(name + ".frames"), helper.frames().toByteArray());
        if (helper.exit().isPresent()) {
            write(files, run.resolve(name + ".exit"),
                (helper.exit().get() + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        if (helper.data().isPresent()) {
            write(files, run.resolve(name + ".data"), helper.data().get());
        }
        if (helper.variables().isPresent()) {
            write(files, run.resolve(name + ".wfsp"), helper.variables().get());
        }
    }

    private static void write(PrivateFileSystem files, Path file, byte[] content)
        throws IOException {
        files.createFile(file);
        Files.write(file, content);
    }

    /** The row of {@code entries} with {@code fileName}. */
    static Optional<QueueEntryRow> row(QueueEntries entries, String fileName) {
        return entries.rows().stream().filter(row -> row.fileName().equals(fileName)).findFirst();
    }

    /**
     * SC-004: the row with the file name of {@code before} is still among {@code after} with the
     * same state, {@code startTime} and {@code fileSize}.
     */
    static void assertUnchanged(QueueEntryRow before, QueueEntries after) {
        QueueEntryRow now = row(after, before.fileName()).orElseThrow(() -> new AssertionError(
            "fileName: no queue row of the process has the file name read any more; the entry"
                + " left the Queue Manager or was renamed while it was read (SC-004)"));
        List<String> changed = new ArrayList<>();
        if (now.state() != before.state() || !now.rawState().equals(before.rawState())) {
            changed.add("state");
        }
        if (!now.since().equals(before.since())) {
            changed.add("startTime");
        }
        if (now.fileSize() != before.fileSize()) {
            changed.add("fileSize");
        }
        if (!changed.isEmpty()) {
            fail("The queue entry changed while it was read (SC-004): " + String.join(", ",
                changed));
        }
    }

    /**
     * A value that must not occur in a text: anywhere as a substring, or, if {@code token}, only
     * where it is not adjacent to a letter, digit or underscore (a short value occurs inside
     * unrelated words).
     */
    record Checked(String value, boolean token) {

        private static final String WORD = "[\\p{L}\\p{N}_]";

        boolean occursIn(String text) {
            return token ? Pattern.compile("(?<!" + WORD + ")" + Pattern.quote(value) + "(?!"
                + WORD + ")").matcher(text).find() : text.contains(value);
        }
    }

    /**
     * The values of a {@code read_queue_entry} result that must not appear in the log: the
     * stripped lines of the data text of at least {@link #MIN_CHECKED_CHARS} characters, and of
     * the text values of the variables that are neither system variables nor masked — from
     * {@link #MIN_CHECKED_CHARS} characters as substrings, from {@link #MIN_TOKEN_CHARS} as
     * tokens. A value within the entry's {@code processId} or {@code fileName} is left out: the
     * log names both (FR-018), and a variable may hold the process id.
     */
    static List<Checked> returnedValues(JsonNode result) {
        List<String> logged = List.of(result.path("entry").path("processId").asString(""),
            result.path("entry").path("fileName").asString(""));
        return candidateValues(result).stream()
            .filter(value -> logged.stream().noneMatch(value::occursIn))
            .toList();
    }

    private static List<Checked> candidateValues(JsonNode result) {
        List<Checked> values = new ArrayList<>();
        JsonNode data = result.path("data");
        if (data.path("kind").asString().equals("text")) {
            lines(data.path("text").asString())
                .filter(line -> line.length() >= MIN_CHECKED_CHARS)
                .forEach(line -> values.add(new Checked(line, false)));
        }
        for (JsonNode item : result.path("variables").path("items")) {
            if (!item.path("system").asBoolean() && !item.path("masked").asBoolean()
                && item.path("kind").asString().equals("text")) {
                lines(item.path("value").asString())
                    .filter(line -> line.length() >= MIN_TOKEN_CHARS)
                    .forEach(line -> values.add(new Checked(line,
                        line.length() < MIN_CHECKED_CHARS)));
            }
        }
        return values;
    }

    private static Stream<String> lines(String text) {
        return text.lines().map(String::strip);
    }

    /** Fails if one of {@code values} occurs in {@code log}; the message counts, never quotes. */
    static void assertNotLogged(List<Checked> values, List<String> log) {
        long logged = values.stream().filter(value -> occursIn(value, log)).count();
        if (logged > 0) {
            fail(logged + " of " + values.size() + " returned values appear in the captured log"
                + " (SC-003)");
        }
    }

    /**
     * The original values of the variables {@code result} reports as {@code masked}, from the raw
     * {@code .wfsp} the helper wrote; the result lists the variables in file order. Each value is
     * stripped; a blank one is skipped, one shorter than {@link #MIN_TOKEN_CHARS} characters is
     * checked as a token, any longer one as a substring.
     */
    static List<Checked> maskedValues(JsonNode result, Optional<byte[]> wfsp) {
        JsonNode variables = result.path("variables");
        List<Integer> masked = new ArrayList<>();
        int index = 0;
        for (JsonNode item : variables.path("items")) {
            if (item.path("masked").asBoolean()) {
                masked.add(index);
            }
            index++;
        }
        if (masked.isEmpty()) {
            return List.of();
        }
        String unchecked = "variables: " + masked.size() + " masked variables cannot be checked";
        if (wfsp.isEmpty()) {
            fail(unchecked + ": the helper wrote no variables file");
        }
        if (!(WfspParser.parse(wfsp.get()) instanceof PartResult.Read<List<RawVariable>> read)) {
            fail(unchecked + ": the helper's variables file cannot be parsed");
            return List.of();
        }
        List<RawVariable> raw = read.value();
        if (raw.size() != variables.path("totalCount").asInt()) {
            fail(unchecked + ": the helper's variables file has " + raw.size() + " variables, the"
                + " result reports " + variables.path("totalCount").asInt());
        }
        List<Checked> values = new ArrayList<>();
        for (int i : masked) {
            String value = raw.get(i).value().strip();
            if (!value.isEmpty()) {
                values.add(new Checked(value, value.length() < MIN_TOKEN_CHARS));
            }
        }
        return values;
    }

    /**
     * SC-003: no original value of a masked variable ({@link #maskedValues}) occurs in
     * {@code log} or in a text of {@code result}; the message counts, never quotes.
     */
    static void assertMaskedValuesAbsent(JsonNode result, Optional<byte[]> wfsp,
        List<String> log) {
        List<Checked> values = maskedValues(result, wfsp);
        List<String> texts = texts(result);
        List<String> where = new ArrayList<>();
        if (values.stream().anyMatch(value -> occursIn(value, log))) {
            where.add("the captured log");
        }
        if (values.stream().anyMatch(value -> occursIn(value, texts))) {
            where.add("the result");
        }
        long leaked = values.stream()
            .filter(value -> occursIn(value, log) || occursIn(value, texts)).count();
        if (leaked > 0) {
            fail(leaked + " of " + values.size() + " masked variable values appear in "
                + String.join(" and ", where) + " (SC-003)");
        }
    }

    /**
     * SC-003: no secret registered in {@code scrubber} (node password, trust-store password and
     * their encoded forms), whatever its length, occurs in {@code log} or a text of
     * {@code result}; the message counts, never quotes.
     */
    static void assertNoKnownSecret(SecretScrubber scrubber, JsonNode result, List<String> log) {
        long logged = log.stream().filter(scrubber::containsSecret).count();
        long returned = texts(result).stream().filter(scrubber::containsSecret).count();
        if (logged > 0 || returned > 0) {
            fail(logged + " captured log lines and " + returned + " texts of the result contain"
                + " a known secret (SC-003)");
        }
    }

    private static boolean occursIn(Checked value, List<String> texts) {
        return texts.stream().anyMatch(value::occursIn);
    }

    /** All string values in {@code node}, recursively. */
    private static List<String> texts(JsonNode node) {
        List<String> texts = new ArrayList<>();
        if (node.isString()) {
            texts.add(node.asString());
        }
        for (JsonNode child : node) {
            texts.addAll(texts(child));
        }
        return texts;
    }

    /**
     * The helper run of the {@code read_queue_entry} call: the last of {@code runs}; fails if the
     * call started none, as then neither the masked variables can be checked nor
     * {@code error_entry} recorded.
     */
    static HelperRun readRun(List<HelperRun> runs) {
        if (runs.isEmpty()) {
            fail("no run of the queue-entry helper was captured for read_queue_entry: its masked"
                + " variables cannot be checked and no error_entry is recorded");
        }
        return runs.getLast();
    }

    /**
     * Launches through {@code delegate} and records each run of the queue-entry helper: its
     * stdout (the frames) as it is read, and, once it has exited, its exit code and the raw files
     * it wrote — before the adapter deletes its private directory. The request on stdin (which
     * carries the password) is not recorded.
     */
    static final class HelperRecorder implements ProcessLauncher {

        private static final String TMPDIR = "-Djava.io.tmpdir=";

        private final ProcessLauncher delegate;
        private final List<HelperRun> runs = new CopyOnWriteArrayList<>();

        HelperRecorder(ProcessLauncher delegate) {
            this.delegate = delegate;
        }

        List<HelperRun> runs() {
            return List.copyOf(runs);
        }

        @Override
        public LaunchedProcess launch(LaunchSpec spec) throws IOException {
            LaunchedProcess process = delegate.launch(spec);
            List<String> command = spec.command();
            if (!command.getLast().equals(HelperInstallation.MAIN_CLASS)) {
                return process;
            }
            HelperRun run = new HelperRun();
            runs.add(run);
            return new Recorded(process, run, outDirectory(command));
        }

        /**
         * The helper's out directory: {@link HelperInstallation} places it next to the vendor
         * temporary directory in the call's private directory.
         */
        private static Path outDirectory(List<String> command) {
            String tmpdir = command.stream().filter(argument -> argument.startsWith(TMPDIR))
                .findFirst().orElseThrow(() -> new AssertionError("the helper has no " + TMPDIR));
            return Path.of(tmpdir.substring(TMPDIR.length())).resolveSibling("out");
        }
    }

    /** One recorded run of the queue-entry helper. */
    static final class HelperRun {

        private final ByteArrayOutputStream frames = new ByteArrayOutputStream();
        private volatile Integer exit;
        private volatile byte[] data;
        private volatile byte[] variables;

        /** The helper's stdout as read so far. */
        ByteArrayOutputStream frames() {
            return frames;
        }

        void finished(int exitCode, Optional<byte[]> dataFile, Optional<byte[]> variablesFile) {
            data = dataFile.orElse(null);
            variables = variablesFile.orElse(null);
            exit = exitCode;
        }

        Optional<Integer> exit() {
            return Optional.ofNullable(exit);
        }

        Optional<byte[]> data() {
            return Optional.ofNullable(data);
        }

        Optional<byte[]> variables() {
            return Optional.ofNullable(variables);
        }
    }

    /** A helper process whose stdout and result are copied into its {@link HelperRun}. */
    private static final class Recorded implements ProcessLauncher.LaunchedProcess {

        /** The adapter's file names in the out directory ({@code CliQueueEntryAdapter}). */
        private static final String DATA_FILE = "data.bin";
        private static final String VARIABLES_FILE = "variables.wfsp";

        private final ProcessLauncher.LaunchedProcess process;
        private final HelperRun run;
        private final Path out;
        private final InputStream stdout;

        Recorded(ProcessLauncher.LaunchedProcess process, HelperRun run, Path out) {
            this.process = process;
            this.run = run;
            this.out = out;
            this.stdout = new FilterInputStream(process.stdout()) {
                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) {
                        run.frames().write(b);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = super.read(buffer, offset, length);
                    if (n > 0) {
                        run.frames().write(buffer, offset, n);
                    }
                    return n;
                }
            };
        }

        @Override
        public boolean waitFor(Duration timeout) throws InterruptedException {
            boolean exited = process.waitFor(timeout);
            if (exited && run.exit().isEmpty()) {
                run.finished(process.exitValue(), read(out.resolve(DATA_FILE)),
                    read(out.resolve(VARIABLES_FILE)));
            }
            return exited;
        }

        private static Optional<byte[]> read(Path file) {
            try {
                return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file))
                    : Optional.empty();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public InputStream stdout() {
            return stdout;
        }

        @Override
        public OutputStream stdin() {
            return process.stdin();
        }

        @Override
        public InputStream stderr() {
            return process.stderr();
        }

        @Override
        public int exitValue() {
            return process.exitValue();
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public void destroy() {
            process.destroy();
        }

        @Override
        public boolean treeAlive() {
            return process.treeAlive();
        }

        @Override
        public void destroyForcibly() {
            process.destroyForcibly();
        }
    }
}
