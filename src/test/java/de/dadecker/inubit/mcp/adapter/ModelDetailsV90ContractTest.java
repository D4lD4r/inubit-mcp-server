package de.dadecker.inubit.mcp.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionParser;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliLibraryRunner;
import de.dadecker.inubit.mcp.adapter.cli.admin.LibraryListParser;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.MediaRange;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81ModelDetailAdapter;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageEntry;
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
import de.dadecker.inubit.mcp.testing.V90Cases;
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
 * T046a (feature 022, US3, research "Live recording T039a"): {@code read_model_details} against
 * the recordings of a 9.0.10 server in {@code fixtures/v9_0/{rest,cli}/admin-model-*}, opened only
 * through {@link V90Cases}. 9.0 uses the 8.1 adapters ({@link V81ModelDetailAdapter},
 * {@link CliLibraryRunner}); these tests pin the recorded 9.0 differences they meet.
 *
 * <ul>
 *   <li>{@code module}: the XML is indented, its root is {@code ns2:Node} with {@code ns2} = model
 *       and {@code ns1} = base; the parser matches local names. Accept JSON gets {@code 406}
 *       ({@code admin-model-node-by-model-json-refused}); the stub answers every request without
 *       the adapter's {@code Accept} with that recording. An unknown id answers a generic
 *       {@code 500} {@code ApiError} that names no module: {@code UNEXPECTED_RESPONSE} with the
 *       hint that the module id probably does not exist (owner decision 2026-11-14);
 *   <li>{@code libraries}: the script-mode answers carry two empty lines after the 9.0 preamble,
 *       the session blocks one empty line; both are the empty list (L-21);
 *   <li>the entries that are not read: {@code GET /model/nodeByName} ({@code 404} on 9.0.10 too,
 *       its 9.0 row stays {@code undocumented}), {@code GET /process/metadata} (L-19) and
 *       {@code GET /isconnector} (L-20).
 * </ul>
 */
