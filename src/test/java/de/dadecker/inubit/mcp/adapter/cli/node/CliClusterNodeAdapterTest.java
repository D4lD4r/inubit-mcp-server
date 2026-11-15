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
import de.dadecker.inubit.mcp.domain.model.node.ClusterNode;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodes;
import de.dadecker.inubit.mcp.domain.model.node.NodeDetail;
import de.dadecker.inubit.mcp.domain.model.node.NodeStatus;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T020 (feature 024, FR-010, FR-011, research D-7, D-20): {@code list_cluster_nodes}' port through
 * the INUBIT client helper. A fake launcher reads the request from stdin and answers with the
 * frames of a {@code node-tools/v8_1/cluster-nodes} case (or of the test), carrying the request's
 * nonce. The member and details frames become {@link ClusterNodes}; nothing is left below the
 * temporary root; argument array, environment, logs, results and errors are checked for leaks.
 */
@Timeout(60)
class CliClusterNodeAdapterTest {

    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final InubitVersion V81 = InubitVersion.parse("8.1.17").orElseThrow();
    private static final String FIXTURES = "node-tools/v8_1/cluster-nodes/";
    private static final String FIXTURE_NONCE = "0123456789abcdef0123456789abcdef";
    private static final String USER = "jdoe";
    private static final Duration BUDGET = Duration.ofSeconds(20);
    private static final String UNEXPECTED = "list_cluster_nodes got an unexpected answer from"
        + " test/node1; the unsupported INUBIT interface may have changed after an INUBIT update";
    private static final String NODE1 = "member node1 ACTIVE 8.1.17 zone-a 10.0.0.11%3A7800"
        + " 345600000";
    private static final String NODE2 = "member node2 - - zone-a - -";
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

    // --- the reconstructed cases (T019) -------------------------------------------------------

    @Test
    void theNoAnswerCaseListsBothMembersAndTheRequestAsksForNoDetails() throws IOException {
        launcher = replaying("no_answer");
        EffectiveNodeConfig server = http().build();

        ClusterNodes nodes = adapter(server).nodes(Optional.empty(), V81, deadline());

        assertThat(nodes.answeringNode()).isEqualTo("node1");
        assertThat(nodes.nodes()).containsExactly(
            new ClusterNode("node1", NodeStatus.ACTIVE, Optional.empty(), Optional.of("8.1.17"),
                Optional.of("zone-a"), Optional.of("10.0.0.11:7800"),
                Optional.of(Duration.ofHours(96))),
            new ClusterNode("node2", NodeStatus.UNKNOWN, Optional.empty(), Optional.empty(),
                Optional.of("zone-a"), Optional.empty(), Optional.empty()));
        assertThat(nodes.total()).isEqualTo(2);
        assertThat(nodes.truncated()).isFalse();
        assertThat(nodes.details()).isEmpty();
        assertThat(guard.confirmed()).as("the login was accepted").isTrue();

        Map<String, String> request = decode(launcher.last().stdinBytes());
        assertThat(request).containsOnlyKeys("qe", "op", "nonce", "variant", "url", "trustStore",
            "trustStorePassword", "hostnameVerification", "details", "user", "password");
        assertThat(request).containsEntry("op", "clusterNodes")
            .containsEntry("details", "")
            .containsEntry("variant", "SOAP_81")
            .containsEntry("url", server.cli().url().toString())
            .containsEntry("user", USER)
            .containsEntry("password", TestSecrets.CONFIGURED_PASSWORD);
        assertThat(launcher.last().stdinClosed()).isTrue();
        assertLaunchedWithoutRequestData(launcher.last().spec());
        TestSecrets.assertNoLeak(nodes.toString());
        assertTempRootEmpty();
    }

    @Test
    void theSingleCaseListsOneMember() throws IOException {
        launcher = replaying("single");

        ClusterNodes nodes = adapter(http().build()).nodes(Optional.empty(), V81, deadline());

        assertThat(nodes.nodes()).extracting(ClusterNode::name).containsExactly("node1");
        assertThat(nodes.total()).isEqualTo(1);
        assertTempRootEmpty();
    }

    @Test
    void anUnknownStatusConstantIsUnknownWithTheRawStatus() throws IOException {
        launcher = replaying("unknown_status");

        ClusterNodes nodes = adapter(http().build()).nodes(Optional.empty(), V81, deadline());

        assertThat(nodes.nodes().get(1)).isEqualTo(new ClusterNode("node2", NodeStatus.UNKNOWN,
            Optional.of("STARTING"), Optional.of("8.1.17"), Optional.of("zone-a"),
            Optional.empty(), Optional.of(Duration.ofMinutes(2))));
        assertTempRootEmpty();
    }

