package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionParser;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliLibraryRunner;
import de.dadecker.inubit.mcp.adapter.cli.admin.LibraryListParser;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageState;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.LibraryEntry;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Connection;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Property;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T046 (feature 022, US3, research "Live recording T039", D-21): {@code read_model_details}
 * against the recordings of an 8.1.17 server, through {@link V81ModelDetailAdapter} with the
 * library list of a {@link CliLibraryRunner} as wired in production.
 *
 * <ul>
 *   <li>{@code module}: {@code fixtures/v8_1/rest/admin-model-node-by-model*}
 *       ({@code GET /model/nodeByModel/{d}/{id}}, XML; the Database Connector module with the
 *       planted secrets, the IS Connector module, the unknown id {@code 999999});
 *   <li>{@code libraries}: {@code fixtures/v8_1/cli/admin-model-library-*} (script mode) and the
 *       two library blocks of {@code admin-model-session}, both the empty list (L-21);
 *   <li>the entries that are not read: {@code GET /model/nodeByName} (404 on 8.1.17),
 *       {@code GET /process/metadata} (L-19) and {@code GET /isconnector} (L-20).
 * </ul>
 */
@Timeout(30)
class ModelDetailsV81ContractTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final String DIAGRAM = "Workflow-0301";
    private static final String MODULES = "/ibis/rest/model/nodeByModel/Workflow-0301/";
    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-model-contract-pw";
    /** The planted secrets of {@code admin-model-node-by-model.xml}. */
    private static final String[] PLANTED = {"synthetic-secret-3901", "synthetic-secret-3902"};
    private static final MajorMinor V8_1 = new MajorMinor(8, 1);
    private static final MajorMinor V9_0 = new MajorMinor(9, 0);
    private static WireMockServer wireMock;

    @TempDir
    Path cliHome;

    private final List<InubitHttpClient> clients = new ArrayList<>();
    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", ID),
        new MutableClock(Instant.parse("2026-11-14T08:00:00Z")));

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        wireMock.stubFor(get(urlPathEqualTo(MODULES + "100"))
            .willReturn(RestFixtures.response("admin-model-node-by-model", "xml")));
        wireMock.stubFor(get(urlPathEqualTo(MODULES + "34"))
            .willReturn(RestFixtures.response("admin-model-node-by-model-is-connector", "xml")));
        wireMock.stubFor(get(urlPathEqualTo(MODULES + "49"))
            .willReturn(RestFixtures.response("admin-model-node-by-model-sd-ext-system", "xml")));
        wireMock.stubFor(get(urlPathEqualTo(MODULES + "999999"))
            .willReturn(RestFixtures.response("admin-model-node-by-model-missing", "html")));
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void installFakeCli() throws IOException {
        FakeStartCli.install(cliHome);
    }

    @AfterEach
    void closeClients() {
        clients.forEach(InubitHttpClient::close);
    }

    private V81ModelDetailAdapter adapter(FakeProcessLauncher launcher) {
        SecretScrubber scrubber = new SecretScrubber();
        InubitHttpClient client = new InubitHttpClient(TestNodeConfig.node().id(ID.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(5))
            .build(),
            Optional.of(new InubitHttpClient.Credentials(USER, scrubber.register(PASSWORD))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
        clients.add(client);
        NodeCredentials credentials = new NodeCredentials(ID,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        CliLibraryRunner libraries = new CliLibraryRunner(TestNodeConfig.node()
            .baseUrl("https://inubit-dev-1.example.test:8443").cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).build(), credentials, guard,
            TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(scrubber, storeLabels("INUBIT", ID), false));
        return new V81ModelDetailAdapter(ID, client, libraries::list);
    }

    private V81ModelDetailAdapter restOnly() {
        return adapter(FakeProcessLauncher.of("", "", 0));
    }

    // --- module: GET /model/nodeByModel/{diagram}/{moduleId} ---------------------------------

    @Test
    void theRecordedDatabaseConnectorModuleKeepsItsPropertiesWithThePlantedSecretsMasked() {
        ModuleDetail detail = restOnly().module(DIAGRAM, "100");

        assertThat(detail.name()).isEqualTo("Demo-DB-Probe");
        assertThat(detail.type()).isEqualTo("twDatabaseConnector");
        assertThat(detail.parentId()).isEmpty();
        assertThat(detail.connections()).containsExactly(new Connection("22", "1"));
        assertThat(detail.properties()).hasSize(38).extracting(Property::name)
            .startsWith("SCHEDULER", "InputConnector", "OutputConnector", "IsActive")
            .contains("dbURL", "user", "driverClass", "staticQuery").doesNotHaveDuplicates();
        assertThat(detail.properties()).filteredOn(Property::masked).extracting(Property::name)
            .as("by type=\"Password\" and by the name only")
            .containsExactlyInAnyOrder("Password", "proxyPassword");
        assertThat(detail.maskedCount()).isEqualTo(2);
        assertThat(detail.properties()).contains(Property.shown("driverClass", "String",
            "org.postgresql.Driver", false));
        assertThat(detail.toString()).doesNotContain(PLANTED);
    }

    @Test
    void theRecordedIsConnectorModuleCarriesItsParent() {
        ModuleDetail detail = restOnly().module(DIAGRAM, "34");

        assertThat(detail.name()).isEqualTo("Demo-IS-Probe");
        assertThat(detail.type()).isEqualTo("twinubitISConfiguration");
        assertThat(detail.parentId()).contains("66");
        assertThat(detail.properties()).extracting(Property::name).contains("ErrorSuppression");
        assertThat(detail.maskedCount()).isZero();
    }

    /** The probed shape of a system diagram element: Map and List properties with entries. */
    @Test
    void theSystemDiagramElementShowsItsSettingsAndLinkedModulesAsEntries() {
        ModuleDetail detail = restOnly().module(DIAGRAM, "49");

        assertThat(detail.type()).isEqualTo("sdExtSystem");
        assertThat(detail.properties()).extracting(Property::name)
            .containsExactly("ResourceType", "ResourceSettings", "ResourcesLinked");
        assertThat(detail.properties().get(2).entries()).extracting(Property::value)
            .containsExactly(Optional.of("Demo-HTTP-Out-A"), Optional.of("Demo-HTTP-Out-B"));
        assertThat(detail.maskedCount()).isEqualTo(7);
        assertThat(detail.toString()).doesNotContain("synthetic-secret-391");
    }

    /** An unknown id answers 500 with the "could not be found" status page, not 404. */
    @Test
    void theRecordedUnknownModuleIdIsNotFoundForTheNode() {
        assertThatThrownBy(() -> restOnly().module(DIAGRAM, "999999"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
                assertThat(e.error().node()).contains(ID);
                assertThat(e.error().message()).contains("999999", DIAGRAM);
                assertThat(e.error().toString()).doesNotContain(PASSWORD);
            });
    }

    // --- libraries: library --list --type driver|plugin --------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"driver", "plugin"})
    void theRecordedEmptyLibraryListIsOneScriptRunPerType(String type) {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("admin-model-library-" + type);

        LibraryEntry.Listing listing = adapter(launcher).libraries(type);

        assertThat(listing).isEqualTo(new LibraryEntry.Listing(List.of(), false));
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(launcher.last().spec().startCliArguments())
            .contains("library --list --type " + type);
    }

    /** The session blocks of the same commands (T039) are the empty list too. */
    @Test
    void theLibraryBlocksOfTheRecordedSessionAreTheEmptyListToo() {
        // The session ran library driver, library plugin, ps -u and fetchXSLT1.0; the command
        // kind only marks the four block boundaries here, not what was sent.
        List<List<String>> blocks = CliSessionParser.STANDARD.blocks(
            FakeProcessLauncher.fixtureText("admin-model-session.stdout"),
            Collections.nCopies(4, CliSessionCommand.UNAME));

        assertThat(LibraryListParser.parse("driver", blocks.get(0)))
            .isEqualTo(new LibraryEntry.Listing(List.of(), false));
        assertThat(LibraryListParser.parse("plugin", blocks.get(1)))
            .isEqualTo(new LibraryEntry.Listing(List.of(), false));
    }

    // --- entries that are not read -----------------------------------------------------------

    /**
     * {@code nodeByName} answered 404 "Not Found" for an existing diagram and module on 8.1.17:
     * the server does not offer it, so it has no {@link RestEndpoint}.
     */
    @Test
    void nodeByNameIsAbsentOn8117AndNeverSent() {
        assertThat(RestFixtures.text("admin-model-node-by-name-not-found.http"))
            .contains("status: 404");
        assertThat(Arrays.stream(RestEndpoint.values()).map(RestEndpoint::template))
            .noneMatch(path -> path.contains("nodeByName"));
        assertThat(InterfaceCoverage.production().state(Channel.REST,
            "GET /model/nodeByName/{diagram}/{module}", V8_1))
            .contains(new CoverageState.Excluded("absent on INUBIT 8.1.17 (404)"));
        assertThat(InterfaceCoverage.production().state(Channel.REST,
            "GET /model/nodeByName/{diagram}/{module}", V9_0))
            .contains(new CoverageState.Undocumented());
    }

    /**
     * {@code /process/metadata} gave only "Workflow details not found" (L-19) and
     * {@code /isconnector} refused the profile's login (L-20): neither is sent, both stay planned.
     */
    @Test
    void processMetadataAndIsConnectorStayPlanned() {
        assertThat(RestFixtures.text("admin-model-metadata-not-found.html"))
            .contains("Workflow details not found");
        assertThat(RestFixtures.text("admin-model-isconnector-refused.txt"))
            .startsWith("Authentication failed.");
        assertThat(Arrays.stream(RestEndpoint.values()).map(RestEndpoint::template))
            .noneMatch(path -> path.contains("/process/metadata") || path.contains("isconnector"));
        InterfaceCoverage coverage = InterfaceCoverage.production();
        for (MajorMinor version : List.of(V8_1, V9_0)) {
            assertThat(coverage.state(Channel.REST, "GET /process/metadata/{name}", version))
                .contains(new CoverageState.Planned());
            assertThat(coverage.state(Channel.REST, "GET /isconnector", version))
                .contains(new CoverageState.Planned());
        }
    }

    // --- coverage ------------------------------------------------------------------------------

    /** 9.0: {@code ModelDetailsV90ContractTest} (T046a). */
    @Test
    void theModuleAndLibraryRowsAreReachableByTheirActionsOn81() {
        InterfaceCoverage coverage = InterfaceCoverage.production();

        assertThat(coverage.state(Channel.REST, "GET /model/nodeByModel/{model}/{node}", V8_1))
            .contains(new CoverageState.Reachable("read_model_details", "module"));
        assertThat(coverage.state(Channel.CLI, "library --list", V8_1))
            .contains(new CoverageState.Reachable("read_model_details", "libraries"));
        assertThat(coverage.state(Channel.CLI, "library --type", V8_1))
            .contains(new CoverageState.Reachable("read_model_details", "libraries"));
    }
}
