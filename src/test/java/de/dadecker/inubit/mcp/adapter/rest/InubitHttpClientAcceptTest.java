package de.dadecker.inubit.mcp.adapter.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.MediaRange;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.ResourceKind;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** T027 (feature 017, research D-11): every request sends the {@code Accept} of its range. */
@Timeout(30)
class InubitHttpClientAcceptTest {

    private static final TestCertificates CERTS = TestCertificates.get();
    private static final String PATH = "/ibis/rest/system/info";

    private static WireMockServer wireMock;

    private final SecretScrubber scrubber = new SecretScrubber();
    private final List<InubitHttpClient> clients = new ArrayList<>();

    @BeforeAll
    static void startWireMock() {
        wireMock = TestCertificates.httpsWireMock(CERTS.localhostKeyStore());
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @AfterEach
    void closeClients() {
        clients.forEach(InubitHttpClient::close);
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
        wireMock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/xml").withBody("<a/>")));
    }

    private InubitHttpClient client() {
        Secret password = scrubber.register("S3cr3t-pa55!");
        InubitHttpClient client = new InubitHttpClient(TestNodeConfig.node()
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(CERTS.trustStore()).build(),
            Optional.of(new InubitHttpClient.Credentials("jdoe", password)), Optional.empty(),
            MaintenanceProbe.NONE, scrubber);
        clients.add(client);
        return client;
    }

    @Test
    void theRangesHaveTheHeaderValuesOfD11() {
        assertThat(MediaRange.XML.header()).isEqualTo("application/xml, */*;q=0.1");
        assertThat(MediaRange.JSON.header()).isEqualTo("application/json, */*;q=0.1");
        assertThat(MediaRange.ANY.header()).isEqualTo("*/*");
    }

    @ParameterizedTest
    @EnumSource(MediaRange.class)
    void getSendsTheHeaderOfItsRange(MediaRange range) {
        client().get(RestEndpoint.SYSTEM_INFO, List.of(), Map.of(), range);

        wireMock.verify(getRequestedFor(urlPathEqualTo(PATH))
            .withHeader("Accept", equalTo(range.header())));
    }

    @ParameterizedTest
    @EnumSource(MediaRange.class)
    void getWithAResourceKindSendsTheHeaderOfItsRange(MediaRange range) {
        client().get(RestEndpoint.SYSTEM_INFO, List.of(), Map.of(), range, ResourceKind.ENDPOINT);

        wireMock.verify(getRequestedFor(urlPathEqualTo(PATH))
            .withHeader("Accept", equalTo(range.header())));
    }

    @ParameterizedTest
    @EnumSource(MediaRange.class)
    void getWithoutCredentialsSendsTheHeaderOfItsRangeAndNoAuthorization(MediaRange range) {
        client().getWithoutCredentials(RestEndpoint.HEALTHCHECK, List.of(), Map.of(), range);

        wireMock.verify(getRequestedFor(urlPathEqualTo("/ibis/rest/healthcheck"))
            .withHeader("Accept", equalTo(range.header()))
            .withHeader("Authorization", absent()));
    }

    @ParameterizedTest
    @EnumSource(MediaRange.class)
    void postSendsTheHeaderOfItsRange(MediaRange range) {
        client().post(RestEndpoint.LOG, List.of("systemLog"), Map.of("format", "json"),
            "<logRequest/>",
            "application/xml", range);

        wireMock.verify(postRequestedFor(urlPathEqualTo("/ibis/rest/log/systemLog"))
            .withHeader("Accept", equalTo(range.header()))
            .withHeader("Content-Type", equalTo("application/xml")));
    }

    @ParameterizedTest
    @EnumSource(MediaRange.class)
    void postWithAResourceKindSendsTheHeaderOfItsRange(MediaRange range) {
        client().post(RestEndpoint.LOG, List.of("systemLog"), Map.of(), "<logRequest/>",
            "application/xml",
            range, ResourceKind.ENDPOINT);

        wireMock.verify(postRequestedFor(urlPathEqualTo("/ibis/rest/log/systemLog"))
            .withHeader("Accept", equalTo(range.header())));
    }

    @Test
    void theResourceKindIsKeptWithARange() {
        wireMock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client().get(RestEndpoint.SYSTEM_INFO, List.of(), Map.of(),
            MediaRange.XML,
            ResourceKind.ENDPOINT))
            .isInstanceOf(InubitHttpClient.EndpointMissing.class)
            .extracting(e -> ((ToolErrorException) e).error().code())
            .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThatThrownBy(() -> client().get(RestEndpoint.SYSTEM_INFO, List.of(), Map.of(),
            MediaRange.XML))
            .isInstanceOf(ToolErrorException.class)
            .extracting(e -> ((ToolErrorException) e).error().code())
            .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void acceptedStatusesAreKeptWithARange() {
        wireMock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(503)
            .withHeader("Content-Type", "application/json").withBody("{}")));

        RestResponse response = client().getWithoutCredentials(RestEndpoint.READY, List.of(),
            Map.of(),
            MediaRange.JSON, 503);

        assertThat(response.status()).isEqualTo(503);
    }
}