    @Test
    void theDetailsCaseReturnsThePairsInOrderMaskedAndTheRequestNamesTheNode()
        throws IOException {
        launcher = replaying("details");

        ClusterNodes nodes = adapter(http().build()).nodes(Optional.of("node1"), V81,
            deadline());

        assertThat(decode(launcher.last().stdinBytes())).containsEntry("op", "clusterNodes")
            .containsEntry("details", "node1");
        assertThat(nodes.nodes()).hasSize(2);
        ClusterNodes.Available details = (ClusterNodes.Available) nodes.details().orElseThrow();
        assertThat(details.node()).isEqualTo("node1");
        assertThat(details.truncated()).isFalse();
        assertThat(details.pairs()).extracting(NodeDetail::key).containsExactly("NodeName",
            "ZoneName", "ClusterBindAddress", "InstallationDirectory", "JavaVersion",
            "Memory.HeapMax", "Memory.HeapUsed", "JvmArguments", "Description");
        assertThat(details.pairs().get(0)).isEqualTo(new NodeDetail("NodeName", "node1", false));
        assertThat(details.pairs().get(3))
            .isEqualTo(new NodeDetail("InstallationDirectory", "/opt/inubit/server", false));
        assertThat(details.pairs().get(8)).isEqualTo(new NodeDetail("Description", "", false));
        NodeDetail jvm = details.pairs().get(7);
        assertThat(jvm.value()).startsWith("-Xmx4g -Djavax.net.ssl.trustStorePassword=")
            .doesNotContain("example-secret");
        assertThat(jvm.cut()).isFalse();
        assertTempRootEmpty();
    }

    @Test
    void theTruncatedDetailsKeepTheHelpersFlagAndCutLongValuesTo2000Characters()
        throws IOException {
        launcher = replaying("details_truncated");

        ClusterNodes nodes = adapter(http().build()).nodes(Optional.of("node1"), V81,
            deadline());

        ClusterNodes.Available details = (ClusterNodes.Available) nodes.details().orElseThrow();
        assertThat(details.truncated()).isTrue();
        assertThat(details.pairs()).hasSize(500);
        NodeDetail classPath = details.pairs().stream()
            .filter(pair -> pair.key().equals("ClassPath")).findFirst().orElseThrow();
        assertThat(classPath.cut()).isTrue();
        assertThat(classPath.value()).hasSize(NodeDetail.MAX_VALUE_CHARS).endsWith("…[cut]")
            .startsWith("/opt/inubit/server/lib/example-001.jar:");
        assertThat(details.pairs()).filteredOn(NodeDetail::cut).hasSize(1);
        assertTempRootEmpty();
    }

