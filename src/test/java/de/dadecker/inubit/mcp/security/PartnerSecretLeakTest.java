package de.dadecker.inubit.mcp.security;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.DiagramXml;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81PartnerManagementAdapter;
import de.dadecker.inubit.mcp.application.FanOut;
import de.dadecker.inubit.mcp.application.InventoryCache;
import de.dadecker.inubit.mcp.application.PartnerAudit;
import de.dadecker.inubit.mcp.application.PartnerService;
import de.dadecker.inubit.mcp.application.PartnerSnapshot;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.application.SecretTiming;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.SettingValue;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.AuditLog;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.tools.FindPartnersTool;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Feature 018 T030 (SC-002, FR-008, Constitution II): {@code find_partners} over MCP on the real
 * {@link PartnerService}, the real 8.1 adapter against WireMock serving the synthetic exports of
 * {@link PartnerFixtures} and the real audit log: a single read, a list with filters and paging,
 * a comparison of stages, errors of every kind, a cache hit and a refresh, a failing audit. No
 * synthetic secret, nor its 12-character prefix, its lines, its unkeyed SHA-256 or its Base64
 * form, reaches a result, an error, the log (captured appender) or an audit line; the cache
 * holds every secret only as {@link SettingValue.Masked}.
 */
@Timeout(120)
class PartnerSecretLeakTest {

    private static final String EXPORT = "/ibis/rest/model/export/";
    private static final String WORKFLOW_XML = "workflow/workflow.xml";
    private static final String OWNER = PartnerFixtures.OWNER;
    private static final String GROUP = PartnerFixtures.GROUP;
    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    private static final NodeId DEV2 = NodeId.parse("dev/node2");
    private static final List<NodeId> STAGES = Stream.of("test/node1", "test/node2",
        "prod/node1", "prod/node2").map(NodeId::parse).toList();
    private static WireMockServer wireMock;

    @TempDir
    Path auditDirectory;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T08:00:00Z"));
    private final SecretScrubber scrubber = new SecretScrubber();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<InubitHttpClient> clients = new ArrayList<>();
    /** The system diagrams each server lists, in list order. */
    private final Map<NodeId, List<DiagramRef>> listed = new HashMap<>();
    private final InventoryCache cache =
        new InventoryCache(clock, node -> Duration.ofMinutes(10));
    private final AtomicInteger maskedInCache = new AtomicInteger();
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
    void captureLogs() {
        wireMock.resetAll();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logs.setContext(context);
        logs.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logs);
        Logger ours = context.getLogger("de.dadecker");
        previousLevel = ours.getLevel();
        ours.setLevel(Level.TRACE);
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
    void singleReadsListsFiltersPagesCacheHitsAndRefreshesLeakNoSecret() throws IOException {
        serve(DEV1, PartnerFixtures.detail(), PartnerFixtures.list(40),
            PartnerFixtures.twoClouds());
        start(audit());
        List<JsonNode> results = new ArrayList<>();

        for (Map<String, Object> arguments : List.<Map<String, Object>>of(
            Map.of("target", "dev/node1"),
            Map.of("target", "dev/node1", "search", "acme"),
            Map.of("target", "dev/node1", "search", "ACME-0002"),
            Map.of("target", "dev/node1", "systemDiagram", "SystemDiagram-02", "offset", 20),
            Map.of("target", "dev/node1", "protocol", "FTP"),
            Map.of("target", "dev/node1", "protocol", "WebService"),
            Map.of("target", "dev/node1", "workflow", "Workflow-0001"),
            Map.of("target", "dev/node1", "systemDiagram", "SystemDiagram-03"))) {
            results.add(call(arguments));
        }
        int requests = wireMock.getAllServeEvents().size();
        results.add(call(Map.of("target", "dev/node1", "search", "acme")));
        assertThat(wireMock.getAllServeEvents()).as("a cache hit reads nothing")
            .hasSize(requests);
        results.add(call(Map.of("target", "dev/node1", "refresh", true)));
        assertThat(wireMock.getAllServeEvents()).as("a refresh reads again")
            .hasSizeGreaterThan(requests);

        assertThat(results).allSatisfy(result ->
            assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse());
        assertThat(results.get(1).path("structuredContent").path("partners"))
            .as("the partners with their masked settings were returned").isNotEmpty();
        assertNoLeak(results, true);
        assertCacheHoldsOnlyMaskedSecrets(DEV1);
    }

