package de.dadecker.inubit.mcp.adapter.cli.v61;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.environmentLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliClientProbe;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.TlsTestServer;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * T020 (feature 011, FR-012, FR-012a, research D-7/D-8): the 6.1 connection sequence — StartCLI
 * available → client options → option and format refusals → concurrency permit → TLS handshake
 * with the effective StartCLI URL → session. The {@code -h} probe and the session are told apart
 * by their argument array; the INUBIT side is a {@link TlsTestServer} that counts connections.
 */
@Timeout(30)
class V61ConnectionTest {

    private static final TestCertificates CERTS = TestCertificates.get();
    private static final NodeId ID = NodeId.parse("legacy/node1");
    private static final String PASSWORD = "S3cr3t-v61-pw";
    private static final List<CliSessionCommand> COMMANDS =
        List.of(CliSessionCommand.UNAME, CliSessionCommand.UPTIME);
    private static final int BOUND = CliRunner.MAX_OUTPUT_BYTES;

    /** A client whose help lists the trust-store, host-name and command options. */
    private static final String HELP_WITH_OPTIONS = """
        usage: startcli [options] [url]
         -u <user>                       user name for the login
         -h                              print this help
         --trustStoreFilePath <file>     trust store for TLS connections
         --disableHostNameVerification   skip the host name check
         --execCommand <command>         run one command and exit
        """;

    @TempDir
    Path cliHome;

    @TempDir
    Path javaHome;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
    private final CredentialGuard guard =
        new CredentialGuard(environmentLabels("INUBIT", ID), clock);
    private final Map<String, String> parentEnvironment = new HashMap<>(Map.of("PATH", "/usr/bin"));
    private final CliConcurrencyLimiter limiter =
        new CliConcurrencyLimiter(CapabilityMatrix.production());
    private TlsTestServer inubit;

    @BeforeEach
    void start() throws IOException, GeneralSecurityException {
        Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
        release(javaHome, "JAVA_VERSION=\"1.8.0_292\"");
        inubit = TlsTestServer.start(CERTS.localhostKeyStore());
    }

    @AfterEach
    void stop() throws IOException, InterruptedException {
        inubit.close();
    }

    /** A 6.1 node on {@link #inubit} with a password-less PKCS12 trust store. */
    private TestNodeConfig node() {
        return TestNodeConfig.node().id(ID.toString()).versionLine(VersionSetting.V6_1)
            .baseUrl("https://localhost:" + inubit.port()).cliHome(cliHome).cliJavaHome(javaHome)
            .trustStore(CERTS.trustStore()).cliTimeout(Duration.ofSeconds(10));
    }

    private static void release(Path home, String javaVersionLine) throws IOException {
        Files.writeString(home.resolve("release"), javaVersionLine + "\n");
    }

