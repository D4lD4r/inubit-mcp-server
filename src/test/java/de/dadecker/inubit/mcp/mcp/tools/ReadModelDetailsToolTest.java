package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.toolError;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.ModelDetailService;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.LibraryEntry;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Connection;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Property;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ModelDetailPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.TestClientSelection;
import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T043 (feature 022, US3 AS2, AS3, contracts/mcp-tools-delta.md {@code read_model_details}):
 * {@code read_model_details} over MCP on the real {@link ModelDetailService} with a fake
 * {@link ModelDetailPort}. The action enum has exactly {@code module} and {@code libraries}: no
 * {@code moduleName}, {@code metadata} or {@code checkIsConnector} (owner decision 2026-11-14).
 */
@Timeout(60)
class ReadModelDetailsToolTest {

    /** The tool description, pinned word for word. */
    private static final String CONTRACT_DESCRIPTION = "Read the model details of one INUBIT"
        + " {node}. `target` takes the id of exactly one {node} (`<{group}>/<{node}>`). `action`"
        + " picks what to read: `module` reads the one module `moduleId` (the module's numeric id)"
        + " of the diagram `diagram` over REST, with its type, parent, outgoing connections and"
        + " properties in INUBIT's order; secret values are masked: a property whose name is a"
        + " secret name, whose type is `Password` or whose value is key material is shown as"
        + " `masked` and counted in `maskedCount`, and a credential inside a value (such as the"
        + " password of a database URL) is replaced by a placeholder; a `Map` or `List` property"
        + " (such as the connection settings and linked modules of a system diagram element)"
        + " lists its `entries`, each masked on its own. An unknown module id gives"
        + " NOT_FOUND on INUBIT 8.1 and UNEXPECTED_RESPONSE with a hint on 9.0. `libraries`"
        + " lists the uploaded library files with StartCLI, `libraryType`"
        + " `driver` or `plugin`, or both without it (one StartCLI run per type, drivers first),"
        + " paged with `offset` and `limit` (at most 100); `nextOffset` gives the next page. Until"
        + " a listed library is recorded, each line of the answer is read as one file name and"
        + " `rowFormatVerified` is false. Reads only; changes nothing on INUBIT.";

    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    private static final String DIAGRAM = "Workflow-0301";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String LIST = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        REST\tGET /model/nodeByModel/{model}/{node}\tREAD\tE\tUS3\treachable:read_model_details/module\tplanned\t-
        CLI\tlibrary --list\tREAD\tC\tUS3\treachable:read_model_details/libraries\tplanned\t-
        CLI\tlibrary --type\tREAD\tC\tUS3\treachable:read_model_details/libraries\tplanned\t-
        """;

    private final FakeModelDetails port = new FakeModelDetails();
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        NodeVersions versions = new NodeVersions(Clock.systemUTC(),
            Map.of(DEV1, VersionSetting.V8_1)::get, node -> Duration.ofMinutes(10));
        versions.clientProbed(DEV1, new ProbedClient(ClientKey.NONE, new ClientVersion(
            InubitVersion.parse("8.1.12").orElseThrow(), InubitVersion.parse("8.1.0"),
            InubitVersion.parse("8.1.99"))));
        VersionGate gate = new VersionGate(versions, new FakeVersionDetection(versions),
            everythingSupported(), (node, version, timeout) -> {
                throw new AssertionError("no client probe expected for " + node);
            }, TestClientSelection.of(DEV1::equals), node -> Duration.ZERO,
            Terminology.DEFAULT, false, InterfaceCoverage.load(new StringReader(LIST)));
        client = McpTestClient.start(List.of(new ReadModelDetailsTool(new ModelDetailService(
            new TargetResolver(List.of(DEV1)), gate, node -> port))), new SecretScrubber());
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    // --- announcement -------------------------------------------------------------------------

    @Test
    void theToolIsReadOnlyWithTheContractDescriptionAndSchema() {
        JsonNode tool = client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("read_model_details");
        assertThat(ReadModelDetailsTool.DESCRIPTION).isEqualTo(CONTRACT_DESCRIPTION);
        assertThat(tool.path("description").asString())
            .isEqualTo("[acme] " + Terminology.DEFAULT.render(CONTRACT_DESCRIPTION));
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString()).isEqualTo("Read INUBIT model details");
        assertThat(annotations.path("readOnlyHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean(false)).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactly("target", "action");
        assertThat(schema.path("properties").propertyNames()).containsExactly("target", "action",
            "diagram", "moduleId", "libraryType", "offset", "limit");
        assertThat(schema.path("properties").path("action").path("enum"))
            .extracting(JsonNode::asString)
            .as("no moduleName, metadata or checkIsConnector (owner decision 2026-11-14)")
            .containsExactly("module", "libraries");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("properties").path("limit").path("default").asInt())
            .isEqualTo(ModelDetailService.DEFAULT_LIMIT);
    }

    @Test
    void theDescriptionStatesTheContractPoints() {
        String description = ReadModelDetailsTool.DESCRIPTION.toLowerCase(Locale.ROOT);

        // AS2 masking and NOT_FOUND; AS3 and L-21 the unverified row format
        assertThat(description).contains("reads only", "changes nothing", "`password`",
            "secret name", "key material", "`maskedcount`", "not_found",
            "each line of the answer is read as one file name", "`rowformatverified` is false",
            "one startcli run per type");
    }

    // --- input schema -------------------------------------------------------------------------

    @Test
    void theSchemaRejectsMalformedCallsBeforeTheService() {
        List<Map<String, Object>> calls = List.of(
            Map.of(),
            Map.of("target", "dev/node1"),
            Map.of("action", "libraries"),
            Map.of("target", "dev", "action", "libraries"),
            Map.of("target", "dev/node1", "action", "metadata"),
            Map.of("target", "dev/node1", "action", "checkIsConnector"),
            Map.of("target", "dev/node1", "action", "module"),
            Map.of("target", "dev/node1", "action", "module", "diagram", DIAGRAM),
            Map.of("target", "dev/node1", "action", "module", "moduleId", "100"),
            Map.of("target", "dev/node1", "action", "module", "diagram", DIAGRAM,
                "moduleId", "1a"),
            Map.of("target", "dev/node1", "action", "module", "diagram", DIAGRAM,
                "moduleId", "12345678901"),
            Map.of("target", "dev/node1", "action", "module", "diagram", "../x",
                "moduleId", "100"),
            Map.of("target", "dev/node1", "action", "module", "diagram", DIAGRAM,
                "moduleName", "Demo-DB-Probe"),
            Map.of("target", "dev/node1", "action", "module", "diagram", DIAGRAM,
                "moduleId", "100", "libraryType", "driver"),
            Map.of("target", "dev/node1", "action", "module", "diagram", DIAGRAM,
                "moduleId", "100", "offset", 0),
            Map.of("target", "dev/node1", "action", "libraries", "diagram", DIAGRAM),
            Map.of("target", "dev/node1", "action", "libraries", "moduleId", "100"),
            Map.of("target", "dev/node1", "action", "libraries", "libraryType", "jar"),
            Map.of("target", "dev/node1", "action", "libraries", "offset", -1),
            Map.of("target", "dev/node1", "action", "libraries", "offset", 10_000),
            Map.of("target", "dev/node1", "action", "libraries", "limit", 0),
            Map.of("target", "dev/node1", "action", "libraries", "limit", 101),
            Map.of("target", "dev/node1", "action", "libraries", "unknown", true));

        for (Map<String, Object> arguments : calls) {
            JsonNode response = client.callTool("read_model_details", arguments);
            assertThat(response.path("isError").asBoolean()).as(arguments.toString()).isTrue();
            assertThat(response.has("structuredContent")).isFalse();
            assertThat(response.path("content").get(0).path("text").asString())
                .as("the schema, not the service's ToolError: %s", arguments)
                .doesNotContain("\"code\"");
        }
        assertThat(port.moduleCalls).as("the service was not called").isEmpty();
        assertThat(port.libraryCalls).isEmpty();
    }

    @Test
    void theLimitsOfTheSchemaAreInclusive() {
        String longest = "D".repeat(200);
        port.module = module(longest, "1234567890");
        port.listings.put("plugin", listing("plugin"));
        port.listings.put("driver", listing("driver"));
        List<Map<String, Object>> arguments = List.of(
            Map.of("target", "dev/node1", "action", "module", "diagram", longest,
                "moduleId", "1234567890"),
            Map.of("target", "dev/node1", "action", "libraries", "offset", 0, "limit", 1),
            Map.of("target", "dev/node1", "action", "libraries", "libraryType", "plugin",
                "offset", 9999, "limit", 100));

        for (Map<String, Object> call : arguments) {
            JsonNode response = client.callTool("read_model_details", call);
            assertThat(response.path("isError").asBoolean()).as(response.toString()).isFalse();
            assertMatchesOutputSchema("read_model_details", response);
        }
    }

    // --- module (AS2) -------------------------------------------------------------------------

    @Test
    void aModuleHasTheContractShapeWithItsMaskedProperties() {
        port.module = new ModuleDetail(DIAGRAM, "100", "Demo-DB-Probe", "twDatabaseConnector",
            Optional.of("7"), List.of(new Connection("22", "1")), List.of(
                Property.shown("dbURL", "String", "jdbc:postgresql://db.example.test/demo",
                    false),
                Property.shown("query", "String", "SELECT id" + ItemBounds.TRUNCATION_MARKER,
                    true),
                Property.masked("Password", "Password")), 1);

        JsonNode content = call(Map.of("target", "dev/node1", "action", "module",
            "diagram", DIAGRAM, "moduleId", "100"));

        assertThat(content.propertyNames()).containsExactly("node", "action", "source", "module",
            "warnings");
        assertThat(content).isEqualTo(json("""
            {"node": "dev/node1", "action": "module", "source": "REST",
             "module": {"diagram": "Workflow-0301", "moduleId": "100", "name": "Demo-DB-Probe",
               "type": "twDatabaseConnector", "parentId": "7",
               "connections": [{"id": "22", "targetId": "1"}],
               "properties": [
                 {"name": "dbURL", "type": "String",
                  "value": "jdbc:postgresql://db.example.test/demo"},
                 {"name": "query", "type": "String", "value": "%s", "truncated": true},
                 {"name": "Password", "type": "Password", "masked": true}],
               "maskedCount": 1},
             "warnings": []}""".formatted("SELECT id" + ItemBounds.TRUNCATION_MARKER)));
        assertThat(port.moduleCalls).containsExactly(DIAGRAM + "/100");
    }

    @Test
    void aMapOrListPropertyShowsItsEntriesInTheSchema() {
        port.module = new ModuleDetail("System-0001", "49", "Demo-ExtSystem", "sdExtSystem",
            Optional.empty(), List.of(), List.of(
                Property.entries("ResourceSettings", "Map", List.of(
                    Property.shown("ServerName", "String", "https://demo.example.test", false),
                    Property.masked("Password", "Password"))),
                Property.entries("ResourcesLinked", "List", List.of(
                    Property.shown("Module", "String", "Demo-HTTP-Out-A", false))),
                Property.masked("ProxyCredentials", "Map")), 2);

        JsonNode content = call(Map.of("target", "dev/node1", "action", "module",
            "diagram", "System-0001", "moduleId", "49"));

        assertThat(content.path("module").path("properties")).isEqualTo(json("""
            [{"name": "ResourceSettings", "type": "Map", "entries": [
               {"name": "ServerName", "type": "String", "value": "https://demo.example.test"},
               {"name": "Password", "type": "Password", "masked": true}]},
             {"name": "ResourcesLinked", "type": "List", "entries": [
               {"name": "Module", "type": "String", "value": "Demo-HTTP-Out-A"}]},
             {"name": "ProxyCredentials", "type": "Map", "masked": true}]"""));
        assertThat(content.path("module").path("maskedCount").asInt()).isEqualTo(2);
    }

    @Test
    void anUnknownModuleIdIsNotFoundForTheNode() {
        port.moduleFailure = new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
            "Module 999 was not found in diagram '" + DIAGRAM + "' on dev/node1",
            "INUBIT reports that the diagram has no module with this id",
            "Check the diagram name and the module id").withNode(DEV1));

        JsonNode error = toolError(client.callTool("read_model_details", Map.of(
            "target", "dev/node1", "action", "module", "diagram", DIAGRAM, "moduleId", "999")));

        assertThat(error.path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(error.path("node").asString()).isEqualTo("dev/node1");
    }

    // --- libraries (AS3, L-21) ----------------------------------------------------------------

    @Test
    void theLibrariesArePagedWithTheUnverifiedRowFormat() {
        port.listings.put("driver", listing("driver", "ojdbc11.jar"));
        port.listings.put("plugin", listing("plugin", "custom-plugin.jar", "other-plugin.jar"));

        JsonNode content = call(Map.of("target", "dev/node1", "action", "libraries",
            "limit", 2));

        assertThat(content).isEqualTo(json("""
            {"node": "dev/node1", "action": "libraries", "source": "CLI",
             "page": {"items": [{"file": "ojdbc11.jar", "type": "driver"},
                 {"file": "custom-plugin.jar", "type": "plugin"}],
               "offset": 0, "limit": 2, "total": 3, "totalIsLowerBound": false,
               "truncated": false, "nextOffset": 2},
             "rowFormatVerified": false,
             "warnings": []}"""));
        assertThat(port.libraryCalls).containsExactly("driver", "plugin");
    }

    @Test
    void oneLibraryTypeIsOneRun() {
        port.listings.put("plugin", listing("plugin", "custom-plugin.jar"));

        JsonNode content = call(Map.of("target", "dev/node1", "action", "libraries",
            "libraryType", "plugin"));

        assertThat(content.path("page").path("items").toString())
            .isEqualTo("[{\"file\":\"custom-plugin.jar\",\"type\":\"plugin\"}]");
        assertThat(port.libraryCalls).containsExactly("plugin");
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Calls the tool; the result validates against the output schema. */
    private JsonNode call(Map<String, Object> arguments) {
        JsonNode response = client.callTool("read_model_details", arguments);
        assertThat(response.path("isError").asBoolean()).as(response.toString()).isFalse();
        assertMatchesOutputSchema("read_model_details", response);
        return response.path("structuredContent");
    }

    private static ModuleDetail module(String diagram, String moduleId) {
        return new ModuleDetail(diagram, moduleId, "Demo-DB-Probe", "twDatabaseConnector",
            Optional.empty(), List.of(), List.of(), 0);
    }

    private static LibraryEntry.Listing listing(String type, String... files) {
        return new LibraryEntry.Listing(IntStream.range(0, files.length)
            .mapToObj(i -> new LibraryEntry(files[i], type)).toList(), false);
    }

    private static CapabilityMatrix everythingSupported() {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, SupportState.SUPPORTED);
        }
        return new CapabilityMatrix(List.of(), states);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    /** The model details of dev/node1, recording what was asked. */
    private static final class FakeModelDetails implements ModelDetailPort {

        volatile ModuleDetail module;
        volatile ToolErrorException moduleFailure;
        final Map<String, LibraryEntry.Listing> listings = new HashMap<>();
        final List<String> moduleCalls = new CopyOnWriteArrayList<>();
        final List<String> libraryCalls = new CopyOnWriteArrayList<>();

        @Override
        public ModuleDetail module(String diagram, String moduleId) {
            moduleCalls.add(diagram + "/" + moduleId);
            if (moduleFailure != null) {
                throw moduleFailure;
            }
            return module;
        }

        @Override
        public LibraryEntry.Listing libraries(String type) {
            libraryCalls.add(type);
            return listings.get(type);
        }
    }
}
