package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.CoverageState;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T046a (feature 022, US3, research D-21, "Live recording T039a"): {@code find_processes}
 * {@code userColumn} through {@link V81ProcessQueryAdapter#find} over the recorded 9.0.10 row
 * {@code fixtures/queue-entry/v9_0/error_entry.rows.json} (no new {@code queueLog} recording: its
 * {@code userDefined1} is set). The 9.0 row {@code ps -u} is {@code covered:find_processes}, so
 * T045's {@code admitOption} admits the column on 9.0 without a code change.
 */
@Timeout(30)
class ProcessUserColumnV90ContractTest {

    private static final NodeId ID = NodeId.parse("test/node1");
    private static final String PATH = "/ibis/rest/log/queueLog";
    private static final String ROWS = "/fixtures/queue-entry/v9_0/error_entry.rows.json";
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    private static final MajorMinor V9_0 = new MajorMinor(9, 0);
    private static WireMockServer wireMock;

    private InubitHttpClient client;

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json").withBody(rows())));
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
                scrubber.register("user-column-v90-contract-pw"))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private static byte[] rows() {
        try (InputStream in = ProcessUserColumnV90ContractTest.class.getResourceAsStream(ROWS)) {
            if (in == null) {
                throw new IllegalStateException("No fixture " + ROWS);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Page<ProcessInstance> find(Optional<Integer> userColumn) {
        return new V81ProcessQueryAdapter(ID, client).find(new ProcessQuery(Set.of(), false,
            Duration.ofMinutes(60), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), 0, 5, NOW, userColumn));
    }

    @Test
    void userColumnOneIsTheUserDefined1OfTheRecordedRow() {
        Page<ProcessInstance> page = find(Optional.of(1));

        assertThat(page.items())
            .extracting(ProcessInstance::processId, ProcessInstance::userColumnValue)
            .containsExactly(tuple("100000090",
                Optional.of("XsdException.ErrorModuleInputValidation")));
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(PATH)));
    }

    /** {@code userDefined2} is blank in the recorded row: the column is absent. */
    @Test
    void aBlankRecordedUserDefinedValueIsAbsent() {
        assertThat(find(Optional.of(2)).items()).singleElement()
            .satisfies(item -> assertThat(item.userColumnValue()).isEmpty());
    }

    @Test
    void psUserColumnIsServedByFindProcessesOn90() {
        InterfaceCoverage coverage = InterfaceCoverage.production();

        assertThat(coverage.state(Channel.CLI, "ps -u", V9_0))
            .contains(new CoverageState.Covered("find_processes"));
        assertThat(coverage.servedBy(new CoverageKey(Channel.CLI, "ps -u"), V9_0,
            "find_processes")).isTrue();
    }
}
