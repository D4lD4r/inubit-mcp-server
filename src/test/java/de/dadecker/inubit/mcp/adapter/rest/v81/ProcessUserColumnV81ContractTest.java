package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageState;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T046 (feature 022, US3, research D-21, "Decided after T039"): {@code find_processes}
 * {@code userColumn} through {@link V81ProcessQueryAdapter#find} over the recorded 8.1.17 answer
 * {@code fixtures/v8_1/rest/log_queueLog.json} (beside {@link QueueEntryRowsContractTest}). The
 * column is the row's {@code userDefined<i>}, the data that {@code ps -u <i>} shows; the search
 * sends the same single {@code queueLog} request with or without it.
 */
@Timeout(30)
class ProcessUserColumnV81ContractTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final String PATH = "/ibis/rest/log/queueLog";
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    private static WireMockServer wireMock;

    private InubitHttpClient client;

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        wireMock.stubFor(post(urlPathEqualTo(PATH))
            .willReturn(RestFixtures.response("log_queueLog", "json")));
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetRequests();
        SecretScrubber scrubber = new SecretScrubber();
        client = new InubitHttpClient(TestNodeConfig.node().id(ID.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(5))
            .build(),
            Optional.of(new InubitHttpClient.Credentials("jdoe",
                scrubber.register("user-column-contract-pw"))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private Page<ProcessInstance> find(Optional<Integer> userColumn) {
        return new V81ProcessQueryAdapter(ID, client).find(new ProcessQuery(Set.of(), false,
            Duration.ofMinutes(60), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), 0, 5, NOW, userColumn));
    }

    @Test
    void userColumnOneIsTheUserDefined1OfEachRecordedRow() {
        Page<ProcessInstance> page = find(Optional.of(1));

        assertThat(page.items())
            .extracting(ProcessInstance::processId, ProcessInstance::userColumnValue)
            .containsExactlyInAnyOrder(tuple("110211377", Optional.of("<message 2>")),
                tuple("110190570", Optional.of("<message 2>")),
                tuple("110190387", Optional.of("<message 2>")),
                tuple("110170178", Optional.of("<message 4>")),
                tuple("110170178", Optional.of("<message 6>")));
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(PATH)));
    }

    /** {@code userDefined3} is blank in every recorded row: the column is absent. */
    @Test
    void aBlankRecordedUserDefinedValueIsAbsent() {
        assertThat(find(Optional.of(3)).items()).hasSize(5)
            .allSatisfy(item -> assertThat(item.userColumnValue()).isEmpty());
    }

    @Test
    void withoutUserColumnTheRecordedRowsCarryNoColumnAndTheRequestIsTheSame() {
        Page<ProcessInstance> plain = find(Optional.empty());
        find(Optional.of(1));

        assertThat(plain.items()).hasSize(5)
            .allSatisfy(item -> assertThat(item.userColumnValue()).isEmpty());
        List<String> bodies = wireMock.findAll(postRequestedFor(urlPathEqualTo(PATH))).stream()
            .map(LoggedRequest::getBodyAsString).toList();
        assertThat(bodies).hasSize(2).as("the same body with and without the column")
            .containsOnly(bodies.get(0));
    }

    /** 9.0: {@link ProcessUserColumnV90ContractTest} (T046a). */
    @Test
    void psUserColumnIsCoveredByFindProcessesOn81() {
        assertThat(InterfaceCoverage.production().state(Channel.CLI, "ps -u", new MajorMinor(8, 1)))
            .contains(new CoverageState.Covered("find_processes"));
    }
}
