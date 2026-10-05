package de.dadecker.inubit.mcp.isolation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ConfigValidator;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.LoadedConfig;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.config.ValidationReport;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 002 T026 (User Story 2, SC-003, quickstart B1–B3): two production wirings in one JVM, profiles
 * {@code acme} and {@code globex}, both with the node {@code test/node1} (write enabled), each on
 * its own HTTPS WireMock server with a fake StartCLI and its own injected environment. Nothing of
 * one profile — credentials, confirmation codes, audit records, export directories — is used by
 * the other, and every result names its profile.
 */
@Timeout(60)
class TwoProfilesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String QUEUE_LOG = "/ibis/rest/log/queueLog";
    private static final String PID = "110219899";
    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final Pattern EXPORT_FILE = Pattern.compile("--exportFile '([^']+)'");

    private static WireMockServer acmeServer;
    private static WireMockServer globexServer;

    @TempDir
    Path home;

    private final List<AutoCloseable> closeables = new ArrayList<>();

    /** One running profile: its MCP client, fake StartCLI and the export files it saw. */
    private record Profile(String name, McpTestClient client, FakeProcessLauncher startCli,
        List<Path> exportFiles) {
    }

    @BeforeAll
    static void start() {
        acmeServer = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        globexServer = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        acmeServer.stop();
        globexServer.stop();
    }

    @BeforeEach
    void reset() {
        for (WireMockServer server : List.of(acmeServer, globexServer)) {
            server.resetAll();
            server.stubFor(post(urlPathEqualTo(QUEUE_LOG)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(queueLogWithRow())));
            server.stubFor(get(urlPathEqualTo("/ibis/rest/system/info"))
                .willReturn(RestFixtures.response("system_info", "xml")));
        }
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : closeables.reversed()) {
            closeable.close();
        }
    }

    private static String queueLogWithRow() {
        return """
            {"queueLog":{"total":1,"success":true,"count":1,"row":[{"owner":"OWNERS",
            "moduleType":"Workflow Connector","moduleName":"Module-9001(23379903)",
            "workflowName":"Sample_Workflow","priority":"normal","node":"ip-192-0-2-1",
            "globalPId":110219899,"startTime":1790861373734,"tag":"","workflowId":110219899,
            "status":{"level":0,"content":"Error"}}]}}""";
    }

    /** {@code <home>/.config/inubit-mcp/<name>.yaml}: write enabled on test/node1, no audit key. */
    private Path profileFile(String name, WireMockServer server) throws IOException {
        Path cliHome = Files.createDirectories(home.resolve("cli-" + name + "/bin")).getParent();
        Files.writeString(cliHome.resolve("bin/startcli.sh"), "#!/bin/sh\n");
        Path file = home.resolve(".config/inubit-mcp/" + name + ".yaml");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, """
            profile:
              name: %s
              description: "%s test profile"
            defaults:
              cliHome: %s
              cliJavaHome: %s
              inventory:
                owner: OWNERS
            groups:
              - name: test
                write:
                  enabled: true
                tls:
                  trustStore: "%s"
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%d
                    versionLine: V8_1
            """.formatted(name, name.toUpperCase(), cliHome, System.getProperty("java.home"),
            TestCertificates.get().trustStore(), server.httpsPort()));
    }

    /**
     * Starts profile {@code name} like the launcher does: {@code --profile name}, credentials
     * from {@code environment} under the profile's prefix, validation (also against the other
     * profile file), the production wiring.
     */
    private Profile start(String name, WireMockServer server, Map<String, String> environment)
        throws IOException {
        profileFile(name, server);
        ConfigLoader loader = new ConfigLoader(environment, home, false);
        LoadedConfig loaded = loader.load(null, name);
        ProfileConfig config = loaded.config();
        SecretScrubber scrubber = new SecretScrubber();
        CredentialResolution credentials = new CredentialResolver(environment, scrubber,
            config.terminology().effectiveOrDefault(), config.credentialPrefix())
            .resolve(config.nodeIds());
        ValidationReport report = new ConfigValidator(Files::exists, environment, false,
            Path.of(System.getProperty("java.io.tmpdir")), loader::otherProfiles)
            .validate(loaded, credentials);
        assertThat(report.errors()).as(name).isEmpty();
        assertThat(report.warnings()).as(name + ": nothing shared with the other profile")
            .isEmpty();
        List<Path> exportFiles = new CopyOnWriteArrayList<>();
        FakeProcessLauncher startCli = exportingStartCli(exportFiles);
        TestWiring wiring = TestWiring.of(config, credentials, scrubber, Files::exists, false,
            startCli, Map.of("PATH", "/usr/bin"));
        closeables.add(wiring);
        McpTestClient client = McpTestClient.start(wiring.serverFactory("test"));
        closeables.add(client);
        client.initialize();
        return new Profile(name, client, startCli, exportFiles);
    }

    private Profile acme() throws IOException {
        return start("acme", acmeServer, Map.of("INUBIT_ACME_TEST_USERNAME", "acme-user",
            "INUBIT_ACME_TEST_PASSWORD", "acme-secret-4711"));
    }

    private Profile globex() throws IOException {
        return start("globex", globexServer, Map.of("INUBIT_GLOBEX_TEST_USERNAME",
            "globex-user", "INUBIT_GLOBEX_TEST_PASSWORD", "globex-secret-4711"));
    }

    /** StartCLI writing the recorded module export to the requested file, which it records. */
    private static FakeProcessLauncher exportingStartCli(List<Path> exportFiles) {
        byte[] modules = FakeProcessLauncher.fixture("export_modules_sample.zip");
        return FakeProcessLauncher.replaying("export_modules_sample").onLaunch(spec -> {
            String command = spec.command().get(spec.command().indexOf("--execCommand") + 1);
            Matcher matcher = EXPORT_FILE.matcher(command);
            if (matcher.find()) {
                Path file = Path.of(matcher.group(1));
                exportFiles.add(file);
                try {
                    Files.write(file, modules);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> authorizations(WireMockServer server) {
        return server.getAllServeEvents().stream()
            .map(event -> event.getRequest())
            .filter(request -> request.containsHeader("Authorization"))
            .map((LoggedRequest request) -> request.getHeader("Authorization"))
            .toList();
    }

    private static JsonNode structured(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        return result.path("structuredContent");
    }

    private static JsonNode toolError(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
        return JSON.readTree(result.path("content").get(0).path("text").asString())
            .path("error");
    }

    private List<JsonNode> auditRecords(String profile) throws IOException {
        Path directory = home.resolve(".inubit-mcp").resolve(profile).resolve("audit");
        List<JsonNode> records = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                for (String line : Files.readAllLines(file)) {
                    records.add(JSON.readTree(line));
                }
            }
        }
        return records;
    }

    private static JsonNode preview(Profile profile) {
        return structured(profile.client().callTool("restart_process", Map.of(
            "node", NODE.value(), "processId", PID, "reason", "isolation test")))
            .path("challenge");
    }

    // --- tests ----------------------------------------------------------------------------------

    @Test
    void eachProfileLogsInWithItsOwnCredentialsOnly() throws IOException {
        Profile acme = acme();
        Profile globex = globex();

        preview(acme);
        preview(globex);

        assertThat(authorizations(acmeServer)).isNotEmpty()
            .containsOnly(basic("acme-user", "acme-secret-4711"));
        assertThat(authorizations(globexServer)).isNotEmpty()
            .containsOnly(basic("globex-user", "globex-secret-4711"));
    }

    @Test
    void oneSharedEnvironmentStillGivesEachProfileOnlyItsOwnVariables() throws IOException {
        // a shell that exports both profiles' variables and the unprefixed 001 names
        Map<String, String> shared = Map.of(
            "INUBIT_ACME_TEST_USERNAME", "acme-user", "INUBIT_ACME_TEST_PASSWORD", "acme-pw-1",
            "INUBIT_GLOBEX_TEST_USERNAME", "globex-user",
            "INUBIT_GLOBEX_TEST_NODE1_PASSWORD", "globex-pw-1",
            "INUBIT_TEST_USERNAME", "legacy-user", "INUBIT_TEST_PASSWORD", "legacy-pw-1");
        profileFile("acme", acmeServer);
        profileFile("globex", globexServer);
        ConfigLoader loader = new ConfigLoader(shared, home, false);

        for (String name : List.of("acme", "globex")) {
            ProfileConfig config = loader.load(null, name).config();
            CredentialResolution resolution = new CredentialResolver(shared,
                new SecretScrubber(), config.credentialPrefix()).resolve(config.nodeIds());

            assertThat(resolution.errors()).as(name).isEmpty();
            assertThat(resolution.credentials(NODE).username().orElseThrow().value())
                .isEqualTo(name + "-user");
            assertThat(resolution.credentials(NODE).password().orElseThrow().sourceVariable())
                .startsWith("INUBIT_" + name.toUpperCase() + "_TEST_");
        }
    }

    @Test
    void aConfirmationCodeOfOneProfileIsRefusedByTheOther() throws IOException {
        Profile acme = acme();
        Profile globex = globex();
        String code = preview(acme).path("confirmationCode").asString();

        JsonNode error = toolError(globex.client().callTool("restart_process", Map.of(
            "node", NODE.value(), "processId", PID, "confirmationCode", code)));

        assertThat(error.path("code").asString()).isEqualTo("CONFIRMATION_INVALID");
        assertThat(acme.startCli().launchCount()).isZero();
        assertThat(globex.startCli().launchCount()).as("no execution").isZero();
    }

    @Test
    void auditRecordsLandInEachProfilesOwnDirectoryAndNameTheProfile() throws IOException {
        Profile acme = acme();
        Profile globex = globex();
        String code = preview(acme).path("confirmationCode").asString();
        globex.client().callTool("restart_process", Map.of(
            "node", NODE.value(), "processId", PID, "confirmationCode", code));

        List<JsonNode> acmeRecords = auditRecords("acme");
        List<JsonNode> globexRecords = auditRecords("globex");

        assertThat(acmeRecords).extracting(record -> record.path("outcome").asString())
            .containsExactly("CHALLENGE_ISSUED");
        assertThat(globexRecords).extracting(record -> record.path("outcome").asString())
            .containsExactly("REFUSED");
        assertThat(acmeRecords).allSatisfy(record -> {
            assertThat(record.path("profile").asString()).isEqualTo("acme");
            assertThat(record.path("account").asString()).isEqualTo("acme-user");
        });
        assertThat(globexRecords).allSatisfy(record -> {
            assertThat(record.path("profile").asString()).isEqualTo("globex");
            assertThat(record.path("account").asString()).isEqualTo("globex-user");
            assertThat(record.path("node").asString()).isEqualTo("test/node1");
        });
        assertThat(Files.exists(home.resolve(".inubit-mcp/audit")))
            .as("the 001 location is not used").isFalse();
    }

    @Test
    void exportDirectoriesCarryTheProfileName() throws IOException {
        Profile acme = acme();
        Profile globex = globex();
        long pid = ProcessHandle.current().pid();

        for (Profile profile : List.of(acme, globex)) {
            JsonNode result = profile.client().callTool("list_inventory", Map.of(
                "target", NODE.value(), "kind", "MODULE"));

            assertThat(structured(result).path("results").get(0).has("error"))
                .as(result.toString()).isFalse();
            assertThat(profile.exportFiles()).as(profile.name()).singleElement()
                .satisfies(file -> {
                    assertThat(file.getParent().getFileName().toString())
                        .startsWith("inubit-mcp-export-" + profile.name() + "-" + pid + "-");
                    assertThat(file.getParent()).as("deleted after the export").doesNotExist();
                });
        }
    }

    @Test
    void listNodesAndEveryToolDescriptionNameTheirProfile() throws IOException {
        for (Profile profile : List.of(acme(), globex())) {
            JsonNode list = structured(profile.client().callTool("list_nodes", Map.of()));

            assertThat(list.path("profile").path("name").asString()).isEqualTo(profile.name());
            assertThat(list.path("groups").get(0).path("nodes").get(0).path("id").asString())
                .isEqualTo("test/node1");
            assertThat(profile.client().listTools().path("tools")).allSatisfy(tool ->
                assertThat(tool.path("description").asString())
                    .startsWith("[" + profile.name() + ": "));
        }
    }
}
