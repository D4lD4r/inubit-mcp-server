package de.dadecker.inubit.mcp.mcp.tools;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.AdapterGatewayFactory;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.FanOut;
import de.dadecker.inubit.mcp.application.InventoryCache;
import de.dadecker.inubit.mcp.application.InventoryService;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.VersionLine;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The US3 tools with the production adapters: {@code dev/node1} on an HTTPS WireMock server with
 * a fake StartCLI that writes the recorded export ZIPs, {@code qa/node1} on WireMock without
 * CLI home, {@code qa/node2} unreachable ({@code 127.0.0.1:9}).
 */
final class InventoryToolFixture implements AutoCloseable {

    static final String PASSWORD = "inventory-tool-test-pw";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Pattern EXPORT_FILE = Pattern.compile("--exportFile '([^']+)'");

    final SecretScrubber scrubber = new SecretScrubber();
    final FakeProcessLauncher launcher;
    final AdapterGatewayFactory gateways;
    final McpTestClient client;
    private final Path cliHome;

    InventoryToolFixture(WireMockServer dev, WireMockServer integration,
        Function<InventoryService, ToolHandler> tool) {
        this(dev, integration, tool, exportingStartCli(), Duration.ofSeconds(120));
    }

    /**
     * @param launcher         the fake StartCLI of {@code dev/node1}
     * @param exportTimeout    the {@code cliExportTimeout} of {@code dev/node1}
     */
    InventoryToolFixture(WireMockServer dev, WireMockServer integration,
        Function<InventoryService, ToolHandler> tool, FakeProcessLauncher launcher,
        Duration exportTimeout) {
        try {
            cliHome = Files.createTempDirectory("inventory-tool-cli");
            Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
            Files.writeString(script, "#!/bin/sh\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.launcher = launcher;
        List<EffectiveNodeConfig> servers = List.of(
            server("dev/node1", "https://localhost:" + dev.httpsPort()).cliHome(cliHome)
                .cliJavaHome(Path.of("/opt/jdk-17")).cliExportTimeout(exportTimeout).build(),
            server("qa/node1", "https://localhost:" + integration.httpsPort()).build(),
            server("qa/node2", "https://127.0.0.1:9").build());
        List<NodeId> ids = servers.stream().map(EffectiveNodeConfig::id).toList();
        gateways = new AdapterGatewayFactory(servers, new CredentialResolver(Map.of(
            "INUBIT_DEV_USERNAME", "jdoe", "INUBIT_DEV_PASSWORD", PASSWORD,
            "INUBIT_QA_USERNAME", "jdoe", "INUBIT_QA_PASSWORD", PASSWORD), scrubber, "INUBIT")
            .resolve(ids), scrubber, Clock.systemUTC(),
            new CliRunner(launcher, Map.of("PATH", "/usr/bin"), false, new CliResources("acme")));
        Map<NodeId, EffectiveNodeConfig> byId = servers.stream().collect(
            Collectors.toMap(EffectiveNodeConfig::id, Function.identity()));
        InventoryService inventory = new InventoryService(gateways, new TargetResolver(ids),
            new FanOut(), ResultLimiter.withDefaults(),
            new InventoryCache(Clock.systemUTC(), id -> byId.get(id).inventory().cacheTtl()),
            id -> byId.get(id).inventory().owner(), id -> byId.get(id).timeout(),
            id -> byId.get(id).cliExportTimeout(), CliRunner.KILL_GRACE.multipliedBy(2),
            ResultJson::size, Terminology.DEFAULT);
        client = McpTestClient.start(List.of(tool.apply(inventory)), scrubber);
        client.initialize();
    }

    private static TestNodeConfig server(String id, String baseUrl) {
        return TestNodeConfig.node().id(id).baseUrl(baseUrl)
            .trustStore(TestCertificates.get().trustStore()).timeout(TIMEOUT)
            .versionLine(VersionLine.AUTO);
    }

    /** StartCLI writing the recorded module or history export, depending on the command. */
    static FakeProcessLauncher exportingStartCli() {
        byte[] modules = FakeProcessLauncher.fixture("export_modules_sample.zip");
        byte[] history = FakeProcessLauncher.fixture("export_history_sample.zip");
        return FakeProcessLauncher.replaying("export_history_sample").onLaunch(spec -> {
            String command = spec.command().get(spec.command().indexOf("--execCommand") + 1);
            Matcher matcher = EXPORT_FILE.matcher(command);
            if (matcher.find()) {
                try {
                    Files.write(Path.of(matcher.group(1)),
                        command.contains("--exportModule") ? modules : history);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
    }

    /** The recorded REST inventory answers on {@code server}. */
    static void stubInventory(WireMockServer server) {
        server.stubFor(get(urlPathEqualTo("/ibis/rest/system/info"))
            .willReturn(RestFixtures.response("system_info", "xml")));
        server.stubFor(get(urlPathEqualTo("/ibis/rest/model/models"))
            .willReturn(RestFixtures.response("model_models_owner", "xml")));
        server.stubFor(get(urlPathEqualTo("/ibis/rest/model/modelByName/Workflow-0101"))
            .atPriority(1).willReturn(RestFixtures.response("modelByName_sample", "xml")));
        // T126: the module usage index reads every technical workflow; the others have no nodes
        server.stubFor(get(urlPathMatching("/ibis/rest/model/modelByName/.+")).atPriority(10)
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
                .withBody(model("any", ""))));
        server.stubFor(get(urlPathEqualTo("/ibis/rest/model/export/Workflow-0101"))
            .willReturn(aResponse().withStatus(200)
                .withBody(RestFixtures.bytes("model_export_sample.zip"))));
    }

    /** A {@code modelByName} answer of a technical workflow with the given nodes. */
    static String model(String name, String nodes) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ns4:Model name=\"" + name
            + "\" type=\"technical\" ns2:version=\"head\" group=\"G\""
            + " xmlns:ns2=\"inubit.com/ibis/external/base\""
            + " xmlns:ns4=\"inubit.com/ibis/external/model\">" + nodes + "</ns4:Model>";
    }

    @Override
    public void close() {
        client.close();
        gateways.close();
        try (Stream<Path> paths = Files.walk(cliHome)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
