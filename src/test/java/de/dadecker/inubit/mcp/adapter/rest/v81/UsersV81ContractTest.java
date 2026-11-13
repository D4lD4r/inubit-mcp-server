package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageState;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.ListedUser;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T037 (feature 022, US2, research "Live recording T028", L-6): the REST part of
 * {@code read_users} through {@link V81UserAdapter} against the recorded answer of an 8.1.17
 * server, {@code fixtures/v8_1/rest/admin-users} ({@code GET /user/users?type=processEngineUser},
 * {@code application/xml}: 58 users with {@code id}, {@code email}, {@code type} and for 26 of
 * them {@code firstName} and {@code lastName}, not sorted).
 */
@Timeout(30)
class UsersV81ContractTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static WireMockServer wireMock;

    private final List<InubitHttpClient> clients = new ArrayList<>();

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/user/users"))
            .willReturn(RestFixtures.response("admin-users", "xml")));
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @AfterEach
    void closeClients() {
        clients.forEach(InubitHttpClient::close);
    }

    private List<ListedUser> listUsers() {
        SecretScrubber scrubber = new SecretScrubber();
        InubitHttpClient client = new InubitHttpClient(TestNodeConfig.node().id(ID.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(5))
            .build(),
            Optional.of(new InubitHttpClient.Credentials("jdoe",
                scrubber.register("users-contract-pw"))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
        clients.add(client);
        return new V81UserAdapter(ID, client, logins -> {
            throw new AssertionError("no StartCLI session expected");
        }, () -> {
            throw new AssertionError("no StartCLI run expected");
        }).listUsers();
    }

    @Test
    void theRecordedListGivesEveryLoginWithItsTypeInTheOrderOfTheAnswer() {
        List<ListedUser> users = listUsers();

        assertThat(users).hasSize(58).allSatisfy(user ->
            assertThat(user.type()).isEqualTo("ProcessEngineUser"));
        assertThat(users).extracting(ListedUser::login)
            .startsWith("User01", "User02").endsWith("qa_robot", "User57", "User58")
            .contains("jdoe", "batch-user", "user42").doesNotHaveDuplicates();
        assertThat(users.get(19)).isEqualTo(new ListedUser("jdoe", "ProcessEngineUser"));
    }

    /** FR-011: the e-mails and names of the answer are never read into the list. */
    @Test
    void noEmailOrNameOfTheRecordingIsInTheList() {
        List<ListedUser> users = listUsers();

        for (String text : List.of(users.toString(),
            ResultJson.mapper().writeValueAsString(users))) {
            assertThat(text).doesNotContain("@", "example.test", "First", "Last");
        }
    }

    @Test
    void theListIsReachableForTheUsersViewOn81AndPlannedOn90() {
        InterfaceCoverage coverage = InterfaceCoverage.production();

        assertThat(coverage.state(Channel.REST, "GET /user/users", new MajorMinor(8, 1)))
            .contains(new CoverageState.Reachable("read_users", "users"));
        assertThat(coverage.state(Channel.REST, "GET /user/users", new MajorMinor(9, 0)))
            .contains(new CoverageState.Planned());
    }
}
