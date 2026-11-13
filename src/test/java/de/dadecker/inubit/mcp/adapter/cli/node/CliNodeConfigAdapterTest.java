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
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
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
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValue;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValue.Absent;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValue.Failed;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValue.Present;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValues;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T034 (feature 024, FR-030 … FR-032, research D-10, D-20, D-21): {@code get_node_config}'s port
 * through the INUBIT client helper. A fake launcher reads the request from stdin and answers with
 * the frames of a {@code node-tools/v8_1/node-config} case (or of the test), carrying the
 * request's nonce. The {@code config} frames become {@link NodeConfigValue}s in request order;
 * nothing is left below the temporary root; argument array, environment, logs, results and errors
 * are checked for leaks.
 */
@Timeout(60)
class CliNodeConfigAdapterTest {

    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final InubitVersion V81 = InubitVersion.parse("8.1.17").orElseThrow();
    private static final String FIXTURES = "node-tools/v8_1/node-config/";
    private static final String FIXTURE_NONCE = "0123456789abcdef0123456789abcdef";
    private static final String USER = "jdoe";
    private static final Duration BUDGET = Duration.ofSeconds(20);
    /** INUBIT 8.1.17's answer for a property the node does not have (spike T018). */
    private static final String MISSING_CHAIN = "com.inubit.ibis.utils.InubitException";
    private static final String MISSING_MESSAGE = enc("Exception from service object:"
        + " @Start@IError@@@Internal INUBIT error!@End@Cannot invoke \"Object.getClass()\""
        + " because \"o\" is null");
    private static final List<NodeConfigName> TWO =
        List.of(NodeConfigName.CLUSTER_NAME, NodeConfigName.NODE_NAME);
    private static final String CLUSTER_NAME_OK =
        "config ClusterName ok String true false Cluster%20name Cluster - acme-cluster";
    /** The server's environment, with credentials the helper must never get (leak check). */
    private static final Map<String, String> PARENT = Map.of(
        "PATH", "/usr/bin:/bin",
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
    private FakeProcessLauncher launcher;
    private CredentialGuard guard;

    @BeforeEach
    void setUp() throws IOException {
        tempRoot = Files.createDirectories(root.resolve("tmp"));
        home = client();
        jdk = jdk();
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

    // --- the reconstructed cases (T033) ---------------------------------------------------------

    @Test
    void theAllCaseAnswersEveryNameInRequestOrderAndTheMissingPropertyFormIsAbsent()
        throws IOException {
        launcher = replaying("all");
        EffectiveNodeConfig server = http().build();
        List<NodeConfigName> names = List.of(NodeConfigName.values());

        NodeConfigValues values = adapter(server).values(names, V81, deadline());

        assertThat(values.answeringNode()).isEqualTo("node1");
        assertThat(values.values()).extracting(NodeConfigValue::name)
            .containsExactlyElementsOf(names);
        assertThat(values.values()).filteredOn(Absent.class::isInstance)
            .extracting(NodeConfigValue::name).containsExactly(
                NodeConfigName.OPERATION_MODE, NodeConfigName.ZONE_NAME,
                NodeConfigName.CONTAINER_SHUTDOWN_ON_NODE_FAILURE, NodeConfigName.REGISTER_MBEANS,
                NodeConfigName.REGISTER_INTERNAL_MBEANS, NodeConfigName.SOCKET_TIMEOUT_IN_MILLISECS,
                NodeConfigName.LOGS_DB_DATA_SOURCE_LOCATION,
                NodeConfigName.REPOSITORY_DATA_SOURCE_LOCATION,
                NodeConfigName.CLUSTER_BIND_ADDRESS);
        assertThat(values.values()).filteredOn(Present.class::isInstance).hasSize(11)
            .noneMatch(value -> ((Present) value).cut());
        assertThat(values.values().get(1)).isEqualTo(new Present(NodeConfigName.CLUSTER_NAME,
            "acme-cluster", false, "String", "Cluster name",
            "Name of the cluster the node belongs to.", "Cluster", true, false));
        assertThat(values.values().get(19)).isEqualTo(new Present(NodeConfigName.RETRY_INTERVAL,
            "5000", false, "Long", "Retry interval", "Wait between two retries in milliseconds.",
            "Cluster", true, false));
        Present dataSource = (Present) values.values().get(14);
        assertThat(dataSource.name()).isEqualTo(NodeConfigName.DATA_SOURCE_LOCATION);
        assertThat(dataSource.value())
            .startsWith("java:/comp/env/jdbc/IBISDB;user=ibis;password=")
            .doesNotContain("example-secret");
        assertThat(guard.confirmed()).as("the login was accepted").isTrue();

        Map<String, String> request = decode(launcher.last().stdinBytes());
        assertThat(request).containsOnlyKeys("qe", "op", "nonce", "variant", "url", "trustStore",
            "trustStorePassword", "hostnameVerification", "names", "user", "password");
        assertThat(request).containsEntry("op", "nodeConfig")
            .containsEntry("names", wireNames(names))
            .containsEntry("variant", "SOAP_81")
            .containsEntry("url", server.cli().url().toString())
            .containsEntry("user", USER)
            .containsEntry("password", TestSecrets.CONFIGURED_PASSWORD);
        assertThat(launcher.last().stdinClosed()).isTrue();
        assertLaunchedWithoutRequestData(launcher.last().spec());
        TestSecrets.assertNoLeak(values.toString());
        assertTempRootEmpty();
    }

    @Test
    void theAbsentCaseMapsBothFormsOfAMissingPropertyToAbsent() throws IOException {
        launcher = replaying("absent");
        List<NodeConfigName> names = List.of(NodeConfigName.OPERATION_MODE,
            NodeConfigName.CLUSTER_BIND_ADDRESS, NodeConfigName.NODE_NAME);

        NodeConfigValues values = adapter(http().build()).values(names, V81, deadline());

        assertThat(decode(launcher.last().stdinBytes()))
            .containsEntry("names", "OperationMode,ClusterBindAddress,NodeName");
        assertThat(values.values()).containsExactly(
            new Absent(NodeConfigName.OPERATION_MODE),
            new Absent(NodeConfigName.CLUSTER_BIND_ADDRESS),
            new Present(NodeConfigName.NODE_NAME, "node1", false, "String", "Node name",
                "Name of this node within the cluster.", "Cluster", true, false));
        assertTempRootEmpty();
    }

    @Test
    void theOneErrorCaseFailsOnlyThatNameWithAScrubbedExcerptAndAnswersTheOthers()
        throws IOException {
        launcher = replaying("one_error");
        List<NodeConfigName> names = List.of(NodeConfigName.CLUSTER_NAME,
            NodeConfigName.NODE_NAME, NodeConfigName.RETRY_INTERVAL);

        NodeConfigValues values = adapter(http().build()).values(names, V81, deadline());

        assertThat(decode(launcher.last().stdinBytes()))
            .containsEntry("names", "ClusterName,NodeName,retryInterval");
        assertThat(values.values()).extracting(NodeConfigValue::name)
            .containsExactlyElementsOf(names);
        assertThat(values.values().get(0)).isInstanceOf(Present.class);
        // the chain names a SocketTimeoutException: that name's code, not a failure of the call
        assertThat(values.values().get(1)).isEqualTo(new Failed(NodeConfigName.NODE_NAME,
            ErrorCode.UNREACHABLE,
            "Exception from service object: Node node1 did not answer in time. Read timed out"));
        assertThat(((Present) values.values().get(2)).value()).isEqualTo("5000");
        assertTempRootEmpty();
    }

    @Test
    void theApiMissingCaseIsCliUnavailableAndLeavesNothing() throws IOException {
        launcher = replaying("api_missing");

        ToolError error = failure(adapter(http().build()), List.of(NodeConfigName.CLUSTER_NAME),
            deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertTempRootEmpty();
    }

    // --- the request ----------------------------------------------------------------------------

    @Test
    void theRequestNamesExactlyTheRequestedNamesInTheirOrder() throws IOException {
        List<NodeConfigName> names = List.of(NodeConfigName.RETRY_INTERVAL,
            NodeConfigName.OPERATION_MODE, NodeConfigName.ITERATION_COUNT);
        launcher = answering(fields -> answer(fields,
            "config retryInterval absent", "config OperationMode absent",
            "config iterationCount absent"));

        NodeConfigValues values = adapter(http().build()).values(names, V81, deadline());

        assertThat(decode(launcher.last().stdinBytes()))
            .containsEntry("names", "retryInterval,OperationMode,iterationCount");
        assertThat(values.values()).extracting(NodeConfigValue::name)
            .containsExactlyElementsOf(names);
    }

    // --- the missing-property shape is matched narrowly (T018) ----------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        // another chain with the same message
        "com.inubit.ibis.utils.InubitException%3Ejava.lang.NullPointerException|MISSING",
        "java.lang.NullPointerException|MISSING",
        "com.inubit.ibis.utils.OtherException|MISSING",
        "-|MISSING",
        // the same chain with another message
        "com.inubit.ibis.utils.InubitException|Cannot%20invoke%20%22Object.getClass%28%29%22"
            + "%20because%20%22x%22%20is%20null",
        "com.inubit.ibis.utils.InubitException|Internal%20INUBIT%20error",
        "com.inubit.ibis.utils.InubitException|-"})
    void anyOtherConfigErrorIsThatNamesFailureNotAbsent(String error) throws IOException {
        String[] parts = error.split("\\|");
        String message = parts[1].equals("MISSING") ? MISSING_MESSAGE : parts[1];
        launcher = answering(fields -> answer(fields, CLUSTER_NAME_OK,
            "config NodeName error " + parts[0] + " " + message));

        NodeConfigValues values = adapter(http().build()).values(TWO, V81, deadline());

        assertThat(values.values().get(0)).isInstanceOf(Present.class);
        Failed failed = (Failed) values.values().get(1);
        assertThat(failed.name()).isEqualTo(NodeConfigName.NODE_NAME);
        assertThat(failed.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(failed.message()).isNotEmpty().hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void theMissingPropertyShapeIsAbsentForAnyRequestedName() throws IOException {
        launcher = answering(fields -> answer(fields,
            "config ClusterName error " + MISSING_CHAIN + " " + MISSING_MESSAGE,
            "config NodeName error " + MISSING_CHAIN + " " + MISSING_MESSAGE));

        NodeConfigValues values = adapter(http().build()).values(TWO, V81, deadline());

        assertThat(values.values()).containsExactly(new Absent(NodeConfigName.CLUSTER_NAME),
            new Absent(NodeConfigName.NODE_NAME));
    }

    @Test
    void aConfigErrorWithAnAccessDeniedChainIsThatNamesForbidden() throws IOException {
        launcher = answering(fields -> answer(fields, CLUSTER_NAME_OK,
            "config NodeName error com.inubit.ibis.utils.InubitException%3EAccessDenied denied"));

        NodeConfigValues values = adapter(http().build()).values(TWO, V81, deadline());

        assertThat(values.values().get(1))
            .isEqualTo(new Failed(NodeConfigName.NODE_NAME, ErrorCode.FORBIDDEN, "denied"));
    }

    @Test
    void aLongErrorMessageIsCutTo200Characters() throws IOException {
        launcher = answering(fields -> answer(fields, CLUSTER_NAME_OK,
            "config NodeName error java.io.IOException " + "x".repeat(900)));

        NodeConfigValues values = adapter(http().build()).values(TWO, V81, deadline());

        assertThat(((Failed) values.values().get(1)).message()).hasSizeLessThanOrEqualTo(200)
            .startsWith("xxx");
    }

    // --- frame rules ----------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "CLUSTER",
        "NODE|CLUSTER",
        "CLUSTER|CLUSTER",
        "CLUSTER|NODE|config ZoneName absent",
        "CLUSTER|config ZoneName absent",
        "CLUSTER|config Database absent",
        "CLUSTER|config nodename absent",
        "CLUSTER|members ok 0",
        "CLUSTER|NODE|members ok 0"})
    void configFramesThatDoNotAnswerEachRequestedNameOnceInOrderAreAnUnexpectedResponse(
        String rest) throws IOException {
        List<String> frames = new ArrayList<>();
        if (!rest.isEmpty()) {
            for (String frame : rest.split("\\|")) {
                frames.add(switch (frame) {
                    case "CLUSTER" -> CLUSTER_NAME_OK;
                    case "NODE" -> "config NodeName absent";
                    default -> frame;
                });
            }
        }
        launcher = answering(fields -> answer(fields, frames.toArray(String[]::new)));

        ToolError error = failure(adapter(http().build()), TWO, deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).startsWith("get_node_config got an unexpected answer from"
            + " test/node1");
        // a name that was not asked for is not repeated
        assertThat(error.toString()).doesNotContain("Database", "nodename");
        assertTempRootEmpty();
    }

    // --- masking (D-21) -------------------------------------------------------------------------

    @Test
    void valuesAndErrorMessagesHaveTheConfiguredCredentialsMasked() throws IOException {
        String password = enc(TestSecrets.CONFIGURED_PASSWORD);
        launcher = answering(fields -> answer(fields,
            "config ClusterName ok String true false Cluster%20name Cluster - pw%20" + password,
            "config NodeName error java.io.IOException Failed%20with%20" + password));

        NodeConfigValues values = adapter(http().build()).values(TWO, V81, deadline());

        assertThat(((Present) values.values().get(0)).value())
            .isEqualTo("pw " + SecretScrubber.MASK);
        assertThat(((Failed) values.values().get(1)).message()).startsWith("Failed with ");
        TestSecrets.assertNoLeak(values.toString());
    }

    @Test
    void aSecretIsMaskedBeforeTheValueIsCut() throws IOException {
        // 2,020 characters as received: a cut at 2,000 first would keep a piece of the password
        String prefix = "x".repeat(1995) + " ";
        launcher = answering(fields -> answer(fields,
            "config ClusterName ok String true false - - - "
                + enc(prefix + TestSecrets.CONFIGURED_PASSWORD)));

        NodeConfigValues values = adapter(http().build())
            .values(List.of(NodeConfigName.CLUSTER_NAME), V81, deadline());

        assertThat(values.values()).containsExactly(new Present(NodeConfigName.CLUSTER_NAME,
            prefix + SecretScrubber.MASK, false, "String", "", "", "", true, false));
    }

    @Test
    void aValueLongerThan2000CharactersIsCutWithTheMarker() throws IOException {
        launcher = answering(fields -> answer(fields,
            "config ClusterName ok String true false - - - " + "v".repeat(4000)));

        NodeConfigValues values = adapter(http().build())
            .values(List.of(NodeConfigName.CLUSTER_NAME), V81, deadline());

        Present value = (Present) values.values().get(0);
        assertThat(value.value()).hasSize(NodeConfigValue.MAX_VALUE_CHARS).endsWith("…[cut]")
            .startsWith("vvv");
        assertThat(value.cut()).isTrue();
    }

    // --- the budget setting ---------------------------------------------------------------------

    @Test
    void aHangingHelperTimesOutNamingCliTimeout() throws IOException {
        launcher = answering(fields -> answer(fields, CLUSTER_NAME_OK)).hanging();

        ToolError error = failure(adapter(http().build()), List.of(NodeConfigName.CLUSTER_NAME),
            System.nanoTime() + Duration.ofMillis(500).toNanos());

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("raise cliTimeout ");
        assertThat(launcher.last().destroyed()).as("process stopped").isTrue();
        assertTempRootEmpty();
    }

    // --- helpers --------------------------------------------------------------------------------

    private CliNodeConfigAdapter adapter(EffectiveNodeConfig server) {
        NodeCredentials credentials = new NodeCredentials(NODE,
            Optional.of(new SourcedValue<>(USER, "test")),
            Optional.of(new SourcedValue<>(scrubber.register(TestSecrets.CONFIGURED_PASSWORD),
                "test")),
            Optional.empty(), Optional.empty());
        guard = new CredentialGuard(server.secretLabels(), Clock.systemUTC());
        CliRunner runner = TestCliRunners.forHost(launcher, PARENT, resources);
        NodeHelperRunner helper = new NodeHelperRunner(server, () -> credentials, guard, runner,
            TestCliRunners.helperHost(PARENT, resources, tempRoot),
            new ProcessRun(launcher, resources), limiter);
        return new CliNodeConfigAdapter(helper, scrubber, server.secretLabels());
    }

    private TestNodeConfig http() {
        return TestNodeConfig.node().id(NODE.value()).baseUrl("http://inubit.example.test:8000")
            .allowInsecureHttp(true).cliHome(home).cliJavaHome(jdk);
    }

    private static long deadline() {
        return System.nanoTime() + BUDGET.toNanos();
    }

    private static String wireNames(List<NodeConfigName> names) {
        return names.stream().map(NodeConfigName::wireName).collect(Collectors.joining(","));
    }

    /** The error of a call; it carries the node and leaks nothing. */
    private static ToolError failure(CliNodeConfigAdapter adapter, List<NodeConfigName> names,
        long deadline) {
        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class,
            () -> adapter.values(names, V81, deadline));
        assertThat(thrown).as("the call fails").isNotNull();
        ToolError error = thrown.error();
        assertThat(error.node()).contains(NODE);
        TestSecrets.assertNoLeak(error.toString());
        return error;
    }

