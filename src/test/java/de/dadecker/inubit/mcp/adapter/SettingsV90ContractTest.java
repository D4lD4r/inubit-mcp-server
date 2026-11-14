package de.dadecker.inubit.mcp.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.CliOutcome;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliSettingsRunner;
import de.dadecker.inubit.mcp.adapter.cli.admin.DbToleranceParser;
import de.dadecker.inubit.mcp.adapter.cli.admin.ServerDateParser;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.MediaRange;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81SettingsAdapter;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CliSettingsSnapshot;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView.Leaf;
import de.dadecker.inubit.mcp.domain.model.CoverageState;
import de.dadecker.inubit.mcp.domain.model.DbConnectionTolerance;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.Masked;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.V90Cases;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
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
 * T026 (feature 022, US1, research "Live recording T024/T028b"): {@code get_server_settings}
 * against the recordings of a 9.0.10 server in {@code fixtures/v9_0/{rest,cli}/admin-*}, opened
 * only through {@link V90Cases}. 9.0 uses the 8.1 adapters ({@link V81SettingsAdapter},
 * {@link CliSettingsRunner}); these tests pin the recorded 9.0 differences they meet.
 *
 * <ul>
 *   <li>REST: {@code GET /license} answers {@code application/xml} with the new
 *       {@code LicenseName} and {@code Environment} elements and a three-space indent;
 *       {@code GET /configurations} answers its JSON body as {@code application/xml}, without the
 *       sections {@code GUIThreadsCount} and {@code GUIThreadPriority}. The stubs answer like 9.0:
 *       a request without the adapter's {@code Accept} gets HTTP 406.
 *   <li>The stdin session: before the first prompt the client prints a Netty {@code ERROR} line
 *       and {@code Connecting as … ?isCli} after {@code Password: }; the root-only commands are
 *       refused as on 8.1, but the session ends with exit code 0. As in the 8.1 contract test, the
 *       two {@code license} blocks are cut, since the runner never sends them (research D-6).
 *   <li>Script mode: the same preamble without {@code Connecting as}, one more empty line after
 *       each answer, and a root-only refusal that is only {@code EXECUTION ERROR} and the
 *       {@code InubitException} line (exit code 1).
 * </ul>
 */
