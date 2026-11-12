package de.dadecker.inubit.mcp.adapter.cli.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperTrustStore;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.TlsTestServer;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LogPresence;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.DownloadedLog;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

/**
 * T041 (feature 021, FR-006, FR-012, research D-2, D-6, D-16): the web service log through the
 * INUBIT client helper. A fake launcher reads the request from stdin, copies the case's
 * {@code server-log/v8_1/webservice/<case>.log} to the request's {@code logOut} and answers with
 * the case's frames carrying the request's nonce. Nothing is left below the temporary root after
 * a failure or after the downloaded log is closed; argument array, environment, logs and errors
 * are checked for leaks (SC-003).
 */
@Timeout(60)
class CliWebServiceLogAdapterTest {

    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final InubitVersion V81 = InubitVersion.parse("8.1.17").orElseThrow();
    private static final InubitVersion V90 = InubitVersion.parse("9.0.10").orElseThrow();
    private static final String FIXTURES = "server-log/v8_1/webservice/";
    private static final String FIXTURE_NONCE = "0123456789abcdef0123456789abcdef";
    private static final String USER = "jdoe";
    private static final Duration BUDGET = Duration.ofSeconds(20);
    private static final TestCertificates CERTS = TestCertificates.get();
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

    // --- success ---------------------------------------------------------------------------

    @Test
    void theOkCaseReturnsTheLogOfTheReportedNodeAndTheRequestNamesNoFile() throws IOException {
        launcher = replaying("ok");
        EffectiveNodeConfig server = http().build();
        Path directory;

        try (DownloadedLog log = adapter(server).download(V81, deadline())) {
            directory = log.file().getParent().getParent();
            assertThat(directory.getParent()).isEqualTo(tempRoot);
            assertThat(directory.getFileName().toString())
                .startsWith(CliResources.LOG_PREFIX + "acme-");
            assertThat(log.file()).isEqualTo(directory.resolve("out").resolve("webservice.log"));
            assertThat(log.file()).hasBinaryContent(fixture("ok.log"));
            assertThat(log.size()).isEqualTo(fixture("ok.log").length);
            assertThat(log.inubitNode()).contains("node1");
            assertThat(log.presence()).isEqualTo(LogPresence.PRESENT);
        }
        assertThat(directory).as("deleted when the log is closed").doesNotExist();
        assertThat(guard.confirmed()).as("the login was accepted").isTrue();

        Map<String, String> request = decode(launcher.last().stdinBytes());
        assertThat(request).containsOnlyKeys("qe", "op", "nonce", "variant", "url", "trustStore",
            "trustStorePassword", "hostnameVerification", "log", "logOut", "user", "password");
        assertThat(request).containsEntry("op", "serverLog")
            .containsEntry("variant", "SOAP_81")
            .containsEntry("url", server.cli().url().toString())
            .containsEntry("trustStore", "")
            .containsEntry("trustStorePassword", "")
            .containsEntry("hostnameVerification", "true")
            .containsEntry("log", "webservice")
            .containsEntry("logOut", directory.resolve("out").resolve("webservice.log").toString())
            .containsEntry("user", USER)
            .containsEntry("password", TestSecrets.CONFIGURED_PASSWORD);
        assertThat(request.get("nonce")).matches("[0-9a-f]{32}");
        assertThat(launcher.last().stdinClosed()).isTrue();
        assertLaunchedWithoutSecrets(launcher.last().spec());
        assertTempRootEmpty();
    }

    @Test
    void nineZeroUsesTheRestVariant() throws IOException {
        launcher = replaying("ok", "REST_90");

        try (DownloadedLog log = adapter(http().build()).download(V90, deadline())) {
            assertThat(log.inubitNode()).contains("node1");
        }

        assertThat(decode(launcher.last().stdinBytes())).containsEntry("variant", "REST_90");
        assertTempRootEmpty();
    }

    @Test
    void anEmptyLogIsPresentWithZeroBytes() throws IOException {
        launcher = replaying("empty");

        try (DownloadedLog log = adapter(http().build()).download(V81, deadline())) {
            assertThat(log.size()).isZero();
            assertThat(log.file()).isEmptyFile();
            assertThat(log.presence()).isEqualTo(LogPresence.PRESENT);
        }
        assertTempRootEmpty();
    }

