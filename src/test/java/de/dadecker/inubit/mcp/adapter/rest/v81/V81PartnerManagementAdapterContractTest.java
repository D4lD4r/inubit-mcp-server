package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

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
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.partner.CommunicationCloud;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort.Result;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort.UnreadableDiagram;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T012: the 8.1 Partner Management port against WireMock (feature 018, research D-1, D-4). The
 * recorded {@code model_export_systemdiagram.{http,zip}} (status 200, no {@code Content-Type})
 * is served as is; the group and owner collision variants and the unreadable archives are
 * modified copies of it, the second diagram is built from {@link PartnerFixtures}.
 */
@Timeout(30)
class V81PartnerManagementAdapterContractTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final String OWNER = "OWNERS";
    private static final String EXPORT = "/ibis/rest/model/export/";
    private static final String WORKFLOW_XML = "workflow/workflow.xml";
    private static final DiagramRef RECORDED = new DiagramRef("SystemDiagram-01", "GRP-PM");
    private static final DiagramRef SYNTHETIC =
        new DiagramRef("SystemDiagram-02", PartnerFixtures.GROUP);
    /** A non-secret value of the recorded workflow.xml; an error must not repeat it. */
    private static final String RECORDED_VALUE = "host1.example.test";
    private static WireMockServer wireMock;

    private final SecretScrubber scrubber = new SecretScrubber();
    private final List<InubitHttpClient> clients = new ArrayList<>();

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

    private PartnerManagementPort adapter() {
        return adapter(Duration.ofSeconds(5), MaintenanceProbe.NONE);
    }

    private PartnerManagementPort adapter(Duration timeout, MaintenanceProbe probe) {
        EffectiveNodeConfig config = TestNodeConfig.node().id(ID.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(timeout).build();
        InubitHttpClient client = new InubitHttpClient(config,
            Optional.of(new InubitHttpClient.Credentials("jdoe",
                scrubber.register("partner-adapter-test-pw"))),
            Optional.empty(), probe, scrubber);
        clients.add(client);
        return new V81PartnerManagementAdapter(ID, client);
    }

    private static void stub(String name, ResponseDefinitionBuilder response) {
        wireMock.stubFor(get(urlPathEqualTo(EXPORT + name)).willReturn(response));
    }

    private static void stubExport(String name, byte[] zip) {
        stub(name, aResponse().withStatus(200).withBody(zip));
    }

    /** The recorded export with {@code from} replaced by {@code to} in its workflow.xml. */
    private static byte[] recordedWith(String from, String to) {
        String xml = recordedWorkflowXml();
        assertThat(xml).contains(from);
        return withWorkflowXml(xml.replace(from, to).getBytes(StandardCharsets.UTF_8));
    }

    private static String recordedWorkflowXml() {
        return new String(ArtifactFixtures.entries(recordedZip()).get(WORKFLOW_XML),
            StandardCharsets.UTF_8);
    }

    private static byte[] recordedZip() {
        assertThat(RestFixtures.text("model_export_systemdiagram.http")).contains("status: 200");
        return RestFixtures.bytes("model_export_systemdiagram.zip");
    }

    /** The recorded export with its workflow.xml replaced (or removed for {@code null}). */
    private static byte[] withWorkflowXml(byte[] xml) {
        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries(recordedZip()));
        if (xml == null) {
            entries.remove(WORKFLOW_XML);
        } else {
            entries.put(WORKFLOW_XML, xml);
        }
        return ArtifactFixtures.zip(entries);
    }

    /** The synthetic detail set (one unresolved entry, so one warning) as {@link #SYNTHETIC}. */
    private static byte[] syntheticExport() {
        DiagramXml detail = PartnerFixtures.detail();
        return withWorkflowXml(PartnerFixtures.workflowXml(new DiagramXml(SYNTHETIC.diagramGroup(),
            SYNTHETIC.name(), PartnerFixtures.OWNER, detail.clouds())));
    }

    private static ToolError errorOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    // --- the recorded export ---------------------------------------------------------------

    @Test
    void theRecordedExportGivesTheMaskedPartnerManagementOfTheDiagram() {
        stubExport(RECORDED.name(), recordedZip());

        Result result = adapter().read(OWNER, List.of(RECORDED));

        assertThat(result.diagrams()).singleElement().satisfies(diagram -> {
            assertThat(diagram.diagram()).isEqualTo(RECORDED);
            assertThat(diagram.clouds()).extracting(CommunicationCloud::moduleName)
                .containsExactly("Module-0901", "Module-0902");
        });
        assertThat(result.unreadableDiagrams()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        // an archive is asked for with any media type, once per diagram (as diagramMetadata)
        assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo(EXPORT + RECORDED.name()))
            .withHeader("Accept", equalTo("*/*")))).hasSize(1);
    }

    @Test
    void everyDiagramIsReadWithItsOwnRequestAndTheWarningsArePassedOn() {
        stubExport(RECORDED.name(), recordedZip());
        stubExport(SYNTHETIC.name(), syntheticExport());

        Result result = adapter().read(OWNER, List.of(SYNTHETIC, RECORDED));

        assertThat(result.diagrams()).extracting(d -> d.diagram())
            .containsExactly(SYNTHETIC, RECORDED);
        assertThat(result.warnings()).containsOnlyKeys(SYNTHETIC);
        assertThat(result.warnings().get(SYNTHETIC)).singleElement().satisfies(warning ->
            assertThat(warning).contains("GRP-PM/SystemDiagram-02", "unresolved"));
        assertThat(wireMock.getAllServeEvents()).hasSize(2);
    }

    // --- D-4: name collisions --------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "another group, <WorkflowGroupName>GRP-PM<, <WorkflowGroupName>GRP-OTHER<",
        "another owner, <UserOrUserGroupName>OWNERS<, <UserOrUserGroupName>OTHERS<"})
    void anExportOfAnotherGroupOrOwnerGivesNoDataAndIsReportedPerDiagram(String variant,
        String from, String to) {
        stubExport(RECORDED.name(), recordedWith(from, to));
        stubExport(SYNTHETIC.name(), syntheticExport());

        Result result = adapter().read(OWNER, List.of(RECORDED, SYNTHETIC));

        assertThat(result.diagrams()).extracting(d -> d.diagram()).containsExactly(SYNTHETIC);
        assertThat(result.unreadableDiagrams()).containsExactly(new UnreadableDiagram(RECORDED,
            ErrorCode.UNEXPECTED_RESPONSE, "the export of this name returned a diagram of another"
                + " group or owner; the name is not unique"));
        assertThat(result.warnings()).containsOnlyKeys(SYNTHETIC);
        assertThat(result.warnings().get(SYNTHETIC)).singleElement().satisfies(warning ->
            assertThat(warning).doesNotContain(RECORDED.name()));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "another name, >SystemDiagram-07<",
        "the name in another case, >systemdiagram-01<"})
    void anExportWithoutAWorkflowOfTheRequestedNameGivesNoDataAndIsReportedPerDiagram(
        String variant, String workflowName) {
        stubExport(RECORDED.name(), recordedWith(">SystemDiagram-01<", workflowName));
        stubExport(SYNTHETIC.name(), syntheticExport());

        Result result = adapter().read(OWNER, List.of(RECORDED, SYNTHETIC));

        assertThat(result.diagrams()).extracting(d -> d.diagram()).containsExactly(SYNTHETIC);
        assertThat(result.unreadableDiagrams()).containsExactly(new UnreadableDiagram(RECORDED,
            ErrorCode.UNEXPECTED_RESPONSE, "the export of this name did not return the requested"
                + " diagram; the name is not unique or not found"));
    }

    @Test
    void twoWorkflowsOfTheRequestedNameGroupAndOwnerGiveNoDataAndAreReportedPerDiagram() {
        DiagramXml twice = new DiagramXml(SYNTHETIC.diagramGroup(), SYNTHETIC.name(),
            PartnerFixtures.OWNER, PartnerFixtures.detail().clouds());
        stubExport(SYNTHETIC.name(), withWorkflowXml(PartnerFixtures.workflowXml(twice, twice)));
        stubExport(RECORDED.name(), recordedZip());

        Result result = adapter().read(OWNER, List.of(SYNTHETIC, RECORDED));

        assertThat(result.diagrams()).extracting(d -> d.diagram()).containsExactly(RECORDED);
        assertThat(result.unreadableDiagrams()).containsExactly(new UnreadableDiagram(SYNTHETIC,
            ErrorCode.UNEXPECTED_RESPONSE, "the export of this name holds several diagrams of this"
                + " name in this group; the name is not unique"));
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void theOwnerOfTheRequestDecidesNotTheRecordedOne() {
        stubExport(RECORDED.name(), recordedZip());

        Result result = adapter().read("OTHERS", List.of(RECORDED));

        assertThat(result.diagrams()).isEmpty();
        assertThat(result.unreadableDiagrams()).extracting(UnreadableDiagram::systemDiagram)
            .containsExactly(RECORDED);
    }

    @Test
    void aGzipEncodedExportAsSentByInubit90IsRead() throws IOException {
        // INUBIT 9.0.10 wraps the export in Content-Encoding gzip (feature 017 live check, F-3)
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gzipped)) {
            out.write(recordedZip());
        }
        stub(RECORDED.name(), aResponse().withStatus(200)
            .withHeader("Content-Type", "application/zip").withHeader("Content-Encoding", "gzip")
            .withBody(gzipped.toByteArray()));

        Result result = adapter().read(OWNER, List.of(RECORDED));

        assertThat(result.diagrams()).singleElement().satisfies(diagram ->
            assertThat(diagram.clouds()).extracting(CommunicationCloud::moduleName)
                .containsExactly("Module-0901", "Module-0902"));
        assertThat(result.unreadableDiagrams()).isEmpty();
    }

    // --- unreadable archives ---------------------------------------------------------------

    @Test
    void aBodyThatIsNoZipIsUnexpected() {
        stub(RECORDED.name(), aResponse().withStatus(200).withHeader("Content-Type", "text/html")
            .withBody("<html>login</html>"));

        ToolError error = errorOf(() -> adapter().read(OWNER, List.of(RECORDED)));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains("not a ZIP archive").doesNotContain("login");
        assertThat(error.node()).contains(ID);
    }

    @Test
    void anExportWithoutWorkflowXmlIsUnexpectedNamingTheEntry() {
        stubExport(RECORDED.name(), withWorkflowXml(null));

        ToolError error = errorOf(() -> adapter().read(OWNER, List.of(RECORDED)));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains(WORKFLOW_XML, RECORDED.name());
        assertThat(error.node()).contains(ID);
    }

    @Test
    void aWorkflowXmlThatIsNotWellFormedIsUnexpectedWithoutItsContent() {
        String xml = recordedWorkflowXml();
        stubExport(RECORDED.name(), withWorkflowXml(xml.substring(0, xml.indexOf(RECORDED_VALUE)
            + RECORDED_VALUE.length()).getBytes(StandardCharsets.UTF_8)));

        ToolError error = errorOf(() -> adapter().read(OWNER, List.of(RECORDED)));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains(WORKFLOW_XML, "not well-formed")
            .doesNotContain(RECORDED_VALUE);
        assertThat(error.toString()).doesNotContain(RECORDED_VALUE);
    }

    @Test
    void aPartnerManagementOfAnotherStructureIsUnexpectedNamingThePartNotTheContent() {
        stubExport(RECORDED.name(), recordedWith(" protocol=\"WebService\"", ""));

        ToolError error = errorOf(() -> adapter().read(OWNER, List.of(RECORDED)));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains(WORKFLOW_XML, "GRP-PM/SystemDiagram-01",
            "protocol").doesNotContain(RECORDED_VALUE);
        assertThat(error.toString()).doesNotContain(RECORDED_VALUE);
        assertThat(error.node()).contains(ID);
    }


    // --- REST failures map to the existing error kinds -------------------------------------

    @Test
    void a404ForOneDiagramGivesNoDataForItAndIsReportedPerDiagram() {
        stub(RECORDED.name(), aResponse().withStatus(404).withBody("<html>gone</html>"));
        stubExport(SYNTHETIC.name(), syntheticExport());

        Result result = adapter().read(OWNER, List.of(RECORDED, SYNTHETIC));

        assertThat(result.diagrams()).extracting(d -> d.diagram()).containsExactly(SYNTHETIC);
        assertThat(result.unreadableDiagrams()).singleElement().satisfies(unreadable -> {
            assertThat(unreadable.systemDiagram()).isEqualTo(RECORDED);
            assertThat(unreadable.code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(unreadable.message()).contains("GRP-PM/SystemDiagram-01 on dev/node1",
                "HTTP 404").doesNotContain("gone");
        });
    }

    @ParameterizedTest(name = "HTTP {0} → {1}")
    @CsvSource({"401, AUTH_FAILED", "403, FORBIDDEN", "503, UNREACHABLE"})
    void restFailuresAreToolErrorsOfTheServer(int status, ErrorCode expected) {
        stub(RECORDED.name(), aResponse().withStatus(status));

        ToolError error = errorOf(() -> adapter().read(OWNER, List.of(RECORDED)));

        assertThat(error.code()).isEqualTo(expected);
        assertThat(error.node()).contains(ID);
    }

    @ParameterizedTest(name = "HTTP {0} → {1}")
    @CsvSource({"500, Check the INUBIT system log", "400, Check the INUBIT system log",
        "503, run get_health"})
    void aFailedExportIsReportedWithoutAnExcerptOfItsAnswer(int status, String nextStep) {
        // T030: the answer of a Partner Management export may quote partner data
        stub(RECORDED.name(), aResponse().withStatus(status)
            .withBody("failed near " + PartnerFixtures.AES_PASSWORD + " " + RECORDED_VALUE));

        ToolError error = errorOf(() -> adapter().read(OWNER, List.of(RECORDED)));

        assertThat(error.excerpt()).isEmpty();
        assertThat(error.toString()).doesNotContain(PartnerFixtures.AES_PASSWORD, RECORDED_VALUE,
            "failed near");
        assertThat(error.nextStep()).doesNotContainIgnoringCase("excerpt").startsWith(nextStep);
        assertThat(error.node()).contains(ID);
    }

    @Test
    void a503InMaintenanceModeIsMaintenanceMode() {
        stub(RECORDED.name(), aResponse().withStatus(503));

        ToolError error = errorOf(() -> adapter(Duration.ofSeconds(5), () -> Optional.of(true))
            .read(OWNER, List.of(RECORDED)));

        assertThat(error.code()).isEqualTo(ErrorCode.MAINTENANCE_MODE);
    }

    @Test
    void aHangingExportIsATimeout() {
        stub(RECORDED.name(), aResponse().withStatus(200).withBody(recordedZip())
            .withFixedDelay(3000));

        ToolError error = errorOf(() -> adapter(Duration.ofMillis(500), MaintenanceProbe.NONE)
            .read(OWNER, List.of(RECORDED)));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.node()).contains(ID);
    }
}