    private static NodeCredentials credentials() {
        return new NodeCredentials(ID,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_LEGACY_NODE1_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_LEGACY_NODE1_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    private V61Connection connection(EffectiveNodeConfig server, Launchers launchers) {
        return connection(server, credentials(), launchers);
    }

    private V61Connection connection(EffectiveNodeConfig server, NodeCredentials credentials,
        Launchers launchers) {
        CliRunner runner =
            new CliRunner(launchers, parentEnvironment, false, new CliResources("acme"));
        return new V61Connection(server, credentials, guard, runner,
            new CliClientProbe(runner, id -> server, CapabilityMatrix.production(), clock),
            limiter);
    }

    /**
     * Sends {@code -h} runs to {@code help} and everything else (sessions) to {@code session};
     * records the order of all launches.
     */
    private static final class Launchers implements ProcessLauncher {

        final FakeProcessLauncher help;
        final FakeProcessLauncher session;
        final List<String> order = new CopyOnWriteArrayList<>();

        Launchers(FakeProcessLauncher help, FakeProcessLauncher session) {
            this.help = help;
            this.session = session;
        }

        /** A client with every option and a session that prints {@code OK}. */
        static Launchers withOptions() {
            return new Launchers(FakeProcessLauncher.of(HELP_WITH_OPTIONS, "", 0),
                FakeProcessLauncher.of("OK\n", "", 0));
        }

        /** The reconstructed 6.1 client: no trust-store and no host-name option. */
        static Launchers sixOne() {
            return new Launchers(FakeProcessLauncher.replaying("v6_1", "help"),
                FakeProcessLauncher.of("OK\n", "", 0));
        }

        @Override
        public LaunchedProcess launch(LaunchSpec spec) throws IOException {
            boolean probe = spec.command().size() == 2 && spec.command().get(1).equals("-h");
            order.add(probe ? "probe" : "session");
            return probe ? help.launch(spec) : session.launch(spec);
        }

        /** Session launches, counted when they begin (a hanging launch counts). */
        int sessions() {
            return (int) order.stream().filter("session"::equals).count();
        }

        List<String> sessionCommand() {
            return session.last().spec().command();
        }
    }

    private static ToolError errorOf(Executable call) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class, () -> {
            try {
                call.execute();
            } catch (ToolErrorException e) {
                throw e;
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
        assertThat(exception).as("expected a ToolErrorException").isNotNull();
        return exception.error();
    }

    // --- order of the steps -------------------------------------------------------------------

    @Test
    void anAdmittedCallProbesThenChecksTheCertificateThenRunsTheSession() {
        Launchers launchers = Launchers.withOptions();
        List<Integer> connectionsAtSession = new ArrayList<>();
        launchers.session.onLaunch(spec -> connectionsAtSession.add(inubit.connections()));

        CliResult result = connection(node().build(), launchers).session(COMMANDS, BOUND);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("OK\n");
        assertThat(launchers.order).containsExactly("probe", "session");
        // exactly one handshake, finished before StartCLI was launched
        assertThat(connectionsAtSession).containsExactly(1);
        assertThat(inubit.connections()).isEqualTo(1);
        assertThat(new String(launchers.session.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\nuname\nuptime\nexit\n");
        assertThat(launchers.sessionCommand()).doesNotContain(PASSWORD)
            .endsWith("https://localhost:" + inubit.port() + EffectiveNodeConfig.DEFAULT_CLI_PATH);
    }

    @Test
    void aNodeWithoutStartCliIsCliUnavailableBeforeAnythingElse() throws IOException {
        Files.delete(cliHome.resolve("bin/startcli.sh"));
        Launchers launchers = Launchers.sixOne();
        // would also be refused for the missing host-name option
        EffectiveNodeConfig server = node().disableHostnameVerification(
            TestCertificates.fingerprint(CERTS.localhostCertificate())).build();

        ToolError error = errorOf(() -> connection(server, launchers).session(COMMANDS, BOUND));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.node()).contains(ID);
        assertThat(launchers.order).isEmpty();
        assertThat(inubit.connections()).isZero();
    }

    @Test
    void missingCredentialsAreAuthFailedWithoutAnyLaunchOrContact() {
        Launchers launchers = Launchers.withOptions();
        NodeCredentials none = new NodeCredentials(ID, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolError error = errorOf(() -> connection(node().build(), none, launchers)
            .session(COMMANDS, BOUND));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(launchers.order).isEmpty();
        assertThat(inubit.connections()).isZero();
    }

    @Test
    void theRefusalsComeAfterTheProbeAndBeforeThePermit() {
        Launchers launchers = Launchers.sixOne();
        EffectiveNodeConfig server = node().disableHostnameVerification(
            TestCertificates.fingerprint(CERTS.localhostCertificate())).build();
        // both slots of the node are taken: a call that reached the limiter would wait
        try (CliConcurrencyLimiter.Permit first = limiter.acquire(server);
            CliConcurrencyLimiter.Permit second = limiter.acquire(server)) {
            long start = System.nanoTime();

            ToolError error = errorOf(() -> connection(server, launchers).session(COMMANDS, BOUND));

            assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isLessThan(Duration.ofSeconds(5));
        }
        assertThat(launchers.order).containsExactly("probe");
        assertThat(inubit.connections()).isZero();
    }

    @Test
    void thePermitIsTakenBeforeTheHandshake() {
        Launchers launchers = Launchers.withOptions();
        EffectiveNodeConfig server = node().cliTimeout(Duration.ofMillis(300)).build();
        try (CliConcurrencyLimiter.Permit first = limiter.acquire(server);
            CliConcurrencyLimiter.Permit second = limiter.acquire(server)) {

            ToolError error = errorOf(() -> connection(server, launchers).session(COMMANDS, BOUND));

            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        }
        assertThat(launchers.order).containsExactly("probe");
        assertThat(inubit.connections()).isZero();
    }

    // --- TLS handshake (FR-012) ---------------------------------------------------------------

    @Test
    void aPinMismatchIsATlsErrorAndStartsNoSession() {
        Launchers launchers = Launchers.withOptions();
        EffectiveNodeConfig server =
            node().pin(TestCertificates.fingerprint(CERTS.selfsignedCertificate())).build();

        ToolError error = errorOf(() -> connection(server, launchers).session(COMMANDS, BOUND));

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(error.node()).contains(ID);
        assertThat(error.likelyCause()).contains("pinned SHA-256 fingerprint");
        assertThat(inubit.connections()).isEqualTo(1);
        assertThat(launchers.sessions()).isZero();
    }

    @Test
    void anOverriddenStartCliUrlIsWhatTheCheckTargets() throws IOException,
        GeneralSecurityException, InterruptedException {
        Launchers launchers = Launchers.withOptions();
        try (TlsTestServer cli = TlsTestServer.start(CERTS.localhostKeyStore())) {
            String cliUrl = "https://localhost:" + cli.port() + "/custom/IBISSoapServlet";
            EffectiveNodeConfig server = node().cliUrl(cliUrl).build();

            connection(server, launchers).session(COMMANDS, BOUND);

            assertThat(cli.connections()).isEqualTo(1);
            assertThat(cli.requestedServerNames()).containsExactly("localhost");
            assertThat(inubit.connections()).isZero();
            assertThat(launchers.sessionCommand()).endsWith(cliUrl);
        }
    }

    @Test
    void anHttpStartCliUrlWithAllowInsecureHttpRunsWithoutHandshake() {
        Launchers launchers = Launchers.withOptions();
        String cliUrl = "http://localhost:" + inubit.port() + EffectiveNodeConfig.DEFAULT_CLI_PATH;
        EffectiveNodeConfig server = node().allowInsecureHttp(true).cliUrl(cliUrl).build();

        connection(server, launchers).session(COMMANDS, BOUND);

        assertThat(inubit.connections()).isZero();
        assertThat(launchers.sessions()).isEqualTo(1);
        assertThat(launchers.sessionCommand()).endsWith(cliUrl);
    }

    @Test
    void anHttpStartCliUrlWithoutAllowInsecureHttpIsRefused() {
        Launchers launchers = Launchers.withOptions();
        EffectiveNodeConfig server = node()
            .cliUrl("http://localhost:" + inubit.port() + EffectiveNodeConfig.DEFAULT_CLI_PATH)
            .build();

        ToolError error = errorOf(() -> connection(server, launchers).session(COMMANDS, BOUND));

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(error.nextStep()).contains("allowInsecureHttp");
        assertThat(inubit.connections()).isZero();
        assertThat(launchers.sessions()).isZero();
    }

    // --- concurrency (FR-015, US5-10) ---------------------------------------------------------

    @Test
    void fiveParallelCallsWithHangingSessionsLaunchAtMostTwo() throws Exception {
        Launchers launchers = Launchers.withOptions();
        V61Connection connection =
            connection(node().cliTimeout(Duration.ofMillis(500)).build(), launchers);
        // a first login confirms the credentials: the guard then lets parallel logins through
        connection.session(COMMANDS, BOUND);
        CountDownLatch release = new CountDownLatch(1);
        // a session that hangs in its launch, holding its slot until the test lets it go
        launchers.session.onLaunch(spec -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        List<Future<CliResult>> calls = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            try {
                for (int i = 0; i < 5; i++) {
                    calls.add(pool.submit(() -> connection.session(COMMANDS, BOUND)));
                }
                // the calls without a slot give up after cliTimeout; with a broken limit they
                // would hang in the launch as well
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (calls.stream().filter(Future::isDone).count() < 3
                    && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                List<ToolError> refused = calls.stream().filter(Future::isDone)
                    .map(V61ConnectionTest::failure).toList();

                assertThat(launchers.sessions()).isEqualTo(1 + 2);
                assertThat(refused).hasSize(3)
                    .allSatisfy(error -> assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT));
            } finally {
                release.countDown();
            }
        }
        assertThat(calls).allSatisfy(call -> assertThat(call).isDone());
        assertThat(launchers.sessions()).isEqualTo(1 + 2);
    }

    private static ToolError failure(Future<CliResult> call) {
        try {
            call.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ToolErrorException error) {
                return error.error();
            }
            throw new AssertionError(e);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("expected a failed call");
    }

    // --- client options and refusals (FR-012a, research D-8) ----------------------------------

    @Test
    void aFirstCallReadsTheOptionsAndPassesTheHostNameOptionWithThePin() throws IOException,
        GeneralSecurityException, InterruptedException {
        Launchers launchers = Launchers.withOptions();
        // CN=selfsigned.example.test is not issued for localhost: only the pin admits it
        try (TlsTestServer selfsigned = TlsTestServer.start(CERTS.selfsignedKeyStore())) {
            EffectiveNodeConfig server = node().baseUrl("https://localhost:" + selfsigned.port())
                .disableHostnameVerification(
                    TestCertificates.fingerprint(CERTS.selfsignedCertificate())).build();

            connection(server, launchers).session(COMMANDS, BOUND);

            assertThat(launchers.order).containsExactly("probe", "session");
            assertThat(selfsigned.connections()).isEqualTo(1);
            assertThat(launchers.sessionCommand()).contains("--disableHostNameVerification");
        }
    }

    @Test
    void aClientWithoutTheHostNameOptionIsRefusedNamingIt() {
        Launchers launchers = Launchers.sixOne();
        EffectiveNodeConfig server = node().disableHostnameVerification(
            TestCertificates.fingerprint(CERTS.localhostCertificate())).build();

        ToolError error = errorOf(() -> connection(server, launchers).session(COMMANDS, BOUND));

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(error.node()).contains(ID);
        assertThat(error.message()).contains("--disableHostNameVerification");
        assertThat(inubit.connections()).isZero();
        assertThat(launchers.sessions()).isZero();
    }

    @Test
    void aPkcs12TrustStoreWithAnUnreadableJavaHomeRuntimeIsRefused(@TempDir Path ownJdk) {
        // no cli.javaHome: the server's JAVA_HOME is the runtime, and it has no release file
        parentEnvironment.put("JAVA_HOME", ownJdk.toString());
        Launchers launchers = Launchers.withOptions();
        EffectiveNodeConfig server = TestNodeConfig.node().id(ID.toString())
            .versionLine(VersionSetting.V6_1).baseUrl("https://localhost:" + inubit.port())
            .cliHome(cliHome).trustStore(CERTS.trustStore()).build();

        ToolError error = errorOf(() -> connection(server, launchers).session(COMMANDS, BOUND));

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(error.message()).contains("PKCS12", "unknown");
        assertThat(error.nextStep()).contains("cliJavaHome", "convert the trust store to JKS");
        assertThat(inubit.connections()).isZero();
        assertThat(launchers.sessions()).isZero();
    }

    @Test
    void aPkcs12TrustStoreWithAJavaSevenRuntimeIsRefused() throws IOException {
        release(javaHome, "JAVA_VERSION=\"1.7.0_80\"");
        Launchers launchers = Launchers.withOptions();

        ToolError error =
            errorOf(() -> connection(node().build(), launchers).session(COMMANDS, BOUND));

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(error.message()).contains("Java 7", "PKCS12");
        assertThat(error.nextStep()).contains("convert the trust store to JKS");
        assertThat(inubit.connections()).isZero();
        assertThat(launchers.sessions()).isZero();
    }

    @Test
    void aJksTrustStoreWithAJavaSevenRuntimeIsAdmitted() throws IOException,
        GeneralSecurityException {
        release(javaHome, "JAVA_VERSION=\"1.7.0_80\"");
        Path jks = javaHome.resolve("truststore.jks");
        KeyStore store = KeyStore.getInstance("JKS");
        store.load(null, null);
        store.setCertificateEntry("localhost", CERTS.localhostCertificate());
        try (OutputStream out = Files.newOutputStream(jks)) {
            store.store(out, new char[0]);
        }
        Launchers launchers = Launchers.sixOne();

        connection(node().trustStore(jks).build(), launchers).session(COMMANDS, BOUND);

        assertThat(launchers.sessions()).isEqualTo(1);
        assertThat(launchers.session.last().spec().environment().get("JAVA_TOOL_OPTIONS"))
            .contains("-Djavax.net.ssl.trustStoreType=JKS");
    }

    @Test
    void aTrustStorePathWithSpacesIsRefusedForAClientWithoutTrustStoreOption()
        throws IOException {
        Path spaced = Files.createDirectories(javaHome.resolve("trust store"))
            .resolve("truststore.p12");
        Files.copy(CERTS.trustStore(), spaced);
        Launchers launchers = Launchers.sixOne();

        ToolError error = errorOf(() -> connection(node().trustStore(spaced).build(), launchers)
            .session(COMMANDS, BOUND));

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(error.nextStep()).contains("path without spaces");
        assertThat(inubit.connections()).isZero();
        assertThat(launchers.sessions()).isZero();
    }

    @Test
    void aTrustStorePathWithSpacesIsPassedToAClientWithTrustStoreOption() throws IOException {
        Path spaced = Files.createDirectories(javaHome.resolve("trust store"))
            .resolve("truststore.p12");
        Files.copy(CERTS.trustStore(), spaced);
        Launchers launchers = Launchers.withOptions();

        connection(node().trustStore(spaced).build(), launchers).session(COMMANDS, BOUND);

        assertThat(launchers.sessionCommand())
            .containsSubsequence("--trustStoreFilePath", spaced.toString());
    }
}
