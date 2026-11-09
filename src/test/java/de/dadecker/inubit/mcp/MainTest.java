package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import de.dadecker.inubit.mcp.adapter.os.NoJob;
import de.dadecker.inubit.mcp.application.ReleaseSource;
import de.dadecker.inubit.mcp.application.UpdateCheck;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ConfigLocation;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.ParameterType;
import de.dadecker.inubit.mcp.config.StoreLocation;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.domain.model.SemVer;
import de.dadecker.inubit.mcp.infra.BuildInfo;
import de.dadecker.inubit.mcp.infra.FileUpdateCache;
import de.dadecker.inubit.mcp.infra.LogLevels;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import de.dadecker.inubit.mcp.testing.FakeCredentialStore;
import de.dadecker.inubit.mcp.testing.FakeParameterStore;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import de.dadecker.inubit.mcp.testing.WindowsAcl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T026: the command line of the server (contracts/configuration.md → Command-line arguments),
 * run through {@link Launcher}; {@link MainProcessTest} covers the entry point {@code Main.main}
 * with its stdout guard. No test reads the real {@code ~/.config} or process environment: the
 * user home is a temporary directory and the environment an explicit map.
 */
@Timeout(60)
class MainTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "main-test-Pw-4711";
    private static final String USERNAME = "main-test-user";

    @TempDir
    Path home;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final Map<String, String> environment = new HashMap<>();
    /** The credential store entries (feature 011: no credential comes from the environment). */
    private final Map<String, String> entries = new HashMap<>();
    /** The Parameter Store of AWS nodes; no test reaches AWS. */
    private final FakeParameterStore store = new FakeParameterStore();
    /** How often the launcher asked for {@link #store}. */
    private final AtomicInteger storesCreated = new AtomicInteger();
    /** What the launcher's release source answers (feature 016); offline unless a test sets it. */
    private ReleaseSource releaseSource = () -> {
        throw new ReleaseSource.ReleaseCheckException("offline (test)");
    };
    private final AtomicInteger releaseLookups = new AtomicInteger();
    private final AtomicInteger updateCachesCreated = new AtomicInteger();
    /** The credential store the launcher created last (feature 011). */
    private final AtomicReference<FakeCredentialStore> credentialStore = new AtomicReference<>();
    /** How often the launcher set up the containment of StartCLI's processes (feature 013). */
    private final AtomicInteger containments = new AtomicInteger();
    /** The containment the launcher sets up. */
    private ProcessContainment containment = new NoJob();
    /** The private files of the launcher (feature 013, FR-007): the host's by default. */
    private PrivateFileSystem privateFiles = PrivateFiles.forHost();
    /** The store the launcher gets instead of one built from {@link #entries}, if set. */
    private FakeCredentialStore scriptedStore;
    /**
     * The launcher's platform: the host ({@link Platform#windows()}) where a test runs the
     * substitute StartCLI, which is {@code startcli.bat} on Windows; otherwise POSIX, whose
     * default configuration directory the profile tests use.
     */
    private boolean windows;

    @AfterEach
    void restoreTestLogLevel() {
        LogLevels.apply("WARN"); // server mode applies the configured logLevel (INFO)
    }

    private Launcher main(InputStream in, OutputStream out) {
        return main(in, out, Wiring::toolHandlers);
    }

    private Launcher main(InputStream in, OutputStream out,
        Function<Wiring, List<ToolHandler>> handlers) {
        return new Launcher(new Launcher.Context(Map.copyOf(environment), home, windows, in,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8), new SecretScrubber(),
            Files::exists, () -> {
                storesCreated.incrementAndGet();
                return store;
            }, () -> {
                FakeCredentialStore created = scriptedStore != null ? scriptedStore
                    : TestSecrets.store(entries);
                credentialStore.set(created);
                return created;
            }, () -> {
                containments.incrementAndGet();
                return containment;
            }, privateFiles, () -> () -> {
                releaseLookups.incrementAndGet();
                return releaseSource.latest();
            }, () -> {
                updateCachesCreated.incrementAndGet();
                return FileUpdateCache.inHome(home, Clock.systemUTC());
            }), handlers);
    }

    private int run(String... args) {
        return main(new ByteArrayInputStream(new byte[0]), stdout).run(args);
    }

    private String out() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    private Path config(String yaml) throws IOException {
        Path file = home.resolve("config.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    private Path validConfig() throws IOException {
        return config("""
            profile:
              name: acme
            auditDirectory: %s
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    versionLine: V8_1
            """.formatted(home.resolve("audit/nested")));
    }

    private void credentials() {
        entries.put("INUBIT_ACME_DEV_USERNAME", USERNAME);
        entries.put("INUBIT_ACME_DEV_PASSWORD", PASSWORD);
    }

    @Test
    void versionPrintsTheProjectVersionAndExitsZero() {
        int exit = run("--version");

        assertThat(exit).isZero();
        assertThat(out()).matches("inubit-mcp-server \\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?\\R");
        assertThat(out()).contains(BuildInfo.version());
        assertThat(BuildInfo.version()).doesNotContain("${");
    }

    @Test
    void checkConfigPrintsServersFlagsAndVariableNamesButNoValues() throws IOException {
        credentials();
        entries.put("INUBIT_ACME_QA_NODE2_PASSWORD", "other-Secret-99");
        entries.put("INUBIT_ACME_QA_USERNAME", "other-user");
        windows = Platform.windows();
        Path cliHome = home.resolve("client");
        FakeStartCli cli = FakeStartCli.replaying(cliHome, "version");
        Path file = config("""
            profile:
              name: acme
            groups:
              - name: dev
                write:
                  enabled: true
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
              - name: qa
                nodes:
                  - name: node2
                    baseUrl: https://node2.example.test:8443
                    cli:
                      home: %s
                      javaHome: %s
            """.formatted(cliHome, home));

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).as(err()).isZero();
        assertThat(out())
            // feature 011 (T017): the entries found in the credential store, never a value
            .contains("dev/node1: write enabled (confirmation SERVER), cli: unavailable,"
                + " inubit: AUTO, secrets: credentialStore (macOS Keychain),"
                + " username ← INUBIT_ACME_DEV_USERNAME ok, password ← INUBIT_ACME_DEV_PASSWORD ok")
            .contains("qa/node2: read-only, cli: available, inubit: AUTO,"
                + " client: 8.1.17 (4.0.1–8.1.17), server version: determined at runtime,"
                + " secrets: credentialStore (macOS Keychain),"
                + " username ← INUBIT_ACME_QA_USERNAME ok,"
                + " password ← INUBIT_ACME_QA_NODE2_PASSWORD ok")
            .contains("Result: OK")
            .doesNotContain(PASSWORD, USERNAME, "other-Secret-99", "other-user",
                "inubit.example.test");
        assertThat(err()).doesNotContain(PASSWORD, "other-Secret-99");
        // feature 009 (FR-011a): the local client probe only, without credentials or server URL
        assertThat(cli.record().args()).containsExactly("-v");
    }

    // --- feature 010 (T006): existing profiles behave as before; AWS nodes are deferred -------

    /**
     * A profile of today (no {@code credentials.source}) plus {@code credentials} settings, with
     * an existing workspace so that repeated runs print the same.
     */
    private Path profileWithCredentials(String credentials) throws IOException {
        Files.createDirectories(home.resolve("workspace"));
        return config("""
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            %sgroups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    versionLine: V8_1
              - name: qa
                nodes:
                  - name: node2
                    baseUrl: https://node2.example.test:8443
            """.formatted(home.resolve("audit"), home.resolve("workspace"), credentials));
    }

    @Test
    void checkConfigOutputOfAnExistingProfileNamesTheEntriesAndLeftoverVariables()
        throws IOException {
        // feature 011: the entries found and their outcome (T017); a variable an earlier version
        // read is a warning
        credentials();
        entries.put("INUBIT_ACME_QA_NODE2_USERNAME", "qa-user");
        entries.put("INUBIT_ACME_QA_PASSWORD", "qa-Password-2");
        environment.put("INUBIT_ACME_QA_NODE2_USERNAME", "qa-user");
        environment.put("INUBIT_ACME_DEV_NODE9_PASSWORD", "unrelated-Secret-1");
        Path file = profileWithCredentials("");

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).isZero();
        assertThat(out()).isEqualTo("""
            Configuration: %s
            Profile: acme
            Terminology: group/groups, node/nodes
            Credential store entries: service inubit-mcp, account \
            INUBIT_ACME_<GROUP>[_<NODE>]_<KIND> (macOS Keychain)
            Audit directory: %s
            Workspace: %s (ok)
            Group dev: archives: allowed
              Node dev/node1: read-only, cli: unavailable, inubit: V8_1, \
            secrets: credentialStore (macOS Keychain), \
            username ← INUBIT_ACME_DEV_USERNAME ok, password ← INUBIT_ACME_DEV_PASSWORD ok
            Group qa: archives: allowed
              Node qa/node2: read-only, cli: unavailable, inubit: AUTO, \
            secrets: credentialStore (macOS Keychain), \
            username ← INUBIT_ACME_QA_NODE2_USERNAME ok, password ← INUBIT_ACME_QA_PASSWORD ok
            Warnings:
              - Environment variables are no longer read for INUBIT credentials (since 0.7.0): \
            INUBIT_ACME_QA_NODE2_USERNAME; move them to the credential store or Parameter Store \
            (docs/migration-environment-variables.md)
            Result: OK
            """.formatted(file, home.resolve("audit"), home.resolve("workspace")));
        assertThat(storesCreated).as("T015: no Parameter Store without an AWS node").hasValue(0);
    }

    @Test
    void checkConfigExitsOneOnAMissingEntryAndNamesItButStartsTheServer() throws IOException {
        // feature 011 (T017, US4-2, quickstart Q15): an error of --check-config, never a value;
        // the server itself starts (the entry is read on first use)
        credentials();
        Path file = profileWithCredentials("");

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(out()).contains("qa/node2: read-only, cli: unavailable, inubit: AUTO,"
                + " secrets: credentialStore (macOS Keychain),"
                + " username ← INUBIT_ACME_QA_NODE2_USERNAME | INUBIT_ACME_QA_USERNAME not found",
            "qa/node2: credential store entry INUBIT_ACME_QA_NODE2_USERNAME (or"
                + " INUBIT_ACME_QA_USERNAME) in the macOS Keychain for the username: not found",
            "Result: FAILED")
            .doesNotContain(PASSWORD, USERNAME);
        stdout.reset();
        assertThat(run("--config", file.toString())).as(err()).isZero();
    }

    @Test
    void checkConfigExitsZeroWithAWarningWhenTheStoreCannotBeAsked() throws IOException {
        // feature 011 (US4-3): no store on this machine is a warning, the names are not checked
        scriptedStore = new FakeCredentialStore(de.dadecker.inubit.mcp.config.StoreKind
            .SECRET_SERVICE).fail("INUBIT_ACME_DEV_NODE1_USERNAME",
                de.dadecker.inubit.mcp.config.FailureKind.STORE_NOT_AVAILABLE, "no D-Bus");
        Path file = profileWithCredentials("");

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).as(out()).isZero();
        assertThat(out()).contains("username ← INUBIT_ACME_DEV_NODE1_USERNAME |"
                + " INUBIT_ACME_DEV_USERNAME not checked",
            "Secret Service for dev/node1, qa/node2: resolvability not checked", "Result: OK")
            .doesNotContain("no D-Bus");
    }

    @Test
    void anAwsOnlyCheckBuildsNoCredentialStore() throws IOException {
        Files.createDirectories(home.resolve("workspace"));
        Path file = config("""
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            credentials:
              source:
                type: awsParameterStore
                region: eu-central-1
            groups:
              - name: prod
                credentials:
                  parameters:
                    username: /inubit/prod/username
                    password: /inubit/prod/password
                nodes:
                  - name: node1
                    baseUrl: https://prod.example.test:8443
            """.formatted(home.resolve("audit"), home.resolve("workspace")));
        store.put(EU_CENTRAL, "/inubit/prod/username", "prod-user")
            .put(EU_CENTRAL, "/inubit/prod/password", "prod-Password-1");

        assertThat(run("--check-config", "--config", file.toString())).as(out()).isZero();
        assertThat(credentialStore.get()).as("no credential store built").isNull();
    }

    @Test
    void anExplicitCredentialStoreSourceChecksAndStartsLikeAProfileWithoutSource()
        throws IOException {
        credentials();
        entries.put("INUBIT_ACME_QA_USERNAME", "qa-user");
        entries.put("INUBIT_ACME_QA_PASSWORD", "qa-Password-2");
        String explicit = """
            credentials:
              source:
                type: credentialStore
            """;
        String explicitWithPrefix = """
            credentials:
              prefix: INUBIT_ACME
              source:
                type: credentialStore
                prefix: INUBIT_ACME
            """;
        List<String> variants = List.of("", explicit, explicitWithPrefix);
        List<String> outputs = new java.util.ArrayList<>();
        List<String> startups = new java.util.ArrayList<>();
        for (String credentials : variants) {
            Path file = profileWithCredentials(credentials);
            stdout.reset();
            stderr.reset();
            assertThat(run("--check-config", "--config", file.toString())).as(out()).isZero();
            outputs.add(out());
            stdout.reset();
            assertThat(run("--config", file.toString())).as(err()).isZero();
            startups.add(err());
        }

        assertThat(outputs).as("--check-config").containsOnly(outputs.get(0));
        assertThat(outputs.get(0)).contains("username ← INUBIT_ACME_QA_USERNAME ok");
        assertThat(startups).as("startup messages").containsOnly(startups.get(0));
    }

    @Test
    void anAwsNodeNeedsNoCredentialVariablesToCheckOrStart() throws IOException {
        credentials();
        Path file = config("""
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    versionLine: V8_1
              - name: prod
                credentials:
                  source:
                    type: awsParameterStore
                    region: eu-central-1
                  parameters:
                    username: /inubit/prod/username
                    password: /inubit/prod/password
                nodes:
                  - name: node1
                    baseUrl: https://prod.example.test:8443
                    versionLine: V8_1
            """.formatted(home.resolve("audit"), home.resolve("workspace")));

        store.put(EU_CENTRAL, "/inubit/prod/username", "prod-user")
            .put(EU_CENTRAL, "/inubit/prod/password", "prod-Password-1");

        int check = run("--check-config", "--config", file.toString());
        String checkOutput = out();
        stdout.reset();
        int start = run("--config", file.toString());

        assertThat(check).as(checkOutput).isZero();
        assertThat(checkOutput).doesNotContain("INUBIT_ACME_PROD", "no username variable",
            "no password variable");
        assertThat(start).as(err()).isZero();
        assertThat(err()).doesNotContain("INUBIT_ACME_PROD", PASSWORD);
    }

    @Test
    void theServerStartsWithAnAwsNodeWithoutAwsAccessAndFetchesNothing() throws Exception {
        store.failCall(new StoreLocation("eu-central-1", Optional.empty()),
            FailureKind.NO_CREDENTIALS, "no AWS credentials in this test");
        Path file = config("""
            profile:
              name: acme
            auditDirectory: %s
            groups:
              - name: prod
                credentials:
                  source:
                    type: awsParameterStore
                    region: eu-central-1
                  parameters:
                    username: /test/prod/username
                    password: /test/prod/password
                nodes:
                  - name: node1
                    baseUrl: https://prod.example.test:8443
                    versionLine: V8_1
            """.formatted(home.resolve("audit")));
        PipedInputStream serverIn = new PipedInputStream(1 << 16);
        PipedOutputStream toServer = new PipedOutputStream(serverIn);
        LineQueue protocol = new LineQueue();
        Launcher main = main(serverIn, protocol);

        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(
            () -> main.run("--config", file.toString()));
        send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
        protocol.next();
        send(toServer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"list_nodes\",\"arguments\":{}}}");
        JsonNode nodes = protocol.next();
        toServer.close();

        assertThat(exit.get(20, TimeUnit.SECONDS)).as(err()).isZero();
        assertThat(nodes.path("result").path("structuredContent").path("groups").path(0)
            .path("nodes").path(0).path("id").asString()).isEqualTo("prod/node1");
        assertThat(store.calls()).as("neither the startup nor list_nodes fetches").isZero();
    }

    @Test
    void theServerStartsWithoutReadingTheCredentialStore() throws Exception {
        // feature 011 (FR-006, review T013-T014 r1): nothing is read at startup or for list_nodes
        entries.put("INUBIT_ACME_DEV_USERNAME", "jdoe");
        entries.put("INUBIT_ACME_DEV_PASSWORD", "dev-Password-1");
        Path file = config("""
            profile:
              name: acme
            auditDirectory: %s
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://dev.example.test:8443
                    versionLine: V8_1
            """.formatted(home.resolve("audit")));
        PipedInputStream serverIn = new PipedInputStream(1 << 16);
        PipedOutputStream toServer = new PipedOutputStream(serverIn);
        LineQueue protocol = new LineQueue();
        Launcher main = main(serverIn, protocol);

        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(
            () -> main.run("--config", file.toString()));
        send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
        protocol.next();
        send(toServer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"list_nodes\",\"arguments\":{}}}");
        protocol.next();
        toServer.close();

        assertThat(exit.get(20, TimeUnit.SECONDS)).as(err()).isZero();
        assertThat(credentialStore.get()).as("the store was built").isNotNull();
        assertThat(credentialStore.get().reads()).isEmpty();
    }

    // --- feature 010 T015: --check-config reads the AWS parameters (FR-009) --------------------

    private static final StoreLocation EU_CENTRAL =
        new StoreLocation("eu-central-1", Optional.empty());

    /** dev: environment variables; prod: AWS Parameter Store, optionally with an AWS profile. */
    private Path awsProfile(String awsProfileLine) throws IOException {
        return config("""
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    versionLine: V8_1
              - name: prod
                credentials:
                  source:
                    type: awsParameterStore
                    region: eu-central-1
                    %s
                  parameters:
                    username: /inubit/prod/username
                    password: /inubit/prod/password
                    trustStorePassword: /inubit/prod/ts-password
                nodes:
                  - name: node1
                    baseUrl: https://prod.example.test:8443
                    versionLine: V8_1
            """.formatted(home.resolve("audit"), home.resolve("workspace"), awsProfileLine));
    }

    @Test
    void checkConfigReadsTheAwsParametersOnceAndExitsZeroWhenAllAreValid() throws IOException {
        credentials();
        store.put(EU_CENTRAL, "/inubit/prod/username", "prod-user")
            .put(EU_CENTRAL, "/inubit/prod/password", "prod-Password-1")
            .put(EU_CENTRAL, "/inubit/prod/ts-password", "prod-Ts-Password-2");

        int exit = run("--check-config", "--config", awsProfile("").toString());

        assertThat(exit).as(out()).isZero();
        assertThat(out()).contains("prod/node1: read-only, cli: unavailable, inubit: V8_1,"
            + " secrets: awsParameterStore (eu-central-1), username ← /inubit/prod/username ok,"
            + " password ← /inubit/prod/password ok, trustStorePassword ← /inubit/prod/ts-password"
            + " ok\n", "Result: OK");
        assertThat(out() + err()).doesNotContain("prod-user", "prod-Password-1",
            "prod-Ts-Password-2", PASSWORD);
        assertThat(storesCreated).hasValue(1);
        assertThat(store.calls()).isEqualTo(1);
    }

    @Test
    void checkConfigExitsOneForAMissingDeniedOrInvalidParameter() throws IOException {
        credentials();
        Path file = awsProfile("");
        List<Runnable> mistakes = List.of(
            () -> store.put(EU_CENTRAL, "/inubit/prod/username", "prod-user")
                .put(EU_CENTRAL, "/inubit/prod/password", "prod-Password-1"),
            () -> store.put(EU_CENTRAL, "/inubit/prod/ts-password", "prod-Ts-Password-2")
                .fail(EU_CENTRAL, "/inubit/prod/password", FailureKind.ACCESS_DENIED,
                    "not authorized"),
            () -> store.put(EU_CENTRAL, "/inubit/prod/password", "x9z"));
        List<String> expected = List.of("/inubit/prod/ts-password not found",
            "/inubit/prod/password access denied", "/inubit/prod/password invalid value");
        for (int i = 0; i < mistakes.size(); i++) {
            mistakes.get(i).run();
            stdout.reset();

            int exit = run("--check-config", "--config", file.toString());

            assertThat(exit).as(out()).isEqualTo(1);
            assertThat(out()).contains(expected.get(i), "Result: FAILED (1 error(s))")
                .doesNotContain("prod-Password-1", "x9z");
        }
    }

    @Test
    void checkConfigExitsOneForAnUnknownAwsProfile() throws IOException {
        credentials();
        store.failCall(new StoreLocation("eu-central-1", Optional.of("missing-profile")),
            FailureKind.UNKNOWN_PROFILE, "AWS profile 'missing-profile' not found");

        int exit = run("--check-config", "--config",
            awsProfile("awsProfile: missing-profile").toString());

        assertThat(exit).as(out()).isEqualTo(1);
        assertThat(out()).contains("secrets: awsParameterStore (eu-central-1, profile"
            + " missing-profile), username ← /inubit/prod/username not checked",
            "the AWS profile missing-profile is not defined in the local AWS configuration files");
    }

    @Test
    void checkConfigWithoutAwsAccessOrWithStringParametersOnlyWarnsAndExitsZero()
        throws IOException {
        credentials();
        Path file = awsProfile("");
        store.failCall(EU_CENTRAL, FailureKind.NO_CREDENTIALS, "Unable to load credentials");

        int withoutAccess = run("--check-config", "--config", file.toString());
        String withoutAccessOutput = out();
        store.clearCallFailure(EU_CENTRAL)
            .put(EU_CENTRAL, "/inubit/prod/username", "prod-user", ParameterType.STRING)
            .put(EU_CENTRAL, "/inubit/prod/password", "prod-Password-1", ParameterType.STRING)
            .put(EU_CENTRAL, "/inubit/prod/ts-password", "prod-Ts-Password-2");
        stdout.reset();
        int unencrypted = run("--check-config", "--config", file.toString());

        assertThat(withoutAccess).as(withoutAccessOutput).isZero();
        assertThat(withoutAccessOutput).contains("resolvability not checked", "Result: OK")
            .doesNotContain("Unable to load credentials");
        assertThat(unencrypted).as(out()).isZero();
        assertThat(out()).contains("password ← /inubit/prod/password not encrypted",
            "is not encrypted (type String); store it as SecureString", "Result: OK")
            .doesNotContain("prod-user", "prod-Password-1");
    }

    @Test
    void checkConfigReportsAFailingClientProbeAsAWarning() throws IOException {
        credentials();
        windows = Platform.windows();
        Path cliHome = home.resolve("client");
        FakeStartCli.replaying(cliHome, "unknown_command"); // exit code 1, no version line
        Path file = config("""
            profile:
              name: acme
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    versionLine: "8.1.17"
                    cli:
                      home: %s
                      javaHome: %s
            """.formatted(cliHome, home));

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).as(out() + err()).isZero();
        assertThat(out()).contains("dev/node1: read-only, cli: available, inubit: 8.1.17,"
                + " secrets: credentialStore (macOS Keychain), username ←")
            .contains("Warnings:", "  - dev/node1: StartCLI client probe failed: StartCLI did not"
                + " report its version (exit code 1)")
            .contains("Result: OK");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a POSIX shell substitute of startcli.sh;"
        + " the Windows launch of a 6.1 session is covered by CliRunnerTest and V61ConnectionTest")
    void checkConfigShowsAV61NodeAsStartCliOnlyWithoutContactingInubit() throws IOException {
        // feature 012 (FR-014, research D-13)
        credentials();
        Path cliHome = fakeStartCli("""
            echo "$@" >> "$(dirname "$0")/arguments"
            echo "CLI 6.1.0"
            echo "Supported inubit Process Engine versions from 4.0.1 to 6.1.0."
            """);
        Path file = config("""
            profile:
              name: acme
            groups:
              - name: dev
                versionLine: V6_1
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    cli:
                      home: %s
                      javaHome: %s
                  - name: node2
                    baseUrl: https://node2.example.test:8443
            """.formatted(cliHome, home));

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).as(out() + err()).isZero();
        String refused = "available: none; refused: monitoring, process diagnosis, log"
            + " diagnosis, inventory, partner management, workspace export, development writes,"
            + " deployment, end-to-end test, process control)";
        assertThat(out())
            .contains("dev/node1: read-only, cli: available, inubit: V6_1 (INUBIT 6.1, StartCLI"
                + " only; " + refused + ", client: 6.1.0 (4.0.1–6.1.0; options: no trust-store"
                + " option, no host-name option), server version: determined at runtime,"
                + " secrets: credentialStore (macOS Keychain), username ←")
            .contains("dev/node2: read-only, cli: unavailable, inubit: V6_1 (INUBIT 6.1, StartCLI"
                + " only; " + refused + ", secrets: credentialStore (macOS Keychain), username ←")
            .contains("  - dev/node2: INUBIT 6.1 is reached through StartCLI only, but no cliHome"
                + " is configured; its capabilities report CLI_UNAVAILABLE")
            .contains("Result: OK")
            .doesNotContain("inubit.example.test", "jsonrpc");
        // no INUBIT contact: the local client probes (version, help) are the only StartCLI
        // calls, without server URL; an unreadable help means every option absent (FR-012a)
        assertThat(Files.readString(cliHome.resolve("bin/arguments"))).isEqualTo("-v\n-h\n");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a POSIX shell substitute of startcli.sh;"
        + " the Windows launch of a 6.1 session is covered by CliRunnerTest and V61ConnectionTest")
    void checkConfigWarnsAboutWhatTheStartCliClientOfAV61NodeCannotDo() throws IOException {
        // feature 012 (FR-012a, contracts/configuration-delta.md → Validation): the warnings name
        // the refusals of V61Connection; local probes only, the server is not contacted
        credentials();
        Path cliHome = fakeStartCli("""
            echo "$@" >> "$(dirname "$0")/arguments"
            if [ "$1" = "-h" ]; then
              echo "usage: startcli.sh -u <user> [--execCommand <command>] <url>"
            else
              echo "CLI 6.1.0"
              echo "Supported inubit Process Engine versions from 4.0.1 to 6.1.0."
            fi
            """);
        Path java7 = Files.createDirectories(home.resolve("jdk7"));
        Files.writeString(java7.resolve("release"), "JAVA_VERSION=\"1.7.0_80\"\n");
        Path trustStore = Files.writeString(home.resolve("trust store.p12"), "not JKS");
        String pin = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD"
            + ":EF:01:23:45:67:89";
        Path file = config("""
            profile:
              name: acme
            groups:
              - name: dev
                cli:
                  home: %s
                  javaHome: %s
                tls:
                  trustStore: "%s"
                  disableHostnameVerification: true
                  pinnedCertificateSha256: "%s"
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    versionLine: V6_1
                  - name: node2
                    baseUrl: https://node2.example.test:8443
                    versionLine: V8_1
            """.formatted(cliHome, java7, trustStore, pin));

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).as(out() + err()).isZero();
        assertThat(out())
            .contains("dev/node1: the StartCLI client offers no option to skip host-name"
                + " verification; StartCLI-backed tools on this node are refused (TLS_ERROR)")
            .contains("dev/node1: the StartCLI runtime (Java 7) cannot read the PKCS12 trust"
                + " store; convert it to JKS or set cliJavaHome")
            .contains("dev/node1: the trust-store path contains spaces, which the StartCLI"
                + " runtime settings cannot carry; use a path without spaces")
            .contains("client: 6.1.0 (4.0.1–6.1.0; options: no trust-store option, no host-name"
                + " option), server version: determined at runtime")
            .contains("dev/node2: read-only, cli: available, inubit: V8_1, client: 6.1.0"
                + " (4.0.1–6.1.0), server version: determined at runtime, secrets:"
                + " credentialStore (macOS Keychain), username ←")
            .doesNotContain("dev/node2: the")
            .contains("Result: OK");
        // the 8.1 node gets its version probe only, no help run; feature 017 (T025): both nodes
        // use the same client (home, javaHome), which is probed once
        assertThat(Files.readString(cliHome.resolve("bin/arguments")))
            .isEqualTo("-v\n-h\n");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a POSIX shell substitute of startcli.sh;"
        + " the Windows launch is covered by CliRunnerTest")
    void checkConfigProbesEachDistinctClientOnceAndShowsTheChoiceOfEachNode() throws IOException {
        // feature 017 (T025, FR-015, contracts/configuration-delta.md → --check-config)
        credentials();
        Path c90 = fakeStartCli("c90", "9.0.7", "9.0.0", "9.0.10");
        Path c81 = fakeStartCli("c81", "8.1.17", "4.0.1", "8.1.17");
        Path jdk21 = jdk("jdk21", "21.0.2");
        Path jdk17 = jdk("jdk17", "17.0.9");
        Path file = config("""
            profile:
              name: acme
            defaults:
              cliClients:
                "8.1": { home: "%s", javaHome: "%s" }
                "9.0": { home: "%s", javaHome: "%s" }
                "9.1": { home: "%s", javaHome: "%s" }
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    versionLine: "9.0.7"
                  - name: node2
                    baseUrl: https://node2.example.test:8443
                    versionLine: V8_1
                  - name: node3
                    baseUrl: https://node3.example.test:8443
                    versionLine: V9_0
                  - name: node4
                    baseUrl: https://node4.example.test:8443
            """.formatted(c81, jdk17, c90, jdk21, c90, jdk21));

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).as(out() + err()).isZero();
        assertThat(out())
            .contains("dev/node1: read-only, cli: available, client: " + c90 + " (cliClients"
                + " \"9.0\"), address: base URL, inubit: 9.0.7, client: 9.0.7 (9.0.0–9.0.10),"
                + " client vs pin: match, secrets: credentialStore (macOS Keychain), username ←")
            .contains("dev/node2: read-only, cli: available, client: " + c81 + " (cliClients"
                + " \"8.1\"), address: servlet path, inubit: V8_1, client: 8.1.17"
                + " (4.0.1–8.1.17), server version: determined at runtime, secrets:")
            .contains("dev/node3: read-only, cli: available, client: " + c90 + " (cliClients"
                + " \"9.0\"), address: base URL, inubit: V9_0, client: 9.0.7 (9.0.0–9.0.10),"
                + " server version: determined at runtime, secrets:")
            .contains("dev/node4: read-only, cli: unavailable, client: none, address: servlet"
                + " path; chosen at runtime by the detected version, inubit: AUTO, secrets:")
            .contains("\nclient " + c81 + " (Java 17): CLI 8.1.17 (4.0.1–8.1.17)\n"
                + "client " + c90 + " (Java 21): CLI 9.0.7 (9.0.0–9.0.10)\nWarnings:")
            .contains("  - defaults.cliClients.\"9.1\": the StartCLI client in " + c90
                + " supports INUBIT 9.0.0 to 9.0.10, which excludes 9.1")
            .doesNotContain("defaults.cliClients.\"9.0\": the StartCLI client",
                "defaults.cliClients.\"8.1\": the StartCLI client")
            .contains("Result: OK")
            .doesNotContain("://", "example.test", PASSWORD, USERNAME);
        // local probes only, no server URL; the 9.0 and 9.1 entries are one client
        assertThat(Files.readString(c90.resolve("bin/arguments"))).isEqualTo("-v\n");
        assertThat(Files.readString(c81.resolve("bin/arguments"))).isEqualTo("-v\n");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a POSIX shell substitute of startcli.sh;"
        + " the Windows launch is covered by CliRunnerTest")
    void checkConfigNamesAFailedEntryProbeAndKeepsTheClientLine() throws IOException {
        credentials();
        Path c90 = fakeStartCli("c90", "echo \"unknown_command\"\nexit 1\n");
        Path file = config("""
            profile:
              name: acme
            defaults:
              cliClients:
                "9.0": { home: "%s", javaHome: "%s" }
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
            """.formatted(c90, home)); // a JDK without release file: Java unknown

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).as(out() + err()).isZero();
        assertThat(out())
            .contains("\nclient " + c90 + " (Java unknown): CLI version unknown\nWarnings:")
            .contains("  - defaults.cliClients.\"9.0\": StartCLI client probe failed: StartCLI"
                + " did not report its version (exit code 1)")
            .contains("Result: OK")
            .doesNotContain("://", "example.test", PASSWORD, USERNAME);
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a POSIX shell substitute of startcli.sh;"
        + " the Windows launch is covered by CliRunnerTest")
    void checkConfigOfA080ProfileShowsNoClientLines() throws IOException {
        // FR-009: without cliClients and 9.x nodes the 0.8.0 output stays
        credentials();
        Path cliHome = fakeStartCli("client", "8.1.17", "4.0.1", "8.1.17");
        Path file = config("""
            profile:
              name: acme
            groups:
              - name: dev
                cli:
                  home: "%s"
                  javaHome: "%s"
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                  - name: node2
                    baseUrl: https://node2.example.test:8443
                    versionLine: V8_1
            """.formatted(cliHome, home));

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).as(out() + err()).isZero();
        assertThat(out())
            .contains("dev/node1: read-only, cli: available, inubit: AUTO, client: 8.1.17"
                + " (4.0.1–8.1.17), server version: determined at runtime, secrets:")
            .contains("dev/node2: read-only, cli: available, inubit: V8_1, client: 8.1.17"
                + " (4.0.1–8.1.17), server version: determined at runtime, secrets:")
            .doesNotContain("\nclient ", "address:", "cliClients");
        assertThat(Files.readString(cliHome.resolve("bin/arguments"))).isEqualTo("-v\n");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a POSIX shell substitute of startcli.sh;"
        + " the Windows launch is covered by CliRunnerTest")
    void checkConfigWarnsAboutAnEntryHomeWithoutStartCli() throws IOException {
        // review of stage 7, S1 (FR-006, research D-17): the INUBIT installation instead of its
        // client directory is reported, not silently left unprobed
        credentials();
        Path installation = Files.createDirectories(home.resolve("inubit-9.0"));
        Path file = config("""
            profile:
              name: acme
            defaults:
              cliClients:
                "9.0": { home: "%s", javaHome: "%s" }
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
            """.formatted(installation, home));

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).as(out() + err()).isZero();
        assertThat(out())
            .contains("  - defaults.cliClients.\"9.0\": " + installation.resolve("bin/startcli.sh")
                + " not found; CLI-backed capabilities report CLI_UNAVAILABLE on nodes using this"
                + " entry")
            .contains("\nclient " + installation + " (Java unknown): CLI version unknown\n")
            .contains("Result: OK")
            .doesNotContain("://", "example.test", PASSWORD, USERNAME);
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a POSIX shell substitute of startcli.sh;"
        + " the Windows launch is covered by CliRunnerTest")
    void checkConfigNamesAnEntryWithoutJdkInsteadOfProbingItThroughANode() throws IOException {
        // review of stage 7, N4: no entry javaHome, no defaults.cliJavaHome, no JAVA_HOME (the
        // environment of this test has none); the warning names the entry, not dev/node1
        credentials();
        Path c90 = fakeStartCli("c90", "9.0.7", "9.0.0", "9.0.10");
        Path file = config("""
            profile:
              name: acme
            defaults:
              cliClients:
                "9.0": { home: "%s" }
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
            """.formatted(c90));

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).as(out() + err()).isZero();
        assertThat(out())
            .contains("  - defaults.cliClients.\"9.0\": neither its javaHome, defaults.cliJavaHome"
                + " nor JAVA_HOME is set; CLI-backed capabilities report CLI_UNAVAILABLE on nodes"
                + " using this entry without their own cli.javaHome")
            .contains("\nclient " + c90 + " (Java unknown): CLI version unknown\n")
            .doesNotContain("probe failed", "No JDK for StartCLI")
            .contains("Result: OK");
        assertThat(c90.resolve("bin/arguments")).as("not started").doesNotExist();
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a POSIX shell substitute of startcli.sh;"
        + " the Windows launch is covered by CliRunnerTest")
    void checkConfigPrintsTheVersionPartExampleOfTheSetupGuide() throws IOException {
        // review of stage 7, N2: the excerpt in docs/setup.md ("The version part of a node
        // line") is the real output, with the client directories under /opt
        credentials();
        entries.put("INUBIT_ACME_NEXT_USERNAME", USERNAME);
        entries.put("INUBIT_ACME_NEXT_PASSWORD", PASSWORD);
        entries.put("INUBIT_ACME_LEGACY_USERNAME", USERNAME);
        entries.put("INUBIT_ACME_LEGACY_PASSWORD", PASSWORD);
        Path c81 = fakeStartCli("inubit-8.1/client", "8.1.17", "4.0.1", "8.1.17");
        Path c61 = fakeStartCli("inubit-6.1/client", """
            echo "CLI 6.1.0"
            echo "Supported inubit Process Engine versions from 4.0.1 to 6.1.0."
            """);
        Path file = config("""
            profile:
              name: acme
            defaults:
              inventory:
                owner: INTEGRATION
            groups:
              - name: dev
                cli:
                  home: "%s"
                  javaHome: "%s"
                nodes:
                  - name: node1
                    baseUrl: https://inubit-dev1.example.test:8443
                  - name: node2
                    baseUrl: https://inubit-dev2.example.test:8443
                    versionLine: "8.1.17"
              - name: next
                nodes:
                  - name: node1
                    baseUrl: https://inubit-next.example.test:8443
                    versionLine: V9_X
              - name: legacy
                versionLine: V6_1
                cli:
                  home: "%s"
                  javaHome: "%s"
                nodes:
                  - name: node1
                    baseUrl: https://inubit-legacy.example.test:8443
            """.formatted(c81, jdk("jdk-17", "17.0.9"), c61, jdk("jdk-8", "1.8.0_402")));
        String guide = Files.readString(Path.of("docs/setup.md"));
        int start = guide.lastIndexOf("```text\n", guide.indexOf("  Node next/node1: "));
        String excerpt = guide.substring(start + "```text\n".length(),
            guide.indexOf("```", start + 3));

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).as(out() + err()).isZero();
        assertThat(out().replace(home.toString(), "/opt")).contains(excerpt);
    }

    /** An executable {@code <home>/client/bin/startcli.sh} running {@code body}; the CLI home. */
    private Path fakeStartCli(String body) throws IOException {
        return fakeStartCli("client", body);
    }

    /**
     * An executable {@code <home>/<name>/bin/startcli.sh} that records its arguments and reports
     * {@code version} supporting {@code from} to {@code to}; the CLI home.
     */
    private Path fakeStartCli(String name, String version, String from, String to)
        throws IOException {
        return fakeStartCli(name, """
            echo "$@" >> "$(dirname "$0")/arguments"
            echo "CLI %s"
            echo "Supported inubit Process Engine versions from %s to %s."
            """.formatted(version, from, to));
    }

    /** An executable {@code <home>/<name>/bin/startcli.sh} running {@code body}; the CLI home. */
    private Path fakeStartCli(String name, String body) throws IOException {
        Path cliHome = Files.createDirectories(home.resolve(name + "/bin")).getParent();
        Path script = cliHome.resolve("bin/startcli.sh");
        Files.writeString(script, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return cliHome;
    }

    /** A JDK directory {@code <home>/<name>} whose {@code release} names {@code version}. */
    private Path jdk(String name, String version) throws IOException {
        Path jdk = Files.createDirectories(home.resolve(name));
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        return jdk;
    }

    @Test
    void checkConfigDoesNotStartTheServerOrCreateTheAuditDirectory() throws IOException {
        credentials();
        Path file = validConfig();

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).isZero();
        assertThat(home.resolve("audit")).doesNotExist();
        assertThat(out()).doesNotContain("jsonrpc");
    }

    /** 002-T033 (FR-018): the format of feature 001, without any value worth echoing. */
    private static final String FORMAT_001 = """
        stages:
          - name: dev
            servers:
              - name: node1
                baseUrl: https://node1.dev.example.test:8443
        """;

    @Test
    void checkConfigRefusesTheFormatOf001WithTheMigrationGuide() throws IOException {
        environment.put("INUBIT_DEV_USERNAME", USERNAME);
        environment.put("INUBIT_DEV_PASSWORD", PASSWORD);
        Path file = config(FORMAT_001);

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(err()).contains("stages", "groups", "stages[0].servers", "nodes", "profile",
                "credentials.prefix: INUBIT", "~/.inubit-mcp/audit",
                "docs/migration-001-to-002.md")
            .doesNotContain("Unknown key", USERNAME, PASSWORD, "node1.dev.example.test");
        assertThat(out()).isEmpty();
    }

    @Test
    void theServerRefusesToStartWithTheFormatOf001() throws IOException {
        Path file = config(FORMAT_001);

        int exit = run("--config", file.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(err()).contains("docs/migration-001-to-002.md").doesNotContain("Unknown key");
        assertThat(out()).isEmpty();
        assertThat(home.resolve(".inubit-mcp")).doesNotExist();
    }

    @Test
    void aMissingConfigFileListsTheSearchedLocations() {
        int exit = run("--check-config");

        assertThat(exit).isEqualTo(1);
        assertThat(err()).contains("No configuration file found")
            .contains("INUBIT_MCP_CONFIG")
            .contains(home.resolve(".config/inubit-mcp/config.yaml").toString());
        assertThat(out()).isEmpty();
    }

    @Test
    void anExplicitMissingConfigFileIsNamed() {
        int exit = run("--config", home.resolve("nope.yaml").toString());

        assertThat(exit).isEqualTo(1);
        assertThat(err()).contains("No configuration file found", "nope.yaml");
        assertThat(out()).isEmpty();
    }

    @Test
    void theConfigIsFoundThroughTheEnvironmentVariable() throws IOException {
        credentials();
        environment.put("INUBIT_MCP_CONFIG", validConfig().toString());

        int exit = run("--check-config");

        assertThat(exit).as(err()).isZero();
        assertThat(out()).contains("dev/node1: read-only");
    }

    @Test
    void invalidConfigurationRefusesToStartWithAllErrorsOnStderr() throws IOException {
        Path file = config("""
            profile:
              name: acme
            groups:
              - name: prod
                production: true
                write:
                  confirmation: CLIENT
                nodes:
                  - name: node1
                    baseUrl: http://inubit.example.test:8080
                    password: %s
            """.formatted(PASSWORD));

        int exit = run("--config", file.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(err()).contains("confirmation CLIENT", "http://",
            "store the value in the credential store", "service inubit-mcp")
            .doesNotContain(PASSWORD);
        assertThat(out()).as("nothing on the protocol stdout").isEmpty();
    }

    // --- 002 research D-9: profile selection --------------------------------------------------

    /** {@code <home>/.config/inubit-mcp/<fileName>.yaml} declaring {@code profileName}. */
    private Path profileConfig(String fileName, String profileName) throws IOException {
        Path file = home.resolve(".config/inubit-mcp/" + fileName + ".yaml");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, """
            profile:
              name: %s
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    versionLine: V8_1
            """.formatted(profileName));
    }

    @Test
    void theProfileArgumentSelectsTheProfileFileInTheDefaultDirectory() throws IOException {
        credentials();
        Path acme = profileConfig("acme", "acme");
        profileConfig("config", "globex");

        int exit = run("--profile", "acme", "--check-config");

        assertThat(exit).as(err()).isZero();
        assertThat(out()).contains("Configuration: " + acme, "Profile: acme",
            "username ← INUBIT_ACME_DEV_USERNAME ok");
    }

    @Test
    void theProfileEnvironmentVariableSelectsTheProfileFile() throws IOException {
        credentials();
        Path acme = profileConfig("acme", "acme");
        environment.put("INUBIT_MCP_PROFILE", "acme");

        int exit = run("--check-config");

        assertThat(exit).as(err()).isZero();
        assertThat(out()).contains("Configuration: " + acme, "Profile: acme");
    }

    @Test
    void configAndProfileTogetherAreAUsageError() throws IOException {
        Path acme = profileConfig("acme", "acme");

        int exit = run("--config", acme.toString(), "--profile", "acme");

        assertThat(exit).isEqualTo(2);
        assertThat(err()).contains("--config and --profile cannot be combined", "Usage:");
        assertThat(out()).isEmpty();
    }

    @Test
    void theUsageNamesCredentialStoreEntriesAndParameters() {
        // feature 011 (T022): no credential variables any more
        run("--unknown");

        assertThat(err()).contains("Usage:", "credential store entry and parameter names only")
            .doesNotContain("variable and");
    }

    @Test
    void profileWithoutAValidNameIsAUsageErrorThatDoesNotEchoTheValue() {
        assertThat(run("--profile")).isEqualTo(2);
        assertThat(run("--profile", "--check-config")).isEqualTo(2);
        assertThat(run("--profile", "../" + PASSWORD)).isEqualTo(2);
        assertThat(run("--profile", "acme", "--profile", "acme")).isEqualTo(2);

        assertThat(err()).contains("--profile needs a profile name",
            "--profile is given twice").doesNotContain(PASSWORD);
        assertThat(out()).isEmpty();
    }

    @Test
    void aProfileFileDeclaringAnotherProfileIsAnError() throws IOException {
        credentials();
        profileConfig("acme", "globex");

        int exit = run("--profile", "acme", "--check-config");

        assertThat(exit).isEqualTo(1);
        assertThat(err()).contains("profile.name 'globex'", "--profile acme");
        assertThat(out()).isEmpty();
    }

    @Test
    void checkConfigReportsAnotherProfileFileThatReadsTheSameCredentialVariables()
        throws IOException {
        // 002 T031 and US2 review P1: the same prefix and the same group → shared variable names
        // (error) and the same prefix (warning); best effort, the default configuration directory
        credentials();
        profileConfig("acme", "acme");
        Path globex = profileConfig("globex", "globex");
        Files.writeString(globex, Files.readString(globex)
            + "credentials:\n  envPrefix: INUBIT_ACME\n");

        int exit = run("--profile", "acme", "--check-config");

        assertThat(exit).isEqualTo(1);
        assertThat(out()).contains("Errors:", globex.toString(), "INUBIT_ACME_DEV_USERNAME",
            "Warnings:", "credential entry prefix INUBIT_ACME")
            .doesNotContain(PASSWORD, USERNAME);
    }

    @Test
    void variablesOfAnotherProfileInTheDefaultDirectoryAreNotReportedAsUnmatched()
        throws IOException {
        // 002 US2 review P2: acme-2's variables start with INUBIT_ACME_
        credentials();
        environment.put("INUBIT_ACME_2_DEV_PASSWORD", "other-profile-pw");
        profileConfig("acme", "acme");
        profileConfig("acme-2", "acme-2");

        int exit = run("--profile", "acme", "--check-config");

        assertThat(exit).as(err()).isZero();
        assertThat(out()).doesNotContain("INUBIT_ACME_2_DEV_PASSWORD", "Warnings:");
    }

    @Test
    void aCopyOfTheSameProfileIsOnlyAWarningAndStillChecksOk() throws IOException {
        // re-review N1 (a): same profile.name → warning, the copy may start
        credentials();
        Path acme = profileConfig("acme", "acme");
        Path copy = home.resolve(".config/inubit-mcp/acme-copy.yaml");
        Files.copy(acme, copy);

        int exit = run("--profile", "acme", "--check-config");

        assertThat(exit).as(out() + err()).isZero();
        assertThat(out()).contains("Warnings:", copy.toString(), "profile name 'acme'")
            .doesNotContain("Errors:");
    }

    @Test
    void theReservedProfileNameAuditIsAUsageError() {
        assertThat(run("--profile", "audit")).isEqualTo(2);
        assertThat(err()).contains("--profile needs a profile name", "audit");
    }

    @Test
    void theDefaultAuditDirectoryAndTheDirectoriesCreatedForItAreOwnerOnly()
        throws Exception {
        // 002 US2 review P3c: ~/.inubit-mcp and ~/.inubit-mcp/<profile> are created rwx------
        org.junit.jupiter.api.Assumptions.assumeTrue(FileSystems.getDefault()
            .supportedFileAttributeViews().contains("posix"));
        credentials();
        profileConfig("acme", "acme");

        int exit = run("--profile", "acme");

        assertThat(exit).as(err()).isZero();
        for (String directory : List.of(".inubit-mcp", ".inubit-mcp/acme",
            ".inubit-mcp/acme/audit")) {
            assertThat(PosixFilePermissions.toString(
                Files.getPosixFilePermissions(home.resolve(directory))))
                .as(directory).isEqualTo("rwx------");
        }
    }

    @Test
    void anExistingParentOfTheAuditDirectoryIsNotModified() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(FileSystems.getDefault()
            .supportedFileAttributeViews().contains("posix"));
        credentials();
        profileConfig("acme", "acme");
        Path base = Files.createDirectory(home.resolve(".inubit-mcp"));
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwxr-xr-x"));

        int exit = run("--profile", "acme");

        assertThat(exit).as(err()).isZero();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(base)))
            .isEqualTo("rwxr-xr-x");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(
            home.resolve(".inubit-mcp/acme")))).isEqualTo("rwx------");
    }

    @Test
    void theUsageTextExplainsTheProfileSelection() {
        int exit = run("--frobnicate");

        assertThat(exit).isEqualTo(2);
        assertThat(err()).contains("--profile <name>", "<name>.yaml", "INUBIT_MCP_PROFILE",
            "INUBIT_MCP_CONFIG");
    }

    @Test
    void unknownArgumentsAreAUsageError() {
        int exit = run("--frobnicate");

        assertThat(exit).isEqualTo(2);
        assertThat(err()).contains("--frobnicate", "--config", "--check-config", "--version");
        assertThat(out()).isEmpty();
    }

    @Test
    void configWithoutValueIsAUsageError() {
        int exit = run("--config");

        assertThat(exit).isEqualTo(2);
        assertThat(err()).contains("--config");
    }

    @Test
    void serverModeServesMcpOnStdioCreatesTheAuditDirectoryAndExitsOnEof() throws Exception {
        credentials();
        Path file = validConfig();
        PipedInputStream serverIn = new PipedInputStream(1 << 16);
        PipedOutputStream toServer = new PipedOutputStream(serverIn);
        LineQueue protocol = new LineQueue();
        Launcher main = main(serverIn, protocol);

        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(
            () -> main.run("--config", file.toString()));
        send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
        JsonNode initialize = protocol.next();
        send(toServer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        JsonNode tools = protocol.next();
        send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"list_nodes\",\"arguments\":{}}}");
        JsonNode servers = protocol.next();
        toServer.close();

        assertThat(exit.get(20, TimeUnit.SECONDS)).as(err()).isZero();
        assertThat(initialize.path("result").path("serverInfo").path("name").asString())
            .isEqualTo("inubit-mcp-server");
        assertThat(initialize.path("result").path("serverInfo").path("version").asString())
            .isEqualTo(BuildInfo.version());
        // US1 (Phase 3), US2 (Phase 4), US3 (Phase 5) and check_artifacts (feature 003); no
        // write tool while write is disabled, no export without a CLI installation
        assertThat(tools.path("result").path("tools"))
            .extracting(tool -> tool.path("name").asString())
            .containsExactlyInAnyOrder("list_nodes", "get_health", "find_processes",
                "query_logs", "list_inventory", "get_inventory_item", "check_artifacts");
        JsonNode server = servers.path("result").path("structuredContent").path("groups").path(0)
            .path("nodes").path(0);
        assertThat(server.path("id").asString()).isEqualTo("dev/node1");
        assertThat(server.path("writeEnabled").asBoolean(true)).isFalse();
        assertThat(server.path("versionLine").asString()).isEqualTo("V8_1");
        assertThat(servers.toString()).doesNotContain("example.test", USERNAME, PASSWORD);
        assertThat(protocol.rawText()).as("no partial trailing line").endsWith("\n");
        assertThat(protocol.raw()).as("only JSON-RPC lines on the protocol stdout")
            .allSatisfy(line -> assertThat(JSON.readTree(line).path("jsonrpc").asString())
                .isEqualTo("2.0"));
        Path audit = home.resolve("audit/nested");
        assertThat(audit).isDirectory();
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(audit)))
                .isEqualTo("rwx------");
        }
        assertThat(err()).doesNotContain(PASSWORD);
    }

    @Test
    void theWiringKnowsTheConfigurationFileInUseAndTheDefaultDirectory() throws Exception {
        // feature 015 T014 (research R-9): LoadedConfig.source() reaches the Wiring
        credentials();
        Path file = Files.createDirectories(home.resolve("elsewhere")).resolve("profile.yaml");
        Files.move(validConfig(), file);
        AtomicReference<Wiring> captured = new AtomicReference<>();

        int exit = main(new ByteArrayInputStream(new byte[0]), stdout, wiring -> {
            captured.set(wiring);
            return wiring.toolHandlers();
        }).run("--config", file.toString());

        assertThat(exit).as(err()).isZero();
        assertThat(captured.get()).as("the server was started").isNotNull();
        assertThat(captured.get().configLocation()).isEqualTo(new ConfigLocation(
            new ConfigLoader(Map.copyOf(environment), home, false).defaultDirectory(), file));
    }

    @Test
    void exportArchivesRefusesTheFolderOfTheConfigurationFileInUse() throws Exception {
        // feature 015 T017 (research R-9): the folder of --config is one of the server's
        // directories, refused before StartCLI
        credentials();
        Path file = Files.createDirectories(home.resolve("elsewhere")).resolve("profile.yaml");
        Files.writeString(file, Files.readString(validConfig())
            + "    cli:\n      home: " + home.resolve("startcli") + "\n");
        PipedInputStream serverIn = new PipedInputStream(1 << 16);
        PipedOutputStream toServer = new PipedOutputStream(serverIn);
        LineQueue protocol = new LineQueue();
        Launcher main = main(serverIn, protocol);

        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(
            () -> main.run("--config", file.toString()));
        send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
        protocol.next();
        send(toServer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"export_archives\",\"arguments\":{\"target\":\"dev/node1\","
            + "\"owner\":\"OWNERS\",\"diagramGroup\":\"GRP-01\",\"targetFolder\":"
            + JSON.writeValueAsString(home.resolve("elsewhere").resolve("sub").toString())
            + ",\"acknowledgeClearTextSecrets\":true}}}");
        JsonNode refused = protocol.next().path("result");
        toServer.close();

        assertThat(exit.get(20, TimeUnit.SECONDS)).as(err()).isZero();
        assertThat(refused.path("isError").asBoolean()).as(refused.toString()).isTrue();
        assertThat(refused.toString()).contains("INVALID_INPUT",
            "is or lies inside one of the server's directories",
            "the folder of the configuration file in use");
        assertThat(home.resolve("elsewhere").resolve("sub")).doesNotExist();
    }

    @Test
    void anExistingAuditDirectoryIsRestrictedToTheOwner() throws Exception {
        credentials();
        Path file = validConfig();
        Path audit = Files.createDirectories(home.resolve("audit/nested"));
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.setPosixFilePermissions(audit, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        int exit = run("--config", file.toString()); // empty stdin: starts and stops at once

        assertThat(exit).as(err()).isZero();
        if (posix) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(audit)))
                .isEqualTo("rwx------");
        }
    }

    // --- feature 013 T024: the audit directory and log are private (FR-007, US3 AS4) ---------

    @Test
    void anAuditDirectoryThatCannotBeMadePrivateStopsTheServerFromStarting() throws Exception {
        credentials();
        Path file = validConfig();
        Files.createDirectories(home.resolve(".inubit-mcp/acme/workspace")); // T026: not created
        privateFiles = PrivateFiles.failing();

        int exit = run("--config", file.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(err()).contains("Cannot create the audit directory "
            + home.resolve("audit/nested"), "the MCP server does not start");
        assertThat(home.resolve("audit")).doesNotExist();
    }

    @Test
    void theDefaultAuditDirectoryAndLogWithBroaderAccessAreNamedAtStartupAndInCheckConfig()
        throws Exception {
        credentials();
        profileConfig("acme", "acme");
        Path audit = Files.createDirectories(home.resolve(".inubit-mcp/acme/audit"));
        Path log = Files.writeString(audit.resolve("audit-2026-10.jsonl"), "{}\n");
        privateFiles = PrivateFiles.reporting(List.of("BUILTIN\\Users"));

        int checked = run("--profile", "acme", "--check-config");
        String summary = out();
        int started = run("--profile", "acme"); // empty stdin: starts and stops at once

        assertThat(checked).as(summary).isZero();
        assertThat(started).as(err()).isZero();
        for (String output : List.of(summary, err())) {
            assertThat(output).contains(
                "the audit directory " + audit + " grants access to BUILTIN\\Users; the server"
                    + " does not change it", "icacls \"" + audit + "\" /inheritance:r",
                "the audit log " + log + " grants access to BUILTIN\\Users");
        }
        assertThat(Files.readString(log)).as("not changed").isEqualTo("{}\n");
    }

    @Test
    void theDeploymentLedgerAndLockWithBroaderAccessAreNamedAtStartupAndInCheckConfig()
        throws Exception {
        // feature 013 T025 (FR-007)
        credentials();
        profileConfig("acme", "acme");
        Path deployments = Files.createDirectories(home.resolve(".inubit-mcp/acme/deployments"));
        Path ledger = Files.writeString(deployments.resolve("int.ledger.json"), "{}\n");
        Path lock = Files.createFile(deployments.resolve("int.lock"));
        privateFiles = PrivateFiles.reporting(List.of("BUILTIN\\Users"));

        int checked = run("--profile", "acme", "--check-config");
        String summary = out();
        int started = run("--profile", "acme"); // empty stdin: starts and stops at once

        assertThat(checked).as(summary).isZero();
        assertThat(started).as(err()).isZero();
        for (String output : List.of(summary, err())) {
            assertThat(output).contains(
                "the deployment ledger " + ledger + " grants access to BUILTIN\\Users; the"
                    + " server does not change it", "icacls \"" + ledger + "\" /inheritance:r",
                "the deployment lock " + lock + " grants access to BUILTIN\\Users");
        }
        assertThat(Files.readString(ledger)).as("not changed").isEqualTo("{}\n");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsTheNewDefaultAuditDirectoryIsPrivate() throws Exception {
        credentials();
        profileConfig("acme", "acme");

        assertThat(run("--profile", "acme")).as(err()).isZero();

        WindowsAcl.assertPrivate(home.resolve(".inubit-mcp/acme/audit"),
            WindowsAcl.DIRECTORY_INHERITANCE);
        assertThat(err()).doesNotContain("grants access to");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsAnInheritedUsersEntryIsNamedAndTheRemedyRestrictsTheDirectory()
        throws Exception {
        credentials();
        profileConfig("acme", "acme");
        Path parent = Files.createDirectories(home.resolve(".inubit-mcp/acme"));
        WindowsAcl.run(new ProcessBuilder(WindowsAcl.system32("icacls.exe").toString(),
            parent.toString(), "/grant", "*" + WindowsAcl.USERS + ":(OI)(CI)(RX)"));
        Path audit = Files.createDirectory(parent.resolve("audit"));
        assertThat(WindowsAcl.readBack(audit).entries())
            .anySatisfy(entry -> {
                assertThat(entry.sid()).isEqualTo(WindowsAcl.USERS);
                assertThat(entry.inherited()).isTrue();
            });

        assertThat(run("--profile", "acme", "--check-config")).as(out()).isZero();

        Matcher remedy = Pattern.compile("the audit directory " + Pattern.quote(audit.toString())
            + " grants access to [^;]*Users[^;]*; the server does not change it\\. Restrict it"
            + " to [^ ]+ and SYSTEM with: icacls (.*)").matcher(out());
        assertThat(remedy.find()).as(out()).isTrue();
        assertThat(WindowsAcl.readBack(audit).entries()).as("not changed")
            .extracting(WindowsAcl.Entry::sid).contains(WindowsAcl.USERS);
        List<String> command = new ArrayList<>(List.of(
            WindowsAcl.system32("icacls.exe").toString()));
        Matcher token = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(remedy.group(1).strip());
        while (token.find()) {
            command.add(token.group(1) != null ? token.group(1) : token.group(2));
        }
        WindowsAcl.run(new ProcessBuilder(command));
        WindowsAcl.ReadBack restricted = WindowsAcl.readBack(audit); // the owner is not changed
        assertThat(restricted.isProtected()).isTrue();
        assertThat(restricted.entries()).extracting(WindowsAcl.Entry::sid)
            .containsExactlyInAnyOrder(restricted.me(), WindowsAcl.SYSTEM);
        stdout.reset();
        assertThat(run("--profile", "acme", "--check-config")).as(out()).isZero();
        assertThat(out()).doesNotContain("grants access to");
    }

    // --- feature 013 T026: the workspace is private (FR-007) ---------------------------------

    @Test
    void aWorkspaceThatCannotBeMadePrivateIsAConfigurationError() throws Exception {
        credentials();
        profileConfig("acme", "acme");
        privateFiles = PrivateFiles.failing();
        Path workspace = home.resolve(".inubit-mcp/acme/workspace");

        int exit = run("--profile", "acme", "--check-config");

        assertThat(exit).as(out()).isNotZero();
        assertThat(out() + err()).contains("The workspace " + workspace + " cannot be created");
        assertThat(workspace).doesNotExist();
    }

    @Test
    void anExistingWorkspaceIsNotChecked() throws Exception {
        credentials();
        profileConfig("acme", "acme");
        Path workspace = Files.createDirectories(home.resolve(".inubit-mcp/acme/workspace"));
        privateFiles = PrivateFiles.reporting(List.of("BUILTIN\\Users"));

        assertThat(run("--profile", "acme", "--check-config")).as(out()).isZero();

        assertThat(out()).doesNotContain(workspace + " grants access");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsTheNewDefaultWorkspaceIsPrivate() throws Exception {
        credentials();
        profileConfig("acme", "acme");

        assertThat(run("--profile", "acme", "--check-config")).as(out()).isZero();

        WindowsAcl.assertPrivate(home.resolve(".inubit-mcp/acme/workspace"),
            WindowsAcl.DIRECTORY_INHERITANCE);
    }

    // --- feature 013 T012: the containment of StartCLI's processes (FR-005, research R-7) ------

    @Test
    void theServerSetsUpTheProcessContainmentOnceAndVersionDoesNot() throws IOException {
        credentials();
        Path file = validConfig();

        assertThat(run("--version")).isZero();
        assertThat(containments).hasValue(0);

        assertThat(run("--config", file.toString())).as(err()).isZero(); // empty stdin
        assertThat(containments).hasValue(1);
    }

    /**
     * T013: {@code --check-config} sets up the containment before the client probe (on Windows
     * the kill-on-close job, so a forced end of the check also ends the probe), and an unavailable
     * one fails the probe without starting StartCLI.
     */
    @Test
    void checkConfigProbesTheClientOnlyWithItsProcessesContained() throws IOException {
        credentials();
        windows = Platform.windows();
        Path cliHome = home.resolve("client");
        FakeStartCli cli = FakeStartCli.replaying(cliHome, "version");
        containment = () -> new ProcessContainment.Unavailable(
            "CreateJobObjectW failed (Windows error 5)");
        Path file = config("""
            profile:
              name: acme
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    versionLine: "8.1.17"
                    cli:
                      home: %s
                      javaHome: %s
            """.formatted(cliHome, home));

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).as(out() + err()).isZero();
        assertThat(containments).hasValue(1);
        assertThat(out()).contains("  - dev/node1: StartCLI client probe failed: StartCLI"
            + " cannot be used for dev/node1: StartCLI processes could not be bound to the"
            + " server (CreateJobObjectW failed (Windows error 5))");
        assertThat(cli.recordFile()).doesNotExist();
    }

    /**
     * T015: {@code --check-config} sets up the containment also when no node has a client, and on
     * Windows an unavailable one is a warning of the check (contracts/configuration-delta.md).
     */
    @Test
    void checkConfigReportsAnUnavailableContainmentOnWindowsAlsoWithoutAClient()
        throws IOException {
        credentials();
        windows = Platform.windows();
        containment = () -> new ProcessContainment.Unavailable(
            "CreateJobObjectW failed (Windows error 5)");
        Path file = config("""
            profile:
              name: acme
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    versionLine: "8.1.17"
                    cli:
                      home: %s
                      javaHome: %s
            """.formatted(home.resolve("no-client"), home));

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).as(out() + err()).isZero();
        assertThat(containments).hasValue(1);
        String warning = "StartCLI processes could not be bound to the server (CreateJobObjectW"
            + " failed (Windows error 5)); CLI-backed capabilities report CLI_UNAVAILABLE";
        if (windows) {
            assertThat(out()).contains(warning);
        } else {
            assertThat(out()).doesNotContain(warning);
        }
    }

    /** Never on Windows: there the system context binds the process (this test JVM) to a job. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void onMacOsAndLinuxTheSystemContextNeedsNoJob() {
        assertThat(Launcher.Context.system(System.out).containment().get())
            .isInstanceOf(NoJob.class);
    }

    @Test
    void aStrayPositionalValueIsNotEchoed() {
        int exit = run("--check-config", "hunter2-typed-by-mistake");

        assertThat(exit).isEqualTo(2);
        assertThat(err()).contains("position 2").doesNotContain("hunter2");
    }

    @Test
    void anUnknownOptionIsEchoedOnlyUpToTheEqualsSign() {
        int exit = run("--password=hunter2-secret");

        assertThat(exit).isEqualTo(2);
        assertThat(err()).contains("Unknown argument: --password").doesNotContain("hunter2");
    }

    @Test
    void checkConfigAndVersionCannotBeCombined() {
        int exit = run("--check-config", "--version");

        assertThat(exit).isEqualTo(2);
        assertThat(err()).contains("cannot be combined");
        assertThat(out()).isEmpty();
    }

    @Test
    void configGivenTwiceIsAUsageError() {
        int exit = run("--config", "a.yaml", "--config", "b.yaml");

        assertThat(exit).isEqualTo(2);
        assertThat(err()).contains("--config is given twice");
    }

    @Test
    void configurationWarningsArePrintedToStderrRegardlessOfTheLogLevel() throws IOException {
        credentials();
        Path file = config("""
            profile:
              name: acme
            logLevel: ERROR
            auditDirectory: %s
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    versionLine: V9_X
            """.formatted(home.resolve("audit")));

        int exit = run("--config", file.toString()); // empty stdin: starts and stops at once

        assertThat(exit).as(err()).isZero();
        assertThat(err()).contains("Warnings:", "versionLine V9_X of dev/node1 does not select"
            + " 9.0 support; use V9_0 (INUBIT 9.0.x) or AUTO");
        assertThat(out()).doesNotContain("V9_X");
    }

    @Test
    void aFailureDuringStartupIsOneLineOnStderrAndExitsOne() throws IOException {
        credentials();
        Path file = validConfig();
        ToolHandler withoutSchema = new ToolHandler() {
            @Override
            public String name() {
                return "no_schema_tool";
            }

            @Override
            public String descriptionText() {
                return "A tool whose schema resources do not exist";
            }

            @Override
            public ToolHints annotations() {
                return ToolHints.readOnly("No schema", false);
            }

            @Override
            public Object handle(Map<String, Object> arguments) {
                return Map.of();
            }
        };

        int exit = main(new ByteArrayInputStream(new byte[0]), stdout,
            wiring -> List.of(withoutSchema)).run("--config", file.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(err().strip()).doesNotContain("\n")
            .startsWith("Startup failed:")
            .contains("schemas/no_schema_tool.input.json");
        assertThat(out()).isEmpty();
    }

    @Test
    void aMarkedStageIsNamedOnceInTheStartupLog() throws Exception {
        // feature 014 (FR-005, research R-5)
        Path file = home.resolve("config.yaml");
        try (InputStream fixture = MainTest.class.getResourceAsStream(
            "/config/pin-per-server.yaml")) {
            Files.writeString(file, new String(fixture.readAllBytes(), StandardCharsets.UTF_8)
                .replace("profile:\n  name: acme\n", "profile:\n  name: acme\nauditDirectory: "
                    + home.resolve("audit") + "\n"));
        }
        Path stores = Files.createDirectories(home.resolve(".config/inubit-mcp"));
        Files.createFile(stores.resolve("acme-dev-truststore.p12"));
        Files.createFile(stores.resolve("acme-int-truststore.p12"));

        List<String> lines = startupLogOfAnInitializedServer(file);

        assertThat(lines).filteredOn(line -> line.contains("tls.pinPerServer"))
            .containsExactly("Stages with one pinned certificate per server (tls.pinPerServer):"
                + " int");
    }

    @Test
    void aProfileWithoutAMarkedStageLogsNoPinPerServerLine() throws Exception {
        credentials();

        List<String> lines = startupLogOfAnInitializedServer(validConfig());

        assertThat(lines).anyMatch(line -> line.contains("started for profile acme"));
        assertThat(lines).noneMatch(line -> line.contains("pinPerServer"));
    }

    @Test
    void aNewerReleaseShowsInTheFirstResultInGetHealthInTheLogAndInTheCache() throws Exception {
        // feature 016 T010 (FR-001, FR-005 to FR-007, FR-017): the check runs after the start
        credentials();
        releaseSource = () -> new ReleaseSource.LatestRelease(SemVer.parse("99.0.0").orElseThrow());
        Path cacheFile = home.resolve(".inubit-mcp").resolve(FileUpdateCache.FILE_NAME);

        List<String> log = logOf(UpdateCheck.class, () -> serve(validConfig(), session -> {
            for (int i = 0; i < 100 && !Files.exists(cacheFile); i++) {
                Thread.sleep(50);
            }
            JsonNode first = session.call(2, "list_nodes", "{}");
            JsonNode health = session.call(3, "get_health", "{\"target\":\"dev/node1\"}");

            assertThat(first.path("result").path("content")).hasSize(2);
            assertThat(first.path("result").path("content").get(1).path("text").asString())
                .startsWith("Note: inubit-mcp-server 99.0.0 is available (installed "
                    + BuildInfo.version() + "), see https://github.com/D4lD4r/inubit-mcp-server/"
                    + "releases/tag/v99.0.0.");
            JsonNode update = health.path("result").path("structuredContent").path("serverUpdate");
            assertThat(update.path("latestVersion").asString()).isEqualTo("99.0.0");
            assertThat(update.path("updateAvailable").asBoolean()).isTrue();
            assertThat(health.path("result").path("content")).as("the note only once").hasSize(1);
        }));

        assertThat(log).containsExactly("A newer inubit-mcp-server is available: 99.0.0 (running "
            + BuildInfo.version() + "): https://github.com/D4lD4r/inubit-mcp-server/releases/tag/"
            + "v99.0.0 – update manually (no automatic download)");
        assertThat(Files.readString(cacheFile)).contains("99.0.0");
        assertThat(releaseLookups).hasValue(1);
    }

    @Test
    void aCachedNewerReleaseIsInTheInstructionsWhileTheSourceDoesNotAnswer() throws Exception {
        // feature 016 SC-006 (FR-018): the handshake does not wait for the network
        credentials();
        FileUpdateCache.inHome(home, Clock.systemUTC())
            .write(SemVer.parse("99.0.0").orElseThrow(), Instant.parse("2026-11-01T08:00:00Z"));
        CountDownLatch never = new CountDownLatch(1);
        releaseSource = () -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new ReleaseSource.ReleaseCheckException("interrupted");
        };

        serve(validConfig(), session -> {
            assertThat(session.initialize.path("result").path("instructions").asString())
                .endsWith(" A newer inubit-mcp-server 99.0.0 is available (installed "
                    + BuildInfo.version() + "): https://github.com/D4lD4r/inubit-mcp-server/"
                    + "releases/tag/v99.0.0; update manually.");
            assertThat(session.call(2, "list_nodes", "{}").path("result").path("content"))
                .hasSize(2);
            assertThat(session.call(3, "list_nodes", "{}").path("result").path("content"))
                .hasSize(1);
        });
    }

    @Test
    void theUpdateCheckCanBeSwitchedOffByTheEnvironment() throws Exception {
        // feature 016 FR-008/FR-009: no request, no cache, no note, an INFO line
        credentials();
        environment.put("INUBIT_MCP_UPDATE_CHECK", " OFF ");
        assertUpdateCheckOff(validConfig(), "Update check is off (INUBIT_MCP_UPDATE_CHECK)");
    }

    @Test
    void theUpdateCheckCanBeSwitchedOffInTheProfile() throws Exception {
        credentials();
        Path file = validConfig();
        Files.writeString(file, Files.readString(file) + "updateCheck:\n  enabled: false\n");
        assertUpdateCheckOff(file, "Update check is off (updateCheck.enabled: false)");
    }

    @Test
    void anUnrecognisedSwitchValueIsWarnedAboutAndTheCheckStaysOn() throws Exception {
        credentials();
        environment.put("INUBIT_MCP_UPDATE_CHECK", "sometimes");

        List<String> log = logOf(Launcher.class, () -> serve(validConfig(), session -> {
            for (int i = 0; i < 100 && releaseLookups.get() == 0; i++) {
                Thread.sleep(50);
            }
        }));

        assertThat(log).contains("Configuration: INUBIT_MCP_UPDATE_CHECK has an unrecognised"
            + " value; use off or on (the update check stays on)");
        assertThat(log).noneMatch(line -> line.contains("sometimes"));
        assertThat(releaseLookups).hasValue(1);
    }

    @Test
    void versionAndCheckConfigDoNotCheckForUpdates() throws IOException {
        // feature 016 FR-013
        credentials();
        assertThat(run("--version")).isZero();
        assertThat(run("--config", validConfig().toString(), "--check-config")).as(err()).isZero();

        assertThat(releaseLookups).hasValue(0);
        assertThat(updateCachesCreated).hasValue(0);
    }

    private void assertUpdateCheckOff(Path file, String infoLine) throws Exception {
        releaseSource = () -> new ReleaseSource.LatestRelease(SemVer.parse("99.0.0").orElseThrow());
        Path cacheFile = home.resolve(".inubit-mcp").resolve(FileUpdateCache.FILE_NAME);
        FileUpdateCache.inHome(home, Clock.systemUTC())
            .write(SemVer.parse("98.0.0").orElseThrow(), Instant.parse("2026-11-01T08:00:00Z"));
        String cached = Files.readString(cacheFile);

        List<String> log = logOf(Launcher.class, () -> serve(file, session -> {
            assertThat(session.initialize.path("result").path("instructions").asString())
                .doesNotContain("newer");
            Thread.sleep(300);
            assertThat(session.call(2, "list_nodes", "{}").path("result").path("content"))
                .hasSize(1);
            assertThat(session.call(3, "get_health", "{\"target\":\"dev/node1\"}")
                .path("result").path("structuredContent").has("serverUpdate")).isFalse();
        }));

        assertThat(log).contains(infoLine);
        assertThat(releaseLookups).hasValue(0);
        assertThat(updateCachesCreated).hasValue(0);
        assertThat(Files.readString(cacheFile)).isEqualTo(cached);
    }

    /** An initialized server session for {@link #serve}. */
    private record Session(PipedOutputStream toServer, LineQueue protocol, JsonNode initialize) {

        JsonNode call(int id, String tool, String arguments) throws Exception {
            send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}}");
            JsonNode response = protocol.next();
            assertThat(response.path("id").asInt()).isEqualTo(id);
            return response;
        }
    }

    private interface SessionBody {
        void accept(Session session) throws Exception;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Starts the server, initializes it, runs {@code body}, closes stdin and awaits exit 0. */
    private void serve(Path file, SessionBody body) throws Exception {
        PipedInputStream serverIn = new PipedInputStream(1 << 16);
        PipedOutputStream toServer = new PipedOutputStream(serverIn);
        LineQueue protocol = new LineQueue();
        Launcher main = main(serverIn, protocol);
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(
            () -> main.run("--config", file.toString()));
        try {
            send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
            JsonNode initialize = protocol.next();
            send(toServer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            body.accept(new Session(toServer, protocol, initialize));
        } finally {
            toServer.close();
        }
        assertThat(exit.get(20, TimeUnit.SECONDS)).as(err()).isZero();
        assertThat(protocol.raw()).as("only JSON-RPC lines on the protocol stdout")
            .allSatisfy(line -> assertThat(JSON.readTree(line).path("jsonrpc").asString())
                .isEqualTo("2.0"));
    }

    /** The formatted messages {@code type}'s logger writes while {@code action} runs. */
    private static List<String> logOf(Class<?> type, ThrowingRunnable action) throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger(type);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.setContext(context);
        logs.start();
        logger.addAppender(logs);
        try {
            action.run();
            synchronized (logs) {
                return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    /** Starts the server, initializes it, closes stdin and returns the {@link Launcher} log. */
    private List<String> startupLogOfAnInitializedServer(Path file) throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger launcher = context.getLogger(Launcher.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.setContext(context);
        logs.start();
        launcher.addAppender(logs);
        try {
            PipedInputStream serverIn = new PipedInputStream(1 << 16);
            PipedOutputStream toServer = new PipedOutputStream(serverIn);
            LineQueue protocol = new LineQueue();
            Launcher main = main(serverIn, protocol);

            CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(
                () -> main.run("--config", file.toString()));
            send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
            JsonNode initialize = protocol.next();
            toServer.close();

            assertThat(exit.get(20, TimeUnit.SECONDS)).as(err()).isZero();
            assertThat(initialize.path("result").path("serverInfo").path("name").asString())
                .isEqualTo("inubit-mcp-server");
            assertThat(protocol.raw()).as("only JSON-RPC lines on the protocol stdout")
                .allSatisfy(line -> assertThat(JSON.readTree(line).path("jsonrpc").asString())
                    .isEqualTo("2.0"));
            assertThat(protocol.rawText()).doesNotContain("pinPerServer");
            return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        } finally {
            launcher.detachAppender(logs);
            logs.stop();
        }
    }

    private static void send(PipedOutputStream toServer, String line) throws IOException {
        toServer.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        toServer.flush();
    }

    /** The protocol stdout of the server: complete lines plus the verbatim bytes. */
    private static final class LineQueue extends OutputStream {

        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final java.util.List<String> all = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final ByteArrayOutputStream current = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            bytes.write(b);
            if (b == '\n') {
                String line = current.toString(StandardCharsets.UTF_8);
                current.reset();
                all.add(line);
                lines.add(line);
            } else {
                current.write(b);
            }
        }

        JsonNode next() throws InterruptedException {
            String line = lines.poll(10, TimeUnit.SECONDS);
            assertThat(line).as("a response line within 10 s").isNotNull();
            return JSON.readTree(line);
        }

        /** Complete lines plus a final line without newline, if any. */
        synchronized java.util.List<String> raw() {
            java.util.List<String> result = new java.util.ArrayList<>(all);
            if (current.size() > 0) {
                result.add(current.toString(StandardCharsets.UTF_8));
            }
            return result;
        }

        synchronized String rawText() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