    @Test
    void aSearchForASecretValueFindsNothing() {
        serve(DEV1, PartnerFixtures.detail());
        start(audit());

        for (String secret : PartnerFixtures.SECRETS) {
            for (String search : List.of(secret.lines().findFirst().orElseThrow(),
                secret.substring(0, 12))) {
                JsonNode result = call(Map.of("target", "dev/node1", "search", search));

                assertThat(result.path("structuredContent").path("partners"))
                    .as("masked values are not searched: " + search).isEmpty();
                assertThat(result.path("structuredContent").path("similarValues").toString())
                    .doesNotContain(search);
            }
        }
    }

    @Test
    void comparisonsOfStagesAndServersLeakNoSecret() throws IOException {
        Map<String, byte[]> stages = PartnerFixtures.stages();
        for (NodeId server : STAGES) {
            serve(server, List.of(new DiagramRef("SystemDiagram-01", GROUP)),
                stages.get(server.value()));
        }
        start(audit());
        List<JsonNode> results = new ArrayList<>();

        results.add(call(Map.of("target", "test", "compareWith", "prod")));
        results.add(call(Map.of("target", "test/node1", "compareWith", "prod/node2",
            "search", "ACME-0002")));
        results.add(call(Map.of("target", "test", "compareWith", "prod", "protocol",
            "WebService")));

        assertThat(results).allSatisfy(result -> {
            assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
            assertThat(result.path("structuredContent").path("compared").asBoolean()).isTrue();
        });
        assertThat(results.get(1).toString()).as("the differing password is compared masked")
            .contains("\"masked\":true");
        assertNoLeak(results, true);
        for (NodeId server : STAGES) {
            assertCacheHoldsOnlyMaskedSecrets(server);
        }
    }

