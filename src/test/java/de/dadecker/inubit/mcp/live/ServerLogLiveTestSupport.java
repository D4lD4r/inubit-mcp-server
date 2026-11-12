package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.fail;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.config.CliConfig;
import de.dadecker.inubit.mcp.config.GroupConfig;
import de.dadecker.inubit.mcp.config.NodeConfig;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Assumptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The offline parts of {@link ServerLogLiveTest} (feature 021, research D-24), tested by
 * {@link ServerLogLiveTestSupportTest}: the log folder and record directory variables, the
 * owner-only recording of the StartCLI and helper output, the checks of the result and the
 * downloaded file, the log check, the summary line and the configuration copy with an
 * unreachable port. Failure messages name variables, fields and counts, never log content.
 */
final class ServerLogLiveTestSupport {

    static final String LOG_FOLDER_VARIABLE = "INUBIT_LIVE_LOG_FOLDER";
    /**
     * Excerpt lines from this length are checked against the captured log; shorter ones (blank
     * lines, brackets) also occur in unrelated log text.
     */
    static final int MIN_CHECKED_CHARS = 10;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ServerLogLiveTestSupport() {
    }

    /**
     * {@code INUBIT_LIVE_LOG_FOLDER} as a real path; skips the live test without it. Refused as
     * {@link QueueEntryLiveTestSupport#privateDirectory}: relative, with {@code ..} segments,
     * inside {@code repository} or inside any git working tree.
     */
    static Path logFolder(Map<String, String> environment, Path repository) throws IOException {
        String value = environment.get(LOG_FOLDER_VARIABLE);
        Assumptions.assumeTrue(value != null && !value.isBlank(), LOG_FOLDER_VARIABLE + " is not"
            + " set (a private folder outside every repository); the server log live test is"
            + " skipped");
        return QueueEntryLiveTestSupport.privateDirectory(LOG_FOLDER_VARIABLE, value, repository,
            "the folder receives server log files");
    }

    /**
     * {@code INUBIT_LIVE_RECORD_DIR} as a real path, refused as {@link #logFolder}; empty without
     * it.
     */
    static Optional<Path> recordDirectory(Map<String, String> environment, Path repository)
        throws IOException {
        String value = environment.get(QueueEntryLiveTestSupport.RECORD_DIR_VARIABLE);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(QueueEntryLiveTestSupport.privateDirectory(
            QueueEntryLiveTestSupport.RECORD_DIR_VARIABLE, value, repository,
            "the recording holds raw StartCLI output and helper frames"));
    }

    /** A new owner-only directory {@code server-log-…} of this run below {@code directory}. */
    static Path runDirectory(PrivateFileSystem files, Path directory) throws IOException {
        return QueueEntryLiveTestSupport.runDirectory(files, directory, "server-log-");
    }

    /**
     * The case of a {@code download_server_log} response: {@code ok}, {@code empty} or
     * {@code error}.
     */
    static String caseName(JsonNode response) {
        if (response.path("isError").asBoolean()) {
            return "error";
        }
        return response.path("structuredContent").path("sizeBytes").asLong() == 0 ? "empty" : "ok";
    }

