package de.dadecker.inubit.mcp.security;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher.Reply;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliOperationRunner;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliTagAdminRunner;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81SettingsAdapter;
import de.dadecker.inubit.mcp.application.ActionSpec;
import de.dadecker.inubit.mcp.application.ChangeAudit;
import de.dadecker.inubit.mcp.application.ChangeChallenges;
import de.dadecker.inubit.mcp.application.ChangeGuard;
import de.dadecker.inubit.mcp.application.ChangeLocks;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.ServerOperationService;
import de.dadecker.inubit.mcp.application.TagAdministrationService;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.WriteChallengeRegistry;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.HealthStatus;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LoadFigures;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.ServerOperationPort;
import de.dadecker.inubit.mcp.domain.port.ServerSettingsPort;
import de.dadecker.inubit.mcp.domain.port.TagAdministrationPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.tools.ManageTagsTool;
import de.dadecker.inubit.mcp.mcp.tools.OperateServerTool;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.RecordingAuditPort;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * T065 (feature 022, US4, SC-005, FR-022, Constitution II): {@code operate_server} and
 * {@code manage_tags} over MCP on the real {@link ServerOperationService} and
 * {@link TagAdministrationService}, their StartCLI forms on the real {@link CliOperationRunner}
 * and {@link CliTagAdminRunner} over a scripted StartCLI, and the midnight task's start time on
 * the real {@link V81SettingsAdapter} against WireMock. Secrets planted in INUBIT's answers never
 * reach a tool result, a tool error, a log line (our loggers at TRACE) or an audit record:
 *
 * <ul>
 *   <li>the node's configured password ({@link #PASSWORD}, registered with the global scrubber as
 *       the production wiring does), printed in unknown StartCLI answers and failed runs;
 *   <li>the free-text secrets of {@link TestSecrets#FREE_TEXT_SECRETS} in the same answers;
 *   <li>a user's e-mail address ({@link #EMAIL}) in the answer of {@code user <name>};
 *   <li>the planted configuration leaves of the recorded {@code admin-configurations}
 *       ({@link #REST_SECRETS}), and an error body of {@code GET /configurations} carrying them.
 * </ul>
 *
 * <p>The test ports wire the runners as {@code V81ServerOperationAdapter} and
 * {@code V81TagAdministrationAdapter} do; the readiness, the load figures, the trace state, the
 * processes in ERROR come from fixed answers without INUBIT text.
 * Every capability is supported, and the US4 rows are reachable only in this test's coverage
 * list. The known answers of the operation forms are the ones recorded on 8.1.17
 * ({@code admin-operate-*}, T054); the active tag is the RECONSTRUCTED
 * {@code admin-tags-user-active-tag} block (no active tag recorded), and the tag forms answer
 * like the recorded {@code tag_ok} (no result line).
 */
@Timeout(60)
class OperationSecretLeakTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String USER = "jdoe";
    private static final String CONFIGURATIONS = "/ibis/rest/configurations";
    private static final String PREAMBLE = "JAVA_HOME is set\nPassword: \n";
    private static final String PROMPT = "CLI|jdoe@localhost> ";
    private static final String REASON = "night window";

    /** The node's configured password (INUBIT login over REST and StartCLI). */
    private static final String PASSWORD = TestSecrets.CONFIGURED_PASSWORD;
    /** A user's e-mail address in an answer of {@code user <name>}. */
    private static final String EMAIL = "planted-mail-0065@example.test";
    /** The planted configuration leaves of the recorded {@code admin-configurations}. */
    private static final List<String> REST_SECRETS = List.of("synthetic-secret-0401",
        "synthetic-secret-0402", "synthetic-secret-0403");
    /** An answer no parser knows, with the StartCLI secrets. */
    private static final String PLANTED = TestSecrets.FREE_TEXT_SECRETS + "\nUnknown answer for "
        + PASSWORD + "\n";
    /** An answer of {@code user <name>} no parser knows, listing the user's e-mail address. */
    private static final List<String> PLANTED_USER = List.of("User name    jdoe",
        "E-mail address   " + EMAIL, TestSecrets.FREE_TEXT_SECRETS, "Unknown row " + PASSWORD);

    /** The US4 rows of interface-coverage.tsv, reachable on 8.1 only in this test. */
    private static final String COVERAGE = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        CLI\tmaintenance on|off\tWRITE\tA\tUS4\treachable:operate_server/setMaintenance\tplanned\t-
        CLI\tmaintenance -eq true|false\tWRITE\tA\tUS4\treachable:operate_server/setMaintenance\tplanned\t-
        REST\tPOST /ready/{status}\tWRITE\tA\tUS4\treachable:operate_server/setReadiness\tplanned\t-
        CLI\ttrace on|off\tWRITE\tA\tUS4\treachable:operate_server/setTrace\tplanned\t-
        CLI\ttrace -l\tWRITE\tA\tUS4\treachable:operate_server/setTraceLevel\tplanned\t-
        CLI\ttriggerMidnightTask\tWRITE\tA\tUS4\treachable:operate_server/triggerMidnightTask\tplanned\t-
        CLI\tprocessdelete error\tWRITE\tA\tUS4\treachable:operate_server/deleteErrorProcesses\tplanned\t-
        CLI\ttag --tagUser\tWRITE\tE\t-\treachable:tag_artifacts/default\treachable:tag_artifacts/default\t-
        CLI\ttag --tagSetActive\tWRITE\tE\tUS4\treachable:manage_tags/setActiveTag\tplanned\t-
        CLI\ttag --tagRemoveActive\tWRITE\tE\tUS4\treachable:manage_tags/removeActiveTag\tplanned\t-
        """;

    /** The answer of each script-mode form recorded on 8.1.17 (T054), after the preamble. */
    private static final Map<String, String> KNOWN = Map.of(
        "maintenance -s", "off",
        "maintenance on", "Updated Successfully",
        "trace on", "",
        "trace -l debug", "Successfully changed level to DEBUG",
        "triggerMidnightTask", "",
        "processdelete error", "Process with status [error] has been deleted.");
    /** The RECONSTRUCTED answer of {@code user jdoe} with an active tag. */
    private static final List<String> ACTIVE_TAG = List.of("User name    jdoe",
        "User group   OPS", "Super group  headoffice", "Active tag   REL-1");

    private static WireMockServer wireMock;

    @TempDir
    Path temp;

    private final SecretScrubber scrubber = SecretScrubber.global();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final RecordingAuditPort records = new RecordingAuditPort();
    private final List<InubitHttpClient> clients = new ArrayList<>();
    /** The answer of a script-mode run by its {@code --execCommand}. */
    private volatile Function<String, Reply> scriptAnswers = OperationSecretLeakTest::known;
    /** The answer block of {@code user <name>} in a session. */
    private volatile List<String> userBlock = ACTIVE_TAG;
    private Level previousLevel;
    private McpTestClient client;

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() throws IOException {
        wireMock.resetAll();
        FakeStartCli.install(temp.resolve("cli"));
        // as the production wiring registers each node's password with the global scrubber
        scrubber.register(PASSWORD);
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logs.setContext(context);
        logs.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logs);
        Logger ours = context.getLogger("de.dadecker");
        previousLevel = ours.getLevel();
        ours.setLevel(Level.TRACE);

        Clock clock = Clock.systemUTC();
        NodeVersions versions = new NodeVersions(clock, node -> VersionSetting.V8_1,
            node -> Duration.ofMinutes(10));
        Map<Capability, SupportState> supported = new LinkedHashMap<>();
        for (Capability capability : Capability.values()) {
            supported.put(capability, SupportState.SUPPORTED);
        }
        ChangeAudit audit = new ChangeAudit(records, clock, "acme", UUID::randomUUID);
        WritePolicy policy = new WritePolicy(DEV, false, true, false,
            WritePolicy.Confirmation.CLIENT, Duration.ofMinutes(5), Optional.of(USER));
        ChangeGuard guard = new ChangeGuard(new TargetResolver(List.of(DEV)),
            Map.of(DEV, policy)::get, (node, area) -> true, gateways,
            FakeVersionDetection.gateWithoutClients(versions, new FakeVersionDetection(versions),
                new CapabilityMatrix(List.of(), supported),
                InterfaceCoverage.load(new StringReader(COVERAGE))), audit);
        ChangeChallenges challenges = new ChangeChallenges(new WriteChallengeRegistry(clock));
        ServerOperationService operations = new ServerOperationService(guard, new ChangeLocks(),
            challenges, audit, gateways, clock);
        TagAdministrationService tags = new TagAdministrationService(guard, new ChangeLocks(),
            challenges, audit, gateways);
        client = McpTestClient.start(List.of(new OperateServerTool(operations::execute),
            new ManageTagsTool(tags::execute)), scrubber);
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        clients.forEach(InubitHttpClient::close);
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(logs);
        logs.stop();
        context.getLogger("de.dadecker").setLevel(previousLevel);
    }

    @Test
    void theFixtureCarriesThePlantedConfigurationSecrets() {
        assertThat(RestFixtures.text("admin-configurations.json")).contains(REST_SECRETS);
    }

    /**
     * Every action executed with INUBIT's known answers; the start time of the midnight task is
     * read from the recorded configuration with its planted leaves.
     */
    @Test
    void everyActionLeaksNoSecret() {
        stub(RestFixtures.response("admin-configurations", "json"));

        List<JsonNode> results = everyAction();

        assertThat(results).allSatisfy(result -> assertThat(outcome(result))
            .as(result.toString()).isEqualTo("EXECUTED"));
        assertThat(results.toString()).contains("MidnightTaskTriggerTime");
        assertNoLeak(results);
    }

    /**
     * Each change answered with the planted secrets: the operation forms with an answer no parser
     * knows, the tag forms as a failed run with them on stdout and stderr. Every StartCLI change
     * fails, and neither its result nor anything else carries a secret.
     */
    @Test
    void unknownAnswersAndFailedRunsOfTheChangesLeakNoSecret() {
        stub(RestFixtures.response("admin-configurations", "json"));
        scriptAnswers = command -> command.equals("maintenance -s") ? known(command)
            : command.startsWith("tag ") ? new Reply(PREAMBLE + PLANTED, PLANTED, 1)
            : new Reply(PREAMBLE + PLANTED, "", 0);

        List<JsonNode> results = everyAction();

        assertThat(results).allSatisfy(result -> {
            String action = result.path("structuredContent").path("result").path("action")
                .asString();
            assertThat(outcome(result)).as(result.toString())
                .isEqualTo(action.equals("setReadiness") ? "EXECUTED" : "FAILED");
        });
        assertNoLeak(results);
    }

    /**
     * The reads before a change answered with the planted secrets: {@code maintenance -s} and
     * {@code user <name>} with an answer no parser knows, {@code GET /configurations} with HTTP
     * 500 and the secrets in its body. The calls whose state cannot be read are refused; the
     * midnight task runs with its start time unknown; nothing carries a secret.
     */
    @Test
    void unknownAnswersOfTheReadsAndRestErrorsLeakNoSecret() {
        stub(aResponse().withStatus(500).withHeader("Content-Type", "application/json")
            .withBody("{\"message\": \"" + String.join(" ", REST_SECRETS) + " "
                + TestSecrets.FREE_TEXT_SECRETS + " " + PASSWORD + "\"}"));
        scriptAnswers = command -> command.equals("maintenance -s")
            ? new Reply(PREAMBLE + PLANTED, "", 0) : known(command);
        userBlock = PLANTED_USER;

        List<JsonNode> refused = List.of(
            operate(Map.of("action", "setMaintenance", "on", true)),
            tags(Map.of("action", "setActiveTag", "user", USER, "tag", "REL-2")),
            tags(Map.of("action", "removeActiveTag", "user", USER)));
        JsonNode midnight = operate(Map.of("action", "triggerMidnightTask"));

        assertThat(refused).allSatisfy(result -> assertThat(result.path("isError").asBoolean())
            .as(result.toString()).isTrue());
        assertThat(outcome(midnight)).as(midnight.toString()).isEqualTo("EXECUTED");
        assertThat(midnight.path("structuredContent").path("result").path("before").asString())
            .startsWith("start time could not be read");
        List<JsonNode> results = new ArrayList<>(refused);
        results.add(midnight);
        assertNoLeak(results);
    }

    // --- the calls -----------------------------------------------------------------------------

    /** One call of each action of both tools, in the order of their specs. */
    private List<JsonNode> everyAction() {
        List<JsonNode> results = new ArrayList<>();
        results.add(operate(Map.of("action", "setMaintenance", "on", true)));
        results.add(operate(Map.of("action", "setReadiness", "ready", false)));
        results.add(operate(Map.of("action", "setTrace", "on", true)));
        results.add(operate(Map.of("action", "setTraceLevel", "level", "debug")));
        results.add(operate(Map.of("action", "triggerMidnightTask")));
        results.add(operate(Map.of("action", "deleteErrorProcesses")));
        results.add(tags(Map.of("action", "setActiveTag", "user", USER, "tag", "REL-2")));
        results.add(tags(Map.of("action", "removeActiveTag", "user", USER)));
        assertThat(results.stream().map(result -> result.path("structuredContent")
            .path("result").path("action").asString()))
            .as("every action of both tools that sends a change")
            .containsExactlyElementsOf(Stream.concat(
                ServerOperationService.SPECS.stream(), TagAdministrationService.SPECS.stream())
                .map(ActionSpec::action).toList());
        return results;
    }

    private JsonNode operate(Map<String, Object> arguments) {
        return call("operate_server", arguments);
    }

    private JsonNode tags(Map<String, Object> arguments) {
        return call("manage_tags", arguments);
    }

    private JsonNode call(String tool, Map<String, Object> arguments) {
        Map<String, Object> withNode = new LinkedHashMap<>(arguments);
        withNode.put("node", DEV.value());
        withNode.put("reason", REASON);
        return client.callTool(tool, withNode);
    }

    private static String outcome(JsonNode result) {
        return result.path("structuredContent").path("result").path("outcome").asString();
    }

    private static Reply known(String command) {
        String answer = command.startsWith("tag ") ? "" : KNOWN.get(command);
        if (answer == null) {
            throw new AssertionError("no answer for " + command);
        }
        return new Reply(PREAMBLE + answer + (answer.isEmpty() ? "" : "\n"), "", 0);
    }

    private static void stub(ResponseDefinitionBuilder answer) {
        wireMock.stubFor(get(urlPathEqualTo(CONFIGURATIONS)).willReturn(answer));
    }

    // --- the checks ----------------------------------------------------------------------------

    /**
     * No secret in the rendered results (JSON and decoded text, tool errors included), in an
     * audit record or in a captured log line.
     */
    private void assertNoLeak(List<JsonNode> results) {
        String rendered = results.stream().map(result -> result + "\n" + texts(result))
            .collect(Collectors.joining("\n"));
        String audit = records.records.stream().map(AuditRecord::toString)
            .collect(Collectors.joining("\n"));
        String log = logText();
        assertThat(audit).as("audit records were written").isNotBlank();
        assertThat(log).as("log lines were captured").isNotBlank();
        Map<String, String> channels = Map.of("result", rendered, "audit", audit, "log", log);
        channels.forEach((channel, text) -> {
            TestSecrets.assertNoLeak(text);
            assertThat(text).as(channel).doesNotContain(EMAIL);
            REST_SECRETS.forEach(secret -> assertThat(text).as(channel).doesNotContain(secret));
        });
    }

    /** Every text value and property name of {@code node}, decoded. */
    private static String texts(JsonNode node) {
        StringBuilder text = new StringBuilder();
        collect(node, text);
        return text.toString();
    }

    private static void collect(JsonNode node, StringBuilder text) {
        if (node.isString()) {
            text.append(node.asString()).append('\n');
        }
        node.forEach(inner -> collect(inner, text));
        node.propertyNames().forEach(name -> text.append(name).append('\n'));
    }

    private String logText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : List.copyOf(logs.list)) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        return text.toString();
    }

    // --- the ports -----------------------------------------------------------------------------

    private EffectiveNodeConfig server() {
        return TestNodeConfig.node().id(DEV.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(10))
            .cliHome(temp.resolve("cli")).cliJavaHome(Path.of("/opt/jdk-17")).build();
    }

    private NodeCredentials credentials() {
        return new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    private CredentialGuard credentialGuard() {
        return new CredentialGuard(storeLabels("INUBIT", DEV),
            new MutableClock(Instant.parse("2026-11-18T08:00:00Z")));
    }

    private CliOutputClassifier classifier() {
        return new CliOutputClassifier(scrubber, storeLabels("INUBIT", DEV), false);
    }

    private CliRunner cliRunner() {
        return TestCliRunners.forHost(startCli(), Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()));
    }

    /**
     * StartCLI: a session (the active tag) answers each command with {@link #userBlock}, a
     * script-mode run with {@link #scriptAnswers} for its command.
     */
    private ProcessLauncher startCli() {
        return spec -> {
            if (!spec.startCliArguments().contains("--execCommand")) {
                return session().launch(spec);
            }
            Reply reply = scriptAnswers.apply(ScriptedProcessLauncher.execCommand(spec));
            return FakeProcessLauncher.of(reply.stdout(), reply.stderr(), reply.exitCode())
                .launch(spec);
        };
    }

    /**
     * A session that answers each command line on stdin with the prompt, the echoed command and
     * {@link #userBlock} in CRLF lines, then the echoed exit.
     */
    private FakeProcessLauncher session() {
        List<String> block = userBlock;
        return FakeProcessLauncher.of("", "", 0).responding(stdin -> {
            List<String> lines = new String(stdin, StandardCharsets.UTF_8).lines().toList();
            StringBuilder out = new StringBuilder(PREAMBLE);
            for (String command : lines.subList(1, lines.size())) {
                out.append(PROMPT).append(command).append('\n');
                if (!command.equals("exit")) {
                    block.forEach(line -> out.append(line).append("\r\n"));
                }
            }
            return new FakeProcessLauncher.Answer(out.toString(), 0);
        });
    }

    private CliOperationRunner operations() {
        return new CliOperationRunner(server(), credentials(), credentialGuard(), cliRunner(),
            classifier());
    }

    private CliTagAdminRunner tagAdmin() {
        return new CliTagAdminRunner(server(), credentials(), credentialGuard(), cliRunner(),
            classifier());
    }

    /** The production composition of {@code V81PortSet}: the REST client of the node. */
    private V81SettingsAdapter settings() {
        InubitHttpClient http = new InubitHttpClient(server(),
            Optional.of(new InubitHttpClient.Credentials(USER, scrubber.register(PASSWORD))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
        synchronized (clients) {
            clients.add(http);
        }
        return new V81SettingsAdapter(DEV, http, parts -> {
            throw new AssertionError("operate_server reads no StartCLI setting");
        });
    }

    private final GatewayFactory gateways = new GatewayFactory() {

        @Override
        public ServerOperationPort serverOperation(NodeId server) {
            CliOperationRunner cli = operations();
            return new ServerOperationPort() {
                @Override
                public boolean maintenanceState() {
                    return cli.maintenanceState();
                }

                @Override
                public String switchMaintenance(boolean on, Optional<Boolean> executeQueued,
                    WritePermit permit) {
                    return cli.switchMaintenance(on, executeQueued.orElse(null), permit);
                }

                @Override
                public void switchReadiness(boolean ready, WritePermit permit) {
                    // POST /ready/on|off: done (V81ServerOperationAdapterTest covers its answer)
                }

                @Override
                public void switchTrace(boolean on, WritePermit permit) {
                    cli.switchTrace(on, permit);
                }

                @Override
                public TraceLevel setTraceLevel(TraceLevel level, WritePermit permit) {
                    return cli.setTraceLevel(level, permit);
                }

                @Override
                public void triggerMidnightTask(WritePermit permit) {
                    cli.triggerMidnightTask(permit);
                }

                @Override
                public ErrorProcesses errorProcesses(Instant now) {
                    return new ErrorProcesses(2, List.of("110190387", "110190388"),
                        List.of("Workflow-9001"));
                }

                @Override
                public String deleteErrorProcesses(WritePermit permit) {
                    return cli.deleteErrorProcesses(permit);
                }
            };
        }

        @Override
        public TagAdministrationPort tagAdministration(NodeId server) {
            CliTagAdminRunner cli = tagAdmin();
            return new TagAdministrationPort() {
                @Override
                public Optional<String> activeTag(String user) {
                    return cli.activeTag(user);
                }

                @Override
                public void setActiveTag(String user, String tag, WritePermit permit) {
                    cli.setActiveTag(user, tag, permit);
                }

                @Override
                public void removeActiveTag(String user, WritePermit permit) {
                    cli.removeActiveTag(user, permit);
                }
            };
        }

        @Override
        public ServerSettingsPort serverSettings(NodeId server) {
            return settings();
        }

        @Override
        public MonitoringPort monitoring(NodeId server) {
            return new MonitoringPort() {
                @Override
                public Healthcheck healthcheck() {
                    return new Healthcheck(200, HealthStatus.OK, Optional.of(false),
                        Optional.empty(), Optional.empty());
                }

                @Override
                public Readiness ready() {
                    return new Readiness(true, Optional.empty());
                }

                @Override
                public SystemInfo systemInfo() {
                    return new SystemInfo(Optional.of("8.1.17"), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.of(false), Optional.empty(),
                        Map.of());
                }

                @Override
                public Metrics metrics() {
                    return new Metrics.Available(LoadFigures.of(512, 1536, 2048, 3, 8, 16, 40,
                        5000));
                }

                @Override
                public boolean credentialsConfirmed() {
                    return true;
                }
            };
        }

        @Override
        public ProcessControlPort processControl(NodeId server) {
            return new ProcessControlPort() {
                @Override
                public void checkAvailable() {
                }

                @Override
                public String restart(String processId, WritePermit permit) {
                    throw new AssertionError("not used");
                }

                @Override
                public String kill(String processId, WritePermit permit) {
                    throw new AssertionError("not used");
                }
            };
        }

        @Override
        public Gateway forServer(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public InubitVersion detect(NodeId server) {
            throw new AssertionError("the node is pinned to 8.1");
        }

        @Override
        public ProcessQueryPort processes(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public LogPort logs(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public InventoryPort inventory(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public Optional<Gateway> knownGateway(NodeId server) {
            return Optional.empty();
        }
    };
}