@Timeout(30)
class SettingsV90ContractTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final MajorMinor V9_0 = new MajorMinor(9, 0);
    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-settings-v90-pw";
    private static final String ROOT_ONLY = "Only root user can use this command.";
    private static final String PROMPT = "CLI|jdoe@inubit-dev-2.example.test> ";
    private static final String CUT_FROM = PROMPT + "license -td\n";
    private static final String CUT_TO = PROMPT + "exit\n";
    private static final String WARNING = "SECURITY WARNING: Hostname verification is disabled."
        + " Your connection might not be secured!";
    private static final int MAX_VALUES = 1_000;
    private static final int MAX_CHARS = 200_000;
    /** The planted secrets of the configuration (fixtures/v9_0/README.md). */
    private static final String[] PLANTED = {"synthetic-secret-2401", "synthetic-secret-2402",
        "synthetic-secret-2403"};
    private static final String NOT_ACCEPTABLE = "{\"statusCode\":406,\"message\":\"Not"
        + " Acceptable\",\"path\":\"/ibis/rest\",\"timestamp\":\"2026-11-13T14:00:00.000+00:00\"}";

    private static WireMockServer wireMock;

    @TempDir
    Path cliHome;

    private final List<InubitHttpClient> clients = new ArrayList<>();
    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
        new MutableClock(Instant.parse("2026-11-13T13:00:00Z")));

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
        // INUBIT 9.0 answers a request without the matching Accept with 406 (feature 017, D-11)
        wireMock.stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(406)
            .withHeader("Content-Type", "application/json").withBody(NOT_ACCEPTABLE)));
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/license")).atPriority(1)
            .withHeader("Accept", equalTo(MediaRange.XML.header()))
            .willReturn(V90Cases.response("admin-license")));
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/configurations")).atPriority(1)
            .withHeader("Accept", equalTo(MediaRange.JSON.header()))
            .willReturn(V90Cases.response("admin-configurations")));
        FakeStartCli.install(cliHome);
    }

    @AfterEach
    void closeClients() {
        clients.forEach(InubitHttpClient::close);
    }

    private V81SettingsAdapter adapter() {
        SecretScrubber scrubber = new SecretScrubber();
        InubitHttpClient client = new InubitHttpClient(TestNodeConfig.node().id(DEV.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(5))
            .build(),
            Optional.of(new InubitHttpClient.Credentials(USER,
                scrubber.register("settings-v90-contract-pw"))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
        clients.add(client);
        return new V81SettingsAdapter(DEV, client, parts -> {
            throw new AssertionError("no StartCLI part expected");
        });
    }

    private ConfigurationView section(String name) {
        return adapter().configuration(Optional.of(name), MAX_VALUES, MAX_CHARS);
    }

    private CliSettingsRunner runner(FakeProcessLauncher launcher) {
        CliRunner cli = TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()));
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliSettingsRunner(TestNodeConfig.node()
            .baseUrl("https://inubit-dev-2.example.test:8443").cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-21")).build(), credentials, guard, cli,
            new CliOutputClassifier(new SecretScrubber(), storeLabels("INUBIT", DEV), false));
    }

    /** The recorded session output without the blocks of {@code license -td} and {@code -ts}. */
    private static String sessionWithoutLicenseBlocks() {
        String stdout = V90Cases.text("admin-settings-session", "stdout");
        assertThat(stdout).contains(CUT_FROM).endsWith(CUT_TO);
        return stdout.substring(0, stdout.indexOf(CUT_FROM)) + CUT_TO;
    }

    /**
     * The answer of a recorded script-mode case as a session block (without blank lines), after
     * the recorded 9.0 preamble: {@code JAVA_HOME is set}, the warning, the client's Netty
     * {@code ERROR} line, the warning, {@code Password: }, the warning.
     */
    private static List<String> answer(String fixtureCase) {
        List<String> lines = V90Cases.text(fixtureCase, "stdout").lines()
            .filter(line -> !line.isBlank()).toList();
        assertThat(lines.subList(0, 6)).as("the preamble of " + fixtureCase).satisfiesExactly(
            line -> assertThat(line).isEqualTo("JAVA_HOME is set"),
            line -> assertThat(line).isEqualTo(WARNING),
            line -> assertThat(line).startsWith("ERROR ")
                .contains("DnsServerAddressStreamProviders"),
            line -> assertThat(line).isEqualTo(WARNING),
            line -> assertThat(line).isEqualTo("Password: "),
            line -> assertThat(line).isEqualTo(WARNING));
        return lines.subList(6, lines.size());
    }

    // --- GET /license ------------------------------------------------------------------------

    @Test
    void theRecordedLicenseGivesExpiryThreadsAndAMaskedLicenseeOnly() {
        assertThat(V90Cases.text("admin-license", "http")).contains("application/xml");

        LicenseFacts facts = adapter().license();

        assertThat(facts).isEqualTo(new LicenseFacts(Optional.of(LocalDate.of(2046, 7, 10)),
            Optional.of(999_999L), Optional.of(Masked.MASK_TOKEN)));
        // the new LicenseName element names the licensee too; it is not read
        assertThat(ResultJson.mapper().writeValueAsString(facts))
            .doesNotContain("Fictitious Licensee");
    }

    // --- GET /configurations -----------------------------------------------------------------

    @Test
    void theRecordedConfigurationIsListedByItsSectionNames() {
        assertThat(V90Cases.text("admin-configurations", "http")).contains("application/xml");

        ConfigurationView view = adapter().configuration(Optional.empty(), MAX_VALUES, MAX_CHARS);

        assertThat(view.sections()).hasSize(99).startsWith("TracingMaxBackupIndex",
            "NotifyWorkflow", "AlarmSystem").endsWith("SchemaPoolSize",
            "ServerRepositoryHTTPDirList").contains("SMTPProperties", "ProcessUserServer",
            "ProxyPassword", "JschConfigurationType", "PrivilegedWorkflowThreadsPercentage")
            .doesNotContain("GUIThreadsCount", "GUIThreadPriority");
        assertThat(view.values()).isEmpty();
        assertThat(ResultJson.mapper().writeValueAsString(view)).doesNotContain(PLANTED);
    }

    @Test
    void aSectionThatOnly81HasIsNotFoundOn90() {
        assertThatThrownBy(() -> section("GUIThreadsCount"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
                assertThat(e.error().node()).contains(DEV);
            });
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
        assertThat(section("MailConnectorSupportedTLSversion").values()).containsExactly(
            Leaf.shown("/0", "TLSv1"), Leaf.shown("/1", "TLSv1.1"), Leaf.shown("/2", "TLSv1.2"));
        assertThat(section("SNMPProperties").values()).contains(Leaf.shown("/SNMPPort", "162"),
            Leaf.shown("/SNMPActive", "false"));
        assertThat(section("MimetypesFileType").values()).contains(
            Leaf.shown("/image~1png", "png PNG"), Leaf.shown("/application~1json", "json JSON"));
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

    // --- StartCLI session ---------------------------------------------------------------------

    @Test
    void theRecordedSessionGivesTheToleranceAndTheTimeAndCostsOnlyTheRefusedParts() {
        CliResult recorded = V90Cases.cliResult("admin-settings-session");
        assertThat(recorded.exitCode()).as("9.0 ends a session with refusals with 0").isZero();
        FakeProcessLauncher launcher = FakeProcessLauncher.of(sessionWithoutLicenseBlocks(),
            recorded.stderr(), recorded.exitCode());

        CliSettingsSnapshot settings = runner(launcher).read(Set.of(UnavailablePart.THREADS,
            UnavailablePart.PRIVILEGED_WORKFLOWS, UnavailablePart.DB_CONNECTION_TOLERANCE,
            UnavailablePart.SERVER_TIME));

        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .as("the recorded stdin without the license lines")
            .isEqualTo(V90Cases.text("admin-settings-session", "stdin")
                .replace("<password>", PASSWORD).replace("license -td\n", "")
                .replace("license -ts\n", ""));
        assertThat(settings.dbConnectionTolerance())
            .contains(new DbConnectionTolerance(12000, 75, 60));
        assertThat(settings.serverTime()).contains("Fri Nov 13 14:38:10 CET 2026");
        assertThat(settings.workflowThreads()).isEmpty();
        assertThat(settings.privilegedWorkflows()).isEmpty();
        assertThat(settings.unavailable()).extracting(UnavailablePart::part)
            .containsExactly(UnavailablePart.THREADS, UnavailablePart.PRIVILEGED_WORKFLOWS);
        assertThat(settings.unavailable()).allSatisfy(part ->
            assertThat(part.reason()).startsWith("UNEXPECTED_RESPONSE: "));
    }

    /** The parts the gate admits on 9.0 read the same blocks in a session of their own. */
    @Test
    void theRecordedBlocksOfTheAdmittedPartsAreReadAlone() {
        String recorded = sessionWithoutLicenseBlocks();
        String stdout = recorded.substring(0, recorded.indexOf(PROMPT))
            + recorded.substring(recorded.indexOf(PROMPT + "dbconnectiontolerance show"));
        FakeProcessLauncher launcher = FakeProcessLauncher.of(stdout,
            V90Cases.text("admin-settings-session", "stderr"), 0);

        CliSettingsSnapshot settings = runner(launcher).read(Set.of(
            UnavailablePart.DB_CONNECTION_TOLERANCE, UnavailablePart.SERVER_TIME));

        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\ndbconnectiontolerance show\ndate\nexit\n");
        assertThat(settings.dbConnectionTolerance())
            .contains(new DbConnectionTolerance(12000, 75, 60));
        assertThat(settings.serverTime()).contains("Fri Nov 13 14:38:10 CET 2026");
        assertThat(settings.unavailable()).isEmpty();
    }

    // --- StartCLI script mode -----------------------------------------------------------------

    /** The script-mode answers carry the same forms as the session blocks. */
    @Test
    void theRecordedScriptAnswersHaveTheSessionForms() {
        assertThat(DbToleranceParser.parse(answer("admin-settings-db-tolerance")))
            .isEqualTo(new DbConnectionTolerance(12000, 75, 60));
        assertThat(ServerDateParser.parse(answer("admin-settings-date")))
            .isEqualTo("Fri Nov 13 14:38:21 CET 2026");
        // the two reads GET /license covers (research D-6): bare numbers
        assertThat(answer("admin-settings-license-days")).containsExactly("7210");
        assertThat(answer("admin-settings-license-seconds")).containsExactly("622887694");
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin-settings-threads-refused",
        "admin-settings-privileged-percentage-refused", "admin-settings-privileged-list-refused"})
    void aRootOnlyRefusalIsNeitherALoginFailureNorAMissingCliRight(String fixtureCase) {
        CliResult refused = V90Cases.cliResult(fixtureCase).forClientMajor(OptionalInt.of(9));

        assertThat(refused.exitCode()).isEqualTo(1);
        assertThat(answer(fixtureCase)).hasSize(2).first().isEqualTo("EXECUTION ERROR");
        assertThat(refused.stdout()).contains(ROOT_ONLY);
        assertThat(CliOutputClassifier.isLoginFailure(refused)).isFalse();
        assertThat(CliOutputClassifier.isMissingCliRight(refused)).isFalse();
        assertThat(new CliOutputClassifier(new SecretScrubber()).classify(refused))
            .isInstanceOfSatisfying(CliOutcome.Failure.class, failure -> assertThat(
                failure.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }

    // --- coverage list -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"dbconnectiontolerance show", "date"})
    void theRecordedCliReadsAreReachableOn90(String entry) {
        assertThat(InterfaceCoverage.production().state(Channel.CLI, entry, V9_0))
            .contains(new CoverageState.Reachable("get_server_settings", "default"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /license", "GET /configurations"})
    void theRecordedRestReadsAreReachableOn90(String entry) {
        assertThat(InterfaceCoverage.production().state(Channel.REST, entry, V9_0))
            .contains(new CoverageState.Reachable("get_server_settings", "default"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"license -td", "license -ts"})
    void theLicenseCountersAreCoveredOn90(String entry) {
        assertThat(InterfaceCoverage.production().state(Channel.CLI, entry, V9_0))
            .contains(new CoverageState.Covered("get_server_settings"));
    }
}
