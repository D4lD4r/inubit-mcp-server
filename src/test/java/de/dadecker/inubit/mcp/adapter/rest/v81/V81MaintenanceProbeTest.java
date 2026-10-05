package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** M1: the 8.1 maintenance probe behind the REST client's 503 mapping (research R-5). */
@Timeout(30)
class V81MaintenanceProbeTest {

    private static final String HEALTHCHECK = "/ibis/rest/healthcheck";
    private static WireMockServer wireMock;

    private final SecretScrubber scrubber = new SecretScrubber();

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

    private static EffectiveNodeConfig server(Duration timeout) {
        return TestNodeConfig.node().baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(timeout).build();
    }

    private Optional<Boolean> probe() {
        try (V81MaintenanceProbe probe = new V81MaintenanceProbe(server(Duration.ofSeconds(5)),
            Optional.empty(), scrubber)) {
            return probe.maintenance();
        }
    }

    private static void healthcheck(int status, String body) {
        wireMock.stubFor(get(urlEqualTo(HEALTHCHECK)).willReturn(aResponse().withStatus(status)
            .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void maintenanceModeOneIsOnAndTheCallIsUnauthenticated() {
        healthcheck(200, "{\"status\":\"OK\",\"maintenancemode\":1,\"timestamp\":\"x\"}");

        assertThat(probe()).contains(true);
        wireMock.verify(1, getRequestedFor(urlEqualTo(HEALTHCHECK))
            .withHeader("Authorization", absent()));
    }

    @Test
    void maintenanceModeZeroIsOff() {
        healthcheck(200, "{\"status\":\"OK\",\"maintenancemode\":0}");

        assertThat(probe()).contains(false);
    }

    @Test
    void booleanFlagsAreAccepted() {
        healthcheck(200, "{\"maintenancemode\":true}");
        assertThat(probe()).contains(true);

        healthcheck(200, "{\"maintenancemode\":false}");
        assertThat(probe()).contains(false);
    }

    @Test
    void aHealthcheckAnswering503WithTheFlagIsOn() {
        healthcheck(503, "{\"status\":\"ERROR\",\"maintenancemode\":1}");

        assertThat(probe()).contains(true);
    }

    @Test
    void failuresAndUnknownShapesGiveNoAnswer() {
        healthcheck(500, "<html>boom</html>");
        assertThat(probe()).isEmpty();

        healthcheck(200, "not json");
        assertThat(probe()).isEmpty();

        healthcheck(200, "{\"status\":\"OK\"}");
        assertThat(probe()).isEmpty();
    }

    @Test
    void theProbeTimeoutIsAtMostTwoSeconds() {
        wireMock.stubFor(get(urlEqualTo(HEALTHCHECK)).willReturn(aResponse()
            .withFixedDelay(5000).withBody("{\"maintenancemode\":1}")));
        long start = System.nanoTime();

        try (V81MaintenanceProbe probe = new V81MaintenanceProbe(server(Duration.ofSeconds(20)),
            Optional.empty(), scrubber)) {
            assertThat(probe.maintenance()).isEmpty();
        }

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void the503OfAnotherEndpointBecomesMaintenanceModeWithOneHealthcheck() {
        wireMock.stubFor(get(urlEqualTo("/ibis/rest/system/info"))
            .willReturn(aResponse().withStatus(503).withBody("<html>unavailable</html>")));
        healthcheck(200, "{\"maintenancemode\":1}");
        EffectiveNodeConfig config = server(Duration.ofSeconds(5));

        try (V81MaintenanceProbe probe = new V81MaintenanceProbe(config, Optional.empty(),
            scrubber);
            InubitHttpClient client = InubitHttpClient.create(config,
                credentials(config), probe, scrubber)) {
            assertThatThrownBy(() -> client.get("/ibis/rest/system/info", Map.of()))
                .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                    e.error().code()).isEqualTo(ErrorCode.MAINTENANCE_MODE));
        }
        wireMock.verify(1, getRequestedFor(urlEqualTo(HEALTHCHECK)));
    }

    @Test
    void aHealthcheckThatItselfAnswers503IsAskedOnlyOnce() {
        wireMock.stubFor(get(urlEqualTo(HEALTHCHECK))
            .willReturn(aResponse().withStatus(503).withBody("<html>unavailable</html>")));
        EffectiveNodeConfig config = server(Duration.ofSeconds(5));

        try (V81MaintenanceProbe probe = new V81MaintenanceProbe(config, Optional.empty(),
            scrubber);
            InubitHttpClient client = InubitHttpClient.create(config,
                credentials(config), probe, scrubber)) {
            assertThatThrownBy(() -> client.get(HEALTHCHECK, Map.of()))
                .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                    e.error().code()).isEqualTo(ErrorCode.UNREACHABLE));
        }
        // one call by the client, one by the probe, no recursion
        wireMock.verify(2, getRequestedFor(urlEqualTo(HEALTHCHECK)));
    }

    private static de.dadecker.inubit.mcp.config.NodeCredentials credentials(
        EffectiveNodeConfig config) {
        return new de.dadecker.inubit.mcp.config.NodeCredentials(config.id(),
            Optional.of(new de.dadecker.inubit.mcp.config.SourcedValue<>("jdoe", "U")),
            Optional.of(new de.dadecker.inubit.mcp.config.SourcedValue<>(
                Secret.of("pw-x"), "P")),
            Optional.empty(), Optional.empty());
    }
}