    @Test
    void errorsOfEveryKindLeakNoSecret() throws IOException {
        String secrets = String.join(" ", PartnerFixtures.SECRETS);
        byte[] detail = PartnerFixtures.workflowXml(PartnerFixtures.detail());
        String detailXml = new String(detail, StandardCharsets.UTF_8);
        int afterSecret = detailXml.indexOf(PartnerFixtures.LEGACY_PASSWORD)
            + PartnerFixtures.LEGACY_PASSWORD.length();
        Map<String, ResponseDefinitionBuilder> answers = new LinkedHashMap<>();
        answers.put("SystemDiagram-11", ok(withWorkflowXml(
            detailXml.substring(0, afterSecret).getBytes(StandardCharsets.UTF_8))));
        answers.put("SystemDiagram-12", aResponse().withStatus(500).withBody(secrets));
        answers.put("SystemDiagram-13", aResponse().withStatus(200).withBody(secrets));
        answers.put("SystemDiagram-14", aResponse().withStatus(404).withBody(secrets));
        answers.put("SystemDiagram-15", ok(withWorkflowXml(named(detailXml.replace(
            "<UserOrUserGroupName>" + OWNER + "<", "<UserOrUserGroupName>OTHERS<"),
            "SystemDiagram-15"))));
        answers.put("SystemDiagram-16", ok(withWorkflowXml(named(detailXml.replace(
            "PartnerManagementSettings", "PartnerManagementSettings\" broken=\"" + secrets),
            "SystemDiagram-16"))));
        answers.put("SystemDiagram-17", ok(withWorkflowXml(named(detailXml.replace(
            "<Property name=\"Partners\" type=\"List\">", "<Property name=\"Partners\""
                + " type=\"List\"><Property name=\"Odd\" type=\"Password\">" + secrets
                + "</Property>"),
            "SystemDiagram-17"))));
        List<DiagramRef> diagrams = answers.keySet().stream()
            .map(name -> new DiagramRef(name, GROUP)).toList();
        listed.put(DEV1, diagrams);
        listed.put(DEV2, diagrams);
        answers.forEach((name, answer) -> {
            stub(DEV1, name, answer);
            stub(DEV2, name, answer);
        });
        start(audit());
        List<JsonNode> results = new ArrayList<>();

        // what each answer leads to, so that every branch is reached, not a 404 of a lost stub
        Map<String, String> reached = Map.of("SystemDiagram-11", "\"UNEXPECTED_RESPONSE\"",
            "SystemDiagram-12", "Unexpected HTTP 500", "SystemDiagram-13",
            "\"UNEXPECTED_RESPONSE\"", "SystemDiagram-14", "HTTP 404", "SystemDiagram-15",
            "another group or owner", "SystemDiagram-16", "ACME-0003", "SystemDiagram-17",
            "ACME-0003");
        for (String name : answers.keySet()) {
            JsonNode single = call(Map.of("target", "dev/node1", "systemDiagram", name));
            assertThat(single.toString()).as(name).contains(reached.get(name));
            results.add(single);
            results.add(call(Map.of("target", "dev/node1", "compareWith", "dev/node2",
                "systemDiagram", name, "refresh", true)));
        }
        assertThat(wireMock.findAllUnmatchedRequests()).isEmpty();
        results.add(call(Map.of("target", "dev/node1", "systemDiagram", "SystemDiagram-99")));
        results.add(call(Map.of("target", "dev/node9")));

        assertThat(results).anySatisfy(result ->
            assertThat(result.path("isError").asBoolean()).isTrue());
        assertThat(results).anySatisfy(result -> assertThat(result.path("structuredContent")
            .path("notCompared")).isNotEmpty());
        assertNoLeak(results, true);
    }

    @Test
    void aFailingAuditLeaksNoSecret() throws IOException {
        serve(DEV1, PartnerFixtures.detail());
        serve(DEV2, PartnerFixtures.detail());
        start(record -> {
            throw new UncheckedIOException(new IOException("disk full"));
        });
        List<JsonNode> results = new ArrayList<>();

        results.add(call(Map.of("target", "dev/node1", "search", "acme")));
        results.add(call(Map.of("target", "dev/node1", "compareWith", "dev/node2")));
        results.add(call(Map.of("target", "dev/node1", "search", "\u0007")));

        assertThat(logText()).contains("could not be written");
        assertNoLeak(results, false);
    }

    // --- support -------------------------------------------------------------------------------

    private AuditPort audit() {
        return new AuditLog(auditDirectory, scrubber, PrivateFiles.forHost());
    }

    private void start(AuditPort port) {
        PartnerAudit.Dependencies audits =
            new PartnerAudit.Dependencies(port, clock, "acme", UUID::randomUUID);
        List<NodeId> nodes = new ArrayList<>(List.of(DEV1, DEV2));
        nodes.addAll(STAGES);
        PartnerService service = new PartnerService(new Gateways(), new TargetResolver(nodes),
            new FanOut(), ResultLimiter.withDefaults(), cache, node -> Optional.of(OWNER),
            node -> Duration.ofSeconds(10), node -> Duration.ofSeconds(30), ResultJson::size,
            Terminology.DEFAULT, FakeVersionDetection.pinned81(), SecretTiming.NONE, audits);
        client = McpTestClient.start(List.of(new FindPartnersTool(service, audits)));
        client.initialize();
    }

    private JsonNode call(Map<String, Object> arguments) {
        return client.callTool("find_partners", arguments);
    }

    /** {@code server} lists {@code diagrams} and exports each of them. */
    private void serve(NodeId server, DiagramXml... diagrams) {
        for (DiagramXml diagram : diagrams) {
            serve(server, List.of(new DiagramRef(diagram.name(), diagram.group())),
                PartnerFixtures.workflowXml(diagram));
        }
    }

