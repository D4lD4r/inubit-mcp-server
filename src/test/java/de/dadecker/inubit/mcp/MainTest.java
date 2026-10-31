package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.ParameterType;
import de.dadecker.inubit.mcp.config.StoreLocation;
import de.dadecker.inubit.mcp.infra.BuildInfo;
import de.dadecker.inubit.mcp.infra.LogLevels;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import de.dadecker.inubit.mcp.testing.FakeParameterStore;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
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
    /** The Parameter Store of AWS nodes; no test reaches AWS. */
    private final FakeParameterStore store = new FakeParameterStore();
    /** How often the launcher asked for {@link #store}. */
    private final AtomicInteger storesCreated = new AtomicInteger();

    @AfterEach
    void restoreTestLogLevel() {
        LogLevels.apply("WARN"); // server mode applies the configured logLevel (INFO)
    }

    private Launcher main(InputStream in, OutputStream out) {
        return main(in, out, Wiring::toolHandlers);
    }

    private Launcher main(InputStream in, OutputStream out,
        Function<Wiring, List<ToolHandler>> handlers) {
        return new Launcher(new Launcher.Context(Map.copyOf(environment), home, false, in,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8), new SecretScrubber(),
            Files::exists, () -> {
                storesCreated.incrementAndGet();
                return store;
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
        environment.put("INUBIT_ACME_DEV_USERNAME", USERNAME);
        environment.put("INUBIT_ACME_DEV_PASSWORD", PASSWORD);
    }

    @Test
    void versionPrintsTheProjectVersionAndExitsZero() {
        int exit = run("--version");

        assertThat(exit).isZero();
        assertThat(out()).matches("inubit-mcp-server \\d+\\.\\d+\\.\\d+(-SNAPSHOT)?\\R");
        assertThat(out()).contains(BuildInfo.version());
        assertThat(BuildInfo.version()).doesNotContain("${");
    }

    @Test
    void checkConfigPrintsServersFlagsAndVariableNamesButNoValues() throws IOException {
        credentials();
        environment.put("INUBIT_ACME_QA_NODE2_PASSWORD", "other-Secret-99");
        environment.put("INUBIT_ACME_QA_USERNAME", "other-user");
        Path cliHome = fakeStartCli("""
            echo "$@" > "$(dirname "$0")/arguments"
            echo "CLI 8.1.17"
            echo "Supported inubit Process Engine versions from 4.0.1 to 8.1.17."
            """);
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
            .contains("dev/node1: write enabled (confirmation SERVER), cli: unavailable,"
                + " inubit: AUTO, username ← INUBIT_ACME_DEV_USERNAME, password ← INUBIT_ACME_DEV_PASSWORD")
            .contains("qa/node2: read-only, cli: available, inubit: AUTO,"
                + " client: 8.1.17 (4.0.1–8.1.17), server version: determined at runtime,"
                + " username ← INUBIT_ACME_QA_USERNAME,"
                + " password ← INUBIT_ACME_QA_NODE2_PASSWORD")
            .contains("Result: OK")
            .doesNotContain(PASSWORD, USERNAME, "other-Secret-99", "other-user",
                "inubit.example.test");
        assertThat(err()).doesNotContain(PASSWORD, "other-Secret-99");
        // feature 009 (FR-011a): the local client probe only, without credentials or server URL
        assertThat(Files.readString(cliHome.resolve("bin/arguments"))).isEqualTo("-v\n");
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
    void checkConfigOutputOfAnExistingProfileIsUnchanged() throws IOException {
        credentials();
        environment.put("INUBIT_ACME_QA_NODE2_USERNAME", "qa-user");
        environment.put("INUBIT_ACME_DEV_NODE9_PASSWORD", "typo-Secret-1");
        Path file = profileWithCredentials("");

        int exit = run("--check-config", "--config", file.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(out()).isEqualTo("""
            Configuration: %s
            Profile: acme
            Terminology: group/groups, node/nodes
            Credential variables: INUBIT_ACME_<GROUP>[_<NODE>]_USERNAME / _PASSWORD
            Audit directory: %s
            Workspace: %s (ok)
            Group dev:
              Node dev/node1: read-only, cli: unavailable, inubit: V8_1, \
            username ← INUBIT_ACME_DEV_USERNAME, password ← INUBIT_ACME_DEV_PASSWORD
            Group qa:
              Node qa/node2: read-only, cli: unavailable, inubit: AUTO, \
            username ← INUBIT_ACME_QA_NODE2_USERNAME, password ← (missing)
            Warnings:
              - Environment variable INUBIT_ACME_DEV_NODE9_PASSWORD matches no configured group or \
            node (typo?)
            Errors:
              - qa/node2: no password variable set (expected INUBIT_ACME_QA_NODE2_PASSWORD or \
            INUBIT_ACME_QA_PASSWORD)
            Result: FAILED (1 error(s))
            """.formatted(file, home.resolve("audit"), home.resolve("workspace")));
        assertThat(storesCreated).as("T015: no Parameter Store without an AWS node").hasValue(0);
    }

    @Test
    void anExplicitEnvironmentSourceChecksAndStartsLikeAProfileWithoutSource()
        throws IOException {
        credentials();
        environment.put("INUBIT_ACME_QA_USERNAME", "qa-user");
        environment.put("INUBIT_ACME_QA_PASSWORD", "qa-Password-2");
        String explicit = """
            credentials:
              source:
                type: credentialStore
            """;
        String explicitWithPrefix = """
            credentials:
              envPrefix: INUBIT_ACME
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
            // T008 adds "secrets: credentialStore" to each node line of a profile that uses source
            outputs.add(out().replace("secrets: credentialStore, ", ""));
            stdout.reset();
            assertThat(run("--config", file.toString())).as(err()).isZero();
            startups.add(err());
        }

        assertThat(outputs).as("--check-config").containsOnly(outputs.get(0));
        assertThat(outputs.get(0)).contains("username ← INUBIT_ACME_QA_USERNAME");
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
        Path cliHome = fakeStartCli("""
            echo "Error: no JDK" >&2
            exit 1
            """);
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
                + " username ←")
            .contains("Warnings:", "  - dev/node1: StartCLI client probe failed: StartCLI did not"
                + " report its version (exit code 1)")
            .contains("Result: OK");
    }

    /** An executable {@code <home>/client/bin/startcli.sh} running {@code body}; the CLI home. */
    private Path fakeStartCli(String body) throws IOException {
        Path cliHome = Files.createDirectories(home.resolve("client/bin")).getParent();
        Path script = cliHome.resolve("bin/startcli.sh");
        Files.writeString(script, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return cliHome;
    }

    @Test
    void checkConfigExitsOneOnErrorsAndNamesTheExpectedVariables() throws IOException {
        environment.put("INUBIT_ACME_DEV_USERNAME", USERNAME);
        Path file = validConfig();

        int exit = run("--config", file.toString(), "--check-config");

        assertThat(exit).isEqualTo(1);
        assertThat(out()).contains("Result: FAILED")
            .contains("INUBIT_ACME_DEV_NODE1_PASSWORD", "INUBIT_ACME_DEV_PASSWORD")
            .doesNotContain(USERNAME);
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
                "credentials.envPrefix: INUBIT", "~/.inubit-mcp/audit",
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
            "set the value in the environment variable", "INUBIT_ACME_PROD_NODE1_USERNAME")
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
            "username ← INUBIT_ACME_DEV_USERNAME");
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
            "Warnings:", "credential variable prefix INUBIT_ACME")
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
        assertThat(err()).contains("Warnings:", "dev/node1: versionLine V9_X: write tools are"
            + " refused on this node (INUBIT 9.x is not supported for writes yet); read tools run"
            + " unverified");
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
