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
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFailures;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.IoListOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperTrustStore;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.TlsTestServer;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
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
 * T016 (feature 024, research D-3, D-20, D-24): one run of the INUBIT client helper for a node
 * tool, against a {@link FakeProcessLauncher} that answers the request on stdin with frames: the
 * argv names the helper class and nothing of the request, the password goes to stdin only, the
 * private directory {@code inubit-mcp-node-…} is deleted after every outcome, the slot wait and
 * the run end in {@code TIMEOUT} naming the caller's budget setting, and the shared failures are
 * mapped by {@link HelperFailures}. Every log line and error is checked for leaks.
 */
@Timeout(30)
class NodeHelperRunnerTest {

    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final InubitVersion V81 = InubitVersion.parse("8.1.17").orElseThrow();
    /** The capability of {@link #SUBJECT}. */
    private static final Capability CAPABILITY = Capability.CLUSTER_NODES_READ;
    private static final String USER = "jdoe";
    private static final Duration BUDGET = Duration.ofSeconds(20);
    private static final TestCertificates CERTS = TestCertificates.get();
    private static final HelperFailures.Subject SUBJECT = new HelperFailures.Subject(
        "INUBIT client helper", "list_cluster_nodes", "Check the cluster in the Workbench",
        "refused the node list for the configured user", "The user lacks the right",
        "Give the user the right");
    /** The server's environment, with credentials the helper must never get (leak check). */
    private static final Map<String, String> PARENT = Map.of(
        "PATH", "/usr/bin:/bin",
        "HOME", "/home/test",
        "INUBIT_PASSWORD", TestSecrets.CONFIGURED_PASSWORD,
        "INUBIT_TRUST_STORE_PASSWORD", TestSecrets.TRUST_STORE_PASSWORD);
    private static final Function<Path, HelperRequest.Operation> OWN_NODE =
        out -> new HelperRequest.OwnNode();

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

    // --- success -----------------------------------------------------------------------------

    @Test
    void anOwnNodeRunAnswersTheNodeAndPassesThePasswordOnStdinOnly() throws IOException {
        AtomicReference<Path> directoryDuringTheRun = new AtomicReference<>();
        launcher = answering(fields -> {
            directoryDuringTheRun.set(launcherDirectory());
            return answer(0, fields, "start SOAP_81", "login ok", "node ok node1", "end");
        });
        EffectiveNodeConfig server = http().build();
        Path directory;

        try (NodeHelperRunner.Run run = runner(server).run(CAPABILITY, V81, OWN_NODE,
            classifier(server),
            "cliTimeout", deadline())) {
            assertThat(run.inubitNode()).isEqualTo("node1");
            assertThat(run.frames()).isEmpty();
            directory = run.outDirectory().getParent();
            assertThat(directory.getParent()).isEqualTo(tempRoot);
            assertThat(directory.getFileName().toString())
                .startsWith(CliResources.NODE_PREFIX + "acme-" + ProcessHandle.current().pid()
                    + "-");
            assertThat(directory).isEqualTo(directoryDuringTheRun.get());
        }
        assertThat(directory).as("deleted when the run is closed").doesNotExist();
        assertThat(guard.confirmed()).as("the login was accepted").isTrue();

        Map<String, String> request = decode(launcher.last().stdinBytes());
        assertThat(request).containsOnlyKeys("qe", "op", "nonce", "variant", "url", "trustStore",
            "trustStorePassword", "hostnameVerification", "user", "password");
        assertThat(request).containsEntry("op", "ownNode")
            .containsEntry("variant", "SOAP_81")
            .containsEntry("url", server.cli().url().toString())
            .containsEntry("trustStore", "")
            .containsEntry("user", USER)
            .containsEntry("password", TestSecrets.CONFIGURED_PASSWORD);
        assertThat(launcher.last().stdinClosed()).isTrue();
        assertLaunchedWithoutRequestData(launcher.last().spec());
        assertTempRootEmpty();
    }

