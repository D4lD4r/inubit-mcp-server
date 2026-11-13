package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
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
import de.dadecker.inubit.mcp.domain.model.ListedUser;
import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UserFacts;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Block;
import de.dadecker.inubit.mcp.domain.model.UserFacts.UserGroups;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T033 (feature 022, US2): the REST login list of the 8.1 user directory port against WireMock
 * with the recorded fixture {@code admin-users} (T028); the StartCLI reads go to the given
 * readers.
 */
@Timeout(30)
class V81UserAdapterTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final String USERS = "/ibis/rest/user/users";
    private static final String XML_ACCEPT = "application/xml, */*;q=0.1";
    private static WireMockServer wireMock;

    private final SecretScrubber scrubber = new SecretScrubber();
    private final List<InubitHttpClient> clients = new ArrayList<>();
    private final List<Object> cliCalls = new ArrayList<>();
    private final List<UserFacts> factsAnswer = List.of(new UserFacts("jdoe",
        new Block.Parsed<>(new UserGroups("OPS", "headoffice")),
        new Block.Unavailable<>(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE, "m", "c", "n"))));
    private final List<LoginEntry> loginsAnswer = List.of(new LoginEntry("jdoe",
        Optional.empty()));

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

    @AfterEach
    void closeClients() {
        clients.forEach(InubitHttpClient::close);
    }

    private V81UserAdapter adapter() {
        return adapter(MaintenanceProbe.NONE);
    }

    private V81UserAdapter adapter(MaintenanceProbe probe) {
        InubitHttpClient client = new InubitHttpClient(TestNodeConfig.node().id(ID.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(5))
            .build(),
            Optional.of(new InubitHttpClient.Credentials("jdoe",
                scrubber.register("users-test-pw"))),
            Optional.empty(), probe, scrubber);
        clients.add(client);
        return new V81UserAdapter(ID, client, logins -> {
            cliCalls.add(logins);
            return factsAnswer;
        }, () -> {
            cliCalls.add("--who");
            return loginsAnswer;
        }, () -> {
            cliCalls.add("--nwho");
            return 7;
        });
    }

    private static void stub(int status, String body) {
        wireMock.stubFor(get(urlPathEqualTo(USERS)).willReturn(aResponse().withStatus(status)
            .withHeader("Content-Type", "text/html").withBody(body)));
    }

    private static void assertError(Runnable call, Consumer<ToolError> check) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().node()).contains(ID);
            check.accept(e.error());
        });
    }

    // --- GET /user/users -----------------------------------------------------------------

    @Test
    void theProcessEngineUsersAreListedWithCredentialsFromTheRecordedFixture() {
        wireMock.stubFor(get(urlPathEqualTo(USERS))
            .willReturn(RestFixtures.response("admin-users", "xml")));

        List<ListedUser> users = adapter().listUsers();

        assertThat(users).hasSize(58);
        assertThat(users.getFirst()).isEqualTo(new ListedUser("User01", "ProcessEngineUser"));
        assertThat(users.toString()).as("FR-011: no e-mail in the listed users")
            .doesNotContain("@");
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(USERS))
            .withQueryParam("type", equalTo("processEngineUser"))
            .withHeader("Authorization", matching("Basic .+"))
            .withHeader("Accept", equalTo(XML_ACCEPT)));
        assertThat(cliCalls).as("no StartCLI call").isEmpty();
    }

    @Test
    void anAnswerThatIsNoUserListIsAnUnexpectedResponseOfTheNode() {
        wireMock.stubFor(get(urlPathEqualTo(USERS)).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/xml")
            .withBody("<Other email=\"synthetic-secret-0402@example.test\"/>")));

        assertError(adapter()::listUsers, error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
            assertThat(error.toString()).doesNotContain("synthetic-secret-0402");
        });
    }

    @Test
    void http403IsForbiddenForTheNodeWithoutTheBody() {
        stub(403, "<html>Forbidden synthetic-secret-0401</html>");

        assertError(adapter()::listUsers, error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(error.toString()).doesNotContain("synthetic-secret-0401");
        });
    }

    @Test
    void http503DuringMaintenanceIsMaintenanceMode() {
        stub(503, "");

        assertError(adapter(() -> Optional.of(true))::listUsers,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.MAINTENANCE_MODE));
    }

    @Test
    void aMissingEndpointIsAnUnexpectedResponse() {
        // HTTP 404 of the endpoint itself: another INUBIT version (stale trigger, research D-10)
        stub(404, "");

        assertError(adapter()::listUsers,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }

    // --- StartCLI reads --------------------------------------------------------------------

    @Test
    void theStartCliReadsGoToTheirReadersWithoutRestCall() {
        V81UserAdapter adapter = adapter();

        assertThat(adapter.readUsers(List.of("jdoe"))).isSameAs(factsAnswer);
        assertThat(adapter.loggedIn()).isSameAs(loginsAnswer);
        assertThat(adapter.connectedSystems()).isEqualTo(7);

        assertThat(cliCalls).containsExactly(List.of("jdoe"), "--who", "--nwho");
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }
}