    private void serve(NodeId server, List<DiagramRef> diagrams, byte[] workflowXml) {
        for (DiagramRef diagram : diagrams) {
            listed.computeIfAbsent(server, key -> new ArrayList<>()).add(diagram);
            stub(server, diagram.name(), ok(withWorkflowXml(workflowXml)));
        }
    }

    private static void stub(NodeId server, String name, ResponseDefinitionBuilder answer) {
        wireMock.stubFor(get(urlPathEqualTo(prefix(server) + EXPORT + name)).willReturn(answer));
    }

    private static byte[] named(String workflowXml, String name) {
        assertThat(workflowXml).contains("SystemDiagram-01");
        return workflowXml.replace("SystemDiagram-01", name).getBytes(StandardCharsets.UTF_8);
    }

    private static ResponseDefinitionBuilder ok(byte[] zip) {
        return aResponse().withStatus(200).withBody(zip);
    }

    private static String prefix(NodeId server) {
        return "/" + server.value().replace('/', '-');
    }

    /** The recorded system diagram export with its workflow.xml replaced. */
    private static byte[] withWorkflowXml(byte[] xml) {
        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries(
            RestFixtures.bytes("model_export_systemdiagram.zip")));
        entries.put(WORKFLOW_XML, xml);
        return ArtifactFixtures.zip(entries);
    }

    private PartnerManagementPort adapter(NodeId server) {
        EffectiveNodeConfig config = TestNodeConfig.node().id(server.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort() + prefix(server))
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(10))
            .build();
        InubitHttpClient http = new InubitHttpClient(config,
            Optional.of(new InubitHttpClient.Credentials("jdoe",
                scrubber.register("partner-leak-test-pw"))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
        synchronized (clients) {
            clients.add(http);
        }
        return new V81PartnerManagementAdapter(server, http);
    }

    // --- the checks ----------------------------------------------------------------------------

    private void assertNoLeak(List<JsonNode> results, boolean audited) throws IOException {
        String result = results.stream().map(JsonNode::toString)
            .collect(Collectors.joining("\n"));
        String decoded = results.stream().map(PartnerSecretLeakTest::texts)
            .collect(Collectors.joining("\n"));
        String audit = auditText();
        String log = logText();
        if (audited) {
            assertThat(audit).as("audit lines were written").isNotBlank();
        }
        assertThat(log).as("log lines were captured").isNotBlank();
        for (String secret : PartnerFixtures.SECRETS) {
            for (String form : derived(secret).toList()) {
                assertThat(result).as("result: " + form).doesNotContain(form);
                assertThat(decoded).as("result text: " + form).doesNotContain(form);
                assertThat(audit).as("audit: " + form).doesNotContain(form);
                assertThat(log).as("log: " + form).doesNotContain(form);
            }
        }
    }

    /** The cached snapshot of {@code server}: no secret form anywhere, secrets only masked. */
    private void assertCacheHoldsOnlyMaskedSecrets(NodeId server) {
        Optional<InventoryCache.Snapshot<PartnerSnapshot>> snapshot = cache.peek(
            new InventoryCache.Key(server, InventoryKind.DIAGRAM,
                PartnerSnapshot.partnersScope(OWNER)));
        assertThat(snapshot).as("cached partners of " + server).isPresent();
        List<String> held = new ArrayList<>();
        maskedInCache.set(0);
        walk(snapshot.get().value(), held, new IdentityHashMap<>());
        assertThat(maskedInCache.get()).as("masked values of " + server).isPositive();
        String text = String.join("\n", held);
        for (String secret : PartnerFixtures.SECRETS) {
            for (String form : derived(secret).toList()) {
                assertThat(text).as("cache of " + server + ": " + form).doesNotContain(form);
            }
        }
    }

    /** Every string of {@code value}'s object graph, byte arrays as hex, Base64 and text. */
    private void walk(Object value, List<String> held, IdentityHashMap<Object, Boolean> seen) {
        if (value == null || seen.put(value, Boolean.TRUE) != null) {
            return;
        }
        if (value instanceof SettingValue.Masked) {
            maskedInCache.incrementAndGet();
        }
        if (value instanceof String text) {
            held.add(text);
        } else if (value instanceof byte[] bytes) {
            held.add(HexFormat.of().formatHex(bytes));
            held.add(Base64.getEncoder().encodeToString(bytes));
            held.add(new String(bytes, StandardCharsets.ISO_8859_1));
        } else if (value instanceof Optional<?> optional) {
            optional.ifPresent(inner -> walk(inner, held, seen));
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(inner -> walk(inner, held, seen));
        } else if (value instanceof Map<?, ?> map) {
            map.forEach((key, inner) -> {
                walk(key, held, seen);
                walk(inner, held, seen);
            });
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                walk(Array.get(value, i), held, seen);
            }
        } else if (value.getClass().getName().startsWith("de.dadecker.")) {
            for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        try {
                            walk(field.get(value), held, seen);
                        } catch (IllegalAccessException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
            }
        } else {
            held.add(String.valueOf(value));
        }
    }

    /**
     * {@code secret}, its 12-character prefix, each of its lines of 8 characters or more, its
     * unkeyed SHA-256 (hex) and its Base64 form.
     */
    private static Stream<String> derived(String secret) {
        try {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
            return Stream.concat(Stream.of(secret, secret.substring(0, 12), sha256,
                    Base64.getEncoder().encodeToString(bytes)),
                secret.lines().map(String::strip).filter(line -> line.length() >= 8)).distinct();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every text value of {@code node}, decoded (a multi-line secret is not JSON-escaped). */
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

    private String auditText() throws IOException {
        StringBuilder text = new StringBuilder();
        if (Files.isDirectory(auditDirectory)) {
            try (Stream<Path> files = Files.walk(auditDirectory)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    text.append(Files.readString(file)).append('\n');
                }
            }
        }
        return text.toString();
    }

    private String logText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : List.copyOf(logs.list)) {
            if (!event.getLoggerName().startsWith("de.dadecker")) {
                continue;
            }
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        return text.toString();
    }

    /** The listed system diagrams and the real 8.1 adapter; every other port is an error. */
    private final class Gateways implements GatewayFactory {

        private final Map<NodeId, PartnerManagementPort> adapters = new HashMap<>();

        @Override
        public InventoryPort inventory(NodeId server) {
            return new InventoryPort() {
                @Override
                public List<InventoryItem> listDiagrams(String owner) {
                    return listed.getOrDefault(server, List.of()).stream()
                        .map(diagram -> InventoryItem.diagram(server, diagram.name(),
                            "systemdiagram", diagram.diagramGroup(), owner))
                        .toList();
                }

                @Override
                public DiagramDetail diagramDetail(String owner, String name) {
                    throw new AssertionError("not used");
                }

                @Override
                public DiagramMetadata diagramMetadata(String name) {
                    throw new AssertionError("not used");
                }

                @Override
                public VersionHistory versionHistory(String owner, String type, String group) {
                    throw new AssertionError("not used");
                }

                @Override
                public List<ModuleEntry> listModules(String owner) {
                    throw new AssertionError("not used");
                }
            };
        }

        @Override
        public synchronized PartnerManagementPort partners(NodeId server) {
            return adapters.computeIfAbsent(server, PartnerSecretLeakTest.this::adapter);
        }

        @Override
        public Gateway forServer(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public InubitVersion detect(NodeId server) {
            throw new AssertionError("the version gate detects through its own factory");
        }

        @Override
        public MonitoringPort monitoring(NodeId server) {
            throw new AssertionError("not used");
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
        public ProcessControlPort processControl(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public Optional<Gateway> knownGateway(NodeId server) {
            return Optional.empty();
        }
    }
}
