package de.dadecker.inubit.mcp.adapter.rest.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.CliOutcome;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.v81.ExportFixtures;
import de.dadecker.inubit.mcp.adapter.cli.v81.ModuleIndexParser;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Property;
import de.dadecker.inubit.mcp.domain.model.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.DiagramDetail;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.ModuleEntry;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T002 (feature 026, research D-11): the synthetic fixtures of the management check parse with the
 * existing parsers, and each REST module detail carries its planted secret, which the parser masks.
 */
class ManagementFixturesTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String OWNER = "OWNERS";
    private static final String XML = "application/xml;charset=UTF-8";

    /** The status and content type of {@code <case>.http} with the body {@code <case>.xml}. */
    private static RestResponse recorded(String fixtureCase) {
        int status = 0;
        Optional<String> contentType = Optional.empty();
        for (String line : RestFixtures.text(fixtureCase + ".http").split("\\R")) {
            if (line.startsWith("status:")) {
                status = Integer.parseInt(line.substring("status:".length()).strip());
            } else if (line.startsWith("content-type:")) {
                contentType = Optional.of(line.substring("content-type:".length()).strip());
            }
        }
        return new RestResponse(status, contentType, RestFixtures.bytes(fixtureCase + ".xml"));
    }

    private static ModuleDetail detail(String fixtureCase, String diagram, String nodeId,
        String plantedSecret) {
        RestResponse response = recorded(fixtureCase);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.contentType()).contains(XML);
        assertThat(response.bodyText()).as("the fixture carries its planted secret")
            .contains(plantedSecret);
        ModuleDetail detail = ModuleDetailXmlParser.parse(DEV, diagram, nodeId, response);
        assertThat(ResultJson.mapper().writeValueAsString(detail)).doesNotContain(plantedSecret);
        return detail;
    }

    @Test
    void theDiagramListHoldsOnlyTheSystemDiagramAndTheWorkflow() {
        RestResponse response = recorded("model-models-management");

        assertThat(response.status()).isEqualTo(200);
        assertThat(ModelListParser.parse(DEV, OWNER, response.body())).containsExactly(
            InventoryItem.diagram(DEV, "SD-HTTP", "systemdiagram", "GRP-CONNECT", OWNER),
            InventoryItem.diagram(DEV, "TW-Out", "technical", "GRP-CONNECT", OWNER));
    }

    @Test
    void theSystemDiagramHasTwoExternalSystemsAnInubitIsAndACommunicationCloud() {
        RestResponse response = recorded("model-by-name-systemdiagram");

        assertThat(response.status()).isEqualTo(200);
        DiagramDetail diagram = ModelDetailParser.parse(DEV, response.body());
        assertThat(diagram.name()).isEqualTo("SD-HTTP");
        assertThat(diagram.type()).contains("systemdiagram");
        assertThat(diagram.modules()).containsExactly(
            new ModuleRef("Central-Backend", "sdExtSystem", "201"),
            new ModuleRef("Second-Backend", "sdExtSystem", "202"),
            new ModuleRef("Demo-IS", "sdInubitIS", "203"),
            new ModuleRef("Demo-Cloud", "sdCommunicationCloud", "204"));
    }

    @Test
    void theWorkflowUsesTheManagedModule() {
        RestResponse response = recorded("model-by-name-tw-out");

        assertThat(response.status()).isEqualTo(200);
        DiagramDetail diagram = ModelDetailParser.parse(DEV, response.body());
        assertThat(diagram.name()).isEqualTo("TW-Out");
        assertThat(diagram.type()).contains("technical");
        assertThat(diagram.modules()).containsExactly(
            new ModuleRef("Demo-HTTP-Out-A", "twHTTPConnector", "301"));
    }

    @Test
    void theCentralElementManagesModulesAAndB() {
        ModuleDetail element = detail("admin-model-node-by-model-central-backend", "SD-HTTP",
            "201", "synthetic-secret-3916");

        assertThat(element.name()).isEqualTo("Central-Backend");
        assertThat(element.type()).isEqualTo("sdExtSystem");
        assertThat(element.properties()).containsExactly(
            Property.shown("ResourceType", "String", "HTTP Connector", false),
            Property.entries("ResourceSettings", "Map", List.of(
                Property.shown("ServerName", "String", "https://central.example.test", false),
                Property.shown("Login", "String", "", false),
                Property.masked("Password", "Password"),
                Property.masked("SSLKeyStore", "HTTPConnectorTrustStore"))),
            Property.entries("ResourcesLinked", "List", List.of(
                Property.shown("Module", "String", "Demo-HTTP-Out-A", false),
                Property.shown("Module", "String", "Demo-HTTP-Out-B", false))));
    }

    @Test
    void theSecondElementManagesBCAndAMissingModule() {
        ModuleDetail element = detail("admin-model-node-by-model-sd-ext-system-2", "SD-HTTP",
            "202", "synthetic-secret-3917");

        assertThat(element.name()).isEqualTo("Second-Backend");
        assertThat(element.type()).isEqualTo("sdExtSystem");
        assertThat(element.properties()).containsExactly(
            Property.shown("ResourceType", "String", "HTTP Connector", false),
            Property.entries("ResourceSettings", "Map", List.of(
                Property.shown("ServerName", "String", "https://second.example.test", false),
                Property.shown("Login", "String", "", false),
                Property.masked("Password", "Password"),
                Property.masked("SSLKeyStore", "HTTPConnectorTrustStore"))),
            Property.entries("ResourcesLinked", "List", List.of(
                Property.shown("Module", "String", "Demo-HTTP-Out-B", false),
                Property.shown("Module", "String", "Demo-HTTP-Out-C", false),
                Property.shown("Module", "String", "Demo-HTTP-Gone", false))));
    }

    @Test
    void theManagedModuleHasItsOwnLocalServerName() {
        ModuleDetail module = detail("admin-model-node-by-model-managed-module", "TW-Out", "301",
            "synthetic-secret-3918");

        assertThat(module.name()).isEqualTo("Demo-HTTP-Out-A");
        assertThat(module.type()).isEqualTo("twHTTPConnector");
        assertThat(module.properties()).contains(
            Property.shown("ServerName", "String", "http://localhost:8000/dummy", false),
            Property.masked("Password", "Password"));
    }

    @Test
    void theModuleExportHoldsAToDAndASecondCOfAnotherType() {
        List<ModuleEntry> modules = ModuleIndexParser.parse(DEV, OWNER,
            ExportFixtures.entry("export_modules_management.zip", "module/module.xml"));

        assertThat(modules).extracting(module -> module.item().name() + " / "
                + module.item().type() + " / " + module.item().group())
            .containsExactly(
                "Demo-HTTP-Out-A / HTTP Connector / HTTP Connector",
                "Demo-HTTP-Out-B / HTTP Connector / HTTP Connector",
                "Demo-HTTP-Out-C / HTTP Connector / HTTP Connector",
                "Demo-HTTP-Out-D / HTTP Connector / HTTP Connector",
                "Demo-HTTP-Out-C / FTP Connector / FTP Connector");
        assertThat(modules.getFirst().connectorWorkflow()).contains("TW-Out");
    }

    @Test
    void theModuleExportRunIsASuccess() {
        CliResult result = new CliResult(
            Integer.parseInt(FakeProcessLauncher.fixtureText("export_modules_management.exit")
                .strip()),
            FakeProcessLauncher.fixtureText("export_modules_management.stdout"),
            FakeProcessLauncher.fixtureText("export_modules_management.stderr"),
            Duration.ofSeconds(2), false, CliRunner.MAX_OUTPUT_BYTES);

        assertThat(new CliOutputClassifier(new SecretScrubber()).classify(result))
            .isInstanceOfSatisfying(CliOutcome.Success.class,
                success -> assertThat(success.output().okMessages()).hasSize(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rest/model-models-management", "rest/model-by-name-systemdiagram",
        "rest/model-by-name-tw-out", "rest/admin-model-node-by-model-central-backend",
        "rest/admin-model-node-by-model-sd-ext-system-2",
        "rest/admin-model-node-by-model-managed-module", "cli/export_modules_management"})
    void everyFixtureIsMarkedSyntheticAndNamesTheProbeOfItsShape(String fixtureCase) {
        String readme = new String(FakeProcessLauncher.resource(
            "v8_1/" + fixtureCase + ".README"), StandardCharsets.UTF_8);

        assertThat(readme).startsWith("SYNTHETIC –").contains("2026-11-27");
    }
}