    @Test
    void anHttpsNodeGetsTheCallsOwnTrustStoreForTheRun() throws Exception {
        AtomicBoolean trustStoreDuringTheRun = new AtomicBoolean();
        byte[] content = fixture("ok.log");
        launcher = answering(fields -> {
            trustStoreDuringTheRun.set(Files.isRegularFile(Path.of(fields.get("trustStore"))));
            write(fields.get("logOut"), content);
            return answer(0, fields, "start SOAP_81", "login ok", "node ok node1",
                "part log ok " + content.length, "end");
        });
        try (TlsTestServer tls = TlsTestServer.start(CERTS.localhostKeyStore())) {
            EffectiveNodeConfig server = https(tls.port())
                .pin(TestCertificates.fingerprint(CERTS.localhostCertificate())).build();

            try (DownloadedLog log = adapter(server).download(V81, deadline())) {
                Path directory = log.file().getParent().getParent();
                Map<String, String> request = decode(launcher.last().stdinBytes());
                assertThat(request.get("trustStore"))
                    .isEqualTo(directory.resolve(HelperTrustStore.FILE_NAME).toString());
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

    // --- the cases of the reconstructed recordings (D-14, D-21) ------------------------------

    @ParameterizedTest
    @CsvSource({
        "missing, NOT_FOUND",
        "forbidden, FORBIDDEN",
        "not_cluster_mode, UNEXPECTED_RESPONSE",
        "garbage_frames, UNEXPECTED_RESPONSE",
        "api_missing, CLI_UNAVAILABLE"})
    void eachFailingCaseHasItsCodeAndLeavesNothing(String fixtureCase, ErrorCode expected)
        throws IOException {
        launcher = replaying(fixtureCase);

        ToolError error = failure(adapter(http().build()), V81);

        assertThat(error.code()).isEqualTo(expected);
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertTempRootEmpty();
    }

    @Test
    void anInvalidNodeNameIsAnUnexpectedResponse() throws IOException {
        launcher = answering(fields -> answer(0, fields, "start SOAP_81", "login ok",
            "node ok node/1", "part log ok 0", "end"));

        ToolError error = failure(adapter(http().build()), V81);

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertTempRootEmpty();
    }

    @Test
    void aFileThatDoesNotHaveTheReportedLengthIsAnUnexpectedResponse() throws IOException {
        launcher = answering(fields -> answer(0, fields, "start SOAP_81", "login ok",
            "node ok node1", "part log ok 10", "end"));

        ToolError error = failure(adapter(http().build()), V81);

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo("download_server_log got an unexpected answer from"
            + " test/node1; the unsupported INUBIT interface may have changed after an INUBIT"
            + " update");
        assertThat(error.likelyCause()).isEqualTo("The INUBIT client helper reported 10 bytes,"
            + " but did not leave a regular file of that length");
        assertTempRootEmpty();
    }

    @Test
    void aRejectedLoginBacksOffTheGuard() throws IOException {
        launcher = replayingResource("queue-entry/v8_1/login_failure");
        CliWebServiceLogAdapter adapter = adapter(http().build());

        ToolError rejected = failure(adapter, V81);
        ToolError cached = failure(adapter, V81);

        assertThat(rejected.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(rejected.message()).isEqualTo(
            "Login of the INUBIT client helper to test/node1 was rejected");
        assertThat(cached.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(cached.message()).contains("authentication failed recently");
        assertThat(launcher.launchCount()).as("no login within the pause").isEqualTo(1);
        assertTempRootEmpty();
    }

    // --- before the launch: version, credentials, client (D-6, D-16) -------------------------

    @Test
    void anUnsupportedVersionLaunchesNothing() throws IOException {
        launcher = replaying("ok");

        ToolError error = failure(adapter(http().build()),
            InubitVersion.parse("9.1.0").orElseThrow());

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void withoutAClientHomeTheVersionEntryToAddIsNamed() throws IOException {
        launcher = replaying("ok");
        EffectiveNodeConfig server = TestNodeConfig.node().id(NODE.value())
            .baseUrl("http://inubit.example.test:8000").allowInsecureHttp(true)
            .cliClient("9.0", home, Optional.of(jdk)).build();

        ToolError error = failure(adapter(server), V81);

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The INUBIT client helper cannot be started for"
            + " test/node1 (INUBIT 8.1.17): no INUBIT client installation is configured");
        assertThat(error.nextStep()).contains("defaults.cliClients.\"8.1\"");
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void aClientWithoutIbisJarIsUnavailable() throws IOException {
        launcher = replaying("ok");
        Files.delete(home.resolve("lib").resolve("ibis.jar"));

        ToolError error = failure(adapter(http().build()), V81);

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).contains("ibis.jar");
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void aClientWithoutJdkIsUnavailable() throws IOException {
        launcher = replaying("ok");

        ToolError error = failure(adapter(http().cliJavaHome(root.resolve("no-jdk")).build()),
            V81);

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void unresolvableCredentialsLaunchNothingAndCheckNoTls() throws Exception {
        launcher = replaying("ok");
        ToolErrorException unresolved = new ToolErrorException(ToolError.of(
            ErrorCode.SECRET_UNRESOLVED, "The password of test/node1 could not be resolved",
            "The secret store has no entry for it", "Add the entry").withNode(NODE));
        try (TlsTestServer tls = TlsTestServer.start(CERTS.localhostKeyStore())) {
            EffectiveNodeConfig server = https(tls.port())
                .pin(TestCertificates.fingerprint(CERTS.localhostCertificate())).build();

            ToolError error = failure(adapter(server, () -> {
                throw unresolved;
            }), V81);

            assertThat(error).isEqualTo(unresolved.error());
            assertThat(tls.connections()).as("no TLS check").isZero();
        }
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void incompleteCredentialsLaunchNothing() throws IOException {
        launcher = replaying("ok");
        NodeCredentials userOnly = new NodeCredentials(NODE,
            Optional.of(new SourcedValue<>(USER, "test")), Optional.empty(), Optional.empty(),
            Optional.empty());

        ToolError error = failure(adapter(http().build(), () -> userOnly), V81);

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.message()).isEqualTo("No INUBIT credentials are configured for"
            + " test/node1, so the INUBIT client helper cannot log in");
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void aPinMismatchLaunchesNothing() throws Exception {
        launcher = replaying("ok");
        try (TlsTestServer tls = TlsTestServer.start(CERTS.localhostKeyStore())) {
            EffectiveNodeConfig server = https(tls.port())
                .pin(TestCertificates.fingerprint(CERTS.selfsignedCertificate())).build();

            ToolError error = failure(adapter(server), V81);

            assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
            assertThat(error.message()).endsWith("; the INUBIT client helper was not started");
        }
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void anUntrustedCertificateLaunchesNothing() throws Exception {
        launcher = replaying("ok");
        try (TlsTestServer tls = TlsTestServer.start(CERTS.rogueKeyStore())) {

            ToolError error = failure(adapter(https(tls.port()).build()), V81);

            assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        }
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    // --- the deadline (D-15) -------------------------------------------------------------------

    @Test
    void aHangingHelperTimesOutNamingCliExportTimeoutAndLeavesNothing() throws IOException {
        launcher = replaying("ok").hanging();

        ToolError error = failure(adapter(http().build()), V81,
            System.nanoTime() + Duration.ofMillis(500).toNanos());

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("raise cliExportTimeout")
            .doesNotContain("raise cliTimeout");
        assertThat(launcher.last().destroyed()).isTrue();
        assertTempRootEmpty();
    }

    @Test
    void aDeadlinePassedWhileWaitingForAnotherLoginLaunchesNothing() throws Exception {
        launcher = replaying("ok");
        CliWebServiceLogAdapter adapter = adapter(http().cliExportTimeout(Duration.ofMillis(300))
            .build());
        CountDownLatch held = new CountDownLatch(1);
        Thread other = Thread.ofVirtual().start(() -> {
            try (CredentialGuard.Permit permit = guard.acquire()) {
                held.countDown();
                sleep(Duration.ofMillis(1500));
            }
        });
        assertThat(held.await(5, TimeUnit.SECONDS)).as("the other attempt holds the permit")
            .isTrue();

        long start = System.nanoTime();
        ToolError error = failure(adapter, V81, start + Duration.ofMillis(300).toNanos());
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        other.join();

        // FR-013, D-15: at the deadline, not when the other attempt ends
        assertThat(took).isLessThan(Duration.ofMillis(1200));
        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.node()).contains(NodeId.parse("test/node1"));
        assertThat(error.message()).isEqualTo("cliExportTimeout on test/node1 passed while"
            + " waiting for another login attempt with the unconfirmed credentials or their"
            + " re-read; this call did not log in");
        assertThat(error.nextStep()).contains("raise cliExportTimeout");
        assertThat(launcher.launchCount()).isZero();
        assertThat(guard.confirmed()).as("no login result").isFalse();
        assertTempRootEmpty();
    }

    // --- the log (D-18) ------------------------------------------------------------------------

    @Test
    void theDebugLogNamesVariantClientAndJdkOnly() throws IOException {
        launcher = replaying("ok");

        adapter(http().build()).download(V81, deadline()).close();

        List<ILoggingEvent> events = appender.list.stream()
            .filter(event -> event.getLoggerName().equals(CliWebServiceLogAdapter.class.getName()))
            .toList();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).isEqualTo("test/node1: web service log"
                + " download, INUBIT client helper SOAP_81, client " + home + ", JDK " + jdk);
        });
        String all = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList()
            .toString();
        assertThat(all).doesNotContain(USER, "acme_OrderService", "partner.example.test",
            "#QE1", "serverLog", "log4j");
    }

    // --- helpers ----------------------------------------------------------------------------

    private CliWebServiceLogAdapter adapter(EffectiveNodeConfig server) {
        NodeCredentials credentials = new NodeCredentials(NODE,
            Optional.of(new SourcedValue<>(USER, "test")),
            Optional.of(new SourcedValue<>(scrubber.register(TestSecrets.CONFIGURED_PASSWORD),
                "test")),
            Optional.empty(), Optional.empty());
        return adapter(server, () -> credentials);
    }

    private CliWebServiceLogAdapter adapter(EffectiveNodeConfig server,
        Supplier<NodeCredentials> credentials) {
        guard = new CredentialGuard(server.secretLabels(), Clock.systemUTC());
        CliRunner runner = TestCliRunners.forHost(launcher, PARENT, resources);
        return new CliWebServiceLogAdapter(server, credentials, guard, runner,
            TestCliRunners.helperHost(PARENT, resources, tempRoot),
            new ProcessRun(launcher, resources), scrubber);
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

    private static ToolError failure(CliWebServiceLogAdapter adapter, InubitVersion version) {
        return failure(adapter, version, deadline());
    }

    /** The error of a download; it carries the node and leaks nothing. */
    private static ToolError failure(CliWebServiceLogAdapter adapter, InubitVersion version,
        long deadline) {
        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class,
            () -> adapter.download(version, deadline).close());
        assertThat(thrown).as("the download fails").isNotNull();
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

    private static void assertLaunchedWithoutSecrets(LaunchSpec spec) {
        String argv = String.join(" ", spec.command());
        TestSecrets.assertNoLeak(argv);
        TestSecrets.assertNoLeak(spec.environment().toString());
        assertThat(argv).doesNotContain(USER, "webservice");
        assertThat(spec.command().get(spec.command().size() - 1))
            .isEqualTo(HelperInstallation.MAIN_CLASS);
    }

    /** Replays the webservice fixture {@code fixtureCase} of 8.1 (also for other tests). */
    static FakeProcessLauncher replaying(String fixtureCase) {
        return replaying(fixtureCase, "SOAP_81");
    }

    /**
     * Replays {@code <case>.frames}/{@code .exit} of the webservice fixtures with the request's
     * nonce and {@code variant}, after copying {@code <case>.log} to the request's {@code logOut}.
     */
    private static FakeProcessLauncher replaying(String fixtureCase, String variant) {
        String frames = new String(fixture(fixtureCase + ".frames"), StandardCharsets.UTF_8)
            .replace(" start SOAP_81", " start " + variant);
        int exit = Integer.parseInt(new String(fixture(fixtureCase + ".exit"),
            StandardCharsets.UTF_8).strip());
        byte[] log = fixture(fixtureCase + ".log");
        return answering(fields -> {
            write(fields.get("logOut"), log);
            return new Answer(frames.replace(FIXTURE_NONCE, fields.get("nonce")), exit);
        });
    }

    /** Replays the frames and exit code of another fixture set, writing no file. */
    private static FakeProcessLauncher replayingResource(String fixture) {
        String frames = new String(FakeProcessLauncher.resource(fixture + ".frames"),
            StandardCharsets.UTF_8);
        int exit = Integer.parseInt(new String(FakeProcessLauncher.resource(fixture + ".exit"),
            StandardCharsets.UTF_8).strip());
        return answering(fields -> new Answer(frames.replace(FIXTURE_NONCE, fields.get("nonce")),
            exit));
    }

    private static FakeProcessLauncher answering(
        Function<Map<String, String>, Answer> responder) {
        return FakeProcessLauncher.of("", "", 0)
            .responding(request -> responder.apply(decode(request)));
    }

    private static byte[] fixture(String name) {
        return FakeProcessLauncher.resource(FIXTURES + name);
    }

    /** Frames of space-separated tokens; the tokens after the fourth are joined, then encoded. */
    private static Answer answer(int exit, Map<String, String> fields, String... frames) {
        StringBuilder out = new StringBuilder("noise before the frames\n");
        for (String frame : frames) {
            String[] tokens = frame.split(" ", 5);
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

    private static void write(String file, byte[] content) {
        try {
            Files.write(Path.of(file), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
