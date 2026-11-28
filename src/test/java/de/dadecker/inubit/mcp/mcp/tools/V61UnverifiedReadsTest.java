package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.LoadedConfig;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.TestMatrices;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import de.dadecker.inubit.mcp.testing.TestValidators;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Feature 025, T043 (spec FR-036, US9): {@code get_health} and {@code find_processes} on INUBIT
 * 6.1 through the production wiring with the T038 test matrix whose 6.1 entry declares monitoring
 * and process diagnosis {@code UNVERIFIED} ({@link TestMatrices#unverified61()}). The group
 * {@code legacy} has {@code legacy/node1} pinned to {@code V6_1} and {@code legacy/node2} pinned to
 * {@code "6.1.0.89"}; both use the version-less {@code defaults.cliHome} and a fake StartCLI that
 * replays the reconstructed {@code fixtures/v6_1/cli/} cases (the existing reads of feature 012).
 * Every entry after the gate, data or an error after contact, carries the unverified warning;
 * every {@code find_processes} entry also the version note. Their baseUrl is a plain-HTTP WireMock
 * without stubs that must never be asked. The other 6.1 capabilities keep their refusal of feature
 * 012 ({@code VersionGateTest}).
 */
@Timeout(60)
class V61UnverifiedReadsTest {

    private static final String NODE1 = "legacy/node1";
    private static final String NODE2 = "legacy/node2";
    private static final String PASSWORD = "unverified-61-pw";
    private static final String LOG_NOTE = "logs of INUBIT 6.1 cannot be read with query_logs;"
        + " read them in the Workbench";
    /** A session that StartCLI answers with something neither 6.1 parser recognizes. */
    private static final String UNRECOGNIZED = "Password: \ncli> Unknown command\ncli> \n";

    private static WireMockServer legacy;

    @TempDir
    Path temp;

    private final FakeProcessLauncher help = FakeProcessLauncher.replaying("v6_1", "help");
    private final FakeProcessLauncher clientVersion = FakeProcessLauncher.of("CLI 6.1.0\n"
        + "Supported inubit Process Engine versions from 4.0.1 to 6.1.0.\n", "", 0);
    /** Every StartCLI launch: option and client probes and sessions. */
    private final List<ProcessLauncher.LaunchSpec> launches = new CopyOnWriteArrayList<>();
    /** Every StartCLI session of the two nodes. */
    private FakeProcessLauncher session;
    private Path cliHome;
    private TestWiring wiring;
    private McpTestClient client;

    @BeforeAll
    static void startWireMock() {
        legacy = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        legacy.start();
    }

    @AfterAll
    static void stopWireMock() {
        legacy.stop();
    }

    @BeforeEach
    void resetWireMock() {
        legacy.resetAll();
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (wiring != null) {
            wiring.close();
        }
    }

    /**
     * Starts the production wiring with the T038 6.1 test matrix: StartCLI and its Java only in
     * {@code defaults}, {@code -h} replays the reconstructed help, {@code -v} answers as a 6.1.0
     * client, every other launch is a session answered by {@code sessions}.
     */
    private void start(FakeProcessLauncher sessions, String cliTimeout) throws IOException {
        session = sessions;
        cliHome = Files.createDirectories(temp.resolve("cli/bin")).getParent();
        Files.writeString(cliHome.resolve("bin/startcli.sh"), "#!/bin/sh\n");
        Path javaHome = Files.createDirectories(temp.resolve("java"));
        Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"1.8.0_292\"\n");
        String yaml = """
            profile:
              name: acme
            defaults:
              cliHome: '%1$s'
              cliJavaHome: '%2$s'
            groups:
              - name: legacy
                nodes:
                  - name: node1
                    versionLine: V6_1
                    baseUrl: http://localhost:%3$d
                    allowInsecureHttp: true
                    cliTimeout: %4$s
                  - name: node2
                    versionLine: "6.1.0.89"
                    baseUrl: http://localhost:%3$d
                    allowInsecureHttp: true
                    cliTimeout: %4$s
            """.formatted(cliHome, javaHome, legacy.port(), cliTimeout);
        Map<String, String> environment = Map.of("INUBIT_ACME_LEGACY_USERNAME", "jdoe",
            "INUBIT_ACME_LEGACY_PASSWORD", PASSWORD);
        LoadedConfig loaded = new ConfigLoader(environment, temp, false)
            .parse(yaml, temp.resolve("config.yaml"));
        SecretScrubber scrubber = new SecretScrubber();
        assertThat(TestValidators.ofSystem(false).validate(loaded).errors()).isEmpty();
        ProcessLauncher launcher = spec -> {
            launches.add(spec);
            return switch (spec.startCliArguments().size() == 1
                ? spec.startCliArguments().get(0) : "") {
                case "-h" -> help.launch(spec);
                case "-v" -> clientVersion.launch(spec);
                default -> session.launch(spec);
            };
        };
        wiring = TestWiring.of(loaded.config(), TestSecrets.store(environment), scrubber,
            Files::exists, false, launcher, Map.of("PATH", "/usr/bin"),
            TestMatrices.unverified61());
        client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        client.initialize();
    }

    private void start(FakeProcessLauncher sessions) throws IOException {
        start(sessions, "PT10S");
    }

    private static String unverified(String name, String node, String capability) {
        return "INUBIT " + name + " on " + node + " is not verified for " + capability
            + "; results may be incomplete";
    }

    /** The unverified warning of each node's get_health entry. */
    private static final Map<String, String> MONITORING = Map.of(
        NODE1, unverified("6.1", NODE1, "monitoring"),
        NODE2, unverified("6.1.0.89", NODE2, "monitoring"));

    /** The admission's warning and version note of each node's find_processes entry. */
    private static final Map<String, List<String>> DIAGNOSIS = Map.of(
        NODE1, List.of(unverified("6.1", NODE1, "process diagnosis"), LOG_NOTE),
        NODE2, List.of(unverified("6.1.0.89", NODE2, "process diagnosis"), LOG_NOTE));

    private static String clientsVersion(String node) {
        return "the version 6.1.0.89 read on " + node + " is the StartCLI client's; the server"
            + " version cannot be read";
    }

    /** The entries of a fan-out result ({@code reports} or {@code results}) in node order. */
    private static List<JsonNode> entries(JsonNode result, String field) {
        List<JsonNode> entries = result.path("structuredContent").path(field).valueStream()
            .toList();
        assertThat(entries).extracting(entry -> entry.path("node").asString())
            .containsExactly(NODE1, NODE2);
        return entries;
    }

    /** No REST request to a 6.1 node; every StartCLI launch used the defaults.cliHome. */
    private void assertOnlyStartCliOfTheDefaultsCliHome() {
        assertThat(legacy.getAllServeEvents()).as("no REST request to a 6.1 node").isEmpty();
        assertThat(launches).isNotEmpty().allSatisfy(spec ->
            assertThat(spec.command()).as(spec.toString())
                .contains(cliHome.resolve("bin/startcli.sh").toString()));
    }

    // --- get_health (US9-1, US9-3, US9-5) ----------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"health_session_ok", "health_session_de"})
    void getHealthReadsBoth61NodesThroughStartCliWithTheUnverifiedWarning(String fixture)
        throws IOException {
        start(FakeProcessLauncher.replaying("v6_1", fixture));

        JsonNode result = client.callTool("get_health", Map.of("target", "legacy"));

        assertMatchesOutputSchema("get_health", result);
        for (JsonNode report : entries(result, "reports")) {
            String node = report.path("node").asString();
            assertThat(report.has("error")).as(report.toString()).isFalse();
            assertThat(report.path("reachable").asBoolean()).isTrue();
            assertThat(report.path("status").asString()).isEqualTo("OK");
            assertThat(report.has("version")).as("the client's version is not reported")
                .isFalse();
            assertThat(report.path("processCounts").path("error").asLong()).isEqualTo(3);
            assertThat(report.path("processCounts").path("waiting").asLong()).isEqualTo(12);
            assertThat(report.path("load").path("maxMemoryMb").asDouble()).isEqualTo(2048);
            assertThat(report.path("unavailable")).extracting(part -> part.path("reason")
                .asString()).allSatisfy(reason ->
                    assertThat(reason).startsWith("NOT_AVAILABLE_ON_6_1: INUBIT 6.1 "));
            assertThat(report.path("warnings")).extracting(JsonNode::asString)
                .containsExactly(MONITORING.get(node), clientsVersion(node));
        }
        assertThat(session.launchCount()).as("one session per node").isEqualTo(2);
        assertOnlyStartCliOfTheDefaultsCliHome();
        assertThat(result.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void getHealthOfACrlfSessionIsReadAsTheLfSession() throws IOException {
        start(FakeProcessLauncher.of(FakeProcessLauncher.fixtureText("v6_1",
            "health_session_ok.stdout").replace("\n", "\r\n"), "", 0));

        JsonNode result = client.callTool("get_health", Map.of("target", "legacy"));

        assertMatchesOutputSchema("get_health", result);
        for (JsonNode report : entries(result, "reports")) {
            String node = report.path("node").asString();
            assertThat(report.path("status").asString()).as(report.toString()).isEqualTo("OK");
            assertThat(report.path("processCounts").path("error").asLong()).isEqualTo(3);
            assertThat(report.path("warnings")).extracting(JsonNode::asString)
                .containsExactly(MONITORING.get(node), clientsVersion(node));
        }
    }

    /** A session answer, the node's cliTimeout and the error code each entry reports. */
    static Stream<Arguments> errorsAfterContact() {
        return Stream.of(
            Arguments.of("login_failed",
                (Supplier<FakeProcessLauncher>) () -> FakeProcessLauncher.replaying("v6_1",
                    "login_failed"), "PT10S", "AUTH_FAILED"),
            Arguments.of("unreachable",
                (Supplier<FakeProcessLauncher>) () -> FakeProcessLauncher.replaying("v6_1",
                    "unreachable"), "PT10S", "UNREACHABLE"),
            Arguments.of("unrecognized output",
                (Supplier<FakeProcessLauncher>) () -> FakeProcessLauncher.of(UNRECOGNIZED, "", 0),
                "PT10S", "UNEXPECTED_RESPONSE"),
            Arguments.of("hanging session",
                (Supplier<FakeProcessLauncher>) () -> FakeProcessLauncher.of("", "", 0).hanging(),
                "PT1S", "TIMEOUT"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errorsAfterContact")
    void getHealthReportsTheErrorOfFeature012AfterContactWithTheWarning(String name,
        Supplier<FakeProcessLauncher> sessions, String cliTimeout, String code)
        throws IOException {
        start(sessions.get(), cliTimeout);

        JsonNode result = client.callTool("get_health", Map.of("target", "legacy"));

        assertMatchesOutputSchema("get_health", result);
        for (JsonNode report : entries(result, "reports")) {
            String node = report.path("node").asString();
            assertThat(report.path("reachable").asBoolean()).isFalse();
            assertThat(report.path("status").asString()).isEqualTo("UNKNOWN");
            assertThat(report.path("error").path("code").asString()).as(report.toString())
                .isEqualTo(code);
            assertThat(report.path("error").path("node").asString()).isEqualTo(node);
            assertThat(report.path("warnings")).extracting(JsonNode::asString)
                .containsExactly(MONITORING.get(node));
        }
        assertOnlyStartCliOfTheDefaultsCliHome();
        assertThat(result.toString()).doesNotContain(PASSWORD);
    }

    // --- find_processes (US9-2, US9-3, US9-5) ------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"ps_session_all", "ps_session_de"})
    void findProcessesListsBoth61NodesWithTheWarningAndTheVersionNote(String fixture)
        throws IOException {
        start(FakeProcessLauncher.replaying("v6_1", fixture));

        JsonNode result = client.callTool("find_processes", Map.of("target", "legacy",
            "limit", 100));

        assertMatchesOutputSchema("find_processes", result);
        for (JsonNode entry : entries(result, "results")) {
            String node = entry.path("node").asString();
            assertThat(entry.has("error")).as(entry.toString()).isFalse();
            JsonNode page = entry.path("page");
            assertThat(page.path("total").asLong()).isEqualTo(9);
            // newest first, as for every node
            assertThat(page.path("items")).extracting(item -> item.path("processId").asString())
                .containsExactly("400108", "400104", "400107", "400105", "400106", "400103",
                    "400101", "400109", "400102");
            assertThat(page.path("items")).extracting(item -> item.path("state").asString())
                .containsExactly("QUEUED", "ACTIVE", "WAITING", "WAITING", "ERROR", "ERROR",
                    "ERROR", "OTHER", "WAITING");
            assertThat(page.path("items").get(0).path("workflow").asString())
                .isEqualTo("OrderImport");
            assertThat(entry.path("warnings")).extracting(JsonNode::asString)
                .containsExactlyElementsOf(DIAGNOSIS.get(node));
        }
        assertThat(session.launchCount()).as("one session per node").isEqualTo(2);
        assertOnlyStartCliOfTheDefaultsCliHome();
        assertThat(result.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void findProcessesOfACrlfListingIsReadAsTheLfListing() throws IOException {
        start(FakeProcessLauncher.of(FakeProcessLauncher.fixtureText("v6_1",
            "ps_session_all.stdout").replace("\n", "\r\n"), "", 0));

        JsonNode result = client.callTool("find_processes", Map.of("target", "legacy"));

        assertMatchesOutputSchema("find_processes", result);
        for (JsonNode entry : entries(result, "results")) {
            assertThat(entry.path("page").path("total").asLong()).as(entry.toString())
                .isEqualTo(9);
            assertThat(entry.path("warnings")).extracting(JsonNode::asString)
                .containsExactlyElementsOf(DIAGNOSIS.get(entry.path("node").asString()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errorsAfterContact")
    void findProcessesReportsTheErrorOfFeature012AfterContactWithTheWarningAndTheNote(
        String name, Supplier<FakeProcessLauncher> sessions, String cliTimeout, String code)
        throws IOException {
        start(sessions.get(), cliTimeout);

        JsonNode result = client.callTool("find_processes", Map.of("target", "legacy"));

        assertMatchesOutputSchema("find_processes", result);
        for (JsonNode entry : entries(result, "results")) {
            String node = entry.path("node").asString();
            assertThat(entry.has("page")).isFalse();
            assertThat(entry.path("error").path("code").asString()).as(entry.toString())
                .isEqualTo(code);
            assertThat(entry.path("error").path("node").asString()).isEqualTo(node);
            assertThat(entry.path("warnings")).extracting(JsonNode::asString)
                .containsExactlyElementsOf(DIAGNOSIS.get(node));
        }
        assertOnlyStartCliOfTheDefaultsCliHome();
        assertThat(result.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void tagIsRefusedOnBoth61NodesAsTodayWithoutAWarningOrALaunch() throws IOException {
        start(FakeProcessLauncher.replaying("v6_1", "ps_session_all"));

        JsonNode result = client.callTool("find_processes", Map.of("target", "legacy",
            "tag", "release-1"));

        assertMatchesOutputSchema("find_processes", result);
        for (JsonNode entry : entries(result, "results")) {
            assertThat(entry.path("error").path("code").asString())
                .isEqualTo("UNSUPPORTED_VERSION");
            assertThat(entry.path("error").path("message").asString()).isEqualTo(
                "find_processes: INUBIT 6.1 does not support the input tag; nothing was sent");
            assertThat(entry.has("warnings")).as(entry.toString()).isFalse();
        }
        assertThat(launches).as("no StartCLI launch").isEmpty();
        assertThat(legacy.getAllServeEvents()).isEmpty();
    }
}
