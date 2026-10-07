package de.dadecker.inubit.mcp.adapter.soap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToIgnoreCase;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.E2ePort;
import de.dadecker.inubit.mcp.infra.Secret;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T022 (feature 004, research D-17, D-25 H8): the SOAP end-to-end client against WireMock —
 * the envelope is posted unchanged with {@code Content-Type}, {@code SOAPAction} and
 * {@code X-Inubit-Mcp-Test-Id}; basic authentication only from configuration; redirects never
 * followed; paths that leave the base address refused before anything is sent; a timeout is an
 * exchange marked timed out; TLS and connection failures mapped.
 */
@Timeout(30)
class SoapE2eClientTest {

    private static final String PATH = "/ibis/ws/Service-01";
    private static final byte[] ENVELOPE = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
        + "<soapenv:Body><order id=\"42\">Ä &amp; Ö</order></soapenv:Body>"
        + "</soapenv:Envelope>\r\n").getBytes(StandardCharsets.UTF_8);
    private static WireMockServer wireMock;

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

    private static EffectiveNodeConfig server(boolean trusted) {
        TestNodeConfig node = TestNodeConfig.node().id("dev/node1")
            .baseUrl("https://localhost:" + wireMock.httpsPort());
        return (trusted ? node.trustStore(TestCertificates.get().trustStore()) : node).build();
    }

    private static SoapE2eClient client(Optional<SoapE2eClient.BasicAuth> auth) {
        return new SoapE2eClient(server(true), URI.create("https://localhost:"
            + wireMock.httpsPort()), Optional.empty(), auth);
    }

    private static E2ePort.Message message(String path, Duration timeout) {
        return new E2ePort.Message(path, ENVELOPE, Optional.of("urn:order"), "test-id-0001",
            timeout);
    }

    @Test
    void theEnvelopeIsPostedUnchangedWithTheTestIdHeader() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
            .withBody("<ok/>")));

        try (SoapE2eClient client = client(Optional.empty())) {
            E2ePort.Exchange exchange = client.post(message("ibis/ws/Service-01",
                Duration.ofSeconds(5)));

            assertThat(exchange.status()).contains(200);
            assertThat(new String(exchange.body(), StandardCharsets.UTF_8)).isEqualTo("<ok/>");
            assertThat(exchange.timedOut()).isFalse();
            assertThat(exchange.endpoint()).isEqualTo("https://localhost:" + wireMock.httpsPort()
                + PATH);
        }
        wireMock.verify(postRequestedFor(urlEqualTo(PATH))
            .withRequestBody(binaryEqualTo(ENVELOPE))
            // Jetty (WireMock) upper-cases the charset name on receipt
            .withHeader("Content-Type", equalToIgnoreCase("text/xml; charset=utf-8"))
            .withHeader("SOAPAction", equalTo("\"urn:order\""))
            .withHeader("X-Inubit-Mcp-Test-Id", equalTo("test-id-0001"))
            .withoutHeader("Authorization"));
    }

    @Test
    void basicAuthenticationComesOnlyFromTheConfiguration() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));

        try (SoapE2eClient client = client(Optional.of(new SoapE2eClient.BasicAuth("e2e-user",
            Secret.of("e2e-test-pw"))))) {
            client.post(message(PATH, Duration.ofSeconds(5)));
        }

        wireMock.verify(postRequestedFor(urlEqualTo(PATH)).withHeader("Authorization",
            equalTo("Basic " + Base64.getEncoder().encodeToString("e2e-user:e2e-test-pw"
                .getBytes(StandardCharsets.UTF_8)))));
    }

    @Test
    void aRedirectIsNeverFollowedAndAFaultIsAnAnswer() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(302)
            .withHeader("Location", "/elsewhere")));
        wireMock.stubFor(post(urlEqualTo("/ibis/ws/Fault")).willReturn(aResponse()
            .withStatus(500).withBody("<soapenv:Fault/>")));

        try (SoapE2eClient client = client(Optional.empty())) {
            assertThat(client.post(message(PATH, Duration.ofSeconds(5))).status()).contains(302);
            E2ePort.Exchange fault = client.post(message("/ibis/ws/Fault",
                Duration.ofSeconds(5)));
            assertThat(fault.status()).contains(500);
            assertThat(new String(fault.body(), StandardCharsets.UTF_8)).contains("Fault");
        }
        wireMock.verify(0, anyRequestedFor(urlEqualTo("/elsewhere")));
        wireMock.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void aTimeoutIsAnExchangeMarkedTimedOut() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
            .withFixedDelay(3000)));

        try (SoapE2eClient client = client(Optional.empty())) {
            E2ePort.Exchange exchange = client.post(message(PATH, Duration.ofMillis(300)));

            assertThat(exchange.timedOut()).isTrue();
            assertThat(exchange.status()).isEmpty();
            // waited for the time limit, not a fast failure; the HTTP client's timer may fire a
            // little before the client's own measurement reaches 300 ms (CI: 299.7 ms)
            assertThat(exchange.duration()).isGreaterThanOrEqualTo(Duration.ofMillis(250));
        }
    }

    @Test
    void pathsThatLeaveTheBaseAddressAreRefusedBeforeAnythingIsSent() {
        try (SoapE2eClient client = client(Optional.empty())) {
            for (String path : List.of("../x", "/ibis/../../x", "https://evil.example.test/x",
                "//evil.example.test/x", "ibis/ws/x?wsdl", "ibis/ws/x#a", "ibis\\ws", "",
                "ibis/ws/%2e%2e/x")) {
                assertThatThrownBy(() -> client.post(message(path, Duration.ofSeconds(1))))
                    .as(path).isInstanceOfSatisfying(ToolErrorException.class, e ->
                        assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT));
            }
        }
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void anUntrustedCertificateIsATlsErrorAndAClosedPortUnreachable() throws Exception {
        try (SoapE2eClient client = new SoapE2eClient(server(false), URI.create(
            "https://localhost:" + wireMock.httpsPort()), Optional.empty(), Optional.empty())) {
            assertThatThrownBy(() -> client.post(message(PATH, Duration.ofSeconds(5))))
                .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                    assertThat(e.error().code()).isEqualTo(ErrorCode.TLS_ERROR);
                    assertThat(e.error().node()).isPresent();
                });
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        try (SoapE2eClient client = new SoapE2eClient(server(true), URI.create(
            "https://localhost:" + port), Optional.empty(), Optional.empty())) {
            assertThatThrownBy(() -> client.post(message(PATH, Duration.ofSeconds(5))))
                .isInstanceOfSatisfying(ToolErrorException.class, e ->
                    assertThat(e.error().code()).isEqualTo(ErrorCode.UNREACHABLE));
        }
    }
}
