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
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliSettingsRunner;
import de.dadecker.inubit.mcp.adapter.cli.admin.DbToleranceParser;
import de.dadecker.inubit.mcp.adapter.cli.admin.PrivilegedWorkflowsParser;
import de.dadecker.inubit.mcp.adapter.cli.admin.ServerDateParser;
import de.dadecker.inubit.mcp.adapter.cli.admin.ThreadsParser;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81SettingsAdapter;
import de.dadecker.inubit.mcp.application.FanOut;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.application.SecretTiming;
import de.dadecker.inubit.mcp.application.ServerSettingsService;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.HealthStatus;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LoadFigures;
import de.dadecker.inubit.mcp.domain.model.Masked;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.ServerSettingsPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.tools.GetServerSettingsTool;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestClientSelection;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
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
 * T023 (feature 022, US1, SC-005, FR-010, Constitution II): {@code get_server_settings} over MCP
 * on the real {@link ServerSettingsService}, the real {@link V81SettingsAdapter} against WireMock
 * and the real {@link CliSettingsRunner} on a scripted StartCLI session. Planted secrets in the
 * license XML, in configuration leaves, in StartCLI output and in INUBIT's error answers never
 * reach the rendered result, an error text, a log line (captured appender, TRACE) or an audit
 * record (the read writes none). A part that fails is reported with its error's code, message,
 * cause and next step; its excerpt is not passed on, and the excerpts of the settings parsers
 * carry no secret either.
 *
 * <ul>
 *   <li>REST: the reconstructed fixtures {@code admin-license-reconstructed} and
 *       {@code admin-configurations-reconstructed} with their planted values
 *       ({@link #REST_SECRETS}, see their READMEs), and error bodies (HTTP 403, 500, unreadable
 *       documents) made of them;
 *   <li>StartCLI: the node's configured password ({@link #PASSWORD}, registered with the global
 *       scrubber as the production wiring does) echoed after the password prompt and printed as
 *       a workflow name, and unknown answers and a failing session carrying it and the
 *       secret-named value {@code password=}{@link #CLI_SECRET}.
 * </ul>
 *
 * <p>The capability matrix supports every capability and the coverage list is a fixture whose US1
 * rows are reachable, so every part reaches the adapters; the production list keeps them
 * {@code planned} until the recordings (T025). The facts {@code get_health} reads come from a
 * fixed {@link MonitoringPort}, which carries no INUBIT text.
 */
@Timeout(60)
class ServerSettingsSecretLeakTest {

    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    private static final String LICENSE = "/ibis/rest/license";
    private static final String CONFIGURATIONS = "/ibis/rest/configurations";
    private static final String USER = "jdoe";
    private static final String PROMPT = "CLI|jdoe@localhost> ";

    /** The node's configured password (INUBIT login over REST and StartCLI). */
    private static final String PASSWORD = "settings-leak-password-0231";
    /** A secret value StartCLI prints under a secret name. */
    private static final String CLI_SECRET = "synthetic-secret-0231";
    /**
     * The planted values of the reconstructed REST fixtures: the licensee (masked by
     * {@code LicenseXmlParser}), {@code activationPassword} and {@code signature} of the license,
     * and the configuration leaves masked by secret name, {@code encrypted} flag and key material.
     */
    private static final List<String> REST_SECRETS = List.of("Fictitious Licensee 0001",
        "synthetic-secret-0201", "synthetic-secret-0202", "synthetic-secret-0301",
        "synthetic-secret-0302", "synthetic-secret-0303", "MIIBsyntheticsecret0304");
    private static final List<String> SECRETS = Stream.concat(REST_SECRETS.stream(),
        Stream.of(CLI_SECRET, PASSWORD)).toList();
    /** The configuration sections with a planted secret. */
    private static final List<String> SECRET_SECTIONS = List.of("ProcessUserServer",
        "SMTPProperties", "LDAPProperties", "SystemProperties");

    /** The US1 rows of interface-coverage.tsv, reachable on 8.1 and 9.0. */
    private static final String COVERAGE = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        CLI\tthreads showNumberOfWorkflowThreads\tREAD\tA\tUS1\t%1$s\t%1$s\t-
        CLI\tprivilegedWorkflows showThreadsPercentage\tREAD\tA\tUS1\t%1$s\t%1$s\t-
        CLI\tprivilegedWorkflows showWorkflowList\tREAD\tA\tUS1\t%1$s\t%1$s\t-
        CLI\tdbconnectiontolerance show\tREAD\tA\tUS1\t%1$s\t%1$s\t-
        CLI\tdate\tREAD\tA\tUS1\t%1$s\t%1$s\t-
        REST\tGET /configurations\tREAD\tA\tUS1\t%1$s\t%1$s\t-
        REST\tGET /license\tREAD\tA\tUS1\t%1$s\t%1$s\t-
        """.formatted("reachable:get_server_settings/default");

    private static final Set<String> CLI_PARTS = Set.of(UnavailablePart.THREADS,
        UnavailablePart.PRIVILEGED_WORKFLOWS, UnavailablePart.DB_CONNECTION_TOLERANCE,
        UnavailablePart.SERVER_TIME);

    private static final List<String> THREADS = block("admin-settings-threads-reconstructed");
    private static final List<String> PERCENTAGE =
        block("admin-settings-privileged-percentage-reconstructed");
    private static final List<String> LIST =
        block("admin-settings-privileged-list-reconstructed");
    private static final List<String> TOLERANCE =
        block("admin-settings-db-tolerance-reconstructed");
    private static final List<String> DATE = block("admin-settings-date-reconstructed");
    /** An answer no settings parser knows, with both StartCLI secrets. */
    private static final List<String> PLANTED = List.of("password=" + CLI_SECRET,
        "Unknown answer for " + PASSWORD);

    private static WireMockServer wireMock;

    @TempDir
    Path cliHome;

    private final SecretScrubber scrubber = SecretScrubber.global();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<InubitHttpClient> clients = new ArrayList<>();
    private Level previousLevel;
    private McpTestClient client;
    /** The scripted StartCLI of the next session. */
    private FakeProcessLauncher launcher;

    private final NodeVersions versions = new NodeVersions(Clock.systemUTC(),
        Map.of(DEV1, VersionSetting.V8_1)::get, node -> Duration.ofMinutes(10));

    private final GatewayFactory gateways = new GatewayFactory() {
        @Override
        public MonitoringPort monitoring(NodeId server) {
            return new FixedMonitoring();
        }

        @Override
        public ServerSettingsPort serverSettings(NodeId server) {
            return adapter();
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
        public ProcessControlPort processControl(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public Optional<Gateway> knownGateway(NodeId server) {
            return Optional.empty();
        }
    };

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
        FakeStartCli.install(cliHome);
        // as the production wiring registers each node's password with the global scrubber
        scrubber.register(PASSWORD);
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logs.setContext(context);
        logs.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logs);
        Logger ours = context.getLogger("de.dadecker");
        previousLevel = ours.getLevel();
        ours.setLevel(Level.TRACE);

        versions.clientProbed(DEV1, new ProbedClient(ClientKey.NONE, new ClientVersion(
            InubitVersion.parse("8.1.12").orElseThrow(), InubitVersion.parse("8.1.0"),
            InubitVersion.parse("8.1.99"))));
        VersionGate gate = new VersionGate(versions, gateways, everythingSupported(),
            (node, version, timeout) -> {
                throw new AssertionError("no client probe expected for " + node);
            }, TestClientSelection.of(node -> true), node -> Duration.ZERO,
            Terminology.DEFAULT, false, InterfaceCoverage.load(new StringReader(COVERAGE)));
        ServerSettingsService service = new ServerSettingsService(gateways,
            new TargetResolver(List.of(DEV1)), new FanOut(), ResultLimiter.withDefaults(),
            node -> Duration.ofSeconds(10), node -> Duration.ofSeconds(10), gate,
            SecretTiming.NONE);
        client = McpTestClient.start(List.of(new GetServerSettingsTool(service::read,
            Optional::empty)), scrubber);
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
    void theFixturesCarryThePlantedSecrets() {
        assertThat(RestFixtures.text("admin-license-reconstructed.xml")
            + RestFixtures.text("admin-configurations-reconstructed.json"))
            .contains(REST_SECRETS);
    }

    /** Every part, and every configuration section with a secret, read without a failure. */
    @Test
    void everyPartAndEverySecretSectionLeakNoSecret() {
        serveFixtures();
        launcher = FakeProcessLauncher.of(session(THREADS, PERCENTAGE,
            append(LIST, PASSWORD), TOLERANCE, DATE), "", 0);
        List<JsonNode> results = new ArrayList<>();

        JsonNode defaults = call(Map.of("target", "dev/node1"));
        results.add(defaults);
        results.add(call(Map.of("target", "dev/node1", "parts", List.of("configuration"))));
        for (String section : SECRET_SECTIONS) {
            results.add(call(Map.of("target", "dev/node1", "configurationSection", section)));
        }

        assertThat(results).allSatisfy(result -> {
            assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
            assertThat(entry(result).path("unavailable")).as(result.toString()).isEmpty();
        });
        JsonNode facts = entry(defaults);
        assertThat(facts.path("license").path("licensee").asString())
            .isEqualTo(Masked.MASK_TOKEN);
        assertThat(facts.path("workflowThreads").asLong()).isEqualTo(16);
        assertThat(facts.path("privilegedWorkflows").toString())
            .as("the printed password is scrubbed, the names are kept")
            .contains("Fictitious-Workflow-0001", SecretScrubber.MASK);
        for (JsonNode result : results.subList(2, results.size())) {
            JsonNode configuration = entry(result).path("configuration");
            assertThat(configuration.path("maskedCount").asInt()).as(result.toString())
                .isPositive();
            assertThat(configuration.toString()).contains("\"masked\":true");
        }
        assertNoLeak(results, List.of());
    }

    /**
     * INUBIT's refusals and failures (403, 500, documents the parsers cannot read) with the
     * secrets in their bodies, and StartCLI answers no parser knows: each part is unavailable, and
     * neither the reasons nor what the adapters' and parsers' errors hand on to them carry a
     * secret; the parsers' excerpts (masked by this feature) carry none either.
     */
    @Test
    void restErrorsAndUnknownStartCliAnswersLeakNoSecret() {
        String secrets = String.join(" ", SECRETS);
        launcher = FakeProcessLauncher.of(session(PLANTED, PLANTED, PLANTED, PLANTED, PLANTED),
            "", 0);
        List<JsonNode> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        stub(LICENSE, aResponse().withStatus(403).withBody(secrets));
        stub(CONFIGURATIONS, aResponse().withStatus(500).withBody(secrets));
        results.add(call(Map.of("target", "dev/node1", "configurationSection",
            "SMTPProperties")));
        adapterErrors().forEach(error -> errors.add(reported(error)));

        stub(LICENSE, aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
            .withBody("<license><licensee>" + secrets + "</licensee></license>"));
        stub(CONFIGURATIONS, aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"SeverConfiguration\": \"" + secrets + "\"}"));
        results.add(call(Map.of("target", "dev/node1", "configurationSection",
            "SMTPProperties")));
        adapterErrors().forEach(error -> errors.add(reported(error)));

        for (ToolError error : List.of(errorOf(() -> ThreadsParser.parse(PLANTED)),
            errorOf(() -> PrivilegedWorkflowsParser.parse(PLANTED, PLANTED)),
            errorOf(() -> PrivilegedWorkflowsParser.parse(PERCENTAGE, PLANTED)),
            errorOf(() -> DbToleranceParser.parse(PLANTED)),
            errorOf(() -> ServerDateParser.parse(PLANTED)))) {
            assertThat(error.excerpt()).as("the parser's excerpt").isPresent();
            errors.add(reported(error) + "\n" + error.excerpt().orElseThrow());
        }

        assertThat(wireMock.findAllUnmatchedRequests()).isEmpty();
        assertThat(results).allSatisfy(result -> {
            Map<String, String> reasons = reasons(result);
            assertThat(reasons).as(result.toString()).containsOnlyKeys(UnavailablePart.LICENSE,
                UnavailablePart.CONFIGURATION, UnavailablePart.THREADS,
                UnavailablePart.PRIVILEGED_WORKFLOWS, UnavailablePart.DB_CONNECTION_TOLERANCE,
                UnavailablePart.SERVER_TIME);
            CLI_PARTS.forEach(part ->
                assertThat(reasons.get(part)).startsWith("UNEXPECTED_RESPONSE: "));
        });
        assertThat(reasons(results.get(0)).get(UnavailablePart.LICENSE)).startsWith("FORBIDDEN: ");
        assertThat(reasons(results.get(0)).get(UnavailablePart.CONFIGURATION))
            .startsWith("UNEXPECTED_RESPONSE: ");
        assertNoLeak(results, errors);
    }

    /**
     * A StartCLI session that fails as a whole, with the secrets on stdout and stderr: the
     * StartCLI parts are unavailable, and what the session's error hands on to them carries no
     * secret.
     */
    @Test
    void aFailedStartCliSessionLeaksNoSecret() {
        serveFixtures();
        launcher = FakeProcessLauncher.of("Password: " + PASSWORD + "\n"
                + "ERROR 08:00:01,234 [main] Session aborted: password=" + CLI_SECRET + " for "
                + PASSWORD + "\n",
            "java.lang.IllegalStateException: password=" + CLI_SECRET + " " + PASSWORD + "\n", 1);

        JsonNode result = call(Map.of("target", "dev/node1"));
        ToolError error = errorOf(() -> runner().read(CLI_PARTS));

        assertThat(reasons(result)).containsOnlyKeys(CLI_PARTS);
        assertThat(reasons(result).values()).allSatisfy(reason ->
            assertThat(reason).startsWith(error.code() + ": "));
        assertThat(entry(result).path("license").path("licensee").asString())
            .isEqualTo(Masked.MASK_TOKEN);
        assertNoLeak(List.of(result), List.of(reported(error)));
    }

    /**
     * SC-005's audit channel: get_server_settings is a read, and neither the tool nor its service
     * takes an {@link AuditPort}, so it writes no audit record that could carry a secret.
     */
    @Test
    void theReadWritesNoAuditRecord() {
        for (Class<?> type : List.of(GetServerSettingsTool.class, ServerSettingsService.class)) {
            for (Constructor<?> constructor : type.getConstructors()) {
                assertThat(constructor.getParameterTypes()).as(constructor.toString())
                    .doesNotContain(AuditPort.class);
            }
        }
    }

    // --- support -------------------------------------------------------------------------------

    private JsonNode call(Map<String, Object> arguments) {
        return client.callTool("get_server_settings", arguments);
    }

    /** The only entry of a single-server result. */
    private static JsonNode entry(JsonNode result) {
        JsonNode settings = result.path("structuredContent").path("settings");
        assertThat(settings.size()).as(result.toString()).isEqualTo(1);
        return settings.get(0);
    }

    /** The {@code unavailable} reasons of the entry by part. */
    private static Map<String, String> reasons(JsonNode result) {
        List<JsonNode> parts = new ArrayList<>();
        entry(result).path("unavailable").forEach(parts::add);
        return parts.stream().collect(Collectors.toMap(part -> part.path("part").asString(),
            part -> part.path("reason").asString()));
    }

    private void serveFixtures() {
        stub(LICENSE, RestFixtures.response("admin-license-reconstructed", "xml"));
        stub(CONFIGURATIONS, RestFixtures.response("admin-configurations-reconstructed", "json"));
    }

    private static void stub(String path, ResponseDefinitionBuilder answer) {
        wireMock.stubFor(get(urlPathEqualTo(path)).willReturn(answer));
    }

    /** The errors of both REST reads of the adapter as the stubs answer now. */
    private List<ToolError> adapterErrors() {
        V81SettingsAdapter adapter = adapter();
        return List.of(errorOf(adapter::license),
            errorOf(() -> adapter.configuration(Optional.of("SMTPProperties"), 100, 50_000)));
    }

    private static ToolError errorOf(Supplier<?> call) {
        try {
            call.get();
        } catch (ToolErrorException e) {
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    /**
     * What {@code UnavailablePart.of} hands on from {@code error}: code and message as reason,
     * cause and next step. The excerpt stays behind; {@link #assertNoLeak} checks that no result
     * carries one.
     */
    private static String reported(ToolError error) {
        return String.join("\n", error.code() + ": " + error.message(), error.likelyCause(),
            error.nextStep());
    }

    private EffectiveNodeConfig server() {
        return TestNodeConfig.node().id(DEV1.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(10))
            .cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17")).build();
    }

    /** The production composition of {@code V81PortSet}: REST client and session runner. */
    private V81SettingsAdapter adapter() {
        InubitHttpClient http = new InubitHttpClient(server(),
            Optional.of(new InubitHttpClient.Credentials(USER, scrubber.register(PASSWORD))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
        synchronized (clients) {
            clients.add(http);
        }
        return new V81SettingsAdapter(DEV1, http, runner()::read);
    }

    private CliSettingsRunner runner() {
        NodeCredentials credentials = new NodeCredentials(DEV1,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliSettingsRunner(server(), credentials,
            new CredentialGuard(storeLabels("INUBIT", DEV1),
                new MutableClock(Instant.parse("2026-11-12T08:00:00Z"))),
            TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(scrubber, storeLabels("INUBIT", DEV1), false));
    }

    /**
     * The session's stdout: the password prompt with the echoed password, one prompt per block,
     * the prompt of exit.
     */
    @SafeVarargs
    private static String session(List<String>... blocks) {
        StringBuilder out = new StringBuilder("Password: " + PASSWORD + "\n");
        for (List<String> block : blocks) {
            out.append(PROMPT).append(String.join("\n", block)).append('\n');
        }
        return out.append(PROMPT).toString();
    }

    private static List<String> append(List<String> block, String line) {
        return Stream.concat(block.stream(), Stream.of(line)).toList();
    }

    /** The non-blank lines of {@code fixtures/v8_1/cli/<fixtureCase>.stdout}. */
    private static List<String> block(String fixtureCase) {
        return FakeProcessLauncher.fixtureText(fixtureCase + ".stdout").lines()
            .map(line -> line.replace("\r", "")).filter(line -> !line.isBlank()).toList();
    }

    private static CapabilityMatrix everythingSupported() {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, SupportState.SUPPORTED);
        }
        return new CapabilityMatrix(List.of(), states);
    }

    // --- the checks ----------------------------------------------------------------------------

    /**
     * No secret in the rendered results (JSON and decoded text), in the {@code errors} texts or in
     * a captured log line; no result carries an error excerpt.
     */
    private void assertNoLeak(List<JsonNode> results, List<String> errors) {
        String result = results.stream().map(JsonNode::toString)
            .collect(Collectors.joining("\n"));
        String decoded = results.stream().map(ServerSettingsSecretLeakTest::texts)
            .collect(Collectors.joining("\n"));
        String error = String.join("\n", errors);
        String log = logText();
        assertThat(result).as("no excerpt is passed on").doesNotContain("\"excerpt\"");
        assertThat(log).as("log lines were captured").isNotBlank();
        for (String secret : SECRETS) {
            assertThat(result).as("result: " + secret).doesNotContain(secret);
            assertThat(decoded).as("result text: " + secret).doesNotContain(secret);
            assertThat(error).as("error: " + secret).doesNotContain(secret);
            assertThat(log).as("log: " + secret).doesNotContain(secret);
        }
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

    /** The facts get_health reads, fixed: no INUBIT text passes through them. */
    private static final class FixedMonitoring implements MonitoringPort {

        @Override
        public Healthcheck healthcheck() {
            return new Healthcheck(200, HealthStatus.OK, Optional.of(false), Optional.empty(),
                Optional.empty());
        }

        @Override
        public Readiness ready() {
            throw new AssertionError("get_server_settings does not read the readiness");
        }

        @Override
        public SystemInfo systemInfo() {
            return new SystemInfo(Optional.of("8.1.12"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Map.of());
        }

        @Override
        public Metrics metrics() {
            return new Metrics.Available(LoadFigures.of(512, 1536, 2048, 3, 8, 16, 40, 5000));
        }

        @Override
        public boolean credentialsConfirmed() {
            return true;
        }
    }
}
