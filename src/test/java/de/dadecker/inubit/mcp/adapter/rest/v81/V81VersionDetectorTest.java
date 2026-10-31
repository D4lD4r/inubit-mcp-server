package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Feature 011 T011: the {@code AUTO} detection marks an HTTP 404 of {@code /system/info} as
 * {@link InubitHttpClient.EndpointMissing} (FR-003, research D-11); every other failure keeps
 * the client's error.
 */
@Timeout(30)
class V81VersionDetectorTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static WireMockServer wireMock;

    private final SecretScrubber scrubber = new SecretScrubber();
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
        client = new InubitHttpClient(TestNodeConfig.node().id(ID.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(5))
            .build(),
            Optional.of(new InubitHttpClient.Credentials("jdoe",
                scrubber.register("detector-test-pw"))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    private static void systemInfo(int status, String body) {
        wireMock.stubFor(get(urlPathEqualTo(V81VersionDetector.SYSTEM_INFO_PATH))
            .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "text/html")
                .withBody(body)));
    }

    private ToolErrorException failure() {
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> V81VersionDetector.detect(ID, client));
        assertThat(e).as("detection failed").isNotNull();
        assertThat(e.error().node()).contains(ID);
        return e;
    }

    @Test
    void theVersionOfTheSystemInformationIsDetected() {
        wireMock.stubFor(get(urlPathEqualTo(V81VersionDetector.SYSTEM_INFO_PATH))
            .willReturn(RestFixtures.response("system_info", "xml")));

        assertThat(V81VersionDetector.detect(ID, client)).isEqualTo("8.1.17");
    }

    @Test
    void a404IsAnUnexpectedResponseWithTheEndpointMissing() {
        systemInfo(404, "<html>Not Found</html>");

        ToolErrorException e = failure();

        assertThat(e).isInstanceOf(InubitHttpClient.EndpointMissing.class);
        assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(e.error().message()).contains("HTTP 404");
    }

    @Test
    void a401IsAnAuthenticationFailureWithoutTheEndpointMissing() {
        systemInfo(401, "");

        ToolErrorException e = failure();

        assertThat(e).isNotInstanceOf(InubitHttpClient.EndpointMissing.class);
        assertThat(e.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
    }

    @Test
    void a500IsAnUnexpectedResponseWithoutTheEndpointMissing() {
        systemInfo(500, "<html>Internal Server Error</html>");

        ToolErrorException e = failure();

        assertThat(e).isNotInstanceOf(InubitHttpClient.EndpointMissing.class);
        assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }
}