    private void assertTempRootEmpty() throws IOException {
        try (Stream<Path> entries = Files.list(tempRoot)) {
            assertThat(entries).as("nothing left below the temporary directory").isEmpty();
        }
    }

    /** The argv names the JVM, its options and the helper class; nothing of the request. */
    private static void assertLaunchedWithoutRequestData(LaunchSpec spec) {
        String argv = String.join(" ", spec.command());
        TestSecrets.assertNoLeak(argv);
        TestSecrets.assertNoLeak(spec.environment().toString());
        assertThat(argv).doesNotContain(USER, "nodeConfig", "inubit.example.test");
        assertThat(Arrays.stream(NodeConfigName.values()).map(NodeConfigName::wireName))
            .noneMatch(argv::contains);
        assertThat(spec.command().get(spec.command().size() - 1))
            .isEqualTo(HelperInstallation.MAIN_CLASS);
    }

    /** Replays {@code <case>.frames}/{@code .exit} of the node-config fixtures with the nonce. */
    private static FakeProcessLauncher replaying(String fixtureCase) {
        String frames = new String(FakeProcessLauncher.resource(FIXTURES + fixtureCase
            + ".frames"), StandardCharsets.UTF_8);
        int exit = Integer.parseInt(new String(FakeProcessLauncher.resource(FIXTURES + fixtureCase
            + ".exit"), StandardCharsets.UTF_8).strip());
        return answering(fields -> new Answer(frames.replace(FIXTURE_NONCE, fields.get("nonce")),
            exit));
    }

