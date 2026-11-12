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
import de.dadecker.inubit.mcp.domain.model.CliSettingsSnapshot;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.Masked;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T020 (feature 022, US1): the REST parts of the 8.1 settings port against WireMock with the
 * reconstructed fixtures {@code admin-license-reconstructed} and
 * {@code admin-configurations-reconstructed}; the StartCLI parts go to the given reader.
 */
@Timeout(30)
class V81SettingsAdapterTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final String LICENSE = "/ibis/rest/license";
    private static final String CONFIGURATIONS = "/ibis/rest/configurations";
    private static final String JSON_ACCEPT = "application/json, */*;q=0.1";
    private static final String XML_ACCEPT = "application/xml, */*;q=0.1";
    private static final int MAX_VALUES = 100;
    private static final int MAX_CHARS = 50_000;
    private static WireMockServer wireMock;

    private final SecretScrubber scrubber = new SecretScrubber();
    private final List<InubitHttpClient> clients = new ArrayList<>();
    private final List<Set<String>> cliCalls = new ArrayList<>();
    private final CliSettingsSnapshot cliAnswer = new CliSettingsSnapshot(Optional.of(12L),
        Optional.empty(), Optional.empty(), Optional.of("2026-11-12 08:00:00"), List.of());

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

    private V81SettingsAdapter adapter() {
        return adapter(MaintenanceProbe.NONE);
    }

    private V81SettingsAdapter adapter(MaintenanceProbe probe) {
        InubitHttpClient client = new InubitHttpClient(TestNodeConfig.node().id(ID.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(5))
            .build(),
            Optional.of(new InubitHttpClient.Credentials("jdoe",
                scrubber.register("settings-test-pw"))),
            Optional.empty(), probe, scrubber);
        clients.add(client);
        return new V81SettingsAdapter(ID, client, parts -> {
            cliCalls.add(parts);
            return cliAnswer;
        });
    }

    private static void stub(String path, int status, String body) {
        wireMock.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(status)
            .withHeader("Content-Type", "text/html").withBody(body)));
    }

    private static void assertError(Runnable call, Consumer<ToolError> check) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().node()).contains(ID);
            check.accept(e.error());
        });
    }

    // --- GET /license --------------------------------------------------------------------

    @Test
    void theLicenseIsReadWithCredentialsFromTheReconstructedFixture() {
        wireMock.stubFor(get(urlPathEqualTo(LICENSE))
            .willReturn(RestFixtures.response("admin-license-reconstructed", "xml")));

        LicenseFacts facts = adapter().license();

        assertThat(facts.expiryDate()).contains(LocalDate.of(2027, 6, 30));
        assertThat(facts.licensedThreads()).contains(32L);
        assertThat(facts.licensee()).contains(Masked.MASK_TOKEN);
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(LICENSE))
            .withHeader("Authorization", matching("Basic .+"))
            .withHeader("Accept", equalTo(XML_ACCEPT)));
        assertThat(cliCalls).as("no StartCLI call").isEmpty();
    }

    // --- GET /configurations -------------------------------------------------------------

    @Test
    void theConfigurationIsListedBySectionNames() {
        wireMock.stubFor(get(urlPathEqualTo(CONFIGURATIONS))
            .willReturn(RestFixtures.response("admin-configurations-reconstructed", "json")));

        ConfigurationView view = adapter().configuration(Optional.empty(), MAX_VALUES, MAX_CHARS);

        assertThat(view.sections()).startsWith("JschConfigurationType", "GUIThreadsCount")
            .hasSize(10);
        assertThat(view.values()).isEmpty();
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(CONFIGURATIONS))
            .withHeader("Authorization", matching("Basic .+"))
            .withHeader("Accept", equalTo(JSON_ACCEPT)));
    }

    @Test
    void oneSectionIsBoundedByTheLimitsOfTheCall() {
        wireMock.stubFor(get(urlPathEqualTo(CONFIGURATIONS))
            .willReturn(RestFixtures.response("admin-configurations-reconstructed", "json")));

        ConfigurationView all = adapter().configuration(Optional.of("JschConfigurationType"),
            MAX_VALUES, MAX_CHARS);
        ConfigurationView one = adapter().configuration(Optional.of("JschConfigurationType"), 1,
            MAX_CHARS);

        assertThat(all.values()).hasSizeGreaterThan(1);
        assertThat(all.truncated()).isFalse();
        assertThat(one.values()).containsExactly(all.values().getFirst());
        assertThat(one.truncated()).isTrue();
    }

    @Test
    void anUnknownSectionIsNotFound() {
        wireMock.stubFor(get(urlPathEqualTo(CONFIGURATIONS))
            .willReturn(RestFixtures.response("admin-configurations-reconstructed", "json")));

        assertError(() -> adapter().configuration(Optional.of("NoSuchSection"), MAX_VALUES,
            MAX_CHARS), error -> assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // --- failures of both endpoints ------------------------------------------------------

    @Test
    void http403NamesTheRoleSystemAdministrator() {
        stub(LICENSE, 403, "<html>Forbidden synthetic-secret-0401</html>");
        stub(CONFIGURATIONS, 403, "<html>Forbidden synthetic-secret-0401</html>");
        V81SettingsAdapter adapter = adapter();

        for (Runnable call : List.<Runnable>of(adapter::license,
            () -> adapter.configuration(Optional.empty(), MAX_VALUES, MAX_CHARS))) {
            assertError(call, error -> {
                assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN);
                assertThat(error.message()).contains("System Administrator", "HTTP 403");
                assertThat(error.nextStep()).contains("System Administrator");
                assertThat(error.toString()).doesNotContain("synthetic-secret-0401");
            });
        }
        assertError(adapter::license,
            error -> assertThat(error.message()).contains("GET " + LICENSE));
        assertError(() -> adapter.configuration(Optional.empty(), MAX_VALUES, MAX_CHARS),
            error -> assertThat(error.message()).contains("GET " + CONFIGURATIONS));
    }

    @Test
    void http403AsAnUnavailablePartNamesTheRole() {
        stub(LICENSE, 403, "");

        assertError(adapter()::license, error ->
            assertThat(UnavailablePart.of(UnavailablePart.LICENSE, error).reason())
                .startsWith("FORBIDDEN: ").contains("System Administrator"));
    }

    @Test
    void http503DuringMaintenanceIsMaintenanceMode() {
        stub(LICENSE, 503, "");
        stub(CONFIGURATIONS, 503, "");
        V81SettingsAdapter adapter = adapter(() -> Optional.of(true));

        assertError(adapter::license,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.MAINTENANCE_MODE));
        assertError(() -> adapter.configuration(Optional.of("GUIThreadsCount"), MAX_VALUES,
            MAX_CHARS), error -> assertThat(error.code()).isEqualTo(ErrorCode.MAINTENANCE_MODE));
    }

    @Test
    void http401KeepsTheSharedMapping() {
        stub(LICENSE, 401, "");

        assertError(adapter()::license, error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(error.message()).doesNotContain("System Administrator");
        });
    }

    @Test
    void aMissingEndpointIsAnUnexpectedResponse() {
        // HTTP 404 of the endpoint itself: another INUBIT version (stale trigger, research D-10)
        stub(LICENSE, 404, "");
        stub(CONFIGURATIONS, 404, "");
        V81SettingsAdapter adapter = adapter();

        assertError(adapter::license,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
        assertError(() -> adapter.configuration(Optional.empty(), MAX_VALUES, MAX_CHARS),
            error -> assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }

    // --- StartCLI parts --------------------------------------------------------------------

    @Test
    void theStartCliPartsGoToTheReaderWithoutRestCall() {
        Set<String> parts = Set.of(UnavailablePart.THREADS, UnavailablePart.SERVER_TIME);

        assertThat(adapter().cliSettings(parts)).isSameAs(cliAnswer);

        assertThat(cliCalls).containsExactly(parts);
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }
}
