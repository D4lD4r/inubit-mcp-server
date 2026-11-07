package de.dadecker.inubit.mcp.adapter.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.MediaRange;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.ResourceKind;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T029 (feature 017, research D-12): a JSON error body with a string {@code message} (INUBIT 9.0:
 * {@code {statusCode, message, path, timestamp}}) becomes the excerpt; the status mapping stays.
 */
@Timeout(30)
class InubitHttpClientJsonErrorTest {

    private static final TestCertificates CERTS = TestCertificates.get();
    private static final String PASSWORD = "S3cr3t-pa55!";
    private static final String PATH = "/ibis/rest/system/info";

    private static WireMockServer wireMock;

    private final SecretScrubber scrubber = new SecretScrubber();
    private final List<InubitHttpClient> clients = new ArrayList<>();
    private MaintenanceProbe probe = MaintenanceProbe.NONE;

    @BeforeAll
    static void startWireMock() {
        wireMock = TestCertificates.httpsWireMock(CERTS.localhostKeyStore());
    }

    @AfterAll
    static void stopWireMock() {
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

    private InubitHttpClient client() {
        Secret password = scrubber.register(PASSWORD);
        InubitHttpClient client = new InubitHttpClient(TestNodeConfig.node()
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(CERTS.trustStore()).build(),
            Optional.of(new InubitHttpClient.Credentials("jdoe", password)), Optional.empty(),
            probe, scrubber);
        clients.add(client);
        return client;
    }

    private static void stub(int status, String contentType, String body) {
        wireMock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(status)
            .withHeader("Content-Type", contentType).withBody(body)));
    }

    private static String json90(int status, String message) {
        return "{\"statusCode\":" + status + ",\"message\":\"" + message + "\",\"path\":\""
            + PATH + "\",\"timestamp\":\"2026-11-06T10:15:30.000+00:00\"}";
    }

    private ToolError errorOf(ResourceKind kind) {
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> client().get(PATH, Map.of(), MediaRange.XML, kind));
        assertThat(e).as("the call must fail").isNotNull();
        return e.error();
    }

    @Test
    void theMessageOfAJsonBodyIsTheExcerptOfAnUnexpectedStatus() {
        stub(500, "application/json", json90(500, "Internal error while reading the models"));

        ToolError error = errorOf(ResourceKind.RESOURCE);

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo("Unexpected HTTP 500 from INUBIT for GET " + PATH);
        assertThat(error.excerpt()).contains("Internal error while reading the models");
    }

    @Test
    void theMessageOfAJsonBodyIsTheExcerptOfAServiceUnavailable() {
        stub(503, "application/json", json90(503, "Service is starting"));
        probe = () -> Optional.of(false);

        ToolError error = errorOf(ResourceKind.RESOURCE);

        assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(error.excerpt()).contains("Service is starting");
    }

    @Test
    void aMaintenanceProbeStillDecidesAJson503() {
        stub(503, "application/json", json90(503, "Maintenance"));
        probe = () -> Optional.of(true);

        assertThat(errorOf(ResourceKind.RESOURCE).code()).isEqualTo(ErrorCode.MAINTENANCE_MODE);
    }

    @Test
    void theMessageIsScrubbed() {
        stub(500, "application/json", json90(500, "Login with " + PASSWORD + " failed"));

        ToolError error = errorOf(ResourceKind.RESOURCE);

        assertThat(error.excerpt()).contains("Login with *** failed");
        assertThat(error.toString()).doesNotContain(PASSWORD, "jdoe:");
    }

    @Test
    void aSecretBehindALongMessageIsScrubbedAndTheExcerptIsCapped() {
        String message = "x".repeat(6000) + " " + PASSWORD;
        stub(500, "application/json", json90(500, message));

        ToolError error = errorOf(ResourceKind.RESOURCE);

        assertThat(error.excerpt()).hasValueSatisfying(excerpt -> assertThat(excerpt)
            .hasSizeLessThanOrEqualTo(ToolError.MAX_EXCERPT_LENGTH)
            .doesNotContain(PASSWORD.substring(0, 3), "statusCode"));
    }

    @Test
    void whitespaceInTheMessageIsCollapsed() {
        stub(500, "application/json", json90(500, "first\\n   second\\tthird"));

        assertThat(errorOf(ResourceKind.RESOURCE).excerpt()).contains("first second third");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"statusCode\":500,\"error\":\"Internal Server Error\"}",
        "{\"statusCode\":500,\"message\":42}",
        "{\"statusCode\":500,\"message\":null}",
        "{\"statusCode\":500,\"message\":\"   \"}",
        "{\"statusCode\":500,\"message\":\"truncated",
        "[\"message\"]"})
    void aJsonBodyWithoutAStringMessageKeepsTheRawExcerpt(String body) {
        stub(500, "application/json", body);

        assertThat(errorOf(ResourceKind.RESOURCE).excerpt())
            .contains(body.replaceAll("\\s+", " ").strip());
    }

    @Test
    void anHtmlBodyKeepsTheExcerptOf081() {
        stub(500, "text/html", "<html><body><h1>Error</h1> {\"message\":\"x\"}</body></html>");

        assertThat(errorOf(ResourceKind.RESOURCE).excerpt()).contains("Error {\"message\":\"x\"}");
    }

    @Test
    void unauthorizedIgnoresAJsonBody() {
        stub(401, "application/json", json90(401, "Bad credentials for jdoe " + PASSWORD));

        ToolError error = errorOf(ResourceKind.ENDPOINT);

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.excerpt()).isEmpty();
        assertThat(error.toString()).doesNotContain(PASSWORD, "Bad credentials");
    }

    @Test
    void theStatusMappingIsUnchangedForJsonBodies() {
        stub(403, "application/json", json90(403, "Forbidden"));
        assertThat(errorOf(ResourceKind.RESOURCE).code()).isEqualTo(ErrorCode.FORBIDDEN);

        stub(404, "application/json", json90(404, "No model"));
        assertThat(errorOf(ResourceKind.RESOURCE).code()).isEqualTo(ErrorCode.NOT_FOUND);
        ToolError missing = errorOf(ResourceKind.ENDPOINT);
        assertThat(missing.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(catchThrowableOfType(InubitHttpClient.EndpointMissing.class,
            () -> client().get(PATH, Map.of(), MediaRange.XML, ResourceKind.ENDPOINT)))
            .isNotNull();

        stub(400, "application/json", json90(400, "Bad request"));
        ToolError other = errorOf(ResourceKind.RESOURCE);
        assertThat(other.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(other.excerpt()).contains("Bad request");
    }
}
