package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRow;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Feature 020 T012 (FR-005, FR-008, research D-9): the queueLog lookup of
 * {@link V81ProcessQueryAdapter#queueEntries} over the recorded 8.1.17 answers
 * {@code fixtures/v8_1/rest/log_queueLog_error.json} and {@code log_queueLog.json}.
 */
@Timeout(30)
class QueueEntryRowsContractTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final String PATH = "/ibis/rest/log/queueLog";
    private static WireMockServer wireMock;

    private InubitHttpClient client;

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        SecretScrubber scrubber = new SecretScrubber();
        client = new InubitHttpClient(TestNodeConfig.node().id(ID.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(5))
            .build(),
            Optional.of(new InubitHttpClient.Credentials("jdoe",
                scrubber.register("rows-contract-test-pw"))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private QueueEntries recorded(String fixtureCase, String processId) {
        wireMock.stubFor(post(urlPathEqualTo(PATH))
            .willReturn(RestFixtures.response(fixtureCase, "json")));
        return new V81ProcessQueryAdapter(ID, client).queueEntries(processId);
    }

    @Test
    void theRecordedErrorRowsKeepFileNameSizeErrorTextsAndTheUncutWorkflow() {
        QueueEntries entries = recorded("log_queueLog_error", "110219899");

        assertThat(entries.total()).isEqualTo(706);
        assertThat(entries.truncated()).isTrue();
        assertThat(entries.rows()).hasSize(5).allSatisfy(row -> {
            assertThat(row.state()).isEqualTo(ProcessState.ERROR);
            assertThat(row.rawState()).isEqualTo("Error");
        });
        QueueEntryRow first = entries.rows().get(0);
        assertThat(first.processId()).isEqualTo("110219899");
        assertThat(first.globalProcessId()).contains("110219899");
        assertThat(first.since()).isEqualTo(Instant.ofEpochMilli(1_790_861_373_734L));
        assertThat(first.workflow()).isEqualTo("Workflow-0424");
        assertThat(first.module()).contains("Module-0036(23379903)");
        assertThat(first.moduleType()).contains("Workflow Connector");
        assertThat(first.fileName()).isEqualTo("module-0036(23379903)@110219899"
            + "@f7a7f128-0a88-0246-1158-a5841aca8b3e_1790861373736");
        assertThat(first.fileSize()).isZero();
        assertThat(first.errorTexts()).containsExactly(
            Map.entry("userDefined1", "<message 14>"),
            Map.entry("userDefined2", "<message 12> (org.xml.sax.SAXParseException)"));

        assertThat(entries.rows().get(2).fileSize()).isEqualTo(37_235L);
        QueueEntryRow last = entries.rows().get(4);
        assertThat(last.processId()).isEqualTo("110219873");
        assertThat(last.globalProcessId()).contains("110082636");
        assertThat(last.workflow()).isEqualTo("Workflow-0366");
        assertThat(last.moduleType()).contains("Assign");
        assertThat(last.fileSize()).isEqualTo(19_218L);
        assertThat(last.errorTexts()).containsExactly(
            Map.entry("userDefined1", "<message 18>"),
            Map.entry("userDefined2", "<message 17>"));
    }

    @Test
    void theRecordedQueueLogIsNewestFirstWithANumericOrUuidGlobalProcessId() {
        QueueEntries entries = recorded("log_queueLog", "110170178");

        assertThat(entries.total()).isEqualTo(706);
        assertThat(entries.rows()).extracting(QueueEntryRow::processId).containsExactly(
            "110211377", "110190570", "110190387", "110170178", "110170178");
        QueueEntryRow uuid = entries.rows().get(3);
        assertThat(uuid.globalProcessId()).contains("813399ff-d909-4011-b4e4-3226deaf1df3");
        assertThat(uuid.state()).isEqualTo(ProcessState.ERROR);
        assertThat(uuid.workflow()).isEqualTo("Workflow-0159");
        assertThat(uuid.fileName()).isEqualTo("module-0033(484)@110170178"
            + "@c92aa066-0a88-0250-068e-c4dc5ca8b37b_1790081409126");
        assertThat(uuid.fileSize()).isEqualTo(33_873L);
        assertThat(uuid.errorTexts()).containsExactly(
            Map.entry("userDefined1", "<message 6>"), Map.entry("userDefined2", "<message 5>"));
        assertThat(entries.rows().get(0).fileSize()).isEqualTo(376L);
        assertThat(entries.rows().get(0).workflow()).isEqualTo("Workflow-0274");
    }
}
