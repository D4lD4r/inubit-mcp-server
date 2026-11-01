package de.dadecker.inubit.mcp.mcp.tools;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.toolError;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ConfigValidator;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.LoadedConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * T070: {@code find_processes} over MCP with the real adapters against WireMock
 * (contracts/mcp-tools.md §3, quickstart V4). Feature 009 (T012): the version warning of a node
 * is in its entry.
 *
 * <p>Feature 011 (T033, US2): {@code find_processes} on an INUBIT 6.1 node through the production
 * wiring, see {@link #startWith61}.
 */
@Timeout(60)
class FindProcessesToolTest {

    private static final String DESCRIPTION = "[acme] Find process instances on INUBIT nodes, e.g."
        + " failed (ERROR) or hanging ones, filtered by workflow, state, and time. Use the"
        + " returned processId and time range with `query_logs` to find the cause.";
    private static final String QUEUE_LOG = "/ibis/rest/log/queueLog";
    private static WireMockServer dev;
    private static WireMockServer integration;
    /** The baseUrl of the 6.1 node: plain HTTP, without stubs, must never be asked. */
    private static WireMockServer legacy;

    private DiagnosisToolFixture fixture;
    /** Feature 011: the production wiring of {@link #startWith61} and its client, if started. */
    private TestWiring wiring;
    private McpTestClient client61;

    @TempDir
    Path temp;

    @BeforeAll
    static void start() {
        dev = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        integration = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        legacy = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        legacy.start();
    }

    @AfterAll
    static void stop() {
        dev.stop();
        integration.stop();
        legacy.stop();
    }

    @BeforeEach
    void setUp() {
        dev.resetAll();
        integration.resetAll();
        legacy.resetAll();
        for (WireMockServer server : List.of(dev, integration)) {
            server.stubFor(post(urlPathEqualTo(QUEUE_LOG))
                .withRequestBody(containing("<value>Error</value>"))
                .willReturn(RestFixtures.response("log_queueLog_error", "json")));
            server.stubFor(post(urlPathEqualTo(QUEUE_LOG))
                .willReturn(RestFixtures.response("log_queueLog", "json")).atPriority(10));
        }
        fixture = new DiagnosisToolFixture(dev, integration, FindProcessesTool::new);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
        if (wiring != null) {
            client61.close();
            wiring.close();
        }
    }

    private JsonNode call(Map<String, Object> arguments) {
        return fixture.client.callTool("find_processes", arguments);
    }

    @Test
    void theToolIsListedReadOnlyIdempotentAndOpenWorldWithTheContractSchema() {
        JsonNode tool = fixture.client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("find_processes");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("readOnlyHint").asBoolean()).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean()).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean()).isTrue();
        JsonNode input = tool.path("inputSchema");
        assertThat(input.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(input.path("required")).extracting(JsonNode::asString)
            .containsExactly("target");
        assertThat(input.path("properties").propertyNames()).containsExactlyInAnyOrder(
            "target", "states", "hangingOnly", "hangingThresholdMinutes", "workflow", "tag",
            "since", "until", "offset", "limit");
        assertThat(input.path("properties").path("limit").path("minimum").asInt()).isEqualTo(1);
        assertThat(input.path("properties").path("limit").path("maximum").asInt())
            .isEqualTo(100);
        assertThat(input.path("properties").path("offset").path("maximum").asInt())
            .isEqualTo(9_999);
        assertThat(input.path("properties").path("states").path("items").path("enum"))
            .extracting(JsonNode::asString)
            .containsExactly("ERROR", "ACTIVE", "WAITING", "QUEUED");
        assertThat(tool.path("outputSchema").path("properties").has("results")).isTrue();
    }

    @Test
    void failedProcessesOfTheLastDayAreListedWithIdWorkflowModuleAndSince() {
        // quickstart V4
        JsonNode result = call(Map.of("target", "dev/node1", "states", List.of("ERROR"),
            "since", "PT24H", "limit", 5));

        assertMatchesOutputSchema("find_processes", result);
        JsonNode results = result.path("structuredContent").path("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).path("node").asString()).isEqualTo("dev/node1");
        assertThat(results.get(0).has("warnings")).as("INUBIT 8.1: no warnings").isFalse();
        JsonNode page = results.get(0).path("page");
        assertThat(page.path("total").asLong()).isEqualTo(706);
        assertThat(page.path("nextOffset").asInt()).isEqualTo(5);
        assertThat(page.path("truncated").asBoolean(true)).isFalse();
        JsonNode first = page.path("items").get(0);
        assertThat(first.path("processId").asString()).isEqualTo("110219899");
        assertThat(first.path("state").asString()).isEqualTo("ERROR");
        assertThat(first.path("rawState").asString()).isEqualTo("Error");
        assertThat(first.path("workflow").asString()).isEqualTo("Workflow-0424");
        assertThat(first.path("module").asString()).isEqualTo("Module-0036(23379903)");
        assertThat(Instant.parse(first.path("since").asString()))
            .isEqualTo(Instant.ofEpochMilli(1_790_861_373_734L));
        assertThat(Duration.parse(first.path("timeInState").asString())).isPositive();
        assertThat(first.path("hanging").asBoolean(true)).isFalse();
        assertThat(dev.findAll(postRequestedFor(urlPathEqualTo(QUEUE_LOG)))).singleElement()
            .satisfies(request -> assertThat(request.getBodyAsString()).contains(
                "<field>status</field>", "<value>Error</value>", "<comparison>GREATER",
                "<noOfItems>5</noOfItems>"));
    }

    @Test
    void aStageListsEachServerAndAnUnreachableServerOnlyGetsItsError() {
        JsonNode result = call(Map.of("target", "qa", "limit", 2));

        assertMatchesOutputSchema("find_processes", result);
        JsonNode results = result.path("structuredContent").path("results");
        assertThat(results).extracting(entry -> entry.path("node").asString())
            .containsExactly("qa/node1", "qa/node2");
        assertThat(results.get(0).path("page").path("items")).hasSize(2)
            .allSatisfy(item -> assertThat(item.path("node").asString())
                .isEqualTo("qa/node1"));
        assertThat(results.get(0).has("error")).isFalse();
        assertThat(results.get(1).has("page")).isFalse();
        assertThat(results.get(1).path("error").path("code").asString())
            .isEqualTo("UNREACHABLE");
        assertThat(results.get(1).path("error").path("node").asString())
            .isEqualTo("qa/node2");
    }

    @Test
    void onInubit90TheEntryOfTheNodeCarriesTheUnverifiedWarning() throws IOException {
        fixture.close();
        fixture = new DiagnosisToolFixture(dev, integration, FindProcessesTool::new,
            VersionSetting.AUTO);
        try (InputStream body = getClass().getResourceAsStream(
            "/fixtures/v9_0/rest/system_info.xml")) {
            dev.stubFor(get(urlPathEqualTo("/ibis/rest/system/info")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/xml;charset=UTF-8")
                .withBody(body.readAllBytes())));
        }

        JsonNode result = call(Map.of("target", "dev/node1", "limit", 2));

        assertMatchesOutputSchema("find_processes", result);
        JsonNode entry = result.path("structuredContent").path("results").get(0);
        assertThat(entry.path("warnings")).extracting(JsonNode::asString).containsExactly(
            "INUBIT 9.0.7 on dev/node1 is not verified for process diagnosis; results may"
                + " be incomplete");
        assertThat(entry.path("page").path("items")).hasSize(2);
        assertThat(dev.findAll(getRequestedFor(urlPathEqualTo("/ibis/rest/system/info"))))
            .as("one detection request").hasSize(1);
    }

    @Test
    void errorWithHangingOnlyIsAnEmptyPageWithoutRequest() {
        JsonNode result = call(Map.of("target", "dev/node1", "states", List.of("ERROR"),
            "hangingOnly", true));

        assertMatchesOutputSchema("find_processes", result);
        JsonNode page = result.path("structuredContent").path("results").get(0).path("page");
        assertThat(page.path("items")).isEmpty();
        assertThat(page.path("total").asLong(-1)).isZero();
        assertThat(dev.getAllServeEvents()).isEmpty();
    }

    @Test
    void schemaViolationsAreRejectedBeforeTheToolRuns() {
        List<Map<String, Object>> invalid = List.of(
            Map.of("target", "dev", "limit", 101),
            Map.of("target", "dev", "limit", 0),
            Map.of("target", "dev", "states", List.of("RUNNING")),
            Map.of("target", "dev", "verbose", true),
            Map.of("target", "DEV/Inubit 1"),
            Map.of("target", "dev", "hangingThresholdMinutes", 0),
            Map.of("target", "dev", "offset", 4_294_967_296L),
            Map.of("target", "dev", "offset", 10_000),
            Map.of("states", List.of("ERROR")));

        for (Map<String, Object> arguments : invalid) {
            JsonNode result = call(arguments);
            assertThat(result.path("isError").asBoolean()).as(arguments.toString()).isTrue();
            assertThat(result.has("structuredContent")).isFalse();
            assertThat(result.path("content").get(0).path("text").asString())
                .doesNotContain("\"code\"");
        }
        assertThat(dev.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void semanticallyInvalidInputIsAnInvalidInputToolError() {
        JsonNode badTime = toolError(call(Map.of("target", "dev", "since", "yesterday")));
        JsonNode tooDeep = toolError(call(Map.of("target", "dev", "offset", 9_990,
            "limit", 20)));
        JsonNode unknown = toolError(call(Map.of("target", "staging")));
        JsonNode control = toolError(call(Map.of("target", "dev", "workflow", "W\u0001")));

        assertThat(badTime.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(badTime.path("nextStep").asString()).contains("PT24H");
        assertThat(tooDeep.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(unknown.path("code").asString()).isEqualTo("TARGET_UNKNOWN");
        assertThat(control.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(control.has("node")).as("review D8").isFalse();
        assertThat(dev.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void noCredentialReachesTheResult() {
        JsonNode result = call(Map.of("target", "qa"));

        assertThat(result.toString()).doesNotContain(DiagnosisToolFixture.PASSWORD,
            "Authorization", "jdoe");
    }

    // --- feature 011, T033: find_processes on a 6.1 node end to end (US2) -------------------

    private static final String LEGACY_81 = "legacy/node1";
    private static final String LEGACY_61 = "legacy/node2";
    private static final String PASSWORD_61 = "find-processes-61-pw";
    private static final String LOG_NOTE = "logs of INUBIT 6.1 cannot be read with query_logs;"
        + " read them in the Workbench";
    /** 8.1 as in production, 6.1 CLI-only with process diagnosis supported (as after T040). */
    private static final CapabilityMatrix WITH_61 = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(6, 1, 6, 1),
            Map.of(Capability.PROCESS_DIAGNOSIS, SupportState.SUPPORTED),
            Map.of(Capability.PROCESS_DIAGNOSIS, Set.of(Channel.CLI)), true),
        new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
            Arrays.stream(Capability.values()).collect(Collectors.toMap(
                capability -> capability, capability -> SupportState.SUPPORTED)))),
        Map.of());

    private final FakeProcessLauncher help = FakeProcessLauncher.replaying("v6_1", "help");
    private final FakeProcessLauncher clientVersion = FakeProcessLauncher.of("CLI 6.1.0\n"
        + "Supported inubit Process Engine versions from 4.0.1 to 6.1.0.\n", "", 0);
    /** Every StartCLI session of the 6.1 node. */
    private FakeProcessLauncher session;

    /**
     * Replaces the server of {@link #setUp} by the production wiring with {@link #WITH_61}: the
     * group {@code legacy} has the 8.1 node {@code legacy/node1} on the {@code dev} WireMock and
     * the 6.1 node {@code legacy/node2} on {@link #legacy}, read through a fake StartCLI whose
     * sessions are answered by {@code sessions}; {@code -h} replays the reconstructed help,
     * {@code -v} answers as a 6.1.0 client. The credentials come from the environment, so they
     * are held from the start.
     */
    private void startWith61(FakeProcessLauncher sessions) throws IOException {
        session = sessions;
        Path cliHome = Files.createDirectories(temp.resolve("cli/bin")).getParent();
        Files.writeString(cliHome.resolve("bin/startcli.sh"), "#!/bin/sh\n");
        Path javaHome = Files.createDirectories(temp.resolve("java"));
        Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"1.8.0_292\"\n");
        String yaml = """
            profile:
              name: acme
            groups:
              - name: legacy
                nodes:
                  - name: node1
                    versionLine: V8_1
                    baseUrl: https://localhost:%1$d
                    tls:
                      trustStore: "%2$s"
                  - name: node2
                    versionLine: V6_1
                    baseUrl: http://localhost:%3$d
                    allowInsecureHttp: true
                    cli:
                      home: "%4$s"
                      javaHome: "%5$s"
            """.formatted(dev.httpsPort(), TestCertificates.get().trustStore(), legacy.port(),
            cliHome, javaHome);
        Map<String, String> environment = Map.of("INUBIT_ACME_LEGACY_USERNAME", "jdoe",
            "INUBIT_ACME_LEGACY_PASSWORD", PASSWORD_61);
        LoadedConfig loaded = new ConfigLoader(environment, temp, false)
            .parse(yaml, temp.resolve("config.yaml"));
        SecretScrubber scrubber = new SecretScrubber();
        CredentialResolution credentials = new CredentialResolver(environment, scrubber,
            loaded.config().credentialPrefix()).resolve(loaded.config().nodeIds());
        assertThat(ConfigValidator.forSystem().validate(loaded, credentials).errors())
            .isEmpty();
        ProcessLauncher launcher = spec -> switch (spec.command().size() == 2
            ? spec.command().get(1) : "") {
            case "-h" -> help.launch(spec);
            case "-v" -> clientVersion.launch(spec);
            default -> session.launch(spec);
        };
        wiring = TestWiring.of(loaded.config(), credentials, scrubber, Files::exists, false,
            launcher, Map.of("PATH", "/usr/bin"), WITH_61);
        client61 = McpTestClient.start(wiring.toolHandlers(), scrubber);
        client61.initialize();
    }

    /** A 6.1 session whose Error listing has {@code rows} rows (header and total as in T029). */
    private static String errorSession(int rows) {
        StringBuilder lines = new StringBuilder("Password: \ncli> NODE                 UID"
            + "             PID        PRIO       STATE      DATE                 WORKFLOW"
            + "             MODULE");
        for (int i = 0; i < rows; i++) {
            lines.append('\n').append(("node1                jdoe            %d     normal     "
                + "Error      2026-10-%02dT08:15:42  OrderImport          Mapping(10234)")
                .formatted(400_000 + i, 1 + i % 30));
        }
        return lines.append("\nTotal: ").append(rows).append("\ncli> \n").toString();
    }

    @Test
    void theFailedProcessesOfA61NodeAreListedFromOneSessionWithinFifteenSeconds()
        throws IOException {
        // SC-002: up to 100 instances within 15 s; US2-1 with the 6.1 note of FR-010
        startWith61(FakeProcessLauncher.of(errorSession(100), "", 0));

        long start = System.nanoTime();
        JsonNode result = client61.callTool("find_processes", Map.of("target", LEGACY_61,
            "states", List.of("ERROR"), "limit", 100));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(15));
        assertMatchesOutputSchema("find_processes", result);
        JsonNode entry = result.path("structuredContent").path("results").get(0);
        assertThat(entry.path("node").asString()).isEqualTo(LEGACY_61);
        assertThat(entry.path("warnings")).extracting(JsonNode::asString)
            .containsExactly(LOG_NOTE);
        JsonNode page = entry.path("page");
        assertThat(page.path("items")).hasSize(100);
        assertThat(page.path("total").asLong()).isEqualTo(100);
        assertThat(page.path("items").get(0).path("state").asString()).isEqualTo("ERROR");
        assertThat(page.path("items").get(0).has("tag")).isFalse();
        assertThat(session.launchCount()).as("one StartCLI session").isEqualTo(1);
        assertThat(legacy.getAllServeEvents()).as("SC-004: no REST request to a 6.1 node")
            .isEmpty();
        assertThat(result.toString()).doesNotContain(PASSWORD_61);
    }

    @Test
    void tagInAMixedGroupRefusesThe61NodeWithoutAnyLaunchAndListsThe81Node()
        throws IOException {
        // FR-007, FR-010: the held-credentials 6.1 node is refused per node before any contact
        startWith61(FakeProcessLauncher.of(errorSession(1), "", 0));

        JsonNode result = client61.callTool("find_processes", Map.of("target", "legacy",
            "tag", "release-1", "limit", 2));

        assertMatchesOutputSchema("find_processes", result);
        JsonNode results = result.path("structuredContent").path("results");
        assertThat(results).extracting(entry -> entry.path("node").asString())
            .containsExactly(LEGACY_81, LEGACY_61);
        assertThat(results.get(0).has("error")).as(results.get(0).toString()).isFalse();
        assertThat(results.get(0).path("page").path("items")).hasSize(2);
        assertThat(dev.findAll(postRequestedFor(urlPathEqualTo(QUEUE_LOG)))).singleElement()
            .satisfies(request -> assertThat(request.getBodyAsString()).contains(
                "<field>tag</field>", "release-1"));
        JsonNode refused = results.get(1);
        assertThat(refused.has("page")).isFalse();
        assertThat(refused.path("error").path("code").asString())
            .isEqualTo("UNSUPPORTED_VERSION");
        assertThat(refused.path("error").path("node").asString()).isEqualTo(LEGACY_61);
        assertThat(refused.path("error").path("message").asString()).isEqualTo(
            "find_processes: INUBIT 6.1 on legacy/node2 does not support the input tag;"
                + " nothing was sent");
        assertThat(session.launchCount()).as("no StartCLI session").isZero();
        assertThat(help.launchCount()).as("no option probe").isZero();
        assertThat(clientVersion.launchCount()).as("no client probe").isZero();
        assertThat(legacy.getAllServeEvents()).as("WireMock: 0 requests for the 6.1 node")
            .isEmpty();
    }
}