    private static FakeProcessLauncher answering(
        Function<Map<String, String>, Answer> responder) {
        return FakeProcessLauncher.of("", "", 0)
            .responding(request -> responder.apply(decode(request)));
    }

    /**
     * A complete run (exit 0) with the operation frames {@code frames} between {@code node ok
     * node1} and {@code end}; the frames are written as given, their tokens already encoded.
     */
    private static Answer answer(Map<String, String> fields, String... frames) {
        List<String> all = new ArrayList<>(List.of("start SOAP_81", "login ok", "node ok node1"));
        all.addAll(List.of(frames));
        all.add("end");
        StringBuilder out = new StringBuilder("noise before the frames\n");
        for (String frame : all) {
            out.append("#QE1:").append(fields.get("nonce")).append(' ').append(frame)
                .append('\n');
        }
        return new Answer(out.toString(), 0);
    }

    /** One token, percent-encoded as the helper writes it. */
    private static String enc(String token) {
        return URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20");
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

    /** A client home with {@code lib/ibis.jar}, {@code lib/shared/} and {@code conf/}. */
    private Path client() throws IOException {
        Path client = root.resolve("client");
        Files.createDirectories(client.resolve("lib").resolve("shared"));
        Files.createDirectories(client.resolve("conf"));
        Files.createFile(client.resolve("lib").resolve("ibis.jar"));
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
