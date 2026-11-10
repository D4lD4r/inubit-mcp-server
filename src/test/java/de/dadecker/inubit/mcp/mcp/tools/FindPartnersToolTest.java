package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.toolError;
import static de.dadecker.inubit.mcp.testing.FakeVersionDetection.gateWithoutClients;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.CloudXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.DiagramXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.FieldXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.Leaf;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.PartnerXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.TwfXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerManagementReader;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree;
import de.dadecker.inubit.mcp.application.FanOut;
import de.dadecker.inubit.mcp.application.InventoryCache;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.PartnerAudit;
import de.dadecker.inubit.mcp.application.PartnerService;
import de.dadecker.inubit.mcp.application.PartnerService.Found;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.application.SecretTiming;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 018 T022: {@code find_partners} over MCP (contracts/find_partners.md, single-server
 * shape) on the real {@link PartnerService} with fake ports serving the synthetic exports of
 * {@link PartnerFixtures} and a recording audit log: hints and schemas, the defaults of the tool,
 * the unresolved workflow shape, Op-2, Op-5 and the audited input refusals (FR-002, FR-020);
 * T023: the list summary and the entry filters; T024: the size bound, measured on the result
 * the tool returns as it is (phase 4 code review).
 */
@Timeout(60)
class FindPartnersToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final NodeId NODE1 = NodeId.parse("dev/node1");
    private static final NodeId NODE2 = NodeId.parse("dev/node2");
    private static final Instant T0 = Instant.parse("2026-11-10T08:00:00Z");
    private static final String OWNER = PartnerFixtures.OWNER;
    private static final String GROUP = PartnerFixtures.GROUP;

    private final MutableClock clock = new MutableClock(T0);
    private final List<AuditRecord> audit = new CopyOnWriteArrayList<>();
    /** One entry per call of the partner port: the server read. */
    private final List<NodeId> portReads = new CopyOnWriteArrayList<>();
    /** The reader's result per system diagram of the owner, in list order. */
    private final Map<DiagramRef, PartnerManagementReader.Result> exports = new LinkedHashMap<>();
    private final Map<NodeId, Optional<String>> owners =
        new HashMap<>(Map.of(NODE1, Optional.of(OWNER), NODE2, Optional.of(OWNER)));
    private final PartnerAudit.Dependencies audits =
        new PartnerAudit.Dependencies(audit::add, clock, "acme", UUID::randomUUID);
    /** {@code resultLimits} of the profile; a test may set smaller ones before its first call. */
    private ResultLimiter limiter = ResultLimiter.withDefaults();
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        export(PartnerFixtures.detail());
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    // --- announcement --------------------------------------------------------------------------

    @Test
    void theToolIsReadOnlyWithTheContractSchemaAndADescriptionWithoutCustomerKnowledge() {
        JsonNode tool = client().listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("find_partners");
        assertThat(tool.path("description").asString()).isEqualTo("[acme] "
            + FindPartnersTool.DESCRIPTION.replace("{group}", "group").replace("{node}", "node"));
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString())
            .isEqualTo("Find partners in the INUBIT Partner Management");
        assertThat(annotations.path("readOnlyHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean(false)).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactly("target");
        assertThat(schema.path("properties").propertyNames()).containsExactly("target",
            "compareWith", "owner", "search", "systemDiagram", "protocol", "workflow", "offset",
            "limit", "refresh");
        assertThat(schema.path("properties").path("owner").path("pattern").asString())
            .as("the owner rule of export_archives")
            .isEqualTo("^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(FindPartnersTool.DESCRIPTION).doesNotContainIgnoringCase("fallback")
            .doesNotContain(OWNER, GROUP, "ACME", "example.test");
    }

    // --- US1 over MCP --------------------------------------------------------------------------

    @Test
    void aSearchShowsThePartnerInTheContractShapeWithoutAnySecret() {
        JsonNode result = call(Map.of("target", "dev/node1", "search", "acme-0003"));

        assertMatchesOutputSchema("find_partners", result);
        JsonNode content = result.path("structuredContent");
        assertThat(audit).hasSize(1);
        assertThat(content.path("callId").asString())
            .isEqualTo(audit.get(0).callId().orElseThrow().toString());
        assertThat(content.path("mode").asString()).isEqualTo("search");
        assertThat(content.path("compared").asBoolean(true)).isFalse();
        JsonNode server = content.path("servers").get(0);
        assertThat(server.path("node").asString()).isEqualTo("dev/node1");
        assertThat(server.path("group").asString()).isEqualTo("dev");
        assertThat(server.path("owner").asString()).isEqualTo(OWNER);
        assertThat(server.path("collectedAt").asString()).isEqualTo(T0.toString());
        assertThat(server.path("fromCache").asBoolean(true)).isFalse();
        assertThat(server.path("systemDiagramsRead").get(0).path("clouds").get(0))
            .isEqualTo(JSON.readTree("{\"cloud\":\"Module-0901\",\"partners\":3}"));
        assertThat(server.has("error")).isFalse();
        JsonNode partner = content.path("partners").get(0);
        assertThat(partner.path("systemDiagram")).isEqualTo(JSON.readTree(
            "{\"name\":\"SystemDiagram-01\",\"diagramGroup\":\"" + GROUP + "\"}"));
        assertThat(partner.path("cloud").asString()).isEqualTo("Module-0901");
        assertThat(partner.has("moduleId")).isFalse();
        assertThat(partner.path("fields").get(0))
            .isEqualTo(JSON.readTree("{\"name\":\"Id\",\"value\":\"ACME-0003\"}"));
        JsonNode resolved = partner.path("entries").get(0);
        assertThat(resolved.path("workflow"))
            .isEqualTo(JSON.readTree("{\"name\":\"Workflow-0003\"}"));
        assertThat(resolved.path("protocolSettings")).contains(
            JSON.readTree("{\"name\":\"Password\",\"masked\":true,\"set\":true}"));
        assertThat(content.path("referencableWorkflows")).containsExactly(JSON.readTree("""
            {"systemDiagram":{"name":"SystemDiagram-01","diagramGroup":"%s"},
             "cloud":"Module-0901",
             "workflows":["Workflow-0001","Workflow-0002","Workflow-0003"]}""".formatted(GROUP)));
        assertThat(content.path("total").asInt()).isEqualTo(1);
        assertThat(content.path("truncated").asBoolean(true)).isFalse();
        assertThat(content.has("similarValues")).isFalse();
        assertNoSecretIn(result);
    }

    @Test
    void anUnresolvedWorkflowIsWrittenWithAnExplicitNullName() {
        JsonNode result = call(Map.of("target", "dev/node1", "search", "acme-0003"));

        JsonNode expected = JSON.readTree("{\"name\":null,\"unresolved\":true}");
        JsonNode unresolved = result.path("structuredContent").path("partners").get(0)
            .path("entries").get(1).path("workflow");
        assertThat(unresolved).isEqualTo(expected);
        assertThat(unresolved.path("name").isNull()).isTrue();
        assertThat(JSON.readTree(result.path("content").get(0).path("text").asString())
            .path("partners").get(0).path("entries").get(1).path("workflow"))
            .as("the text block carries the same shape").isEqualTo(expected);
        assertThat(result.path("content").get(0).path("text").asString())
            .contains("\"workflow\":{\"name\":null,\"unresolved\":true}");
    }

    @Test
    void omittedOffsetLimitAndRefreshGetTheToolDefaults() {
        Map<String, Object> arguments = Map.of("target", "dev/node1", "search", "acme");

        JsonNode first = call(arguments).path("structuredContent");
        JsonNode second = call(arguments).path("structuredContent");

        assertThat(first.path("offset").asInt(-1)).isZero();
        assertThat(first.path("limit").asInt()).isEqualTo(20);
        assertThat(first.path("servers").get(0).path("fromCache").asBoolean(true)).isFalse();
        assertThat(second.path("servers").get(0).path("fromCache").asBoolean(false))
            .as("refresh defaults to false: the second call is served from the cache").isTrue();
        assertThat(portReads).containsExactly(NODE1);

        JsonNode refreshed = call(Map.of("target", "dev/node1", "search", "acme",
            "refresh", true)).path("structuredContent");
        assertThat(refreshed.path("servers").get(0).path("fromCache").asBoolean(true)).isFalse();
        assertThat(portReads).containsExactly(NODE1, NODE1);
    }

    @Test
    void givenOffsetAndLimitReachTheQuery() {
        JsonNode content = call(Map.of("target", "dev/node1", "offset", 5, "limit", 7))
            .path("structuredContent");

        assertThat(content.path("offset").asInt()).isEqualTo(5);
        assertThat(content.path("limit").asInt()).isEqualTo(7);
        assertThat(content.path("partners")).as("an offset beyond the matches").isEmpty();
        assertThat(content.path("total").asInt()).isEqualTo(3);
        assertThat(content.has("nextOffset")).isFalse();
    }

    @Test
    void offsetAndLimitSelectThePageWithTotalAndNextOffset() {
        JsonNode first = call(Map.of("target", "dev/node1", "limit", 2));
        JsonNode last = call(Map.of("target", "dev/node1", "offset", 2, "limit", 2));

        assertMatchesOutputSchema("find_partners", first);
        assertMatchesOutputSchema("find_partners", last);
        JsonNode content = first.path("structuredContent");
        assertThat(content.path("partners")).extracting(FindPartnersToolTest::id)
            .containsExactly("ACME-0001", "ACME-0002");
        assertThat(content.path("total").asInt()).isEqualTo(3);
        assertThat(content.path("nextOffset").asInt()).isEqualTo(2);
        assertThat(content.path("truncated").asBoolean(true)).isFalse();
        content = last.path("structuredContent");
        assertThat(content.path("partners")).extracting(FindPartnersToolTest::id)
            .containsExactly("ACME-0003");
        assertThat(content.path("total").asInt()).isEqualTo(3);
        assertThat(content.has("nextOffset")).isFalse();
        assertThat(audit).extracting(record -> record.details().get("partnersReturned"))
            .containsExactly(2, 1);
    }

    @Test
    void theLimitEchoedIsThePageSizeAppliedAtMostMaxItems() {
        limiter = new ResultLimiter(5, 50_000);
        export(PartnerFixtures.list(20));

        JsonNode content = call(Map.of("target", "dev/node1", "limit", 20))
            .path("structuredContent");

        assertThat(content.path("limit").asInt()).isEqualTo(5);
        assertThat(content.path("partners")).hasSize(5);
        assertThat(content.path("nextOffset").asInt()).isEqualTo(5);
        assertThat(content.path("truncated").asBoolean(false)).isTrue();
    }

    @Test
    void theResultStaysWithinMaxCharsAndReportsTheCut() {
        limiter = new ResultLimiter(100, 10_000);
        export(PartnerFixtures.list(1000));

        JsonNode result = call(Map.of("target", "dev/node1", "search", "acme", "limit", 100));

        assertMatchesOutputSchema("find_partners", result);
        JsonNode content = result.path("structuredContent");
        assertThat(content.toString().length()).isLessThanOrEqualTo(10_000);
        assertThat(content.path("truncated").asBoolean(false)).isTrue();
        assertThat(content.path("total").asInt()).isEqualTo(1003);
        assertThat(content.path("partners").size()).isPositive().isLessThan(100);
        assertThat(content.path("nextOffset").asInt())
            .isEqualTo(content.path("partners").size());
        assertThat(audit).singleElement().satisfies(record -> assertThat(record.details())
            .containsEntry("partnersReturned", content.path("partners").size()));
        assertNoSecretIn(result);
    }

    @Test
    void theToolReturnsWhatTheBoundMeasuredWithinMaxCharsAlsoAtTheLargestOffset() {
        limiter = new ResultLimiter(100, 10_000);
        export(PartnerFixtures.list(1000));
        List<String> fields = Stream.of(Found.class.getRecordComponents())
            .map(RecordComponent::getName).toList();
        FindPartnersTool tool = new FindPartnersTool(service(FakeVersionDetection.pinned81()),
            audits);

        for (Map<String, Object> arguments : List.of(
            Map.<String, Object>of("target", "dev/node1", "search", "acme", "limit", 100),
            Map.<String, Object>of("target", "dev/node1", "limit", 100),
            Map.<String, Object>of("target", "dev/node1", "offset", 999, "limit", 100),
            Map.<String, Object>of("target", "dev/node1", "search", "acme", "offset",
                Integer.MAX_VALUE, "limit", 100))) {
            String what = arguments.toString();
            assertThat(tool.handle(arguments)).as(what + ": the service's result as it is")
                .isInstanceOfSatisfying(Found.class, found ->
                    assertThat(ResultJson.size(found)).as(what).isLessThanOrEqualTo(10_000));
            JsonNode content = call(arguments).path("structuredContent");
            assertThat(content.toString().length()).as(what).isLessThanOrEqualTo(10_000);
            assertThat(content.propertyNames()).as(what + ": no field outside the bound")
                .isSubsetOf(fields);
        }
    }

    @Test
    void withoutASearchTermTheModeIsListAndEveryPartnerIsASummary() {
        JsonNode result = call(Map.of("target", "dev/node1"));

        assertMatchesOutputSchema("find_partners", result);
        JsonNode content = result.path("structuredContent");
        assertThat(content.path("mode").asString()).isEqualTo("list");
        assertThat(content.path("partners")).hasSize(3);
        assertThat(content.path("total").asInt()).isEqualTo(3);
        JsonNode partner = content.path("partners").get(2);
        assertThat(partner.path("fields")).hasSize(2);
        assertThat(partner.path("maskedCount").asInt()).isEqualTo(6);
        assertThat(partner.path("entries").get(0)).isEqualTo(JSON.readTree("""
            {"protocol":"WebService","workflow":{"name":"Workflow-0003"},
             "protocolSettings":[{"name":"ServerName","value":"http://host3.example.test/path1"},
                                 {"name":"Login","value":"login3"}]}"""));
        assertNoSecretIn(result);
    }

    @Test
    void protocolAndWorkflowFilterTheEntriesInBothModes() {
        JsonNode listed = call(Map.of("target", "dev/node1", "protocol", "ftp"));
        JsonNode searched = call(Map.of("target", "dev/node1", "search", "acme",
            "workflow", "WORKFLOW-0003"));

        assertMatchesOutputSchema("find_partners", listed);
        assertMatchesOutputSchema("find_partners", searched);
        JsonNode content = listed.path("structuredContent");
        assertThat(content.path("partners")).extracting(FindPartnersToolTest::id)
            .containsExactly("ACME-0001", "ACME-0002");
        assertThat(content.path("partners").get(1).path("entries"))
            .extracting(entry -> entry.path("protocol").asString()).containsExactly("FTP");
        content = searched.path("structuredContent");
        assertThat(content.path("mode").asString()).isEqualTo("search");
        assertThat(content.path("partners")).extracting(FindPartnersToolTest::id)
            .containsExactly("ACME-0002", "ACME-0003");
        assertThat(content.path("partners").get(1).path("entries")).singleElement()
            .satisfies(entry -> assertThat(entry.has("additionalParameters")).isTrue());
        assertThat(audit).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.EXECUTED, AuditOutcome.EXECUTED);
        assertThat(audit.get(0).inputs()).containsEntry("protocol", "ftp");
        assertThat(audit.get(1).inputs()).containsEntry("workflow", "WORKFLOW-0003");
        assertNoSecretIn(listed);
        assertNoSecretIn(searched);
    }

    @Test
    void aSearchWithoutHitsListsSimilarValuesAndTheSystemDiagramsRead() {
        JsonNode result = call(Map.of("target", "dev/node1", "search", "acme-0004"));

        assertMatchesOutputSchema("find_partners", result);
        JsonNode content = result.path("structuredContent");
        assertThat(content.path("partners")).isEmpty();
        assertThat(content.path("similarValues")).extracting(JsonNode::asString)
            .contains("ACME-0001").allMatch(value -> value.startsWith("ACME"));
        assertThat(content.path("servers").get(0).path("systemDiagramsRead"))
            .extracting(diagram -> diagram.path("name").asString())
            .containsExactly("SystemDiagram-01");
        assertNoSecretIn(result);
    }

    @Test
    void cloudsOfTheSameNameCarryTheirModuleIdAlsoInTheReferencableWorkflows() {
        exports.clear();
        List<FieldXml> fields = List.of(new FieldXml("Id", "String"));
        List<TwfXml> workflows = List.of(new TwfXml("1", "34", "Workflow-0001"));
        PartnerXml partner = new PartnerXml(List.of(Leaf.plain("Id", "ACME-0001")), List.of());
        export(new DiagramXml(GROUP, "SystemDiagram-01", OWNER, List.of(
            new CloudXml("33", "Module-0901", fields, workflows, List.of(partner)),
            new CloudXml("213", "Module-0901", fields, workflows, List.of(partner)))));

        JsonNode result = call(Map.of("target", "dev/node1", "search", "acme"));

        assertMatchesOutputSchema("find_partners", result);
        JsonNode content = result.path("structuredContent");
        assertThat(content.path("referencableWorkflows"))
            .extracting(cloud -> cloud.path("moduleId").asString()).containsExactly("33", "213");
        assertThat(content.path("partners"))
            .extracting(cloud -> cloud.path("moduleId").asString()).containsExactly("33", "213");
    }

    // --- Op-2, Op-5 ----------------------------------------------------------------------------

    @Test
    void op2Version61IsRefusedForItsNodeAnd90IsAnsweredWithAWarning() {
        NodeVersions versions = new NodeVersions(clock, Map.<NodeId, VersionSetting>of(
            NODE1, VersionSetting.V6_1, NODE2, VersionSetting.V9_0)::get,
            node -> Duration.ofMinutes(10));
        client = client(gateWithoutClients(versions, new FakeVersionDetection(versions),
            CapabilityMatrix.production()));

        JsonNode result = call(Map.of("target", "dev", "search", "acme-0001"));

        assertMatchesOutputSchema("find_partners", result);
        JsonNode servers = result.path("structuredContent").path("servers");
        assertThat(servers.get(0).path("error").path("code").asString())
            .isEqualTo("UNSUPPORTED_VERSION");
        assertThat(servers.get(1).has("error")).isFalse();
        assertThat(servers.get(1).path("warnings")).extracting(JsonNode::asString)
            .anySatisfy(warning -> assertThat(warning)
                .contains("dev/node2", "not verified for partner management"));
        assertThat(result.path("structuredContent").path("partners")).hasSize(1);
        assertThat(portReads).containsExactly(NODE2);
    }

    @Test
    void op5AnUnknownSystemDiagramIsNotFoundWithSimilarNames() {
        JsonNode error = toolError(call(Map.of("target", "dev/node1",
            "systemDiagram", "SystemDiagram-1")));

        assertThat(error.path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(error.path("nextStep").asString()).contains("SystemDiagram-01");
    }

    @Test
    void op5ANodeWithoutOwnerFailsAloneWithNotConfigured() {
        owners.put(NODE2, Optional.empty());

        JsonNode result = call(Map.of("target", "dev", "search", "acme-0001"));

        assertMatchesOutputSchema("find_partners", result);
        JsonNode servers = result.path("structuredContent").path("servers");
        assertThat(servers.get(0).has("error")).isFalse();
        assertThat(servers.get(1).path("error").path("code").asString())
            .isEqualTo("NOT_CONFIGURED");
        assertThat(result.path("structuredContent").path("partners")).hasSize(1);
        assertThat(audit).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.EXECUTED, AuditOutcome.REFUSED);
    }

    // --- input refusals (FR-002, FR-020) -------------------------------------------------------

    @Test
    void aCompareWithThatOverlapsTheTargetIsAnAuditedInvalidInputWithoutAnyRead() {
        JsonNode error = toolError(call(Map.of("target", "dev", "compareWith", "dev/node1",
            "search", "acme")));

        assertThat(error.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(audit).hasSize(1);
        AuditRecord refused = audit.get(0);
        assertThat(refused.outcome()).isEqualTo(AuditOutcome.REFUSED);
        assertThat(refused.capability()).isEqualTo("find_partners");
        assertThat(refused.node()).as("the target as requested").isEqualTo("dev");
        assertThat(refused.group()).contains("dev");
        assertThat(refused.inputs()).containsEntry("target", "dev")
            .containsEntry("compareWith", "dev/node1").containsEntry("search", "acme");
        assertThat(refused.reason()).hasValueSatisfying(reason ->
            assertThat(reason).startsWith("INVALID_INPUT"));
        assertThat(refused.mcpClient()).contains("mcp-test-client/1.0");
        assertThat(portReads).isEmpty();
    }

    @Test
    void aMissingOrBlankTargetIsAnAuditedInvalidInput() {
        FindPartnersTool tool = new FindPartnersTool(service(FakeVersionDetection.pinned81()),
            audits);

        ToolErrorException missing = catchThrowableOfType(ToolErrorException.class,
            () -> tool.handle(Map.of("search", "acme")));
        ToolErrorException blank = catchThrowableOfType(ToolErrorException.class,
            () -> tool.handle(Map.of("target", " ")));

        assertThat(missing.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(blank.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(audit).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.REFUSED, AuditOutcome.REFUSED);
        assertThat(audit).extracting(AuditRecord::node).containsExactly("", " ");
        assertThat(audit.get(0).inputs()).doesNotContainKey("target")
            .containsEntry("search", "acme");
        assertThat(portReads).isEmpty();
    }

    @Test
    void theSchemaRejectsMalformedCallsBeforeTheToolWithoutAudit() {
        List<Map<String, Object>> calls = List.of(
            Map.of(),
            Map.of("target", "Dev"),
            Map.of("target", "dev", "compareWith", "prod/"),
            Map.of("target", "dev", "owner", "-jdoe"),
            Map.of("target", "dev", "search", ""),
            Map.of("target", "dev", "search", "s".repeat(201)),
            Map.of("target", "dev", "search", "acme\u0001"),
            Map.of("target", "dev", "systemDiagram", "SystemDiagram\n01"),
            Map.of("target", "dev", "protocol", "SFTP v2"),
            Map.of("target", "dev", "workflow", "W".repeat(201)),
            Map.of("target", "dev", "offset", -1),
            Map.of("target", "dev", "limit", 0),
            Map.of("target", "dev", "limit", 101),
            Map.of("target", "dev", "refresh", "yes"),
            Map.of("target", "dev", "reason", "not a parameter"));

        for (Map<String, Object> arguments : calls) {
            JsonNode result = call(arguments);
            assertThat(result.path("isError").asBoolean()).as(arguments.toString()).isTrue();
        }
        assertThat(audit).as("schema violations are not audited").isEmpty();
        assertThat(portReads).isEmpty();
    }

    @Test
    void theLimitsOfTheSchemaAreInclusive() {
        JsonNode result = call(Map.of("target", "dev/node1", "search", "s".repeat(200),
            "limit", 100));
        JsonNode longestProtocol = call(Map.of("target", "dev/node1",
            "protocol", "P".repeat(64)));

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertThat(longestProtocol.path("isError").asBoolean()).as(longestProtocol.toString())
            .isFalse();
        assertThat(longestProtocol.path("structuredContent").path("partners"))
            .as("no entry has this protocol: an empty result, not NOT_FOUND").isEmpty();
    }

    /** Interim (phase 3 review): T026 removes this refusal and this test. */
    @Test
    void compareWithIsAuditedAsNotSupportedYetWithoutAnyRead() {
        JsonNode error = toolError(call(Map.of("target", "dev/node1", "compareWith",
            "dev/node2")));

        assertThat(error.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(error.path("message").asString()).isEqualTo("compareWith is not supported yet");
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.node()).isEqualTo("dev/node1");
            assertThat(record.reason()).hasValueSatisfying(reason ->
                assertThat(reason).startsWith("INVALID_INPUT: "));
            assertThat(record.inputs().keySet()).containsExactly("target", "compareWith");
        });
        assertThat(portReads).isEmpty();
    }

    // --- support -------------------------------------------------------------------------------

    private JsonNode call(Map<String, Object> arguments) {
        if (client == null) {
            client = client(FakeVersionDetection.pinned81());
        }
        return client.callTool("find_partners", arguments);
    }

    private McpTestClient client() {
        client = client(FakeVersionDetection.pinned81());
        return client;
    }

    private McpTestClient client(VersionGate gate) {
        McpTestClient started = McpTestClient.start(
            List.of(new FindPartnersTool(service(gate), audits)));
        started.initialize();
        return started;
    }

    private PartnerService service(VersionGate gate) {
        return new PartnerService(new Gateways(), new TargetResolver(List.of(NODE1, NODE2)),
            new FanOut(), limiter, new InventoryCache(clock, node -> Duration.ofMinutes(10)),
            owners::get, node -> Duration.ofSeconds(5), node -> Duration.ofSeconds(120),
            ResultJson::size, Terminology.DEFAULT, gate, SecretTiming.NONE, audits);
    }

    /** Reads {@code diagram} with the reader as the adapter does and offers it to the owner. */
    private void export(DiagramXml diagram) {
        DiagramRef ref = new DiagramRef(diagram.name(), diagram.group());
        exports.put(ref, PartnerManagementReader.read(ref,
            XmlTree.parse(PartnerFixtures.workflowXml(diagram)).root()));
    }

    private static String id(JsonNode partner) {
        return partner.path("fields").get(0).path("value").asString();
    }

    /** No synthetic secret, not even its first 12 characters, in either channel. */
    private static void assertNoSecretIn(JsonNode result) {
        String text = result.toString();
        for (String secret : PartnerFixtures.SECRETS) {
            assertThat(text).doesNotContain(secret).doesNotContain(secret.substring(0, 12));
        }
    }

    /** Only the inventory list and the partner port; every other port is an assertion error. */
    private final class Gateways implements GatewayFactory {

        @Override
        public InventoryPort inventory(NodeId server) {
            return new InventoryPort() {
                @Override
                public List<InventoryItem> listDiagrams(String owner) {
                    return exports.keySet().stream()
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
        public PartnerManagementPort partners(NodeId server) {
            return (owner, diagrams) -> {
                portReads.add(server);
                return new PartnerManagementPort.Result(
                    diagrams.stream().map(diagram -> exports.get(diagram).partners()).toList(),
                    List.of(), diagrams.stream().collect(Collectors.toMap(diagram -> diagram,
                        diagram -> exports.get(diagram).warnings())));
            };
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