    @Test
    void anUnknownDetailsNodeIsInvalidInputNamingTheMembers() throws IOException {
        launcher = replaying("details_unknown");

        ToolError error = failure(adapter(http().build()), Optional.of("node3"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo(
            "Node node3 is not in the cluster of test/node1; known nodes: node1, node2");
        assertThat(error.nextStep()).isEqualTo("Use a name from the list");
        assertThat(decode(launcher.last().stdinBytes())).containsEntry("details", "node3");
        assertTempRootEmpty();
    }

    @Test
    void detailsThatInubitCannotDeliverAreUnavailableWithAScrubbedReasonAndTheListStays()
        throws IOException {
        launcher = replaying("details_error");

        ClusterNodes nodes = adapter(http().build()).nodes(Optional.of("node2"), V81,
            deadline());

        assertThat(nodes.nodes()).hasSize(2);
        assertThat(nodes.details()).contains(
            new ClusterNodes.Unavailable("node2", "Node node2 did not answer. Read timed out"));
        assertTempRootEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "api_missing, CLI_UNAVAILABLE",
        "garbage, UNEXPECTED_RESPONSE"})
    void eachFailingCaseHasItsCodeAndLeavesNothing(String fixtureCase, ErrorCode expected)
        throws IOException {
        launcher = replaying(fixtureCase);

        ToolError error = failure(adapter(http().build()), Optional.empty());

        assertThat(error.code()).isEqualTo(expected);
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertTempRootEmpty();
    }

    // --- member rules (D-7) ------------------------------------------------------------------

    @Test
    void anOfflineMemberWithoutVersionAndWithUptimeZeroHasNoUptime() throws IOException {
        // T018: on 8.1.17 a member that does not answer is OFFLINE with version - and uptime 0
        launcher = answering(fields -> answer(fields, NODE1, "member node2 OFFLINE - - - 0",
            "members ok 2"));

        ClusterNodes nodes = adapter(http().build()).nodes(Optional.empty(), V81, deadline());

        assertThat(nodes.nodes().get(1)).isEqualTo(new ClusterNode("node2", NodeStatus.OFFLINE,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty()));
        assertTempRootEmpty();
    }

    @Test
    void maintenanceIsMappedAsNamedAndUptimeZeroOfAnAnsweringMemberIsKept() throws IOException {
        launcher = answering(fields -> answer(fields, "member node1 MAINTENANCE 8.1.17 - - 0",
            "members ok 1"));

        ClusterNode node = adapter(http().build()).nodes(Optional.empty(), V81, deadline())
            .nodes().get(0);

        assertThat(node.status()).isEqualTo(NodeStatus.MAINTENANCE);
        assertThat(node.rawStatus()).isEmpty();
        assertThat(node.uptime()).contains(Duration.ZERO);
    }

    @Test
    void moreThan200MembersListTheFirst200AndMarkTheRestAsTruncated() throws IOException {
        List<String> frames = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            frames.add("member node" + i + " ACTIVE 8.1.17 - - 1000");
        }
        frames.add("members ok 250");
        launcher = answering(fields -> answer(fields, frames.toArray(String[]::new)));

        ClusterNodes nodes = adapter(http().build()).nodes(Optional.empty(), V81, deadline());

        assertThat(nodes.nodes()).hasSize(200);
        assertThat(nodes.total()).isEqualTo(250);
        assertThat(nodes.truncated()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "member node%2F2 ACTIVE 8.1.17 - - 1000|members ok 2",
        "member node2 STARTING%20UP 8.1.17 - - 1000|members ok 2",
        "member node2 ACTIVE TOO_LONG - - 1000|members ok 2",
        "members ok 3",
        "member node2 - - - - -|members ok 1",
        "member node2 - - - - -",
        "member node2 - - - - -|members ok 2|details ok 0 false",
        "member node2 - - - - -|members ok 2|members ok 2"})
    void aMemberOrFrameOrderThatBreaksTheRulesIsAnUnexpectedResponse(String rest)
        throws IOException {
        String tooLong = "v".repeat(ClusterNode.MAX_TEXT_CHARS + 1);
        List<String> frames = new ArrayList<>(List.of(NODE1));
        for (String frame : rest.split("\\|")) {
            frames.add(frame.replace("TOO_LONG", tooLong));
        }
        launcher = answering(fields -> answer(fields, frames.toArray(String[]::new)));

        ToolError error = failure(adapter(http().build()), Optional.empty());

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.likelyCause()).doesNotContain("node/2", "STARTING UP", tooLong);
        assertTempRootEmpty();
    }

    @Test
    void aMembersErrorIsAnUnexpectedResponseWithAScrubbedExcerpt() throws IOException {
        launcher = answering(fields -> answer(fields, "members error"
            + " com.inubit.ibis.utils.InubitException Cluster%20manager%20failed%20for%20"
            + enc(TestSecrets.CONFIGURED_PASSWORD)));

        ToolError error = failure(adapter(http().build()), Optional.empty());

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.nextStep())
            .isEqualTo("Use the Workbench's Cluster Manager; report the INUBIT version");
        assertThat(error.excerpt()).hasValueSatisfying(
            excerpt -> assertThat(excerpt).startsWith("Cluster manager failed for "));
        assertTempRootEmpty();
    }

    @Test
    void aMembersErrorWithAnAccessDeniedChainIsForbiddenWithTheToolsRefusal() throws IOException {
        launcher = answering(fields -> answer(fields, "members error"
            + " com.inubit.ibis.utils.InubitException%3Ecom.inubit.ibis.AccessDeniedException"
            + " Access%20denied"));

        ToolError error = failure(adapter(http().build()), Optional.empty());

        assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(error.message())
            .isEqualTo("test/node1 refused the cluster's node list for the configured user");
        assertThat(error.likelyCause())
            .isEqualTo("The INUBIT user of this node lacks the right to read the cluster's nodes");
        assertThat(error.nextStep()).isEqualTo(
            "Give the user the right the Workbench's Cluster Manager needs (Configuration)");
        assertTempRootEmpty();
    }

    // --- details rules (D-7) -----------------------------------------------------------------

    @Test
    void manyMembersAreNamedUpTo20InTheInvalidInput() throws IOException {
        List<String> frames = new ArrayList<>();
        for (int i = 1; i <= 23; i++) {
            frames.add("member node" + i + " ACTIVE 8.1.17 - - 1000");
        }
        frames.add("members ok 23");
        frames.add("details unknown");
        launcher = answering(fields -> answer(fields, frames.toArray(String[]::new)));

        ToolError error = failure(adapter(http().build()), Optional.of("other"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).endsWith("; known nodes: node1, node2, node3, node4, node5,"
            + " node6, node7, node8, node9, node10, node11, node12, node13, node14, node15,"
            + " node16, node17, node18, node19, node20 and 3 more");
    }

    @Test
    void detailValuesAndTheReasonHaveTheConfiguredCredentialsMasked() throws IOException {
        String password = enc(TestSecrets.CONFIGURED_PASSWORD);
        launcher = answering(fields -> answer(fields, NODE1, "members ok 1",
            "detail Startup java%20-Dpw%3D" + password, "details ok 1 false"));

        ClusterNodes nodes = adapter(http().build()).nodes(Optional.of("node1"), V81,
            deadline());

        ClusterNodes.Available details = (ClusterNodes.Available) nodes.details().orElseThrow();
        assertThat(details.pairs().get(0).value()).startsWith("java -Dpw=");
        TestSecrets.assertNoLeak(nodes.toString());

        launcher = answering(fields -> answer(fields, NODE1, "members ok 1",
            "details error java.io.IOException Failed%20with%20" + password));
        ClusterNodes failed = adapter(http().build()).nodes(Optional.of("node1"), V81,
            deadline());

        assertThat(((ClusterNodes.Unavailable) failed.details().orElseThrow()).reason())
            .startsWith("Failed with ");
        TestSecrets.assertNoLeak(failed.toString());
    }

    @Test
    void aSecretIsMaskedBeforeTheValueIsCut() throws IOException {
        // 2,020 characters as received: a cut at 2,000 first would keep a piece of the password
        String prefix = "x".repeat(1995) + " ";
        launcher = answering(fields -> answer(fields, NODE1, "members ok 1",
            "detail Long " + enc(prefix + TestSecrets.CONFIGURED_PASSWORD),
            "details ok 1 false"));

        ClusterNodes nodes = adapter(http().build()).nodes(Optional.of("node1"), V81,
            deadline());

        NodeDetail pair = ((ClusterNodes.Available) nodes.details().orElseThrow()).pairs().get(0);
        assertThat(pair).isEqualTo(new NodeDetail("Long", prefix + SecretScrubber.MASK, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "detail Key value|details ok 2 false",
        "detail Key value",
        "detail Key value|details unknown",
        "details ok 0 false|details ok 0 false",
        "details error java.io.IOException failed|details ok 0 false"})
    void detailsFramesThatBreakTheRulesAreAnUnexpectedResponse(String rest) throws IOException {
        List<String> frames = new ArrayList<>(List.of(NODE1, "members ok 1"));
        if (!rest.isEmpty()) {
            frames.addAll(List.of(rest.split("\\|")));
        }
        launcher = answering(fields -> answer(fields, frames.toArray(String[]::new)));

        ToolError error = failure(adapter(http().build()), Optional.of("node1"));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertTempRootEmpty();
    }

    @Test
    void aDetailKeyLongerThan200CharactersIsAnUnexpectedResponse() throws IOException {
        String key = "k".repeat(NodeDetail.MAX_KEY_CHARS + 1);
        launcher = answering(fields -> answer(fields, NODE1, "members ok 1",
            "detail " + key + " value", "details ok 1 false"));

        ToolError error = failure(adapter(http().build()), Optional.of("node1"));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.likelyCause()).doesNotContain(key);
    }

    // --- the budget setting -------------------------------------------------------------------

    @Test
    void aHangingHelperTimesOutNamingCliTimeout() throws IOException {
        launcher = answering(fields -> answer(fields, NODE1)).hanging();

        ToolError error = failure(adapter(http().build()), Optional.empty(),
            System.nanoTime() + Duration.ofMillis(500).toNanos());

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("raise cliTimeout ");
        assertThat(launcher.last().destroyed()).as("process stopped").isTrue();
        assertTempRootEmpty();
    }

    // --- helpers ----------------------------------------------------------------------------

    private CliClusterNodeAdapter adapter(EffectiveNodeConfig server) {
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
        return new CliClusterNodeAdapter(helper, scrubber, server.secretLabels());
    }

    private TestNodeConfig http() {
        return TestNodeConfig.node().id(NODE.value()).baseUrl("http://inubit.example.test:8000")
            .allowInsecureHttp(true).cliHome(home).cliJavaHome(jdk);
    }

    private static long deadline() {
        return System.nanoTime() + BUDGET.toNanos();
    }

    private ToolError failure(CliClusterNodeAdapter adapter, Optional<String> details) {
        return failure(adapter, details, deadline());
    }

    /** The error of a call; it carries the node and leaks nothing. */
    private static ToolError failure(CliClusterNodeAdapter adapter, Optional<String> details,
        long deadline) {
        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class,
            () -> adapter.nodes(details, V81, deadline));
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
        assertThat(argv).doesNotContain(USER, "clusterNodes", "inubit.example.test");
        assertThat(spec.command().get(spec.command().size() - 1))
            .isEqualTo(HelperInstallation.MAIN_CLASS);
    }

    /** Replays {@code <case>.frames}/{@code .exit} of the cluster-nodes fixtures with the nonce. */
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
