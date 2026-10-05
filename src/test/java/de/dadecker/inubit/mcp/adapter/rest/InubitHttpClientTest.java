package de.dadecker.inubit.mcp.adapter.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToXml;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T021: the REST client against WireMock over HTTPS (research R-5). */
@Timeout(30)
class InubitHttpClientTest {

    private static final TestCertificates CERTS = TestCertificates.get();
    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-pa55!";
    private static final String TOKEN = Base64.getEncoder()
        .encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    private static final String ACCEPT = "application/json, application/xml;q=0.9, */*;q=0.8";
    private static final NodeId ID = NodeId.parse("dev/node1");

    private static WireMockServer wireMock;

    private final SecretScrubber scrubber = new SecretScrubber();
    private final List<InubitHttpClient> clients = new ArrayList<>();
    private MaintenanceProbe probe = MaintenanceProbe.NONE;
    private Secret password;

    @BeforeAll
    static void startWireMock() {
        wireMock = httpsServer(CERTS.localhostKeyStore());
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
        password = scrubber.register(PASSWORD);
        scrubber.registerBasicAuth(USER, password);
    }

    private static WireMockServer httpsServer(Path keyStore) {
        return TestCertificates.httpsWireMock(keyStore);
    }

    private InubitHttpClient track(InubitHttpClient client) {
        clients.add(client);
        return client;
    }

    private static TestNodeConfig trusted(WireMockServer server) {
        return TestNodeConfig.node()
            .baseUrl("https://localhost:" + server.httpsPort())
            .trustStore(CERTS.trustStore());
    }

    private InubitHttpClient client(EffectiveNodeConfig config) {
        return track(new InubitHttpClient(config,
            Optional.of(new InubitHttpClient.Credentials(USER, password)), Optional.empty(),
            probe, scrubber));
    }

