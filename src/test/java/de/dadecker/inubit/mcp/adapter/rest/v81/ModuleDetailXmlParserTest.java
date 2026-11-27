package de.dadecker.inubit.mcp.adapter.rest.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Connection;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Property;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.testing.V90Cases;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T040 (feature 022, US3): the module detail of {@code GET /model/nodeByModel/{d}/{id}} against
 * the 8.1.17 recordings of T039 ({@code admin-model-node-by-model*}, with two planted test secrets,
 * fixtures/v8_1/cli/README.md) and short documents for what the recordings do not hold.
 */
class ModuleDetailXmlParserTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String DIAGRAM = "Workflow-0301";
    /** The planted secrets of {@code admin-model-node-by-model.xml}. */
    private static final String[] PLANTED = {"synthetic-secret-3901", "synthetic-secret-3902"};
    private static final String XML = "application/xml;charset=UTF-8";
    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<ns4:Node name=\"Probe\" type=\"twDatabaseConnector\" id=\"7\""
        + " xmlns:ns2=\"inubit.com/ibis/external/base\""
        + " xmlns:ns4=\"inubit.com/ibis/external/model\"><ns2:Properties>";
    private static final String TAIL = "</ns2:Properties></ns4:Node>";

    /** The recorded answer: status and content type of {@code <case>.http}, the body. */
    private static RestResponse recorded(String fixtureCase, String extension) {
        int status = 0;
        Optional<String> contentType = Optional.empty();
        for (String line : RestFixtures.text(fixtureCase + ".http").split("\\R")) {
            if (line.startsWith("status:")) {
                status = Integer.parseInt(line.substring("status:".length()).strip());
            } else if (line.startsWith("content-type:")) {
                contentType = Optional.of(line.substring("content-type:".length()).strip());
            }
        }
        return new RestResponse(status, contentType,
            RestFixtures.bytes(fixtureCase + "." + extension));
    }

    private static RestResponse answer(int status, String contentType, String body) {
        return new RestResponse(status, Optional.of(contentType),
            body.getBytes(StandardCharsets.UTF_8));
    }

    private static ModuleDetail properties(String properties) {
        return ModuleDetailXmlParser.parse(DEV, DIAGRAM, "7", answer(200, XML,
            HEAD + properties + TAIL));
    }

    private static ToolError unexpected(String moduleId, RestResponse response) {
        ToolError[] error = new ToolError[1];
        assertThatThrownBy(() -> ModuleDetailXmlParser.parse(DEV, DIAGRAM, moduleId, response))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().excerpt()).isPresent();
                error[0] = e.error();
            });
        return error[0];
    }

    @Test
    void theRecordedDatabaseConnectorModuleIsReadInInubitsOrderWithThePlantedSecretsMasked() {
        ModuleDetail detail = ModuleDetailXmlParser.parse(DEV, DIAGRAM, "100",
            recorded("admin-model-node-by-model", "xml"));

        assertThat(detail.diagram()).isEqualTo(DIAGRAM);
        assertThat(detail.moduleId()).isEqualTo("100");
        assertThat(detail.name()).isEqualTo("Demo-DB-Probe");
        assertThat(detail.type()).isEqualTo("twDatabaseConnector");
        assertThat(detail.parentId()).isEmpty();
        assertThat(detail.connections()).containsExactly(new Connection("22", "1"));
        assertThat(detail.properties()).hasSize(38);
        assertThat(detail.properties().subList(0, 3)).containsExactly(
            Property.shown("SCHEDULER", "Boolean", "false", false),
            Property.shown("InputConnector", "Boolean", "true", false),
            Property.shown("OutputConnector", "Boolean", "true", false));
        assertThat(detail.properties()).contains(
            Property.masked("Password", "Password"),
            Property.masked("proxyPassword", "String"),
            Property.shown("dbURL", "String",
                "jdbc:postgresql://inubit-db-01.example.test:5432/demodb", false),
            Property.shown("user", "String", "demo_reader", false),
            Property.shown("staticQuery", "String", "select version from demo_versions", false));
        assertThat(detail.properties().getLast())
            .isEqualTo(Property.shown("xsdTypesOutput", "Boolean", "false", false));
        assertThat(detail.maskedCount()).isEqualTo(2);
    }

    @Test
    void neitherPlantedSecretLeaksIntoTheResult() {
        ModuleDetail detail = ModuleDetailXmlParser.parse(DEV, DIAGRAM, "100",
            recorded("admin-model-node-by-model", "xml"));

        assertThat(ResultJson.mapper().writeValueAsString(detail)).doesNotContain(PLANTED);
        assertThat(detail.toString()).doesNotContain(PLANTED);
    }

    @Test
    void theRecordedIsConnectorModuleHasAParentId() {
        ModuleDetail detail = ModuleDetailXmlParser.parse(DEV, DIAGRAM, "34",
            recorded("admin-model-node-by-model-is-connector", "xml"));

        assertThat(detail.name()).isEqualTo("Demo-IS-Probe");
        assertThat(detail.type()).isEqualTo("twinubitISConfiguration");
        assertThat(detail.parentId()).contains("66");
        assertThat(detail.connections()).containsExactly(new Connection("146", "36"));
        assertThat(detail.properties())
            .containsExactly(Property.shown("ErrorSuppression", "Boolean", "false", false));
        assertThat(detail.maskedCount()).isZero();
    }

    /**
     * The recording answered Accept JSON with the same {@code application/xml} body as Accept XML
     * (research "Live recording T039"); the parser reads the body as XML whatever was asked for.
     */
    @Test
    void anAcceptJsonRequestAnsweredWithXmlIsReadAsXml() {
        RestResponse answerToJson = recorded("admin-model-node-by-model", "xml");

        assertThat(answerToJson.contentType()).contains(XML);
        assertThat(ModuleDetailXmlParser.parse(DEV, DIAGRAM, "100", answerToJson).properties())
            .hasSize(38);
    }

    /**
     * A system diagram element whose answer has a {@code Map} and a {@code List} property
     * ({@code admin-model-node-by-model-sd-ext-system}, structure probed on 8.1.17, values
     * synthetic): their {@code Property} children become entries, each masked on its own.
     */
    @Test
    void theSystemDiagramElementShowsItsMapAndListPropertiesAsMaskedEntries() {
        ModuleDetail detail = ModuleDetailXmlParser.parse(DEV, DIAGRAM, "49",
            recorded("admin-model-node-by-model-sd-ext-system", "xml"));

        assertThat(detail.name()).isEqualTo("Demo-ExtSystem");
        assertThat(detail.type()).isEqualTo("sdExtSystem");
        assertThat(detail.properties()).containsExactly(
            Property.shown("ResourceType", "String", "HTTP Connector", false),
            Property.entries("ResourceSettings", "Map", List.of(
                Property.shown("ServerName", "String",
                    "https://demo-backend.example.test/api/rates", false),
                Property.shown("Login", "String", "", false),
                Property.masked("Password", "Password"),
                Property.masked("SSLKeyStore", "HTTPConnectorTrustStore"),
                Property.shown("requireOAuthAuthorization", "Boolean", "true", false),
                Property.shown("PROPERTY_OAUTH_GRANT_TYPE", "String", "client_credentials", false),
                Property.masked("PROPERTY_OAUTH_ACCESS_TOKEN_URL", "String"),
                Property.shown("OAUTH_PROPERTY.client_id", "String", "demo-client", false),
                Property.masked("OAUTH_PROPERTY.client_secret", "String"),
                Property.masked("OAUTH_PROPERTY.refresh_token", "String"),
                Property.shown("OAUTH_PROPERTY.client_assertion_type", "String",
                    "urn:ietf:params:oauth:client-assertion-type:jwt-bearer", false),
                Property.masked("OAUTH_PROPERTY.client_assertion", "String"),
                Property.shown("OAUTH_PROPERTY.username", "String", "demo_user", false),
                Property.masked("OAUTH_PROPERTY.password", "String"))),
            Property.entries("ResourcesLinked", "List", List.of(
                Property.shown("Module", "String", "Demo-HTTP-Out-A", false),
                Property.shown("Module", "String", "Demo-HTTP-Out-B", false))));
        assertThat(detail.maskedCount()).as("every masked entry counts").isEqualTo(7);
        assertThat(ResultJson.mapper().writeValueAsString(detail)).doesNotContain(
            "synthetic-secret-3911", "synthetic-secret-3912", "synthetic-secret-3913",
            "synthetic-secret-3914");
    }

    @Test
    void aSecretNamedContainerIsMaskedAsAWhole() {
        ModuleDetail detail = properties("<ns2:Property name=\"ProxyCredentials\" type=\"Map\">"
            + "<ns2:Property name=\"host\" type=\"String\">synthetic-secret-3915</ns2:Property>"
            + "</ns2:Property>");

        assertThat(detail.properties()).containsExactly(
            Property.masked("ProxyCredentials", "Map"));
        assertThat(detail.maskedCount()).isOne();
        assertThat(detail.toString()).doesNotContain("synthetic-secret-3915");
    }

    @Test
    void aNestedContainerKeepsItsEntriesAndAnEmptyOneIsAnEmptyValue() {
        ModuleDetail detail = properties("<ns2:Property name=\"Outer\" type=\"Map\">"
            + "<ns2:Property name=\"Inner\" type=\"Map\"><ns2:Property name=\"pwd\""
            + " type=\"String\">synthetic-secret-3916</ns2:Property></ns2:Property>"
            + "\n\t<ns2:Property name=\"Empty\" type=\"Map\"/></ns2:Property>");

        assertThat(detail.properties()).containsExactly(Property.entries("Outer", "Map", List.of(
            Property.entries("Inner", "Map", List.of(Property.masked("pwd", "String"))),
            Property.shown("Empty", "Map", "", false))));
        assertThat(detail.maskedCount()).isOne();
    }

    @Test
    void aCommentInAContainerIsIgnored() {
        ModuleDetail detail = properties("<ns2:Property name=\"M\" type=\"Map\"><!-- note -->"
            + "<ns2:Property name=\"e\" type=\"String\">x</ns2:Property></ns2:Property>");

        assertThat(detail.properties()).containsExactly(Property.entries("M", "Map",
            List.of(Property.shown("e", "String", "x", false))));
    }

    @Test
    void containersNestedDeeperThanTheLimitAreUnexpected() {
        String open = "<ns2:Property name=\"M\" type=\"Map\">";
        String close = "</ns2:Property>";
        String leaf = "<ns2:Property name=\"e\" type=\"String\">x</ns2:Property>";
        int levels = ModuleDetailXmlParser.MAX_DEPTH;

        assertThat(properties(open.repeat(levels) + leaf + close.repeat(levels)).properties())
            .hasSize(1);
        ToolError error = unexpected("7", answer(200, XML, HEAD + open.repeat(levels + 1) + leaf
            + close.repeat(levels + 1) + TAIL));
        assertThat(error.message()).contains("nested deeper than");
    }

    @Test
    void theExcerptMasksTheWholeOutermostPropertyAlsoWhenItIsCutInside() {
        String mask = de.dadecker.inubit.mcp.domain.model.Masked.MASK_TOKEN;

        assertThat(ModuleDetailXmlParser.maskPropertyContent("<a><ns2:Property name=\"m\">"
            + "<ns2:Property n=\"e\">s1</ns2:Property>s2<ns2:Property n=\"f\"/><v x=\"s3\">s4</v>"
            + "</ns2:Property>tail<Property name=\"k\">s5"))
            .isEqualTo("<a><ns2:Property name=\"m\">" + mask + "</ns2:Property>tail"
                + "<Property name=\"k\">" + mask);
    }

    @Test
    void aJsonBodyIsNotARecordedFormAndIsUnexpectedWithoutTheSecret() {
        ToolError error = unexpected("7", answer(200, "application/json",
            "{\"name\": \"Probe\", \"properties\": {\"Password\": \"synthetic-secret-3903\"}}"));

        assertThat(error.toString()).doesNotContain("synthetic-secret-3903");
    }

    @Test
    void aPasswordTypedPropertyIsMaskedAlsoWhenEmpty() {
        ModuleDetail detail = properties("<ns2:Property name=\"Secret\" type=\"Password\"/>"
            + "<ns2:Property name=\"Pin\" type=\"Password\"></ns2:Property>");

        assertThat(detail.properties()).containsExactly(Property.masked("Secret", "Password"),
            Property.masked("Pin", "Password"));
        assertThat(detail.maskedCount()).isEqualTo(2);
    }

    @Test
    void keyMaterialIsMaskedByItsValue() {
        String pem = "-----BEGIN PRIVATE KEY-----\nMIIBsyntheticsecret3904\n-----END PRIVATE"
            + " KEY-----";
        ModuleDetail detail = properties("<ns2:Property name=\"content\" type=\"String\">" + pem
            + "</ns2:Property>");

        assertThat(detail.properties()).containsExactly(Property.masked("content", "String"));
        assertThat(detail.maskedCount()).isEqualTo(1);
        assertThat(ResultJson.mapper().writeValueAsString(detail))
            .doesNotContain("MIIBsyntheticsecret3904");
    }

    /**
     * A credential inside a shown value (here a JDBC URL) is masked in part by the free-text
     * masker; the rest of the value stays. The property is not masked as a whole, so it does not
     * count in {@code maskedCount}; the placeholder in the value shows the masking.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:sqlserver://inubit-db-01.example.test:1433;user=demo_reader;"
            + "password=synthetic-secret-3907;encrypt=true",
        "jdbc:postgresql://inubit-db-01.example.test:5432/demodb?user=demo_reader&amp;"
            + "password=synthetic-secret-3907&amp;ssl=true"})
    void aCredentialInsideAShownValueIsMaskedInPart(String url) {
        ModuleDetail detail = properties("<ns2:Property name=\"dbURL\" type=\"String\">" + url
            + "</ns2:Property>");

        Property dbUrl = detail.properties().getFirst();
        assertThat(dbUrl.value()).hasValueSatisfying(value -> assertThat(value)
            .startsWith("jdbc:").contains("inubit-db-01.example.test", "user=demo_reader",
                "password=${secret:").doesNotContain("synthetic-secret-3907"));
        assertThat(dbUrl.truncated()).isFalse();
        assertThat(detail.maskedCount()).isZero();
        assertThat(ResultJson.mapper().writeValueAsString(detail))
            .doesNotContain("synthetic-secret-3907");
        assertThat(detail.toString()).doesNotContain("synthetic-secret-3907");
    }

    @Test
    void aCredentialAtTheCutDoesNotSurviveInPart() {
        String head = "a,".repeat(ItemBounds.MAX_ITEM_CHARS / 2 - 20);
        ModuleDetail detail = properties("<ns2:Property name=\"staticQuery\" type=\"String\">"
            + head + ";password=synthetic-secret-3908;" + "b".repeat(100) + "</ns2:Property>");

        Property cut = detail.properties().getFirst();
        assertThat(cut.truncated()).isTrue();
        assertThat(cut.value()).hasValueSatisfying(value -> assertThat(value)
            .hasSizeLessThanOrEqualTo(ItemBounds.MAX_ITEM_CHARS).doesNotContain("synthetic"));
    }

    @Test
    void aValueAboveTheItemBoundIsCutWithTheMarker() {
        String value = "a,".repeat(ItemBounds.MAX_ITEM_CHARS);
        ModuleDetail detail = properties("<ns2:Property name=\"staticQuery\" type=\"String\">"
            + value + "</ns2:Property><ns2:Property name=\"ID\" type=\"String\">"
            + "a,".repeat(ItemBounds.MAX_ITEM_CHARS / 2) + "</ns2:Property>");

        Property cut = detail.properties().getFirst();
        assertThat(cut.value()).hasValueSatisfying(text -> {
            assertThat(text).hasSize(ItemBounds.MAX_ITEM_CHARS)
                .endsWith(ItemBounds.TRUNCATION_MARKER);
            assertThat(value).startsWith(text.substring(0,
                text.length() - ItemBounds.TRUNCATION_MARKER.length()));
        });
        assertThat(cut.truncated()).isTrue();
        Property whole = detail.properties().getLast();
        assertThat(whole.value()).contains("a,".repeat(ItemBounds.MAX_ITEM_CHARS / 2));
        assertThat(whole.truncated()).isFalse();
    }

    @Test
    void theValueKeepsItsWhiteSpace() {
        ModuleDetail detail = properties("<ns2:Property name=\"COLUMN\" type=\"String\"> a b"
            + " </ns2:Property>");

        assertThat(detail.properties()).containsExactly(
            Property.shown("COLUMN", "String", " a b ", false));
    }

    @Test
    void theRecordedNotFoundPageIsNotFound() {
        RestResponse missing = recorded("admin-model-node-by-model-missing", "html");

        assertThatThrownBy(() -> ModuleDetailXmlParser.parse(DEV, DIAGRAM, "999999", missing))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().message()).contains("999999", DIAGRAM);
                assertThat(e.error().excerpt()).isEmpty();
            });
    }

    /**
     * T046a (owner decision 2026-11-14): INUBIT 9.0.10 answers an unknown module id with a generic
     * {@code 500} {@code ApiError} that names no module (T039a). It cannot be told apart from
     * another failure of the endpoint, so it is {@code UNEXPECTED_RESPONSE}, not
     * {@code NOT_FOUND}, with the hint that the module id probably does not exist in the diagram.
     */
    @Test
    void theRecorded90GenericErrorIsUnexpectedWithTheHintThatTheModuleIsProbablyMissing() {
        RestResponse missing = new RestResponse(500, Optional.of("application/xml"),
            V90Cases.restBody("admin-model-node-by-model-missing"));

        ToolError error = unexpected("999999", missing);

        assertThat(error.message()).contains("999999", DIAGRAM);
        assertThat(error.likelyCause())
            .contains("the module id probably does not exist in the diagram");
        assertThat(error.nextStep()).contains("diagram name and the module id");
        assertThat(error.excerpt()).hasValueSatisfying(excerpt -> assertThat(excerpt)
            .contains("Error while getting node by model"));
    }

    @Test
    void theExcerptOfThe90GenericErrorHoldsNoSecret() {
        ToolError error = unexpected("7", answer(500, "application/xml", "<ApiError><statusCode>"
            + "500</statusCode><message>Error while getting node by model</message><path>/ibis/rest"
            + "/model/nodeByModel/x?password=synthetic-secret-3905</path></ApiError>"));

        assertThat(error.likelyCause()).contains("probably does not exist");
        assertThat(error.toString()).doesNotContain("synthetic-secret-3905");
    }

    /** Another recorded 9.0 {@code ApiError} keeps the general cause, without the hint. */
    @Test
    void another90ApiErrorIsUnexpectedWithoutTheHint() {
        RestResponse other = new RestResponse(500, Optional.of("application/xml"),
            V90Cases.restBody("admin-model-metadata-not-found"));

        ToolError error = unexpected("100", other);

        assertThat(error.likelyCause()).doesNotContain("probably does not exist");
        assertThat(error.excerpt()).hasValueSatisfying(excerpt -> assertThat(excerpt)
            .contains("An unexpected error occurred"));
    }

    @Test
    void anotherStatusPageIsUnexpectedWithItsMessageAsExcerpt() {
        ToolError error = unexpected("100", recorded("admin-model-metadata-not-found", "html"));

        assertThat(error.excerpt()).hasValueSatisfying(excerpt -> assertThat(excerpt)
            .contains("Workflow details not found").doesNotContain("<p", "<html"));
    }

    @Test
    void theNotFoundMessageInA200BodyIsNotTakenForNotFound() {
        String page = RestFixtures.text("admin-model-node-by-model-missing.html");

        unexpected("999999", answer(200, "text/html;charset=UTF-8", page));
    }

    @Test
    void theNotFoundStatusPageOfAnotherEndpointIsUnexpected() {
        unexpected("100", recorded("admin-model-node-by-name-not-found", "html"));
    }

    @Test
    void anAnswerForAnotherModuleIsUnexpected() {
        unexpected("101", recorded("admin-model-node-by-model", "xml"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // cut off inside the planted secret
        "<ns4:Node name=\"P\" type=\"t\" id=\"7\" xmlns:ns2=\"b\" xmlns:ns4=\"m\"><ns2:Properties>"
            + "<ns2:Property name=\"Password\" type=\"Password\">synthetic-secret-3905",
        // another root
        "<ns4:Other xmlns:ns2=\"b\" xmlns:ns4=\"m\"><ns2:Property name=\"dbPwd\" type=\"String\">"
            + "synthetic-secret-3905</ns2:Property></ns4:Other>",
        // a property without a type
        "<Node name=\"P\" type=\"t\" id=\"7\"><Properties><Property name=\"proxyPassword\">"
            + "synthetic-secret-3905</Property></Properties></Node>",
        // a property with a child element that is not a property
        "<Node name=\"P\" type=\"t\" id=\"7\"><Properties><Property name=\"a\" type=\"Password\">"
            + "<v>synthetic-secret-3905</v></Property></Properties></Node>",
        // a container with text next to its entries
        "<Node name=\"P\" type=\"t\" id=\"7\"><Properties><Property name=\"m\" type=\"Map\">"
            + "synthetic-secret-3905<Property name=\"e\" type=\"String\">x</Property></Property>"
            + "</Properties></Node>",
        // an entry without a type
        "<Node name=\"P\" type=\"t\" id=\"7\"><Properties><Property name=\"m\" type=\"Map\">"
            + "<Property name=\"proxyPassword\">synthetic-secret-3905</Property></Property>"
            + "</Properties></Node>",
        // text after an entry
        "<Node name=\"P\" type=\"t\" id=\"7\"><Properties><Property name=\"m\" type=\"Map\">"
            + "<Property name=\"e\" type=\"String\">x</Property>synthetic-secret-3905</Property>"
            + "</Properties></Node>",
        // text after a nested container
        "<Node name=\"P\" type=\"t\" id=\"7\"><Properties><Property name=\"m\" type=\"Map\">"
            + "<Property name=\"n\" type=\"Map\"><Property name=\"e\" type=\"String\">x"
            + "</Property></Property>synthetic-secret-3905</Property></Properties></Node>",
        // another element after an entry, with a secret attribute and text
        "<Node name=\"P\" type=\"t\" id=\"7\"><Properties><Property name=\"m\" type=\"Map\">"
            + "<Property name=\"e\" type=\"String\">x</Property><v a=\"synthetic-secret-3905\">"
            + "synthetic-secret-3905</v></Property></Properties></Node>",
        // a DTD
        "<!DOCTYPE Node [<!ENTITY s 'P'>]><Node name=\"&s;\" type=\"t\" id=\"7\"><Properties>"
            + "<Property name=\"Password\" type=\"Password\">synthetic-secret-3905</Property>"
            + "</Properties></Node>",
        // a secret-named attribute in an unknown element
        "<Node name=\"P\" type=\"t\" id=\"7\"><Credentials dbPassword=\"synthetic-secret-3905\"/>"
            + "<Properties><Property name=\"a\"/></Properties></Node>",
        // a connection without a target
        "<Node name=\"P\" type=\"t\" id=\"7\"><Connection id=\"1\"/><Properties><Property"
            + " name=\"Password\" type=\"Password\">synthetic-secret-3905</Property></Properties>"
            + "</Node>",
        // no module name
        "<Node type=\"t\" id=\"7\"><Properties><Property name=\"Password\" type=\"Password\">"
            + "synthetic-secret-3905</Property></Properties></Node>",
        ""})
    void anotherDocumentIsUnexpectedAndItsExcerptHoldsNoSecret(String body) {
        ToolError error = unexpected("7", answer(200, XML, body));

        assertThat(error.toString()).doesNotContain("synthetic-secret-3905");
    }
}
