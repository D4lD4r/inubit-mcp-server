package de.dadecker.inubit.mcp.adapter.cli.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T042 (feature 024, research D-1, D-3, D-11): the log-level port. The answering node comes from
 * the INUBIT client helper's {@code ownNode} operation (a fake helper answers with frames carrying
 * the request's nonce); a raise or a reset is one StartCLI run of a {@code trace} form, scripted
 * with {@link ScriptedProcessLauncher} and answered with the {@code node-tools/v8_1/log-level}
 * cases. Argument arrays, environments, logs and errors are checked for leaks.
 */
@Timeout(60)
class CliLogLevelAdapterTest {

    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final InubitVersion V81 = InubitVersion.parse("8.1.17").orElseThrow();
    private static final String USER = "jdoe";
    private static final LoggerName ACME = new LoggerName("com.inubit.ibis.acme");
    private static final Duration BUDGET = Duration.ofSeconds(20);
    private static final String SET_ACME = "trace --nodelevel DEBUG --package"
        + " 'com.inubit.ibis.acme'";
    private static final String RESET = "trace --reset";
    /** The server's environment, with credentials no process may get (leak check). */
    private static final Map<String, String> PARENT = Map.of(
        "PATH", Platform.searchPath(),
        "HOME", "/home/test",
        "INUBIT_PASSWORD", TestSecrets.CONFIGURED_PASSWORD,
        "INUBIT_TRUST_STORE_PASSWORD", TestSecrets.TRUST_STORE_PASSWORD);

    @TempDir
    Path root;

    private Path tempRoot;
    private Path home;
    private Path jdk;
    private final CliResources resources = new CliResources("acme", PrivateFiles.forHost());
    private final SecretScrubber scrubber = new SecretScrubber();
    private final CliConcurrencyLimiter limiter =
        new CliConcurrencyLimiter(CapabilityMatrix.production());
    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger("de.dadecker.inubit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;
    private FakeProcessLauncher helper;
    private ScriptedProcessLauncher cli;
    private CredentialGuard guard;

    @BeforeEach
    void setUp() throws IOException {
        tempRoot = Files.createDirectories(root.resolve("tmp"));
        home = client();
        jdk = jdk();
        helper = answering(nodeOk("node1"));
        cli = new ScriptedProcessLauncher();
        previousLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
        resources.close();
        for (ILoggingEvent event : appender.list) {
            TestSecrets.assertNoLeak(event.getFormattedMessage());
        }
    }

    // --- the answering node (op=ownNode) --------------------------------------------------------

    @Test
    void theOwnNodeLookupNamesTheAnsweringNode() throws IOException {
        EffectiveNodeConfig server = http().build();

        String node = adapter(server).answeringNode(V81, deadline());

        assertThat(node).isEqualTo("node1");
        assertThat(helper.launchCount()).as("one helper run").isEqualTo(1);
        assertThat(cli.launches()).as("no StartCLI run").isEmpty();
        assertThat(guard.confirmed()).as("the login was accepted").isTrue();
        Map<String, String> request = decode(helper.last().stdinBytes());
        assertThat(request).containsEntry("op", "ownNode")
            .containsEntry("variant", "SOAP_81")
            .containsEntry("url", server.cli().url().toString())
            .containsEntry("user", USER)
            .containsEntry("password", TestSecrets.CONFIGURED_PASSWORD);
        assertThat(helper.last().stdinClosed()).isTrue();
        LaunchSpec spec = helper.last().spec();
        String argv = String.join(" ", spec.command());
        TestSecrets.assertNoLeak(argv);
        TestSecrets.assertNoLeak(spec.environment().toString());
        assertThat(argv).doesNotContain(USER, "ownNode", "inubit.example.test");
        assertThat(spec.command().get(spec.command().size() - 1))
            .isEqualTo(HelperInstallation.MAIN_CLASS);
        assertTempRootEmpty();
    }

    @Test
    void framesAfterTheNodeNameAreAnUnexpectedResponse() throws IOException {
        helper = answering(nodeOk("node1"), "member node1 ACTIVE - - - -");

        ToolError error = failure(() -> adapter(http().build()).answeringNode(V81, deadline()));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.nextStep())
            .isEqualTo("Reset with reset_temporary_log_level; report the INUBIT version");
        assertTempRootEmpty();
    }

