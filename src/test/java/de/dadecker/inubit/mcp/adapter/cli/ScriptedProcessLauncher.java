package de.dadecker.inubit.mcp.adapter.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test double for {@link ProcessLauncher} that answers a <em>sequence</em> of StartCLI launches
 * (feature 004, research D-22), e.g. export → import → export.
 *
 * <p>The script is a list of steps in launch order. Each step matches the {@code --execCommand}
 * line of the next launch by a prefix ({@link #expect(String)}) or a regular expression
 * ({@link #expect(Pattern)}), answers with a response ({@code stdout}, {@code stderr}, exit code;
 * a recorded fixture case or inline) and may run an action with the launch before the process
 * "runs" (e.g. {@link #writingExportFile(byte[])}). A step can {@link #hanging() hang} until it
 * is destroyed, so that the runner's timeout fires.
 *
 * <p>Every launch is recorded ({@link #launches()}). A launch that does not match the next step,
 * or comes after the last one, throws an {@link AssertionError} naming the command — a write
 * that should not happen fails the test — and is also kept in {@link #unexpected()}, so that
 * {@link #verifyComplete()} reports it even if the code under test swallowed the error.
 *
 * <p>{@link #throughTheLaunch(ProcessLauncher, Path)} (feature 013 T027) keeps the script but
 * starts every matched launch for real: the step's actions still run in the test JVM before the
 * start, and its answer is handed to the substitute StartCLI ({@code ArgvRecorder}) through the
 * environment variable {@code ARGV_RECORDER_REPLY}, which prints it and exits with its code. So
 * the command line, its quoting, stdin, the output's line ends and the exit code take the real
 * way through the interpreter and the operating system.
 */
public final class ScriptedProcessLauncher implements ProcessLauncher {

    private static final Pattern EXPORT_FILE = Pattern.compile("--exportFile '([^']+)'");
    private static final Pattern IMPORT_FILE = Pattern.compile("--importFile '([^']+)'");

    private final Deque<Step> steps = new ArrayDeque<>();
    private final List<Step> script = new ArrayList<>();
    private final List<Launch> launches = new CopyOnWriteArrayList<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();
    private final ProcessLauncher real;
    private final Path replies;

    /** Answers every launch with a simulated process. */
    public ScriptedProcessLauncher() {
        this(null, null);
    }

    private ScriptedProcessLauncher(ProcessLauncher real, Path replies) {
        this.real = real;
        this.replies = replies;
    }

    /**
     * Starts every matched launch with {@code real} (a launcher of a substitute StartCLI), its
     * answer written into a directory of its own below {@code replies}. Hanging steps are not
     * supported.
     */
    public static ScriptedProcessLauncher throughTheLaunch(ProcessLauncher real, Path replies) {
        return new ScriptedProcessLauncher(java.util.Objects.requireNonNull(real, "real"),
            java.util.Objects.requireNonNull(replies, "replies"));
    }

    /** The next launch's command line must start with {@code prefix}. */
    public ScriptedProcessLauncher expect(String prefix) {
        return add(new Step(prefix, line -> line.startsWith(prefix)));
    }

    /** The next launch's command line must match {@code pattern} completely. */
    public ScriptedProcessLauncher expect(Pattern pattern) {
        return add(new Step(pattern.pattern(), line -> pattern.matcher(line).matches()));
    }

    /** Answers the last expected step with {@code fixtures/v8_1/cli/<fixtureCase>.*}. */
    public ScriptedProcessLauncher replying(String fixtureCase) {
        return replying(FakeProcessLauncher.fixtureText(fixtureCase + ".stdout"),
            FakeProcessLauncher.fixtureText(fixtureCase + ".stderr"),
            Integer.parseInt(FakeProcessLauncher.fixtureText(fixtureCase + ".exit").strip()));
    }

    /** Answers the last expected step with the given output and exit code. */
    public ScriptedProcessLauncher replying(String stdout, String stderr, int exitCode) {
        Step step = last();
        step.stdout = stdout.getBytes(StandardCharsets.UTF_8);
        step.stderr = stderr.getBytes(StandardCharsets.UTF_8);
        step.exitCode = exitCode;
        return this;
    }

    /**
     * Answers the last expected step with an exit code 0, the usual stderr line and a stdout
     * computed at launch (after the step's actions), e.g. an import protocol of a fake server.
     */
    public ScriptedProcessLauncher replyingWith(Function<LaunchSpec, String> stdout) {
        Step step = last();
        step.dynamicStdout = stdout;
        step.stderr = "Picked up JAVA_TOOL_OPTIONS: -Duser.language=en -Duser.country=US\n"
            .getBytes(StandardCharsets.UTF_8);
        step.exitCode = 0;
        return this;
    }

    /** A whole answer: stdout, stderr and exit code. */
    public record Reply(String stdout, String stderr, int exitCode) {
    }

    /**
     * Answers the last expected step with a reply computed at launch (after the step's
     * actions), e.g. a fake server's export or its {@code NOT_FOUND}.
     */
    public ScriptedProcessLauncher answering(Function<LaunchSpec, Reply> reply) {
        last().dynamicReply = reply;
        return this;
    }

    /** The last expected step never exits until it is destroyed (timeout cases). */
    public ScriptedProcessLauncher hanging() {
        last().hang = true;
        return this;
    }

    /** Runs {@code action} with the launch of the last expected step before it "runs". */
    public ScriptedProcessLauncher then(Consumer<ProcessLauncher.LaunchSpec> action) {
        Step step = last();
        step.action = step.action.andThen(action);
        return this;
    }

    /** The file named by {@code --importFile} of a launch (for fake servers). */
    public static Path importFile(LaunchSpec spec) {
        return file(IMPORT_FILE, execCommand(spec));
    }

    /** The file named by {@code --exportFile} of a launch (for fake servers). */
    public static Path exportFile(LaunchSpec spec) {
        return file(EXPORT_FILE, execCommand(spec));
    }

    /** Writes {@code zip} to the {@code --exportFile} of the launch, as StartCLI does. */
    public ScriptedProcessLauncher writingExportFile(byte[] zip) {
        byte[] bytes = zip.clone();
        return then(spec -> {
            try {
                Files.write(file(EXPORT_FILE, execCommand(spec)), bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Keeps a copy of the {@code --importFile} at launch time ({@link Launch#importFile()}); the
     * runner deletes the file afterwards.
     */
    public ScriptedProcessLauncher capturingImportFile() {
        last().captureImport = true;
        return this;
    }

    @Override
    public LaunchedProcess launch(LaunchSpec spec) {
        String line = execCommand(spec);
        Step step;
        synchronized (steps) {
            step = steps.peekFirst();
            if (step == null || !step.matcher.test(line)) {
                unexpected.add(line);
                throw new AssertionError("unexpected StartCLI command: " + line
                    + (step == null ? " (no further launch was scripted)"
                        : " (expected: " + step.expected + ")"));
            }
            steps.removeFirst();
        }
        step.action.accept(spec);
        byte[] importFile = step.captureImport ? read(file(IMPORT_FILE, line)) : null;
        byte[] stdout = step.dynamicStdout == null ? step.stdout
            : step.dynamicStdout.apply(spec).getBytes(StandardCharsets.UTF_8);
        byte[] stderr = step.stderr;
        int exitCode = step.exitCode;
        if (step.dynamicReply != null) {
            Reply reply = step.dynamicReply.apply(spec);
            stdout = reply.stdout().getBytes(StandardCharsets.UTF_8);
            stderr = reply.stderr().getBytes(StandardCharsets.UTF_8);
            exitCode = reply.exitCode();
        }
        if (real != null) {
            try {
                return started(spec, line, importFile, step, new Reply(new String(stdout,
                    StandardCharsets.UTF_8), new String(stderr, StandardCharsets.UTF_8),
                    exitCode));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        FakeProcessLauncher.FakeProcess process = new FakeProcessLauncher.FakeProcess(spec,
            stdout, stderr, exitCode, step.hang, false, false, -1);
        launches.add(new Launch(spec, line, process, importFile, null));
        return process;
    }

    private LaunchedProcess started(LaunchSpec spec, String line, byte[] importFile, Step step,
        Reply reply) throws IOException {
        if (step.hang) {
            throw new IllegalStateException("a hanging step is not started for real: " + line);
        }
        Path directory = Files.createDirectories(replies.resolve(String.valueOf(
            launches.size() + 1)));
        Files.writeString(directory.resolve("stdout"), reply.stdout(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("stderr"), reply.stderr(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("exit"), String.valueOf(reply.exitCode()),
            StandardCharsets.UTF_8);
        java.util.Map<String, String> environment = new java.util.HashMap<>(spec.environment());
        environment.put("ARGV_RECORDER_REPLY", directory.toString());
        LaunchedProcess process = real.launch(new LaunchSpec(spec.command(), environment,
            spec.workingDirectory(), spec.startCliArguments()));
        launches.add(new Launch(spec, line, null, importFile, directory));
        return process;
    }

    /** The {@code --execCommand} lines of all matched launches, in order. */
    public List<String> execCommands() {
        return launches.stream().map(Launch::execCommand).toList();
    }

    /** All matched launches, in order. */
    public List<Launch> launches() {
        return List.copyOf(launches);
    }

    /** The command lines that did not match the script. */
    public List<String> unexpected() {
        return List.copyOf(unexpected);
    }

    /** Fails if a launch was unexpected or a scripted launch did not happen. */
    public void verifyComplete() {
        if (!unexpected.isEmpty()) {
            throw new AssertionError("unexpected StartCLI command(s): " + unexpected);
        }
        synchronized (steps) {
            if (!steps.isEmpty()) {
                throw new AssertionError(steps.size() + " scripted launch(es) did not happen: "
                    + steps.stream().map(step -> step.expected).toList());
            }
        }
    }

    /** The {@code --execCommand} value of a launch, from its StartCLI arguments. */
    public static String execCommand(LaunchSpec spec) {
        List<String> command = spec.startCliArguments();
        int index = command.indexOf("--execCommand");
        if (index < 0 || index + 1 >= command.size()) {
            throw new AssertionError("launch without --execCommand: " + spec);
        }
        return command.get(index + 1);
    }

    private ScriptedProcessLauncher add(Step step) {
        script.add(step);
        steps.addLast(step);
        return this;
    }

    private Step last() {
        if (script.isEmpty()) {
            throw new IllegalStateException("call expect(…) first");
        }
        return script.get(script.size() - 1);
    }

    private static Path file(Pattern option, String line) {
        Matcher matcher = option.matcher(line);
        if (!matcher.find()) {
            throw new AssertionError("no " + option.pattern() + " in: " + line);
        }
        return Path.of(matcher.group(1));
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * One matched launch: its simulated {@code process}, or (started for real) the
     * {@code replyDirectory} with its answer and the substitute's record of it.
     */
    public record Launch(LaunchSpec spec, String execCommand,
        FakeProcessLauncher.FakeProcess process, byte[] importFile, Path replyDirectory) {

        /** The captured import archive, if the step captured it. */
        @Override
        public byte[] importFile() {
            return Optional.ofNullable(importFile).map(byte[]::clone).orElse(null);
        }
    }

    /** One scripted launch. */
    private static final class Step {
        private final String expected;
        private final Predicate<String> matcher;
        private byte[] stdout = new byte[0];
        private byte[] stderr = new byte[0];
        private int exitCode;
        private boolean hang;
        private boolean captureImport;
        private Consumer<LaunchSpec> action = spec -> { };
        private Function<LaunchSpec, String> dynamicStdout;
        private Function<LaunchSpec, Reply> dynamicReply;

        Step(String expected, Predicate<String> matcher) {
            this.expected = expected;
            this.matcher = matcher;
        }
    }
}
