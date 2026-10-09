package de.dadecker.inubit.mcp.adapter.git;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.HistoryEntry;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The workspace history through the system {@code git} command (research D-1, FR-006, FR-007).
 *
 * <ul>
 *   <li>Argument arrays only, never a shell; the working directory is the workspace root.
 *   <li>Every call carries {@code -c core.hooksPath=<empty directory>}, {@code -c
 *       commit.gpgsign=false}, {@code -c core.autocrlf=false}, {@code -c core.quotepath=false},
 *       {@code -c gc.autoDetach=false}, {@code -c maintenance.autoDetach=false}, {@code -c
 *       user.name=INUBIT MCP (<profile>)} and {@code -c user.email=inubit-mcp@localhost}, so
 *       that hooks, signing, line-ending conversion and the identity of the person's git
 *       configuration have no effect; commits also pass {@code --no-verify}.
 *   <li>The automatic maintenance a command triggers ({@code git maintenance run --auto}, which
 *       runs {@code gc --auto} or a geometric repack depending on the git version) runs in the
 *       foreground of that call instead of detaching. A detached maintenance outlives the call,
 *       still writes below {@code .git/objects} while the next call runs and so breaks the
 *       serialization by the workspace lock. {@code maintenance.autoDetach} is set as well
 *       because git prefers it over {@code gc.autoDetach}, also when it comes from the
 *       repository's own configuration. Automatic maintenance stays enabled (rather than
 *       {@code gc.auto=0}) so that the workspace does not grow loose objects without bound. In
 *       production a commit roughly every 6700 new objects takes a few seconds longer (measured
 *       about 2.5 s for 8000 loose objects in a workspace of 40000 XML files, 1 s for an
 *       all-into-one repack of 53 packs), well within the timeout; should it exceed the timeout,
 *       the commit is already recorded, the call reports {@code TIMEOUT} and the stopped
 *       maintenance leaves the repository intact.
 *   <li>The environment is minimal: {@code PATH} of the server plus
 *       {@code GIT_TERMINAL_PROMPT=0}, {@code GIT_CONFIG_NOSYSTEM=1} and
 *       {@code GIT_CONFIG_GLOBAL=/dev/null} (no system or global configuration, e.g. no
 *       {@code core.fsmonitor} or templates of the person), {@code GIT_LITERAL_PATHSPECS=1}
 *       (paths are never patterns) and {@code LC_ALL=C}. Needs git 2.32 or newer
 *       ({@code GIT_CONFIG_GLOBAL}; {@code init -b} needs 2.28).
 *   <li>Each call is stopped after the timeout (30 s) and reported as {@code TIMEOUT}; a failing
 *       call is {@code PRECONDITION_FAILED} with git's error output as excerpt; a missing
 *       {@code git} is {@code PRECONDITION_FAILED} "git not found", any other start failure
 *       {@code PRECONDITION_FAILED} "git could not be started".
 *   <li>There is no operation that configures a remote or transmits the history (no remote,
 *       push, fetch, pull or clone).
 * </ul>
 *
 * <p>Not thread-safe: the workspace lock serializes the callers.
 */
public final class GitCli implements VersionHistoryPort {