    @Test
    void anInvalidNodeNameIsAnUnexpectedResponseWithoutTheName() throws IOException {
        helper = answering(nodeOk("node%2F1"));

        ToolError error = failure(() -> adapter(http().build()).answeringNode(V81, deadline()));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.toString()).doesNotContain("node/1");
        assertTempRootEmpty();
    }

    // --- raise and reset through StartCLI (D-11) ------------------------------------------------

    @Test
    void aRaiseIsOneStartCliRunOfTheNodeLevelForm() {
        script(SET_ACME, "set_ok");
        EffectiveNodeConfig server = http().build();

        adapter(server).raise(TemporaryLevel.DEBUG, ACME, V81, BUDGET);

        cli.verifyComplete();
        assertThat(cli.execCommands()).containsExactly(SET_ACME);
        assertThat(helper.launchCount()).as("no own-node lookup").isZero();
        assertStartCliRun(server, cli.launches().get(0), SET_ACME);
        assertThat(guard.confirmed()).as("the login was accepted").isTrue();
    }

    @Test
    void aResetIsOneStartCliRunOfTheResetForm() {
        script(RESET, "reset_ok");
        EffectiveNodeConfig server = http().build();

        adapter(server).resetAll(V81, BUDGET);

        cli.verifyComplete();
        assertThat(cli.execCommands()).containsExactly(RESET);
        assertThat(helper.launchCount()).as("no own-node lookup").isZero();
        assertStartCliRun(server, cli.launches().get(0), RESET);
    }

    @ParameterizedTest
    @CsvSource({
        "missing_level, UNEXPECTED_RESPONSE",
        "login_failure, AUTH_FAILED",
        "forbidden, FORBIDDEN"})
    void aFailedRaiseIsClassifiedAsTheCase(String fixtureCase, ErrorCode code) {
        script(SET_ACME, fixtureCase);

        ToolError error = failure(() -> adapter(http().build())
            .raise(TemporaryLevel.DEBUG, ACME, V81, BUDGET));

        assertThat(error.code()).isEqualTo(code);
        assertThat(cli.launches()).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({
        "missing_level, UNEXPECTED_RESPONSE",
        "login_failure, AUTH_FAILED",
        "forbidden, FORBIDDEN"})
    void aFailedResetIsClassifiedAsTheCase(String fixtureCase, ErrorCode code) {
        script(RESET, fixtureCase);

        ToolError error = failure(() -> adapter(http().build()).resetAll(V81, BUDGET));

        assertThat(error.code()).isEqualTo(code);
        assertThat(cli.launches()).hasSize(1);
    }

    @Test
    void aRunWithoutTheConfirmationIsAnUnexpectedResponse() {
        // exit code 0 without the n-OK confirmation (D-11)
        cli.expect(RESET).replying("Password: \n", "", 0);

        ToolError error = failure(() -> adapter(http().build()).resetAll(V81, BUDGET));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo("StartCLI did not confirm the log level change");
        assertThat(error.nextStep())
            .isEqualTo("Reset with reset_temporary_log_level; report the INUBIT version");
    }

    @Test
    void aHangingStartCliTimesOutNamingCliTimeout() {
        cli.expect(SET_ACME).hanging();

        ToolError error = failure(() -> adapter(http().build())
            .raise(TemporaryLevel.DEBUG, ACME, V81, Duration.ofMillis(500)));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("cliTimeout");
        assertThat(cli.launches().get(0).process().destroyed()).as("process stopped").isTrue();
    }

    @Test
    void withoutCredentialsNothingIsLaunched() {
        NodeCredentials none = new NodeCredentials(NODE, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());
        CliLogLevelAdapter adapter = adapter(http().build(), none);

        assertThat(failure(() -> adapter.raise(TemporaryLevel.DEBUG, ACME, V81, BUDGET)).code())
            .isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(failure(() -> adapter.resetAll(V81, BUDGET)).code())
            .isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(cli.launches()).isEmpty();
    }

    // --- helpers --------------------------------------------------------------------------------

    private CliLogLevelAdapter adapter(EffectiveNodeConfig server) {
        return adapter(server, new NodeCredentials(NODE,
            Optional.of(new SourcedValue<>(USER, "test")),
            Optional.of(new SourcedValue<>(scrubber.register(TestSecrets.CONFIGURED_PASSWORD),
                "test")),
            Optional.empty(), Optional.empty()));
    }

    private CliLogLevelAdapter adapter(EffectiveNodeConfig server, NodeCredentials credentials) {
        guard = new CredentialGuard(server.secretLabels(), Clock.systemUTC());
        CliRunner runner = TestCliRunners.forHost(cli, PARENT, resources);
        NodeHelperRunner helperRunner = new NodeHelperRunner(server, () -> credentials, guard,
            runner, TestCliRunners.helperHost(PARENT, resources, tempRoot),
            new ProcessRun(helper, resources), limiter);
        return new CliLogLevelAdapter(helperRunner, server, credentials, guard, runner,
            new CliOutputClassifier(scrubber, server.secretLabels(), runner.windows()), scrubber);
    }

    private TestNodeConfig http() {
        return TestNodeConfig.node().id(NODE.value()).baseUrl("http://inubit.example.test:8000")
            .allowInsecureHttp(true).cliHome(home).cliJavaHome(jdk);
    }

    private static long deadline() {
        return System.nanoTime() + BUDGET.toNanos();
    }

    /** The error of a call; it carries the node and leaks nothing. */
    private static ToolError failure(Runnable call) {
        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class, call::run);
        assertThat(thrown).as("the call fails").isNotNull();
        ToolError error = thrown.error();
        assertThat(error.node()).contains(NODE);
        TestSecrets.assertNoLeak(error.toString());
        return error;
    }

    /** The user and the command in the arguments, the password on stdin only. */
    private static void assertStartCliRun(EffectiveNodeConfig server,
        ScriptedProcessLauncher.Launch launch, String command) {
        LaunchSpec spec = launch.spec();
        assertThat(spec.startCliArguments()).containsExactly("-u", USER, "--execCommand",
            command, server.cli().url().toString());
        TestSecrets.assertNoLeak(String.join(" ", spec.command()));
        TestSecrets.assertNoLeak(spec.environment().toString());
        assertThat(new String(launch.process().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(TestSecrets.CONFIGURED_PASSWORD + "\n");
    }

    /** Expects {@code command}, answered by {@code node-tools/v8_1/log-level/<case>.*}. */
    private void script(String command, String fixtureCase) {
        String base = "node-tools/v8_1/log-level/" + fixtureCase;
        cli.expect(command).replying(text(base + ".stdout"), text(base + ".stderr"),
            Integer.parseInt(text(base + ".exit").strip()));
    }

    private static String text(String resource) {
        return new String(FakeProcessLauncher.resource(resource), StandardCharsets.UTF_8);
    }

    private void assertTempRootEmpty() throws IOException {
        try (Stream<Path> entries = Files.list(tempRoot)) {
            assertThat(entries).as("nothing left below the temporary directory").isEmpty();
        }
    }

    private static String nodeOk(String name) {
        return "node ok " + name;
    }

    /**
     * A helper that answers every request with a complete run (exit 0): {@code start SOAP_81},
     * {@code login ok}, the given frames, {@code end}, all with the request's nonce.
     */
    private static FakeProcessLauncher answering(String... frames) {
        return FakeProcessLauncher.of("", "", 0).responding(request -> {
            String nonce = decode(request).get("nonce");
            List<String> all = new ArrayList<>(List.of("start SOAP_81", "login ok"));
            all.addAll(List.of(frames));
            all.add("end");
            StringBuilder out = new StringBuilder("noise before the frames\n");
            for (String frame : all) {
                out.append("#QE1:").append(nonce).append(' ').append(frame).append('\n');
            }
            return new Answer(out.toString(), 0);
        });
    }

    /** The request's {@code key=value} lines up to {@code end}, percent-decoded. */
    private static Map<String, String> decode(byte[] request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : new String(request, StandardCharsets.US_ASCII).split("\n")) {
            if (line.equals("end")) {
                break;
            }
            int equals = line.indexOf('=');
            fields.put(line.substring(0, equals), percentDecode(line.substring(equals + 1)));
        }
        return fields;
    }

    private static String percentDecode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                bytes.writeBytes(HexFormat.of().parseHex(value, i + 1, i + 3));
                i += 2;
            } else {
                bytes.write(c);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** A client home with StartCLI, {@code lib/ibis.jar}, {@code lib/shared/} and {@code conf/}. */
    private Path client() throws IOException {
        Path client = root.resolve("client");
        Files.createDirectories(client.resolve("lib").resolve("shared"));
        Files.createDirectories(client.resolve("conf"));
        Files.createFile(client.resolve("lib").resolve("ibis.jar"));
        FakeStartCli.install(client);
        return client;
    }

    /** A JDK directory with an executable {@code bin/java} and {@code bin/java.exe}. */
    private Path jdk() throws IOException {
        Path directory = root.resolve("jdk");
        Path bin = Files.createDirectories(directory.resolve("bin"));
        for (String name : List.of("java", "java.exe")) {
            Path java = Files.createFile(bin.resolve(name));
            if (!Platform.windows()) {
                Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
            }
        }
        return directory;
    }
}
