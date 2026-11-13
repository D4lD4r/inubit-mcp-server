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
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView.Leaf;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.Masked;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T025 (feature 022, US1, research "Live recording T014", L-5, L-11): the REST parts of
 * {@code get_server_settings} through {@link V81SettingsAdapter} against the recorded answers of an
 * 8.1.17 server, {@code fixtures/v8_1/rest/admin-license} ({@code text/xml}) and
 * {@code admin-configurations} ({@code application/json}, root key {@code ServerConfiguration},
 * no {@code type} or {@code encrypted} fields, three planted test secrets).
 */
@Timeout(30)
class SettingsV81ContractTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final int MAX_VALUES = 1_000;
    private static final int MAX_CHARS = 200_000;
    /** The planted secrets of the configuration (fixtures/v8_1/cli/README.md). */
    private static final String[] PLANTED = {"synthetic-secret-0401", "synthetic-secret-0402",
        "synthetic-secret-0403"};
    private static WireMockServer wireMock;

    private final List<InubitHttpClient> clients = new ArrayList<>();

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void serveTheRecordings() {
        wireMock.resetAll();
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/license"))
            .willReturn(RestFixtures.response("admin-license", "xml")));
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/configurations"))
            .willReturn(RestFixtures.response("admin-configurations", "json")));
    }

    @AfterEach
    void closeClients() {
        clients.forEach(InubitHttpClient::close);
    }

    private V81SettingsAdapter adapter() {
        SecretScrubber scrubber = new SecretScrubber();
        InubitHttpClient client = new InubitHttpClient(TestNodeConfig.node().id(ID.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(5))
            .build(),
            Optional.of(new InubitHttpClient.Credentials("jdoe",
                scrubber.register("settings-contract-pw"))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
        clients.add(client);
        return new V81SettingsAdapter(ID, client, parts -> {
            throw new AssertionError("no StartCLI part expected");
        });
    }

    private ConfigurationView section(String name) {
        return adapter().configuration(Optional.of(name), MAX_VALUES, MAX_CHARS);
    }

    // --- GET /license ------------------------------------------------------------------------

    @Test
    void theRecordedLicenseGivesExpiryThreadsAndAMaskedLicenseeOnly() {
        assertThat(adapter().license()).isEqualTo(new LicenseFacts(
            Optional.of(LocalDate.of(2046, 10, 8)), Optional.of(999_999L),
            Optional.of(Masked.MASK_TOKEN)));
    }

    // --- GET /configurations -----------------------------------------------------------------

    @Test
    void theRecordedConfigurationIsListedByItsSectionNames() {
        ConfigurationView view = adapter().configuration(Optional.empty(), MAX_VALUES, MAX_CHARS);

        assertThat(view.sections()).hasSize(101).startsWith("TracingMaxBackupIndex",
            "NotifyWorkflow", "AlarmSystem").endsWith("SchemaPoolSize",
            "ServerRepositoryHTTPDirList").contains("SMTPProperties", "ProcessUserServer",
            "ProxyPassword", "JschConfigurationType", "PrivilegedWorkflowThreadsPercentage");
        assertThat(view.values()).isEmpty();
        assertThat(ResultJson.mapper().writeValueAsString(view)).doesNotContain(PLANTED);
    }

    @Test
    void theRecordedSecretsAreMaskedByNameAndByPath() {
        assertThat(section("ProxyPassword").values()).containsExactly(Leaf.masked(""));
        assertThat(section("SMTPProperties").values()).contains(Leaf.shown("/ErrorUser", ""),
            Leaf.masked("/ErrorPassword"), Leaf.shown("/ErrorPort", "25"));
        assertThat(section("ProcessUserServer").values()).contains(
            Leaf.shown("/Login", "jdoe"),
            Leaf.masked("/ProcessUserImTenantAdminCredentials/Value"));
    }

    @Test
    void theRecordedValueFormsAreShownAsText() {
        // numbers, strings with units, booleans and lists, as recorded (research L-11)
        assertThat(section("GUIThreadsCount").values()).containsExactly(Leaf.shown("", "25"));
        assertThat(section("MailConnectorSupportedTLSversion").values()).containsExactly(
            Leaf.shown("/0", "TLSv1"), Leaf.shown("/1", "TLSv1.1"), Leaf.shown("/2", "TLSv1.2"));
        assertThat(section("SNMPProperties").values()).contains(Leaf.shown("/SNMPPort", "162"),
            Leaf.shown("/SNMPActive", "false"));
        assertThat(section("MimetypesFileType").values())
            .contains(Leaf.shown("/image~1png", "png PNG"));
    }

    @Test
    void noPlantedSecretLeavesInAnySection() {
        List<String> names = adapter().configuration(Optional.empty(), MAX_VALUES, MAX_CHARS)
            .sections();
        int masked = 0;

        for (String name : names) {
            ConfigurationView view = section(name);
            assertThat(view.truncated()).as(name).isFalse();
            assertThat(ResultJson.mapper().writeValueAsString(view)).as(name)
                .doesNotContain(PLANTED);
            assertThat(view.toString()).as(name).doesNotContain(PLANTED);
            masked += view.maskedCount();
        }
        assertThat(masked).as("at least the three planted secrets").isGreaterThanOrEqualTo(3);
    }
}
