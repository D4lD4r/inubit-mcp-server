package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LibraryEntry;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Connection;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Property;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T042 (feature 022, US3): the module read of the 8.1 model detail port against WireMock with the
 * bodies and media types recorded in T039 ({@code admin-model-node-by-model*}); the library list
 * goes to the given reader.
 */
@Timeout(30)
class V81ModelDetailAdapterTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final String DIAGRAM = "Workflow-0301";
    private static final String MODULE = "/ibis/rest/model/nodeByModel/Workflow-0301/100";
    private static final String XML_ACCEPT = "application/xml, */*;q=0.1";
    private static final String PASSWORD = "model-test-pw";
    /** The planted secrets of {@code admin-model-node-by-model.xml}. */
    private static final String[] PLANTED = {"synthetic-secret-3901", "synthetic-secret-3902"};
    private static WireMockServer wireMock;

    private final SecretScrubber scrubber = new SecretScrubber();
    private final List<InubitHttpClient> clients = new ArrayList<>();
    private final List<String> libraryCalls = new ArrayList<>();
    private final LibraryEntry.Listing librariesAnswer = new LibraryEntry.Listing(
        List.of(new LibraryEntry("postgresql-42.7.7.jar", "driver")), false);

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @AfterEach
    void closeClients() {
        clients.forEach(InubitHttpClient::close);
    }

    private V81ModelDetailAdapter adapter() {
        return adapter(MaintenanceProbe.NONE);
    }

    private V81ModelDetailAdapter adapter(MaintenanceProbe probe) {
        InubitHttpClient client = new InubitHttpClient(TestNodeConfig.node().id(ID.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(5))
            .build(),
            Optional.of(new InubitHttpClient.Credentials("jdoe", scrubber.register(PASSWORD))),
            Optional.empty(), probe, scrubber);
        clients.add(client);
        return new V81ModelDetailAdapter(ID, client, type -> {
            libraryCalls.add(type);
            return librariesAnswer;
        });
    }

    private static void stub(int status, String contentType, String body) {
        wireMock.stubFor(get(urlPathEqualTo(MODULE)).willReturn(aResponse().withStatus(status)
            .withHeader("Content-Type", contentType).withBody(body)));
    }

    private static void assertError(Runnable call, Consumer<ToolError> check) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().node()).contains(ID);
            assertThat(e.error().toString()).doesNotContain(PASSWORD).doesNotContain(PLANTED);
            check.accept(e.error());
        });
    }

    // --- GET /model/nodeByModel/{diagram}/{moduleId} ----------------------------------------

    @Test
    void theRecordedDatabaseConnectorModuleIsReadWithCredentialsAndTheSecretsMasked() {
        wireMock.stubFor(get(urlPathEqualTo(MODULE))
            .willReturn(RestFixtures.response("admin-model-node-by-model", "xml")));

        ModuleDetail detail = adapter().module(DIAGRAM, "100");

        assertThat(detail.diagram()).isEqualTo(DIAGRAM);
        assertThat(detail.moduleId()).isEqualTo("100");
        assertThat(detail.name()).isEqualTo("Demo-DB-Probe");
        assertThat(detail.type()).isEqualTo("twDatabaseConnector");
        assertThat(detail.connections()).containsExactly(new Connection("22", "1"));
        assertThat(detail.properties()).hasSize(38)
            .contains(Property.masked("Password", "Password"));
        assertThat(detail.maskedCount()).isEqualTo(2);
        assertThat(detail.toString()).doesNotContain(PLANTED);
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(MODULE))
            .withHeader("Authorization", matching("Basic .+")));
        assertThat(libraryCalls).as("no StartCLI call").isEmpty();
    }

    /**
     * The adapter asks for XML. INUBIT 8.1.17 answered Accept JSON with the same XML body as
     * Accept XML (T039); an XML answer is read whatever XML content type it carries.
     */
    @ParameterizedTest
    @ValueSource(strings = {"application/xml;charset=UTF-8", "application/xml", "text/xml"})
    void theRequestAsksForXmlAndAnXmlAnswerIsReadWhateverItsContentType(String contentType) {
        stub(200, contentType, RestFixtures.text("admin-model-node-by-model.xml"));

        assertThat(adapter().module(DIAGRAM, "100").properties()).hasSize(38);

        wireMock.verify(1, getRequestedFor(urlPathEqualTo(MODULE))
            .withHeader("Accept", equalTo(XML_ACCEPT)));
    }

    @Test
    void theRecordedIsConnectorModuleHasItsParent() {
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/model/nodeByModel/Workflow-0301/34"))
            .willReturn(RestFixtures.response("admin-model-node-by-model-is-connector", "xml")));

        ModuleDetail detail = adapter().module(DIAGRAM, "34");

        assertThat(detail.name()).isEqualTo("Demo-IS-Probe");
        assertThat(detail.parentId()).contains("66");
    }

    @Test
    void theDiagramIsSentAsOneEncodedPathSegment() {
        wireMock.stubFor(get(urlEqualTo("/ibis/rest/model/nodeByModel/Order%20Flow%2F2/100"))
            .willReturn(RestFixtures.response("admin-model-node-by-model", "xml")));

        assertThat(adapter().module("Order Flow/2", "100").diagram()).isEqualTo("Order Flow/2");
    }

    /** The recorded answer to an unknown module id: 500, {@code text/html}, the status page. */
    @Test
    void theRecordedNotFoundPageIsNotFoundForTheNode() {
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/model/nodeByModel/Workflow-0301/999999"))
            .willReturn(RestFixtures.response("admin-model-node-by-model-missing", "html")));

        assertError(() -> adapter().module(DIAGRAM, "999999"), error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(error.message()).contains("999999", DIAGRAM);
            assertThat(error.excerpt()).isEmpty();
        });
    }

    @Test
    void anotherServerErrorIsUnexpectedWithoutTheSecretInItsExcerpt() {
        stub(500, "text/html;charset=UTF-8", "<html><body>@Start@Internal error@@@"
            + "<ns2:Property name=\"Password\" type=\"Password\">synthetic-secret-4201"
            + "</ns2:Property>@End@</body></html>");

        assertError(() -> adapter().module(DIAGRAM, "100"), error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
            assertThat(error.excerpt()).isPresent();
            assertThat(error.toString()).doesNotContain("synthetic-secret-4201");
        });
    }

    /** The recorded 404 status page of {@code nodeByName}: an endpoint that is not there. */
    @Test
    void aMissingEndpointIsAnUnexpectedResponse() {
        wireMock.stubFor(get(urlPathEqualTo(MODULE))
            .willReturn(RestFixtures.response("admin-model-node-by-name-not-found", "html")));

        assertError(() -> adapter().module(DIAGRAM, "100"),
            error -> assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }

    @Test
    void http403IsForbiddenForTheNodeWithoutTheBody() {
        stub(403, "text/html", "<html>Forbidden synthetic-secret-4202</html>");

        assertError(() -> adapter().module(DIAGRAM, "100"), error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(error.toString()).doesNotContain("synthetic-secret-4202");
        });
    }

    @Test
    void http503DuringMaintenanceIsMaintenanceMode() {
        stub(503, "text/html", "");

        assertError(() -> adapter(() -> Optional.of(true)).module(DIAGRAM, "100"),
            error -> assertThat(error.code()).isEqualTo(ErrorCode.MAINTENANCE_MODE));
    }

    // --- library list ------------------------------------------------------------------------

    @Test
    void theLibrariesGoToTheirReaderWithoutRestCall() {
        V81ModelDetailAdapter adapter = adapter();

        assertThat(adapter.libraries("driver")).isSameAs(librariesAnswer);
        assertThat(adapter.libraries("plugin")).isSameAs(librariesAnswer);

        assertThat(libraryCalls).containsExactly("driver", "plugin");
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }
}