    @Test
    void theOperationIsBuiltInTheOutDirectoryAndItsFilesStayUntilTheRunIsClosed()
        throws IOException {
        launcher = answering(fields -> {
            write(fields.get("listOut"), "P1@@@Orders(Module-0001)@@@Input.0\n");
            return answer(0, fields, "start SOAP_81", "login ok", "node ok node1", "io list ok 1",
                "end");
        });
        EffectiveNodeConfig server = http().build();
        Path list;

        try (NodeHelperRunner.Run run = runner(server).run(CAPABILITY, V81,
            out -> new HelperRequest.IoLog("P1", "Orders", Optional.empty(),
                out.resolve("io.list"), Optional.empty()),
            classifier(server), "cliTimeout", deadline())) {
            list = run.outDirectory().resolve("io.list");
            assertThat(run.frames()).containsExactly(new IoListOk(1));
            assertThat(list).hasContent("P1@@@Orders(Module-0001)@@@Input.0");
            assertThat(decode(launcher.last().stdinBytes())).containsEntry("op", "ioLog")
                .containsEntry("listOut", list.toString());
        }
        assertThat(list).doesNotExist();
        assertTempRootEmpty();
    }

    @Test
    void nineZeroUsesTheRestVariant() throws IOException {
        launcher = answering(fields -> answer(0, fields, "start REST_90", "login ok",
            "node ok node1", "end"));
        EffectiveNodeConfig server = http().build();

        runner(server).run(CAPABILITY, InubitVersion.parse("9.0.10").orElseThrow(), OWN_NODE,
            classifier(server), "cliTimeout", deadline()).close();

        assertThat(decode(launcher.last().stdinBytes())).containsEntry("variant", "REST_90");
        assertTempRootEmpty();
    }

    @Test
    void anHttpsNodeGetsTheCallsOwnTrustStoreForTheRun() throws Exception {
        AtomicBoolean trustStoreDuringTheRun = new AtomicBoolean();
        launcher = answering(fields -> {
            trustStoreDuringTheRun.set(Files.isRegularFile(Path.of(fields.get("trustStore"))));
            return answer(0, fields, "start SOAP_81", "login ok", "node ok node1", "end");
        });
        try (TlsTestServer tls = TlsTestServer.start(CERTS.localhostKeyStore())) {
            EffectiveNodeConfig server = https(tls.port())
                .pin(TestCertificates.fingerprint(CERTS.localhostCertificate())).build();

            try (NodeHelperRunner.Run run = runner(server).run(CAPABILITY, V81, OWN_NODE,
                classifier(server), "cliTimeout", deadline())) {
                Map<String, String> request = decode(launcher.last().stdinBytes());
                assertThat(request.get("trustStore")).isEqualTo(run.outDirectory().getParent()
                    .resolve(HelperTrustStore.FILE_NAME).toString());
                assertThat(request.get("trustStorePassword")).isNotBlank();
                assertThat(String.join(" ", launcher.last().spec().command()))
                    .doesNotContain(request.get("trustStorePassword"));
                assertThat(launcher.last().spec().environment().toString())
                    .doesNotContain(request.get("trustStorePassword"));
            }
            assertThat(tls.handshakes()).isEqualTo(1);
        }
        assertThat(trustStoreDuringTheRun).isTrue();
        assertTempRootEmpty();
    }

    @Test
    void anUntrustedCertificateLaunchesNothingAndReleasesTheSlot() throws Exception {
        launcher = answering(fields -> answer(0, fields, "start SOAP_81", "login ok",
            "node ok node1", "end"));
        EffectiveNodeConfig server;
        ToolError error;
        try (TlsTestServer tls = TlsTestServer.start(CERTS.rogueKeyStore())) {
            server = https(tls.port()).build();

            error = failure(runner(server), "cliTimeout", deadline());
        }

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(launcher.launchCount()).isZero();
        assertBothSlotsFree(server);
        assertTempRootEmpty();
    }