    static final Duration TIMEOUT = Duration.ofSeconds(30);
    static final List<String> IGNORED = List.of(".tests/", ".reports/", ".lock");
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{7,40}$");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern TRAILER_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9-]{0,63}$");
    private static final Pattern TRAILER_VALUE = Pattern.compile("^[^\\p{Cntrl}]{1,200}$");
    /** Field and record separators of the {@code git log} format (unit and record separator). */
    private static final String FIELD = "%x1f";
    private static final String RECORD = "%x1e";
    private static final String FIELD_CHAR = "\u001f";
    private static final String RECORD_CHAR = "\u001e";
    private static final Duration KILL_GRACE = Duration.ofSeconds(2);
    private static final long MAX_OUTPUT_BYTES = 64L << 20;

    private final Path root;
    private final String profile;
    private final ProcessLauncher launcher;
    private final String executable;
    private final Duration timeout;
    private final Map<String, String> environment;
    private Path emptyHooks;

    /** Runs the {@code git} found on {@code PATH} with the 30 s timeout. */
    public GitCli(Path root, String profile, ProcessLauncher launcher,
        Map<String, String> parentEnvironment) {
        this(root, profile, launcher, parentEnvironment, "git", TIMEOUT);
    }

    /** The bound of one git call. */
    Duration timeout() {
        return timeout;
    }

    GitCli(Path root, String profile, ProcessLauncher launcher,
        Map<String, String> parentEnvironment, String executable, Duration timeout) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (!ProfileInfo.isValidName(profile)) {
            throw new IllegalArgumentException("Invalid profile name for the git identity");
        }
        this.profile = profile;
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.executable = Objects.requireNonNull(executable, "executable");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        Map<String, String> env = new LinkedHashMap<>();
        Optional.ofNullable(parentEnvironment.get("PATH")).ifPresent(path -> env.put("PATH", path));
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_LITERAL_PATHSPECS", "1");
        env.put("LC_ALL", "C");
        this.environment = Map.copyOf(env);
    }

    /** Creates the repository and its {@code .gitignore} if missing; idempotent. */
    @Override
    public void init() {
        if (!Files.isDirectory(root.resolve(".git"))) {
            run("init", "-q", "-b", "main");
        }
        Path gitignore = root.resolve(".gitignore");
        try {
            List<String> lines = Files.exists(gitignore)
                ? new ArrayList<>(Files.readAllLines(gitignore, StandardCharsets.UTF_8))
                : new ArrayList<>();
            List<String> missing = IGNORED.stream().filter(entry -> !lines.contains(entry))
                .toList();
            if (!missing.isEmpty()) {
                lines.addAll(missing);
                Files.writeString(gitignore, String.join("\n", lines) + "\n",
                    StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw unusable("The workspace " + root + " cannot be prepared ("
                + e.getClass().getSimpleName() + ")", null);
        }
    }

    @Override
    public List<PathChange> status() {
        String output = run("status", "--porcelain=v1", "-z", "--untracked-files=all",
            "--no-renames");
        List<PathChange> changes = new ArrayList<>();
        for (String entry : output.split("\0")) {
            if (entry.length() < 4) {
                continue;
            }
            String code = entry.substring(0, 2);
            String path = entry.substring(3);
            PathChange.Kind kind = code.equals("??") || code.indexOf('A') >= 0
                ? PathChange.Kind.ADDED
                : code.indexOf('D') >= 0 ? PathChange.Kind.DELETED : PathChange.Kind.MODIFIED;
            changes.add(new PathChange(path, kind));
        }
        return List.copyOf(changes);
    }

    /**
     * Records every change with {@code message}; the trailers (feature 004, research D-3) follow
     * as the last paragraph, {@code <key>: <value>} per line, so that {@code git log
     * --format=%(trailers)} reads them.
     *
     * @throws IllegalArgumentException for a message with control characters (a line break
     *     could forge a trailer paragraph, review I1), a trailer key that is not a single token
     *     or a value that is empty or contains control characters
     */
    @Override
    public Optional<HistoryEntry> commitAll(String message, Map<String, String> trailers) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(trailers, "trailers");
        if (CONTROL.matcher(message).find()) {
            throw new IllegalArgumentException("A history message is one line without control"
                + " characters");
        }
        StringBuilder full = new StringBuilder(message);
        if (!trailers.isEmpty()) {
            full.append("\n");
            trailers.forEach((key, value) -> {
                if (key == null || !TRAILER_KEY.matcher(key).matches() || value == null
                    || !TRAILER_VALUE.matcher(value).matches()) {
                    throw new IllegalArgumentException("A trailer is a token key and a"
                        + " non-empty value without control characters");
                }
                full.append("\n").append(key).append(": ").append(value);
            });
        }
        run("add", "-A");
        List<PathChange> changes = parseNameStatus(run("diff", "--cached", "--name-status",
            "-z", "--no-renames"));
        if (changes.isEmpty()) {
            return Optional.empty();
        }
        run("commit", "-q", "--no-verify", "-m", full.toString());
        String commit = run("rev-parse", "--short", "HEAD").strip();
        return Optional.of(new HistoryEntry(commit, message, changes));
    }

    /** {@code --name-status -z --no-renames} output: status and path, NUL-separated. */
    private static List<PathChange> parseNameStatus(String output) {
        String[] fields = output.split("\0");
        List<PathChange> changes = new ArrayList<>();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            if (fields[i].isEmpty()) {
                continue;
            }
            PathChange.Kind kind = switch (fields[i].charAt(0)) {
                case 'A' -> PathChange.Kind.ADDED;
                case 'D' -> PathChange.Kind.DELETED;
                default -> PathChange.Kind.MODIFIED;
            };
            changes.add(new PathChange(fields[i + 1], kind));
        }
        return List.copyOf(changes);
    }

    /**
     * Restores {@code subtree} to {@code HEAD} (D-9): changed and deleted files come back,
     * files added since are removed; ignored files are left alone.
     *
     * @throws IllegalArgumentException if {@code subtree} is empty, absolute, leaves the
     *     workspace or is inside {@code .git}
     */
    @Override
    public void restore(Path subtree) {
        String pathspec = checkSubtree(subtree);
        boolean tracked = !run(true, "rev-parse", "--verify", "-q", "HEAD").isEmpty()
            && !run("ls-tree", "-r", "--name-only", "HEAD", "--", pathspec).isEmpty();
        if (tracked) {
            run("restore", "--source=HEAD", "--staged", "--worktree", "--", pathspec);
        } else {
            run("rm", "-r", "-q", "--cached", "--ignore-unmatch", "--", pathspec);
        }
        run("clean", "-f", "-d", "-q", "--", pathspec);
    }

    /**
     * Walks the entries that touched {@code path}, newest first, and returns the first that
     * records a server state of {@code group}: its {@code Server-State} trailer names the group,
     * or it has no such trailer and its subject is a feature-003 export of the group
     * ({@code export <group>/<node>: …}, research D-25).
     */
    @Override
    public Optional<String> lastServerState(GroupId group, String path) {
        Objects.requireNonNull(group, "group");
        String pathspec = checkPath(path);
        if (!hasCommits()) {
            return Optional.empty();
        }
        String output = run("log", "--format=%H" + FIELD + "%s" + FIELD + "%(trailers:key="
            + SERVER_STATE + ",valueonly,separator=%x1d)" + RECORD, "--", pathspec);
        String exportPrefix = "export " + group.value() + "/";
        for (String record : output.split(RECORD_CHAR)) {
            // not strip(): the separators count as white space in Java
            String[] fields = record.replaceFirst("^\n+", "").split(FIELD_CHAR, -1);
            if (fields.length < 3 || fields[0].isEmpty()) {
                continue;
            }
            if (isServerState(group, fields[1], fields[2])) {
                return Optional.of(fields[0]);
            }
        }
        return Optional.empty();
    }

    /**
     * An entry records a server state of {@code group} if its {@code Server-State} trailer names
     * the group, or, without any such trailer, its subject is a feature-003 export of the group.
     */
    private static boolean isServerState(GroupId group, String subject, String trailers) {
        List<String> states = trailers.isBlank() ? List.of()
            : Stream.of(trailers.split("\u001d")).map(String::strip).toList();
        return states.contains(group.value())
            || states.isEmpty() && subject.startsWith("export " + group.value() + "/");
    }

    /**
     * One walk over the entries that touched {@code subtree}, newest first: a file's newest entry
     * decides whether it is a local change, its newest server-state entry is its base.
     */
    @Override
    public List<LocalChange> localChanges(GroupId group, String subtree) {
        Objects.requireNonNull(group, "group");
        String pathspec = checkPath(subtree);
        if (!hasCommits()) {
            return List.of();
        }
        String output = run("log", "--format=" + RECORD + "%H" + FIELD + "%s" + FIELD
            + "%(trailers:key=" + SERVER_STATE + ",valueonly,separator=%x1d)" + FIELD,
            "--name-status", "-z", "--no-renames", "--", pathspec);
        Map<String, String> newestStatus = new LinkedHashMap<>();
        Map<String, Boolean> newestIsState = new HashMap<>();
        Map<String, String> states = new HashMap<>();
        for (String record : output.split(RECORD_CHAR)) {
            String[] fields = record.split(FIELD_CHAR, 4);
            if (fields.length < 4 || fields[0].isEmpty()) {
                continue;
            }
            boolean state = isServerState(group, fields[1], fields[2]);
            List<String> tokens = Stream.of(fields[3].split("\0"))
                .map(token -> token.replaceFirst("^\n+", "")).filter(t -> !t.isEmpty())
                .toList();
            for (int i = 0; i + 1 < tokens.size(); i += 2) {
                String path = tokens.get(i + 1);
                if (!newestStatus.containsKey(path)) {
                    newestStatus.put(path, tokens.get(i));
                    newestIsState.put(path, state);
                }
                if (state) {
                    // a server state that removed the file means: no longer on the server
                    states.putIfAbsent(path, tokens.get(i).startsWith("D") ? "" : fields[0]);
                }
            }
        }
        List<LocalChange> changes = new ArrayList<>();
        newestStatus.forEach((path, status) -> {
            if (newestIsState.get(path)) {
                return;
            }
            Optional<String> base = Optional.ofNullable(states.get(path))
                .filter(commit -> !commit.isEmpty());
            boolean deleted = status.startsWith("D");
            if (deleted && base.isEmpty()) {
                return; // added and removed again locally
            }
            changes.add(new LocalChange(path, deleted ? PathChange.Kind.DELETED
                : base.isEmpty() ? PathChange.Kind.ADDED : PathChange.Kind.MODIFIED, base));
        });
        return List.copyOf(changes);
    }

    /**
     * {@code git cat-file blob <commit>:<path>}; a missing file or a directory is empty.
     *
     * @throws IllegalArgumentException if {@code commit} is not a commit of this history
     */
    @Override
    public Optional<byte[]> show(String commit, String path) {
        String object = checkCommit(commit) + ":" + checkPath(path);
        if (execute(true, "cat-file", "-e", commit + "^{commit}") == null) {
            throw new IllegalArgumentException("The commit is not in the workspace history");
        }
        String type = run(true, "cat-file", "-t", object).strip();
        if (!type.equals("blob")) {
            return Optional.empty();
        }
        return Optional.of(runBytes("cat-file", "blob", object));
    }

    /** {@code git diff --name-status --no-renames <fromCommit> HEAD -- <subtree>}. */
    @Override
    public List<PathChange> changedPaths(String fromCommit, String subtree) {
        String from = checkCommit(fromCommit);
        String pathspec = checkPath(subtree);
        String output = run("diff", "--name-status", "-z", "--no-renames", from, "HEAD", "--",
            pathspec);
        return parseNameStatus(output);
    }

    private boolean hasCommits() {
        return !run(true, "rev-parse", "--verify", "-q", "HEAD").isEmpty();
    }

    /** A commit id of this history: 7 to 40 lower-case hex digits, never an option or a name. */
    private static String checkCommit(String commit) {
        if (commit == null || !COMMIT.matcher(commit).matches()) {
            throw new IllegalArgumentException("A commit must be 7 to 40 lower-case hex digits");
        }
        return commit;
    }

    /** A path inside the workspace, relative, not below {@code .git}. */
    private String checkPath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("A path must be inside the workspace (not .git)");
        }
        return checkSubtree(Path.of(path));
    }

    private String checkSubtree(Path subtree) {
        Objects.requireNonNull(subtree, "subtree");
        Path normalized = subtree.normalize();
        if (subtree.isAbsolute() || normalized.toString().isEmpty()
            || normalized.startsWith("..") || normalized.startsWith(".git")
            || !root.resolve(normalized).normalize().startsWith(root)) {
            throw new IllegalArgumentException("A subtree to restore must be a path inside the"
                + " workspace (not .git)");
        }
        return normalized.toString().replace('\\', '/');
    }

    private String run(String... arguments) {
        return run(false, arguments);
    }

    private String run(boolean tolerateFailure, String... arguments) {
        byte[] output = execute(tolerateFailure, arguments);
        return output == null ? "" : new String(output, StandardCharsets.UTF_8);
    }

    private byte[] runBytes(String... arguments) {
        return execute(false, arguments);
    }

    /**
     * Runs {@code git <fixed options> <arguments>} in the workspace root.
     *
     * @param tolerateFailure return {@code null} instead of failing on a non-zero exit
     */
    private byte[] execute(boolean tolerateFailure, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-c");
        command.add("core.hooksPath=" + emptyHooksDirectory());
        Stream.of("commit.gpgsign=false", "core.autocrlf=false", "core.quotepath=false",
            "gc.autoDetach=false", "maintenance.autoDetach=false",
            "user.name=INUBIT MCP (" + profile + ")", "user.email=inubit-mcp@localhost")
            .forEach(option -> {
                command.add("-c");
                command.add(option);
            });
        command.addAll(List.of(arguments));
        String name = "git " + arguments[0];
        LaunchedProcess process;
        try {
            process = launcher.launch(new LaunchSpec(command, environment, root));
        } catch (IOException e) {
            if (isMissingExecutable(e)) {
                throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                    "git not found: the workspace history needs the git command",
                    "git is not installed or not on the PATH of the MCP server",
                    "Install git (2.32 or newer) and make it available on the PATH of the MCP"
                        + " server"));
            }
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "git could not be started in the workspace " + root + " ("
                    + e.getClass().getSimpleName() + ")",
                "The git command is not executable, or the workspace directory is missing or"
                    + " not accessible",
                "Check the permissions of git and of the workspace directory"));
        }
        Drain stdout = Drain.start(process.stdout());
        Drain stderr = Drain.start(process.stderr());
        try (OutputStream stdin = process.stdin()) {
            // git reads nothing from stdin
        } catch (IOException e) {
            // already exited
        }
        try {
            if (!process.waitFor(timeout)) {
                stop(process);
                throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
                    name + " did not finish within " + Durations.human(timeout) + " in the"
                        + " workspace " + root,
                    "The workspace is very large, on a slow or hanging file system, or locked"
                        + " by another git process",
                    "Check the workspace directory and retry"));
            }
        } catch (InterruptedException e) {
            stop(process);
            Thread.currentThread().interrupt();
            throw unusable(name + " was interrupted in the workspace " + root, null);
        }
        String error = stderr.text();
        if (process.exitValue() != 0) {
            if (tolerateFailure) {
                return null;
            }
            throw unusable(name + " failed in the workspace " + root + " (exit "
                + process.exitValue() + ")", error.strip());
        }
        return stdout.bytes();
    }

    /**
     * The JDK reports a program that does not exist as {@code error=2, No such file or
     * directory}; other errors (e.g. {@code error=13}) mean it exists but cannot be started.
     */
    private boolean isMissingExecutable(IOException e) {
        String message = String.valueOf(e.getMessage());
        boolean notFound = message.contains("error=2,") || message.contains("No such file");
        boolean isPath = executable.indexOf('/') >= 0;
        return notFound && (!isPath || !Files.exists(Path.of(executable)))
            && Files.isDirectory(root);
    }

    private static void stop(LaunchedProcess process) {
        process.destroy();
        try {
            if (!process.waitFor(KILL_GRACE)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * An owner-only, empty directory below {@code java.io.tmpdir} for {@code core.hooksPath},
     * created on first use and again if something was put into it.
     */
    private Path emptyHooksDirectory() {
        try {
            if (emptyHooks == null || !Files.isDirectory(emptyHooks) || !isEmpty(emptyHooks)) {
                emptyHooks = Files.createTempDirectory("inubit-mcp-git-hooks-",
                    PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
                emptyHooks.toFile().deleteOnExit();
            }
            return emptyHooks.toAbsolutePath();
        } catch (IOException | UnsupportedOperationException e) {
            throw unusable("No private empty directory for git hooks ("
                + e.getClass().getSimpleName() + ")", null);
        }
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private ToolErrorException unusable(String message, String excerpt) {
        ToolError error = ToolError.of(ErrorCode.PRECONDITION_FAILED, message,
            "The workspace is not a usable git repository or not writable",
            "Check the workspace directory (setting workspace) and its permissions");
        return new ToolErrorException(excerpt == null || excerpt.isEmpty() ? error
            : error.withExcerpt(excerpt));
    }

    /** Reads a stream to its end on a virtual thread, refusing more than 64 MiB. */
    private static final class Drain {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Thread reader;
        private volatile boolean overflow;

        static Drain start(InputStream in) {
            Drain drain = new Drain();
            drain.reader = Thread.ofVirtual().start(() -> drain.consume(in));
            return drain;
        }

        private void consume(InputStream in) {
            byte[] buffer = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    synchronized (bytes) {
                        if (bytes.size() + n > MAX_OUTPUT_BYTES) {
                            overflow = true;
                            continue;
                        }
                        bytes.write(buffer, 0, n);
                    }
                }
            } catch (IOException e) {
                // stream closed: keep what was read
            }
        }

        String text() {
            return new String(bytes(), StandardCharsets.UTF_8);
        }

        byte[] bytes() {
            try {
                reader.join(KILL_GRACE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (overflow) {
                throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                    "git produced more than " + MAX_OUTPUT_BYTES + " bytes of output",
                    "The workspace holds an unexpected number of files",
                    "Check the workspace directory"));
            }
            synchronized (bytes) {
                return bytes.toByteArray();
            }
        }
    }
}
