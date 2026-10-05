package de.dadecker.inubit.mcp.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81MaintenanceProbe;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.DiagramDetail;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.DiagramMetadata;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.ModuleEntry;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.VersionHistory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T089: contract test of the combined 8.1 inventory adapter (research R-11): REST on WireMock
 * ({@code model_models_owner.xml}, {@code modelByName_sample.xml}, {@code model_export_sample.zip})
 * and StartCLI on {@link FakeProcessLauncher} ({@code export_history_sample},
 * {@code export_modules_sample}).
 */
@Timeout(60)
class V81InventoryAdapterTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String PASSWORD = "inventory-adapter-pw";
    private static final String SYSTEM_INFO = "/ibis/rest/system/info";
    private static final Pattern EXPORT_FILE = Pattern.compile("--exportFile '([^']+)'");
    private static WireMockServer wireMock;

    @TempDir
    Path cliHome;

    private final SecretScrubber scrubber = new SecretScrubber();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T08:00:00Z"));
    private EffectiveNodeConfig config;
    private NodeCredentials credentials;
    private CredentialGuard guard;
    private InubitHttpClient client;
    private V81MaintenanceProbe probe;

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() throws IOException {
        wireMock.resetAll();
        Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
        config = TestNodeConfig.node().baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).build();
        credentials = new CredentialResolver(Map.of("INUBIT_DEV_USERNAME", "jdoe",
            "INUBIT_DEV_PASSWORD", PASSWORD), scrubber, "INUBIT").resolve(List.of(DEV)).all()
            .get(0);
        guard = new CredentialGuard(new CredentialVariables("INUBIT", DEV), clock);
        wireMock.stubFor(get(urlPathEqualTo(SYSTEM_INFO))
            .willReturn(RestFixtures.response("system_info", "xml")));
        probe = new V81MaintenanceProbe(config, Optional.empty(), scrubber);
        client = InubitHttpClient.create(config, credentials, probe, scrubber, guard);
    }

    @AfterEach
    void tearDown() {
        client.close();
        probe.close();
    }

    private InventoryPort adapter(FakeProcessLauncher launcher) {
        CliRunner runner = new CliRunner(launcher, Map.of("PATH", "/usr/bin"), false,
            new CliResources("acme"));
        return new V81InventoryAdapter(DEV, client, new CliExportRunner(config, credentials,
            guard, runner, new CliOutputClassifier(scrubber)));
    }

    private InventoryPort restOnly() {
        return adapter(FakeProcessLauncher.of("", "", 1));
    }

    /** StartCLI that writes the recorded export ZIP to the requested file. */
    private static FakeProcessLauncher exporting(String fixtureCase) {
        byte[] zip = FakeProcessLauncher.fixture(fixtureCase + ".zip");
        return FakeProcessLauncher.replaying(fixtureCase).onLaunch(spec -> {
            Matcher matcher = EXPORT_FILE.matcher(execCommand(spec.command()));
            if (matcher.find()) {
                try {
                    Files.write(Path.of(matcher.group(1)), zip);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
    }

    private static String execCommand(List<String> command) {
        return command.get(command.indexOf("--execCommand") + 1);
    }

    private static ToolError errorOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    @Test
    void listDiagramsReadsTheModelsOfTheOwner() {
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/model/models"))
            .willReturn(RestFixtures.response("model_models_owner", "xml")));

        List<InventoryItem> diagrams = restOnly().listDiagrams("OWNERS");

        assertThat(diagrams).hasSize(443);
        assertThat(diagrams).allSatisfy(item -> assertThat(item.node()).isEqualTo(DEV));
        assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo("/ibis/rest/model/models"))
            .withQueryParam("user", equalTo("OWNERS"))
            .withHeader("Authorization", matching("Basic .+")))).hasSize(1);
    }

    @Test
    void diagramDetailReadsTheNodesOfTheEncodedNameForTheOwner() {
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/model/modelByName/Sample%20diagram%2001"))
            .willReturn(RestFixtures.response("modelByName_sample", "xml")));

        DiagramDetail detail = restOnly().diagramDetail("OWNERS", "Sample diagram 01");

        assertThat(detail.modules()).hasSize(3);
        assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo(
            "/ibis/rest/model/modelByName/Sample%20diagram%2001"))
            .withQueryParam("user", equalTo("OWNERS")))).hasSize(1);
    }

    @Test
    void diagramMetadataReadsTheExportZipWithoutContentType() {
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/model/export/Workflow-0101"))
            .willReturn(aResponse().withStatus(200)
                .withBody(RestFixtures.bytes("model_export_sample.zip"))));

        DiagramMetadata metadata = restOnly().diagramMetadata("Workflow-0101");

        assertThat(metadata.active()).contains(true);
        assertThat(metadata.checkinComment()).contains("[message 107]");
        assertThat(metadata.owner()).contains("OWNERS");
    }

    @Test
    void versionHistoryRunsTheQuotedGroupExport() {
        FakeProcessLauncher launcher = exporting("export_history_sample");

        VersionHistory history = adapter(launcher).versionHistory("OWNERS", "technical",
            "GRP-41");

        assertThat(history.workflows()).containsKey("Workflow-0101");
        assertThat(history.modules()).containsKey("Module-0028");
        assertThat(execCommand(launcher.last().spec().command())).startsWith(
            "export --exportWorkflowUser 'OWNERS' --exportWorkflowType 'technical'"
                + " --exportWorkflowGroup 'GRP-41' --includeHistory --exportFile '");
        assertThat(launcher.last().spec().command()).doesNotContain(PASSWORD);
    }

    @Test
    void aGroupFailingTheAllowlistIsUnexpectedAndTheCliIsNotLaunched() {
        FakeProcessLauncher launcher = exporting("export_history_sample");

        ToolError error = errorOf(() -> adapter(launcher).versionHistory("OWNERS", "technical",
            "Group 'quoted'"));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains("not supported by CLI quoting");
        assertThat(error.node()).contains(DEV);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void listModulesRunsTheModuleExport() {
        FakeProcessLauncher launcher = exporting("export_modules_sample");

        List<ModuleEntry> modules = adapter(launcher).listModules("OWNERS");

        assertThat(modules).hasSize(20);
        assertThat(modules.get(0).item().owner()).isEqualTo("OWNERS");
        assertThat(execCommand(launcher.last().spec().command())).startsWith(
            "export --exportModule '' --exportModuleGroup '' --exportModuleUser 'OWNERS'");
    }

    @Test
    void restAndCliFailuresAreToolErrorsOfTheServer() {
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/model/modelByName/Unknown"))
            .willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/model/models"))
            .willReturn(aResponse().withStatus(500).withBody("boom")));
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/model/export/W"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                .withBody("<html>login</html>")));
        InventoryPort adapter = adapter(FakeProcessLauncher.replaying("login_failed"));

        ToolError notFound = errorOf(() -> adapter.diagramDetail("OWNERS", "Unknown"));
        ToolError failed = errorOf(() -> adapter.listDiagrams("OWNERS"));
        ToolError notZip = errorOf(() -> adapter.diagramMetadata("W"));
        ToolError login = errorOf(() -> adapter.listModules("OWNERS"));

        assertThat(notFound.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(failed.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(notZip.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(login.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(List.of(notFound, failed, notZip, login))
            .allSatisfy(error -> assertThat(error.node()).contains(DEV));
    }

    // --- review I3: confirmed credentials before a long export ------------------------------

    private static int systemInfoCalls() {
        return wireMock.findAll(getRequestedFor(urlPathEqualTo(SYSTEM_INFO))).size();
    }

    @Test
    void withUnconfirmedCredentialsAnExportIsPrecededByOneCheapRestLogin() {
        List<Integer> callsAtLaunch = new java.util.concurrent.CopyOnWriteArrayList<>();
        FakeProcessLauncher launcher = exporting("export_modules_sample");
        launcher.onLaunch(launcher.onLaunchAction().andThen(spec ->
            callsAtLaunch.add(systemInfoCalls())));

        adapter(launcher).listModules("OWNERS");

        assertThat(callsAtLaunch).containsExactly(1);
        assertThat(guard.confirmed()).isTrue();
        assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo(SYSTEM_INFO))
            .withHeader("Authorization", matching("Basic .+")))).hasSize(1);
    }

    @Test
    void withConfirmedCredentialsTheExportStartsWithoutRestCall() {
        try (CredentialGuard.Permit permit = guard.acquire()) {
            permit.accepted();
        }
        FakeProcessLauncher launcher = exporting("export_history_sample");

        adapter(launcher).versionHistory("OWNERS", "technical", "GRP-41");

        assertThat(systemInfoCalls()).isZero();
        assertThat(launcher.launchCount()).isEqualTo(1);
    }

    @Test
    void aRejectedRestLoginStopsTheExportBeforeStartCli() {
        wireMock.stubFor(get(urlPathEqualTo(SYSTEM_INFO)).willReturn(aResponse().withStatus(401)));
        FakeProcessLauncher launcher = exporting("export_modules_sample");

        ToolError error = errorOf(() -> adapter(launcher).listModules("OWNERS"));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void aForbiddenRestPreflightConfirmsTheCredentialsAndTheExportRuns() {
        // US3 re-review N1: 403 = authenticated but not allowed to read /system/info; the
        // login itself was accepted, so the export must not fail with FORBIDDEN
        wireMock.stubFor(get(urlPathEqualTo(SYSTEM_INFO)).willReturn(aResponse().withStatus(403)));
        FakeProcessLauncher launcher = exporting("export_modules_sample");

        assertThat(adapter(launcher).listModules("OWNERS")).isNotEmpty();
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(guard.confirmed()).isTrue();
    }

    @Test
    void noRestCallIsMadeWhenTheExportCannotRunAnyway() {
        FakeProcessLauncher launcher = exporting("export_history_sample");

        errorOf(() -> adapter(launcher).versionHistory("OWNERS", "technical", "a'b"));
        config = TestNodeConfig.node().baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).build();
        ToolError noCli = errorOf(() -> adapter(launcher).listModules("OWNERS"));

        assertThat(noCli.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(systemInfoCalls()).isZero();
        assertThat(launcher.launchCount()).isZero();
    }
}