    private InubitHttpClient client() {
        return client(trusted(wireMock).build());
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = InubitHttpClientTest.class.getResourceAsStream(
            "/fixtures/v8_1/rest/" + name)) {
            return in.readAllBytes();
        }
    }

    private ToolError errorOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            assertThat(e.error().toString() + e.getMessage())
                .doesNotContain(PASSWORD, TOKEN);
            assertThat(e.error().node()).contains(ID);
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    private void stubStatus(int status, String contentType, String body) {
        wireMock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(status)
            .withHeader("Content-Type", contentType).withBody(body)));
    }

    @Test
    void getSendsBasicAuthAndTheAcceptHeaderAndReturnsTheBody() throws IOException {
        byte[] systemInfo = fixture("system_info.xml");
        wireMock.stubFor(get(urlEqualTo("/ibis/rest/system/info")).willReturn(aResponse()
            .withHeader("Content-Type", "application/xml").withBody(systemInfo)));

        RestResponse response = client().get("/ibis/rest/system/info", Map.of());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.contentType()).contains("application/xml");
        assertThat(response.body()).isEqualTo(systemInfo);
        assertThat(response.bodyText()).contains("SystemInformationList");
        wireMock.verify(getRequestedFor(urlEqualTo("/ibis/rest/system/info"))
            .withHeader("Authorization", equalTo("Basic " + TOKEN))
            .withHeader("Accept", equalTo(ACCEPT)));
    }

    @Test
    void theAuthorizationHeaderIsSentOnEveryRequest() {
        stubStatus(200, "application/json", "{}");
        InubitHttpClient client = client();

        client.get("/ibis/rest/metrics", Map.of("format", "json"));
        client.get("/ibis/rest/metrics", Map.of("format", "json"));

        wireMock.verify(2, getRequestedFor(urlEqualTo("/ibis/rest/metrics?format=json"))
            .withHeader("Authorization", equalTo("Basic " + TOKEN)));
    }

    @Test
    void withoutCredentialsNoAuthorizationHeaderIsSent() {
        stubStatus(200, "application/json", "{\"status\":\"OK\"}");
        InubitHttpClient client = track(new InubitHttpClient(trusted(wireMock).build(),
            Optional.empty(), Optional.empty(), MaintenanceProbe.NONE, scrubber));

        client.get("/ibis/rest/healthcheck", Map.of());

        wireMock.verify(getRequestedFor(urlEqualTo("/ibis/rest/healthcheck"))
            .withHeader("Authorization", absent())
            .withHeader("Accept", equalTo(ACCEPT)));
    }

    @Test
    void getWithoutCredentialsOmitsTheAuthorizationHeaderOfAClientWithCredentials() {
        stubStatus(200, "application/json", "{\"status\":\"OK\"}");
        InubitHttpClient client = client();

        RestResponse response = client.getWithoutCredentials("/ibis/rest/healthcheck", Map.of());
        client.get("/ibis/rest/system/info", Map.of());

        assertThat(response.bodyText()).contains("OK");
        wireMock.verify(getRequestedFor(urlEqualTo("/ibis/rest/healthcheck"))
            .withHeader("Authorization", absent())
            .withHeader("Accept", equalTo(ACCEPT)));
        wireMock.verify(getRequestedFor(urlEqualTo("/ibis/rest/system/info"))
            .withHeader("Authorization", equalTo("Basic " + TOKEN)));
    }

    @Test
    void getWithoutCredentialsMapsErrorsAndAcceptsStatuses() {
        stubStatus(503, "application/json", "{\"status\":\"NOT_READY\"}");
        InubitHttpClient client = client();

        assertThat(client.getWithoutCredentials("/ibis/rest/ready", Map.of(), 503).status())
            .isEqualTo(503);
        assertThat(errorOf(() -> client.getWithoutCredentials("/ibis/rest/ready", Map.of()))
            .code()).isEqualTo(ErrorCode.UNREACHABLE);
    }

    // --- credential guard (Phase 3 review M2) ------------------------------------------------

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T08:00:00Z"));

    private InubitHttpClient guardedClient(CredentialGuard guard) {
        return track(new InubitHttpClient(trusted(wireMock).build(),
            Optional.of(new InubitHttpClient.Credentials(USER, password)), Optional.empty(),
            probe, scrubber, guard));
    }

    private static int authenticatedRequests() {
        return wireMock.findAll(getRequestedFor(anyUrl())
            .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock
                .matching("Basic .+"))).size();
    }

    @Test
    void aRejectedLoginBlocksAuthenticatedCallsForSixtySecondsWithoutNetwork() {
        stubStatus(401, "text/html", "<html>HTTP Status 401</html>");
        CredentialGuard guard = new CredentialGuard(new CredentialVariables("INUBIT", ID), clock);
        InubitHttpClient client = guardedClient(guard);

        assertThat(errorOf(() -> client.get("/ibis/rest/system/info", Map.of())).code())
            .isEqualTo(ErrorCode.AUTH_FAILED);
        ToolError cached = errorOf(() -> client.get("/ibis/rest/metrics", Map.of()));
        clock.advance(Duration.ofSeconds(59));
        ToolError stillCached = errorOf(() -> client.get("/ibis/rest/metrics", Map.of()));

        assertThat(cached.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(cached.message()).contains("not retried for 60 s");
        assertThat(stillCached.message()).contains("not retried for 1 s");
        assertThat(authenticatedRequests()).as("one failed login only").isEqualTo(1);

        client.getWithoutCredentials("/ibis/rest/healthcheck", Map.of(), 401);
        assertThat(wireMock.findAll(getRequestedFor(urlEqualTo("/ibis/rest/healthcheck"))))
            .as("unauthenticated calls are not blocked").hasSize(1);

        clock.advance(Duration.ofSeconds(1));
        errorOf(() -> client.get("/ibis/rest/system/info", Map.of()));
        assertThat(authenticatedRequests()).as("retried after the pause").isEqualTo(2);
    }

    @Test
    void concurrentUnconfirmedCallsMakeOnlyOneLoginAttempt() throws Exception {
        wireMock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(401)
            .withFixedDelay(300).withBody("no")));
        InubitHttpClient client =
            guardedClient(new CredentialGuard(new CredentialVariables("INUBIT", ID), clock));
        List<java.util.concurrent.Future<ErrorCode>> calls = new ArrayList<>();
        try (java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 5; i++) {
                calls.add(executor.submit(() ->
                    errorOf(() -> client.get("/ibis/rest/system/info", Map.of())).code()));
            }
            for (java.util.concurrent.Future<ErrorCode> call : calls) {
                assertThat(call.get()).isEqualTo(ErrorCode.AUTH_FAILED);
            }
        }

        assertThat(authenticatedRequests()).isEqualTo(1);
    }

    @Test
    void successAndForbiddenConfirmTheCredentials() {
        CredentialGuard guard = new CredentialGuard(new CredentialVariables("INUBIT", ID), clock);
        InubitHttpClient client = guardedClient(guard);
        stubStatus(403, "text/html", "forbidden");
        errorOf(() -> client.get("/ibis/rest/metrics", Map.of()));
        assertThat(guard.confirmed()).as("403: authenticated, but not allowed").isTrue();

        CredentialGuard other = new CredentialGuard(new CredentialVariables("INUBIT", ID), clock);
        stubStatus(200, "application/json", "{}");
        guardedClient(other).get("/ibis/rest/metrics", Map.of());
        assertThat(other.confirmed()).isTrue();
    }

    @Test
    void otherFailuresNeitherConfirmNorBlock() {
        CredentialGuard guard = new CredentialGuard(new CredentialVariables("INUBIT", ID), clock);
        InubitHttpClient client = guardedClient(guard);
        stubStatus(500, "text/html", "boom");

        errorOf(() -> client.get("/ibis/rest/metrics", Map.of()));
        errorOf(() -> client.get("/ibis/rest/metrics", Map.of()));

        assertThat(guard.confirmed()).isFalse();
        assertThat(authenticatedRequests()).isEqualTo(2);
    }

    @Test
    void queryParametersAreEncodedInOrder() {
        stubStatus(200, "application/xml", "<ModelList/>");
        Map<String, String> query = new LinkedHashMap<>();
        query.put("user", "OWNERS Group&co");
        query.put("deep", "true");

        client().get("/ibis/rest/model/models", query);

        wireMock.verify(getRequestedFor(
            urlEqualTo("/ibis/rest/model/models?user=OWNERS%20Group%26co&deep=true")));
    }

    @Test
    void pathSegmentsCanBeEncoded() {
        assertThat(InubitHttpClient.encodePathSegment("OWNERS BPD/Ä?x"))
            .isEqualTo("OWNERS%20BPD%2F%C3%84%3Fx");
    }

    @Test
    void postSendsTheBodyWithItsContentType() {
        stubStatus(200, "application/json", "{\"queueLog\":{\"total\":0}}");
        String body = "<logRequest><startIndex>0</startIndex><noOfItems>5</noOfItems>"
            + "</logRequest>";

        RestResponse response = client().post("/ibis/rest/log/queueLog",
            Map.of("format", "json"), body, "application/xml");

        assertThat(response.bodyText()).contains("queueLog");
        wireMock.verify(postRequestedFor(urlPathEqualTo("/ibis/rest/log/queueLog"))
            .withQueryParam("format", equalTo("json"))
            .withHeader("Content-Type", equalTo("application/xml"))
            .withHeader("Authorization", equalTo("Basic " + TOKEN))
            .withHeader("Accept", equalTo(ACCEPT))
            .withRequestBody(equalToXml(body)));
    }

    @Test
    void unauthorizedWithATomcatPageIsAuthFailedWithoutParsingTheBody() throws IOException {
        stubStatus(401, "text/html;charset=utf-8", "<html><head><title>HTTP Status 401 –"
            + " Unauthorized</title></head><body><h1>HTTP Status 401 – Unauthorized</h1>"
            + "<p>Tomcat page</p></body></html>");

        ToolError error = errorOf(() -> client().get("/ibis/rest/system/info", Map.of()));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.excerpt()).isEmpty();
        assertThat(error.toString()).doesNotContain("Tomcat page");
        // Phase 3 re-review N5: the concrete variables of this server, like the guard's message
        assertThat(error.nextStep()).contains("INUBIT_DEV_NODE1_PASSWORD",
            "INUBIT_DEV_PASSWORD", "_USERNAME", "restart the MCP client");
        assertThat(error.nextStep()).doesNotContain("<STAGE>", "<SERVER>");
    }

    @Test
    void forbiddenIsMapped() {
        stubStatus(403, "text/html", "<html>forbidden</html>");

        assertThat(errorOf(() -> client().get("/ibis/rest/metrics", Map.of())).code())
            .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void notFoundIsMapped() {
        stubStatus(404, "text/html", "<html>not found</html>");

        assertThat(errorOf(() -> client().get("/ibis/rest/model/modelByName/x", Map.of()))
            .code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void serviceUnavailableIsMaintenanceModeWhenTheProbeSaysSo() {
        stubStatus(503, "text/html", "<html>Service Unavailable</html>");
        AtomicInteger probes = new AtomicInteger();
        probe = () -> {
            probes.incrementAndGet();
            return Optional.of(true);
        };

        assertThat(errorOf(() -> client().get("/ibis/rest/system/info", Map.of())).code())
            .isEqualTo(ErrorCode.MAINTENANCE_MODE);
        assertThat(probes).hasValue(1);
    }

    @Test
    void serviceUnavailableWithoutMaintenanceIsUnreachableWithAnExcerpt() {
        stubStatus(503, "text/html", "<html><body>Service Unavailable " + PASSWORD
            + "</body></html>");
        probe = () -> Optional.of(false);

        ToolError error = errorOf(() -> client().get("/ibis/rest/system/info", Map.of()));

        assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(error.likelyCause()).isEqualTo("INUBIT answered 503: starting up,"
            + " overloaded, or behind a proxy that is not serving");
        assertThat(error.nextStep()).isEqualTo("run get_health");
        assertThat(error.excerpt()).contains("Service Unavailable ***");
    }

    @Test
    void serviceUnavailableIsUnreachableWhenTheProbeFailsOrKnowsNothing() {
        stubStatus(503, "text/html", "<html>Service Unavailable</html>");

        probe = () -> {
            throw new IllegalStateException("probe broke");
        };
        assertThat(errorOf(() -> client().get("/ibis/rest/system/info", Map.of())).code())
            .isEqualTo(ErrorCode.UNREACHABLE);
        probe = Optional::empty;
        assertThat(errorOf(() -> client().get("/ibis/rest/system/info", Map.of())).code())
            .isEqualTo(ErrorCode.UNREACHABLE);
    }

    @Test
    void otherStatusesDoNotAskTheMaintenanceProbe() {
        stubStatus(500, "text/html", "<html>boom</html>");
        probe = () -> {
            throw new AssertionError("must not be called");
        };

        assertThat(errorOf(() -> client().get("/ibis/rest/system/info", Map.of())).code())
            .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    @Test
    void responsesAboveTheBodyLimitAreRejected() {
        stubStatus(200, "application/json", "x".repeat(10_000));
        InubitHttpClient client = track(new InubitHttpClient(trusted(wireMock).build(),
            Optional.empty(), Optional.empty(), MaintenanceProbe.NONE, scrubber, 1024));

        ToolError error = errorOf(() -> client.get("/ibis/rest/log/systemLog", Map.of()));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains("too large");
        assertThat(InubitHttpClient.MAX_BODY_BYTES).isEqualTo(64L << 20);
    }

    @Test
    void responsesWithinTheBodyLimitAreReturned() {
        stubStatus(200, "application/json", "x".repeat(1024));
        InubitHttpClient client = track(new InubitHttpClient(trusted(wireMock).build(),
            Optional.empty(), Optional.empty(), MaintenanceProbe.NONE, scrubber, 1024));

        assertThat(client.get("/ibis/rest/log/systemLog", Map.of()).body()).hasSize(1024);
    }

    @Test
    void aSecretAcrossTheExcerptWindowBoundaryIsStillScrubbed() {
        // tags fill the first ~4 KB, so after stripping, the text at the 4 KB cut lands at the
        // start of the excerpt; the password straddles that cut
        String tags = "<b></b>".repeat(584);
        String body = tags + "x".repeat(4096 - tags.length() - 6) + "ab " + PASSWORD + " tail";
        stubStatus(500, "text/html", body);

        ToolError error = errorOf(() -> client().get("/ibis/rest/system/info", Map.of()));

        assertThat(error.excerpt()).hasValueSatisfying(excerpt -> assertThat(excerpt)
            .contains("ab").doesNotContain(PASSWORD.substring(0, 3)));
    }

    @Test
    void trustStoreProblemsInTheConstructorNameTheServer() {
        ToolError missing = errorOf(() -> client(TestNodeConfig.node()
            .baseUrl("https://localhost:1").trustStore(Path.of("/no/such/trust.p12")).build()));
        ToolError badPin = errorOf(() -> client(TestNodeConfig.node()
            .baseUrl("https://localhost:1").trustStore(CERTS.trustStore()).pin("xyz").build()));

        assertThat(missing.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(badPin.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(badPin.message()).contains("pinnedCertificateSha256");
    }

    @Test
    void acceptedStatusesAreReturnedInsteadOfMapped() {
        stubStatus(503, "application/json", "{\"status\":\"NOT_READY\",\"message\":\"m\"}");

        RestResponse response = client().get("/ibis/rest/ready", Map.of(), 503);

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.bodyText()).contains("NOT_READY");
    }

    @Test
    void otherStatusesAreUnexpectedResponsesWithAScrubbedBoundedExcerpt() throws IOException {
        String html = new String(fixture("log_processLog.html"), StandardCharsets.UTF_8)
            .replace("[message 1]", "echo " + PASSWORD + " " + TOKEN)
            + "x".repeat(2000);
        stubStatus(500, "text/html;charset=UTF-8", html);

        ToolError error = errorOf(() -> client().get("/ibis/rest/log/processLog",
            Map.of("format", "json")));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains("500");
        assertThat(error.excerpt()).hasValueSatisfying(excerpt -> {
            assertThat(excerpt).hasSizeLessThanOrEqualTo(ToolError.MAX_EXCERPT_LENGTH)
                .contains("DBConnectorFieldNotFoundInAvailableNames", "echo ***")
                .doesNotContain("<p", PASSWORD, TOKEN);
        });
    }

    @Test
    void redirectsAreNotFollowed() {
        wireMock.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(302)
            .withHeader("Location", "https://localhost:" + wireMock.httpsPort() + "/login")));

        assertThat(errorOf(() -> client().get("/ibis/rest/system/info", Map.of())).code())
            .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        wireMock.verify(0, getRequestedFor(urlEqualTo("/login")));
    }

    @Test
    void theConfiguredTimeoutYieldsTimeout() {
        wireMock.stubFor(get(anyUrl()).willReturn(aResponse().withFixedDelay(3000)
            .withBody("late")));
        InubitHttpClient client = client(trusted(wireMock).timeout(Duration.ofMillis(300))
            .build());
        long start = System.nanoTime();

        ToolError error = errorOf(() -> client.get("/ibis/rest/system/info", Map.of()));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void connectionRefusedIsUnreachable() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        InubitHttpClient client = client(TestNodeConfig.node()
            .baseUrl("https://127.0.0.1:" + port).trustStore(CERTS.trustStore()).build());

        assertThat(errorOf(() -> client.get("/ibis/rest/healthcheck", Map.of())).code())
            .isEqualTo(ErrorCode.UNREACHABLE);
    }

    @Test
    void unknownHostIsUnreachable() {
        // .invalid never resolves (RFC 6761); a slow resolver may hit the timeout instead
        InubitHttpClient client = client(TestNodeConfig.node()
            .baseUrl("https://no-such-host.invalid:8443").trustStore(CERTS.trustStore())
            .timeout(Duration.ofSeconds(3)).build());

        assertThat(errorOf(() -> client.get("/ibis/rest/healthcheck", Map.of())).code())
            .isIn(ErrorCode.UNREACHABLE, ErrorCode.TIMEOUT);
    }

    @Test
    void untrustedCertificateIsATlsError() {
        InubitHttpClient client = client(TestNodeConfig.node()
            .baseUrl("https://localhost:" + wireMock.httpsPort()).build()); // JVM default trust

        assertThat(errorOf(() -> client.get("/ibis/rest/healthcheck", Map.of())).code())
            .isEqualTo(ErrorCode.TLS_ERROR);
    }

    @Test
    void pinMismatchIsATlsError() {
        InubitHttpClient client = client(trusted(wireMock)
            .pin(TestCertificates.fingerprint(CERTS.selfsignedCertificate())).build());

        assertThat(errorOf(() -> client.get("/ibis/rest/healthcheck", Map.of())).code())
            .isEqualTo(ErrorCode.TLS_ERROR);
    }

    @Test
    void selfsignedCertificateWorksOnlyWithDisabledHostnameVerificationAndThePin() {
        WireMockServer selfsigned = httpsServer(CERTS.selfsignedKeyStore());
        try {
            selfsigned.stubFor(get(anyUrl()).willReturn(aResponse().withBody("{}")));
            String baseUrl = "https://localhost:" + selfsigned.httpsPort();
            String pin = TestCertificates.fingerprint(CERTS.selfsignedCertificate());
            InubitHttpClient strict = client(TestNodeConfig.node().baseUrl(baseUrl)
                .trustStore(CERTS.trustStore()).pin(pin).build());
            InubitHttpClient relaxed = client(TestNodeConfig.node().baseUrl(baseUrl)
                .trustStore(CERTS.trustStore()).disableHostnameVerification(pin).build());

            assertThat(errorOf(() -> strict.get("/x", Map.of())).code())
                .isEqualTo(ErrorCode.TLS_ERROR);
            assertThat(relaxed.get("/x", Map.of()).status()).isEqualTo(200);
        } finally {
            selfsigned.stop();
        }
    }

    @Test
    void createUsesTheResolvedCredentials() {
        stubStatus(200, "application/json", "{}");
        EffectiveNodeConfig config = trusted(wireMock).build();
        NodeCredentials credentials = new NodeCredentials(ID,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(password, "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());

        track(InubitHttpClient.create(config, credentials, MaintenanceProbe.NONE, scrubber))
            .get("/ibis/rest/metrics", Map.of());

        wireMock.verify(getRequestedFor(urlEqualTo("/ibis/rest/metrics"))
            .withHeader("Authorization", equalTo("Basic " + TOKEN)));
    }

    @Test
    void toStringRevealsNoCredentials() {
        assertThat(client().toString()).doesNotContain(PASSWORD, TOKEN);
        assertThat(new InubitHttpClient.Credentials(USER, password).toString())
            .doesNotContain(PASSWORD);
    }
}