    // --- failures of the run (HelperFailures, NodeFramesClassifier) --------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "3 | login error java.io.IOException HTTP 403 Forbidden | FORBIDDEN",
        "3 | login error javax.net.ssl.SSLHandshakeException PKIX path | TLS_ERROR",
        "3 | login error java.net.ConnectException refused | UNREACHABLE",
        "0 | node error java.io.IOException no node | UNEXPECTED_RESPONSE",
        "0 | node ok node/1 | UNEXPECTED_RESPONSE",
        "1 | node ok node1 | UNEXPECTED_RESPONSE"})
    void eachFailingRunHasItsCodeAndLeavesNothing(int exit, String frame, ErrorCode expected)
        throws IOException {
        launcher = frame.startsWith("login")
            ? answering(fields -> answer(exit, fields, "start SOAP_81", frame))
            : answering(fields -> answer(exit, fields, "start SOAP_81", "login ok", frame, "end"));

        ToolError error = failure(runner(http().build()), "cliTimeout", deadline());

        assertThat(error.code()).isEqualTo(expected);
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertTempRootEmpty();
    }

    @Test
    void aMissingVendorApiIsCliUnavailable() throws IOException {
        launcher = answering(fields -> answer(4, fields,
            "api missing com.inubit.ibis.server.cluster.ClusterClient.getNode()"));

        ToolError error = failure(runner(http().build()), "cliTimeout", deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.nextStep()).contains("defaults.cliClients.\"8.1\"");
        assertTempRootEmpty();
    }

    @Test
    void garbageFramesAreAnUnexpectedResponse() throws IOException {
        launcher = answering(fields -> new Answer("#QE1:" + fields.get("nonce")
            + " start SOAP_81\n#QE1:" + fields.get("nonce") + " what is this\n", 0));

        ToolError error = failure(runner(http().build()), "cliTimeout", deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo("list_cluster_nodes got an unexpected answer from"
            + " test/node1; the unsupported INUBIT interface may have changed after an INUBIT"
            + " update");
        assertTempRootEmpty();
    }

    @Test
    void aLaunchFailureIsCliUnavailableAndLeavesNothing() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0)
            .failingToLaunch(new IOException("java not found"));

        ToolError error = failure(runner(http().build()), "cliTimeout", deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertTempRootEmpty();
    }

    @Test
    void aRejectedLoginBacksOffTheGuard() throws IOException {
        launcher = answering(fields -> answer(3, fields, "start SOAP_81",
            "login error com.inubit.ibis.utils.LoginFailure password does not match"));
        NodeHelperRunner runner = runner(http().build());

        ToolError rejected = failure(runner, "cliTimeout", deadline());
        ToolError cached = failure(runner, "cliTimeout", deadline());

        assertThat(rejected.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(rejected.message())
            .isEqualTo("Login of the INUBIT client helper to test/node1 was rejected");
        assertThat(cached.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(launcher.launchCount()).as("no login within the pause").isEqualTo(1);
        assertTempRootEmpty();
    }

    // --- the budget setting: slot wait and run (D-24) -----------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"cliTimeout", "cliExportTimeout"})
    void aHangingHelperTimesOutNamingTheBudgetSettingAndLeavesNothing(String budget)
        throws IOException {
        launcher = answering(fields -> answer(0, fields, "start SOAP_81")).hanging();

        ToolError error = failure(runner(http().build()), budget,
            System.nanoTime() + Duration.ofMillis(500).toNanos());

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("raise " + budget + " ");
        assertThat(launcher.last().destroyed()).as("process stopped").isTrue();
        assertThat(launcher.last().treeAlive()).isFalse();
        assertBothSlotsFree(http().build());
        assertTempRootEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cliTimeout", "cliExportTimeout"})
    void bothSlotsHeldByOthersEndTheSlotWaitInTimeoutNamingTheBudgetSetting(String budget)
        throws IOException {
        launcher = answering(fields -> answer(0, fields, "start SOAP_81", "login ok",
            "node ok node1", "end"));
        EffectiveNodeConfig server = http().build();

        ToolError error;
        long start = System.nanoTime();
        try (CliConcurrencyLimiter.Permit first =
                 limiter.acquireForHelper(server, Duration.ZERO, "cliTimeout");
             CliConcurrencyLimiter.Permit second =
                 limiter.acquireForHelper(server, Duration.ZERO, "cliTimeout")) {
            error = failure(runner(server), budget, start + Duration.ofMillis(300).toNanos());
        }

        assertThat(Duration.ofNanos(System.nanoTime() - start))
            .isGreaterThanOrEqualTo(Duration.ofMillis(300)).isLessThan(Duration.ofSeconds(10));
        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.likelyCause()).contains("other StartCLI runs on the node");
        assertThat(error.nextStep()).contains("raise " + budget + " ");
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void theRunHoldsASlotAndReleasesItWhenDone() throws IOException {
        EffectiveNodeConfig server = http().build();
        AtomicReference<ErrorCode> slotDuringRun = new AtomicReference<>();
        launcher = answering(fields -> {
            slotDuringRun.set(errorOf(() -> limiter.acquireForHelper(server, Duration.ZERO,
                "cliTimeout").close()).code());
            return answer(0, fields, "start SOAP_81", "login ok", "node ok node1", "end");
        });

        try (CliConcurrencyLimiter.Permit other =
                 limiter.acquireForHelper(server, Duration.ZERO, "cliTimeout")) {
            runner(server).run(CAPABILITY, V81, OWN_NODE, classifier(server), "cliTimeout",
                deadline())
                .close();
        }

        assertThat(slotDuringRun).as("the run held the second slot").hasValue(ErrorCode.TIMEOUT);
        assertBothSlotsFree(server);
        assertTempRootEmpty();
    }

    @Test
    void aDeadlineAlreadyPassedLaunchesNothing() throws IOException {
        launcher = answering(fields -> answer(0, fields, "start SOAP_81", "login ok",
            "node ok node1", "end"));

        ToolError error = failure(runner(http().build()), "cliExportTimeout",
            System.nanoTime() - 1);

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("raise cliExportTimeout");
        assertThat(launcher.launchCount()).isZero();
        assertThat(guard.confirmed()).as("no login result").isFalse();
        assertTempRootEmpty();
    }

    @Test
    void aShutdownDuringTheRunStopsTheHelperAndRemovesTheDirectory() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Thread.ofVirtual().start(resources::close);
            return new Answer("", 0);
        }).hanging();

        ToolError error = failure(runner(http().build()), "cliTimeout", deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).contains("shutting down");
        assertTempRootEmpty();
    }

    // --- before the launch: version, credentials, client --------------------------------------

    /**
     * A version the helper has no variant for (the gate refuses it first): the refusal names the
     * node tool's capability and the gate's next step for it, not the queue-entry helper of
     * feature 020.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "CLUSTER_NODES_READ|reading the cluster's node list|Use the Workbench's Cluster Manager",
        "IO_LOG_READ|reading module input/output logging"
            + "|Use the Workbench's input/output logging view",
        "NODE_CONFIG_READ|reading node configuration values|Use the Workbench's Cluster Manager",
        "LOG_LEVEL_WRITE|changing temporary log levels|Use StartCLI trace -nl/-r by hand"})
    void anUnsupportedVersionNamesTheNodeToolAndLaunchesNothing(Capability capability,
        String label, String nextStep) throws IOException {
        launcher = answering(fields -> answer(0, fields, "end"));
        EffectiveNodeConfig server = http().build();

        ToolError error = failure(runner(server), capability,
            InubitVersion.parse("9.1.0").orElseThrow(), "cliTimeout", deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).isEqualTo("INUBIT 9.1.0 on test/node1 does not support "
            + label + " through the INUBIT client helper; supported: 8.1, 9.0; nothing was sent");
        assertThat(error.likelyCause()).isEqualTo("The unsupported INUBIT interface the INUBIT"
            + " client helper uses is only known for INUBIT 8.1 and 9.0");
        assertThat(error.nextStep()).isEqualTo(nextStep);
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void withoutAClientHomeTheVersionEntryToAddIsNamed() throws IOException {
        launcher = answering(fields -> answer(0, fields, "end"));
        EffectiveNodeConfig server = TestNodeConfig.node().id(NODE.value())
            .baseUrl("http://inubit.example.test:8000").allowInsecureHttp(true)
            .cliClient("9.0", home, Optional.of(jdk)).build();

        ToolError error = failure(runner(server), "cliTimeout", deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The INUBIT client helper cannot be started for"
            + " test/node1 (INUBIT 8.1.17): no INUBIT client installation is configured");
        assertThat(error.nextStep()).contains("defaults.cliClients.\"8.1\"");
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void incompleteCredentialsLaunchNothing() throws IOException {
        launcher = answering(fields -> answer(0, fields, "end"));
        NodeCredentials userOnly = new NodeCredentials(NODE,
            Optional.of(new SourcedValue<>(USER, "test")), Optional.empty(), Optional.empty(),
            Optional.empty());

        ToolError error = failure(runner(http().build(), () -> userOnly), V81, "cliTimeout",
            deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    // --- the log ------------------------------------------------------------------------------

    @Test
    void theDebugLogNamesOperationVariantClientAndJdkOnly() throws IOException {
        launcher = answering(fields -> answer(0, fields, "start SOAP_81", "login ok",
            "node ok node1", "end"));
        EffectiveNodeConfig server = http().build();

        runner(server).run(CAPABILITY, V81, OWN_NODE, classifier(server), "cliTimeout",
            deadline()).close();

        List<ILoggingEvent> events = appender.list.stream()
            .filter(event -> event.getLoggerName().equals(NodeHelperRunner.class.getName()))
            .toList();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).isEqualTo("test/node1: INUBIT client helper"
                + " OwnNode, SOAP_81, client " + home + ", JDK " + jdk);
        });
        String all = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList()
            .toString();
        assertThat(all).doesNotContain(USER, "#QE1");
    }

    // --- helpers ----------------------------------------------------------------------------

    private NodeHelperRunner runner(EffectiveNodeConfig server) {
        NodeCredentials credentials = new NodeCredentials(NODE,
            Optional.of(new SourcedValue<>(USER, "test")),
            Optional.of(new SourcedValue<>(scrubber.register(TestSecrets.CONFIGURED_PASSWORD),
                "test")),
            Optional.empty(), Optional.empty());
        return runner(server, () -> credentials);
    }

    private NodeHelperRunner runner(EffectiveNodeConfig server,
        Supplier<NodeCredentials> credentials) {
        guard = new CredentialGuard(server.secretLabels(), Clock.systemUTC());
        CliRunner runner = TestCliRunners.forHost(launcher, PARENT, resources);
        return new NodeHelperRunner(server, credentials, guard, runner,
            TestCliRunners.helperHost(PARENT, resources, tempRoot),
            new ProcessRun(launcher, resources), limiter);
    }

    private NodeFramesClassifier classifier(EffectiveNodeConfig server) {
        return new NodeFramesClassifier(scrubber, server.secretLabels(), SUBJECT);
    }

    private TestNodeConfig http() {
        return TestNodeConfig.node().id(NODE.value()).baseUrl("http://inubit.example.test:8000")
            .allowInsecureHttp(true).cliHome(home).cliJavaHome(jdk);
    }

    private TestNodeConfig https(int port) {
        return TestNodeConfig.node().id(NODE.value()).baseUrl("https://127.0.0.1:" + port)
            .trustStore(CERTS.trustStore()).cliHome(home).cliJavaHome(jdk);
    }

    private static long deadline() {
        return System.nanoTime() + BUDGET.toNanos();
    }

    private ToolError failure(NodeHelperRunner runner, String budget, long deadline) {
        return failure(runner, V81, budget, deadline);
    }

    private ToolError failure(NodeHelperRunner runner, InubitVersion version, String budget,
        long deadline) {
        return failure(runner, CAPABILITY, version, budget, deadline);
    }

    /** The error of an {@code ownNode} run; it carries the node and leaks nothing. */
    private ToolError failure(NodeHelperRunner runner, Capability capability,
        InubitVersion version, String budget, long deadline) {
        EffectiveNodeConfig server = http().build();
        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class,
            () -> runner.run(capability, version, OWN_NODE, classifier(server), budget,
                deadline).close());
        assertThat(thrown).as("the run fails").isNotNull();
        ToolError error = thrown.error();
        assertThat(error.node()).contains(NODE);
        TestSecrets.assertNoLeak(error.toString());
        return error;
    }

    private static ToolError errorOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    /** Both slots of {@code server} are free: two can be taken at once. */
    private void assertBothSlotsFree(EffectiveNodeConfig server) {
        try (CliConcurrencyLimiter.Permit first =
                 limiter.acquireForHelper(server, Duration.ZERO, "cliTimeout");
             CliConcurrencyLimiter.Permit second =
                 limiter.acquireForHelper(server, Duration.ZERO, "cliTimeout")) {
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
        }
    }

    /** The one private directory below the temporary root while the helper runs. */
    private Path launcherDirectory() {
        try (Stream<Path> entries = Files.list(tempRoot)) {
            return entries.toList().get(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        assertThat(argv).doesNotContain(USER, "ownNode", "inubit.example.test");
        assertThat(spec.command().get(spec.command().size() - 1))
            .isEqualTo(HelperInstallation.MAIN_CLASS);
        assertThat(spec.command()).filteredOn(argument -> !argument.startsWith("-"))
            .as("the JVM, the class path and the helper class only").hasSize(3);
    }

    private static FakeProcessLauncher answering(
        Function<Map<String, String>, Answer> responder) {
        return FakeProcessLauncher.of("", "", 0)
            .responding(request -> responder.apply(decode(request)));
    }

    /** Frames of space-separated tokens; the tokens after the fourth are joined, then encoded. */
    private static Answer answer(int exit, Map<String, String> fields, String... frames) {
        StringBuilder out = new StringBuilder("noise before the frames\n");
        for (String frame : frames) {
            String[] tokens = frame.split(" ", 4);
            out.append("#QE1:").append(fields.get("nonce"));
            for (String token : tokens) {
                out.append(' ')
                    .append(URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20"));
            }
            out.append('\n');
        }
        return new Answer(out.toString(), exit);
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

    private static void write(String file, String content) {
        try {
            Files.writeString(Path.of(file), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