@Timeout(30)
class ModelDetailsV90ContractTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final String DIAGRAM = "Workflow-0301";
    private static final String MODULES = "/ibis/rest/model/nodeByModel/Workflow-0301/";
    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-model-v90-pw";
    /** The planted secrets of {@code admin-model-node-by-model.xml}. */
    private static final String[] PLANTED = {"synthetic-secret-3901", "synthetic-secret-3902"};
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
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void serveTheRecordings() throws IOException {
        wireMock.resetAll();
        // INUBIT 9.0.10 answers this endpoint with 406 unless XML is asked for (T039a)
        wireMock.stubFor(any(anyUrl()).atPriority(10)
            .willReturn(V90Cases.response("admin-model-node-by-model-json-refused")));
        for (Map.Entry<String, String> module : Map.of("100", "admin-model-node-by-model",
            "34", "admin-model-node-by-model-is-connector",
            "999999", "admin-model-node-by-model-missing").entrySet()) {
            wireMock.stubFor(get(urlPathEqualTo(MODULES + module.getKey())).atPriority(1)
                .withHeader("Accept", equalTo(MediaRange.XML.header()))
                .willReturn(V90Cases.response(module.getValue())));
        }
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
            .baseUrl("https://inubit-dev-2.example.test:8443").cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-21")).build(), credentials, guard,
            TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(scrubber, storeLabels("INUBIT", ID), false));
        return new V81ModelDetailAdapter(ID, client, libraries::list);
    }

    private V81ModelDetailAdapter restOnly() {
        return adapter(FakeProcessLauncher.of("", "", 0));
    }

    private static FakeProcessLauncher replaying(String fixtureCase) {
        CliResult recorded = V90Cases.cliResult(fixtureCase);
        return FakeProcessLauncher.of(recorded.stdout(), recorded.stderr(), recorded.exitCode());
    }

    // --- module: GET /model/nodeByModel/{diagram}/{moduleId} ---------------------------------

    /** The same 38 properties as on 8.1, in the same order, under other prefixes. */
    @Test
    void theRecordedDatabaseConnectorModuleKeepsItsPropertiesWithThePlantedSecretsMasked() {
        assertThat(V90Cases.text("admin-model-node-by-model", "xml"))
            .contains("<ns2:Node ", "xmlns:ns1=\"inubit.com/ibis/external/base\"",
                "\n    <ns1:Properties>");

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
        assertThat(detail.connections()).containsExactly(new Connection("146", "36"));
        assertThat(detail.properties()).extracting(Property::name).contains("ErrorSuppression");
        assertThat(detail.maskedCount()).isZero();
    }

    /** The adapter asks for XML only, so the recorded {@code 406} for JSON is never met. */
    @Test
    void theAdapterAsksForXmlWhichIsTheOnlyFormThat90Answers() {
        assertThat(V90Cases.text("admin-model-node-by-model-json-refused", "http"))
            .contains("status: 406");

        restOnly().module(DIAGRAM, "100");

        wireMock.verify(1, getRequestedFor(urlPathEqualTo(MODULES + "100"))
            .withHeader("Accept", equalTo(MediaRange.XML.header())));
    }

    /**
     * An unknown id answers {@code 500} with the generic {@code ApiError} "Error while getting
     * node by model", which does not name the module: {@code UNEXPECTED_RESPONSE} with the hint
     * (owner decision 2026-11-14), not {@code NOT_FOUND} as the 8.1 status page.
     */
    @Test
    void theRecordedUnknownModuleIdIsUnexpectedWithTheHintThatItProbablyDoesNotExist() {
        assertThatThrownBy(() -> restOnly().module(DIAGRAM, "999999"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(ID);
                assertThat(e.error().message()).contains("999999", DIAGRAM);
                assertThat(e.error().likelyCause())
                    .contains("the module id probably does not exist in the diagram");
                assertThat(e.error().excerpt()).hasValueSatisfying(excerpt ->
                    assertThat(excerpt).contains("Error while getting node by model"));
                assertThat(e.error().toString()).doesNotContain(PASSWORD);
            });
    }

    // --- libraries: library --list --type driver|plugin --------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"driver", "plugin"})
    void theRecordedEmptyLibraryListIsOneScriptRunPerType(String type) {
        assertThat(V90Cases.text("admin-model-library-" + type, "stdout"))
            .as("the preamble and two empty lines").endsWith("\n\n\n");
        FakeProcessLauncher launcher = replaying("admin-model-library-" + type);

        LibraryEntry.Listing listing = adapter(launcher).libraries(type);

        assertThat(listing).isEqualTo(new LibraryEntry.Listing(List.of(), false));
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(launcher.last().spec().startCliArguments())
            .contains("library --list --type " + type);
    }

    /** The session blocks of the same commands (T039a) are the empty list too. */
    @Test
    void theLibraryBlocksOfTheRecordedSessionAreTheEmptyListToo() {
        // The session ran library driver, library plugin and fetchXSLT1.0 -h; the command kind
        // only marks the three block boundaries here, not what was sent.
        List<List<String>> blocks = CliSessionParser.STANDARD.blocks(
            V90Cases.text("admin-model-session", "stdout"),
            Collections.nCopies(3, CliSessionCommand.UNAME));

        assertThat(LibraryListParser.parse("driver", blocks.get(0)))
            .isEqualTo(new LibraryEntry.Listing(List.of(), false));
        assertThat(LibraryListParser.parse("plugin", blocks.get(1)))
            .isEqualTo(new LibraryEntry.Listing(List.of(), false));
        assertThat(blocks.get(2)).containsExactly("Command not found.");
    }

    // --- entries that are not read -----------------------------------------------------------

    /**
     * {@code nodeByName} answered {@code 404} "Resource Not Found" for an existing diagram and
     * module on 9.0.10 as well; it has no {@link RestEndpoint} and its 9.0 row stays
     * {@code undocumented} (owner decision 2026-11-14).
     */
    @Test
    void nodeByNameIsAbsentOn9010TooAndNeverSent() {
        assertThat(V90Cases.text("admin-model-node-by-name-not-found", "http"))
            .contains("status: 404");
        assertThat(V90Cases.text("admin-model-node-by-name-not-found", "xml"))
            .contains("<message>Resource Not Found</message>");
        assertThat(Arrays.stream(RestEndpoint.values()).map(RestEndpoint::template))
            .noneMatch(path -> path.contains("nodeByName"));
        assertThat(InterfaceCoverage.production().state(Channel.REST,
            "GET /model/nodeByName/{diagram}/{module}", V9_0))
            .contains(new CoverageState.Undocumented());
    }

    /**
     * {@code /process/metadata} gave only a generic {@code ApiError} (the registry is empty, L-19)
     * and {@code /isconnector} refused the profile's login (L-20): neither is sent, both stay
     * planned on 9.0 and wait only for their live check.
     */
    @Test
    void processMetadataAndIsConnectorStayPlannedAndWaitOnlyForTheirLiveChecks() {
        assertThat(V90Cases.text("admin-model-metadata-not-found", "xml"))
            .contains("An unexpected error occurred for workflow");
        assertThat(V90Cases.text("admin-model-isconnector-refused", "xml"))
            .contains("Authentication failed");
        assertThat(Arrays.stream(RestEndpoint.values()).map(RestEndpoint::template))
            .noneMatch(path -> path.contains("/process/metadata") || path.contains("isconnector"));
        for (Map.Entry<String, String> entry : Map.of("GET /process/metadata/{name}", "L-19",
            "GET /isconnector", "L-20").entrySet()) {
            CoverageEntry row = InterfaceCoverage.production().entries().stream()
                .filter(candidate -> candidate.channel() == Channel.REST
                    && candidate.entry().equals(entry.getKey()))
                .findFirst().orElseThrow();
            assertThat(row.states().get(V9_0)).as(entry.getKey())
                .isEqualTo(new CoverageState.Planned());
            assertThat(row.waits()).as(entry.getKey()).containsExactly(entry.getValue());
        }
    }

    // --- coverage ------------------------------------------------------------------------------

    @Test
    void theModuleAndLibraryRowsAreReachableByTheirActionsOn90() {
        InterfaceCoverage coverage = InterfaceCoverage.production();

        assertThat(coverage.state(Channel.REST, "GET /model/nodeByModel/{model}/{node}", V9_0))
            .contains(new CoverageState.Reachable("read_model_details", "module"));
        for (String entry : List.of("library --list", "library --type")) {
            assertThat(coverage.state(Channel.CLI, entry, V9_0)).as(entry)
                .contains(new CoverageState.Reachable("read_model_details", "libraries"));
        }
    }
}
