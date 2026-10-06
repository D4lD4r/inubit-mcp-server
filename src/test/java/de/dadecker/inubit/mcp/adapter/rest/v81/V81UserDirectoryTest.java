package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * T009 (feature 004, research D-21, D-25): INUBIT's user list {@code GET
 * /ibis/rest/user/users?type=processEngineUser} through the authenticated REST client, against
 * the neutralized recording {@code fixtures/v8_1/rest/user_users.xml}.
 */
@Timeout(30)
class V81UserDirectoryTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final String PATH = "/ibis/rest/user/users";
    private static final String PASSWORD = "user-directory-test-pw";
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
            Optional.of(new InubitHttpClient.Credentials("jdoe", scrubber.register(PASSWORD))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private V81UserDirectory directory() {
        return new V81UserDirectory(ID, client);
    }

    @Test
    void theRecordedListYieldsTheUserIdsWithAnAuthenticatedRead() {
        wireMock.stubFor(get(urlEqualTo(PATH + "?type=processEngineUser"))
            .willReturn(RestFixtures.response("user_users", "xml")));

        var users = directory().users();

        assertThat(users).hasSize(58).contains("jdoe", "User01", "User57")
            .doesNotContain("OWNERS");
        wireMock.verify(1, getRequestedFor(urlEqualTo(PATH + "?type=processEngineUser"))
            .withHeader("Authorization", matching("Basic .+")));
    }

    @Test
    void entriesWithoutIdAreIgnored() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/xml")
            .withBody("<ns2:UserList xmlns:ns2=\"inubit.com/ibis/external/user\">"
                + "<ns2:User id=\"jdoe\"/><ns2:User email=\"x@example.test\"/>"
                + "<ns2:User id=\" \"/></ns2:UserList>")));

        assertThat(directory().users()).containsExactly("jdoe");
    }

    @Test
    void anotherDocumentIsAnUnexpectedResponse() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/xml").withBody("<ModelList/>")));

        assertThatThrownBy(() -> directory().users())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(ID);
            });
    }

    @Test
    void aRejectedLoginIsAuthFailedWithoutThePassword() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> directory().users())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
                assertThat(e.error().toString()).doesNotContain(PASSWORD);
            });
    }
}