    /**
     * The run that downloaded {@code log}: the last StartCLI run for {@code trace} (a client
     * version probe may precede it), the last helper run for {@code webservice}.
     */
    static Run lastRun(List<Run> runs, String log) {
        boolean helper = log.equals("webservice");
        return runs.stream().filter(run -> run.helper() == helper).reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no " + (helper ? "helper" : "StartCLI")
                + " run was captured for the " + log + " download: nothing to record"));
    }

    /**
     * Writes {@code process} into {@code run}/{@code log} as the case files of research D-21,
     * each owner-only: StartCLI as {@code <name>.stdout}, {@code .stderr} and {@code .exit}, the
     * helper as {@code <name>.frames} (its stdout) and {@code .exit}. The stdin of either, which
     * carries the password, is never recorded.
     */
    static void record(PrivateFileSystem files, Path run, String log, String name, Run process)
        throws IOException {
        Path directory = run.resolve(log);
        files.createDirectories(directory);
        if (process.helper()) {
            write(files, directory.resolve(name + ".frames"), process.stdout().toByteArray());
        } else {
            write(files, directory.resolve(name + ".stdout"), process.stdout().toByteArray());
            write(files, directory.resolve(name + ".stderr"), process.stderr().toByteArray());
        }
        if (process.exit().isPresent()) {
            write(files, directory.resolve(name + ".exit"),
                (process.exit().get() + "\n").getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static void write(PrivateFileSystem files, Path file, byte[] content)
        throws IOException {
        files.createFile(file);
        Files.write(file, content);
    }

    /**
     * The structured {@code result} of a {@code download_server_log} call matches the output
     * schema, names {@code node} and {@code log} (and, for {@code webservice}, the INUBIT node),
     * and its {@code file} is a regular file directly in {@code folder} with {@code sizeBytes}
     * bytes, owner-only ({@code rw-------}, on Windows the access list of {@code files}).
     */
    static void assertDownloaded(JsonNode result, NodeId node, String log, Path folder,
        PrivateFileSystem files) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = JSON.readValue(SchemaResources.forClasspath()
            .load("schemas/download_server_log.output.json"), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> content = JSON.treeToValue(result, Map.class);
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, content);
        if (!validation.valid()) {
            fail("the result does not match the output schema: " + validation.errorMessage());
        }
        if (!result.path("node").asString().equals(node.value())) {
            fail("node: the result names another node than " + node);
        }
        if (!result.path("log").asString().equals(log)) {
            fail("log: the result names another log than " + log);
        }
        if (log.equals("webservice") && result.path("inubitNode").asString("").isBlank()) {
            fail("inubitNode: a webservice result names the node INUBIT reported");
        }
        Path file = Path.of(result.path("file").asString());
        if (!folder.equals(file.getParent()) || !Files.isRegularFile(file)) {
            fail("file: no regular file directly in " + LOG_FOLDER_VARIABLE);
        }
        long size = Files.size(file);
        if (size != result.path("sizeBytes").asLong()) {
            fail("sizeBytes: the file has " + size + " bytes, the result reports "
                + result.path("sizeBytes").asLong());
        }
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            String permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
            if (!permissions.equals("rw-------")) {
                fail("file: permissions " + permissions + ", expected rw-------");
            }
        } else if (!files.check(file).isEmpty()) {
            fail("file: others may access it: " + files.check(file));
        }
    }

    /**
     * No stripped excerpt line of {@code result} with at least {@link #MIN_CHECKED_CHARS}
     * characters occurs in {@code log}; the message counts, never quotes.
     */
    static void assertNoExcerptLineLogged(JsonNode result, List<String> log) {
        List<String> lines = result.path("excerpt").path("lines").valueStream()
            .map(line -> line.path("text").asString().strip())
            .filter(text -> text.length() >= MIN_CHECKED_CHARS)
            .toList();
        long logged = lines.stream()
            .filter(text -> log.stream().anyMatch(entry -> entry.contains(text))).count();
        if (logged > 0) {
            fail(logged + " of " + lines.size() + " excerpt lines appear in the captured log");
        }
    }

    /**
     * {@code [live] download_server_log <node> <log>: <n> bytes, window <lines>}; without a
     * window the excerpt kind, for {@code webservice} the INUBIT node appended.
     */
    static String summary(JsonNode result) {
        JsonNode excerpt = result.path("excerpt");
        String window = excerpt.has("window")
            ? String.valueOf(excerpt.path("window").path("lines").asInt())
            : "none (" + excerpt.path("kind").asString() + ")";
        String inubitNode = result.has("inubitNode")
            ? ", INUBIT node " + result.path("inubitNode").asString() : "";
        return "[live] download_server_log " + result.path("node").asString() + " "
            + result.path("log").asString() + ": " + result.path("sizeBytes").asLong()
            + " bytes, window " + window + inubitNode;
    }

    /**
     * {@code config} with {@code node}'s {@code baseUrl} and effective explicit StartCLI address
     * moved to {@code 127.0.0.1:<port>} (the forced failure of research D-24: a closed loopback
     * port refuses at once, and nothing else is contacted); every other node is unchanged. The
     * copy exists in memory only.
     */
    static ProfileConfig unreachable(ProfileConfig config, NodeId node, int port) {
        List<GroupConfig> groups = config.groups().stream()
            .map(group -> !group.name().equals(node.group().value()) ? group
                : new GroupConfig(group.name(), group.production(), group.nodes().stream()
                    .map(candidate -> candidate.name().equals(node.name())
                        ? unreachable(candidate, group.cli(), port) : candidate)
                    .toList(), group.write(), group.tls(), group.cli(), group.inventory(),
                    group.versionLine(), group.timeout(), group.cliTimeout(),
                    group.cliExportTimeout(), group.hangingThreshold(), group.confirmationTtl(),
                    group.development(), group.e2eTests(), group.e2e(), group.deploy(),
                    group.credentials(), group.archives(), group.unsupported()))
            .toList();
        return new ProfileConfig(config.profile(), config.terminology(), config.credentials(),
            groups, config.defaults(), config.auditDirectory(), config.logLevel(),
            config.resultLimits(), config.workspace(), config.updateCheck(), config.knowledge());
    }

    private static NodeConfig unreachable(NodeConfig node, CliConfig groupCli, int port) {
        CliConfig cli = new CliConfig(node.cli().url().or(groupCli::url)
            .map(url -> loopback(url, port)), node.cli().home(), node.cli().javaHome());
        return new NodeConfig(node.name(), node.baseUrl().map(url -> loopback(url, port)),
            node.allowInsecureHttp(), node.versionLine(), node.write(), node.tls(), cli,
            node.inventory(), node.timeout(), node.cliTimeout(), node.cliExportTimeout(),
            node.hangingThreshold(), node.confirmationTtl(), node.development(), node.e2eTests(),
            node.e2e(), node.credentials());
    }

    private static URI loopback(URI url, int port) {
        try {
            return new URI(url.getScheme(), null, "127.0.0.1", port, url.getPath(), url.getQuery(),
                null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Launches through {@code delegate} and records each StartCLI run (a launch with StartCLI
     * arguments) and each run of the INUBIT client helper: stdout and stderr as they are read,
     * the exit code once it has exited. Other processes and the stdin of every process are not
     * recorded.
     */
    static final class ProcessRecorder implements ProcessLauncher {

        private final ProcessLauncher delegate;
        private final List<Run> runs = new CopyOnWriteArrayList<>();

        ProcessRecorder(ProcessLauncher delegate) {
            this.delegate = delegate;
        }

        List<Run> runs() {
            return List.copyOf(runs);
        }

        @Override
        public LaunchedProcess launch(LaunchSpec spec) throws IOException {
            LaunchedProcess process = delegate.launch(spec);
            boolean helper = spec.command().getLast().equals(HelperInstallation.MAIN_CLASS);
            if (!helper && spec.startCliArguments().isEmpty()) {
                return process;
            }
            Run run = new Run(helper);
            runs.add(run);
            return new Recorded(process, run);
        }
    }

    /** One recorded StartCLI or helper run. */
    static final class Run {

        private final boolean helper;
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private volatile Integer exit;

        Run(boolean helper) {
            this.helper = helper;
        }

        /** Whether this is a helper run (else a StartCLI run). */
        boolean helper() {
            return helper;
        }

        ByteArrayOutputStream stdout() {
            return stdout;
        }

        ByteArrayOutputStream stderr() {
            return stderr;
        }

        void finished(int exitCode) {
            exit = exitCode;
        }

        Optional<Integer> exit() {
            return Optional.ofNullable(exit);
        }
    }

    /** A process whose stdout, stderr and exit code are copied into its {@link Run}. */
    private static final class Recorded implements ProcessLauncher.LaunchedProcess {

        private final ProcessLauncher.LaunchedProcess process;
        private final Run run;
        private final InputStream stdout;
        private final InputStream stderr;

        Recorded(ProcessLauncher.LaunchedProcess process, Run run) {
            this.process = process;
            this.run = run;
            this.stdout = copying(process.stdout(), run.stdout());
            this.stderr = copying(process.stderr(), run.stderr());
        }

        private static InputStream copying(InputStream in, ByteArrayOutputStream copy) {
            return new FilterInputStream(in) {
                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) {
                        copy.write(b);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = super.read(buffer, offset, length);
                    if (n > 0) {
                        copy.write(buffer, offset, n);
                    }
                    return n;
                }
            };
        }

        @Override
        public boolean waitFor(Duration timeout) throws InterruptedException {
            boolean exited = process.waitFor(timeout);
            if (exited && run.exit().isEmpty()) {
                run.finished(process.exitValue());
            }
            return exited;
        }

        @Override
        public InputStream stdout() {
            return stdout;
        }

        @Override
        public InputStream stderr() {
            return stderr;
        }

        @Override
        public OutputStream stdin() {
            return process.stdin();
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
