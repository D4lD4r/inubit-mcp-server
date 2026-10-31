package de.dadecker.inubit.mcp.security;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.FakeCredentialStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Feature 011 (SC-002, research R-15): no INUBIT credential is read from an environment variable.
 * Every name the profile derives ({@link ProfileConfig#credentialNameSet()}) is set in the
 * environment of the production wiring to a distinct value, the credential store double holds
 * other values; a tool run authenticates with the store's values only, and no environment value
 * reaches a request or a result.
 */
@Timeout(60)
class NoEnvironmentCredentialsTest {

    private static final String STORE_USER = "store-user";
    private static final String STORE_PASSWORD = "store-Password-1";
    private static final String SERVICE = "/ibis/ws/Service-01";

    @TempDir
    Path temp;

    private WireMockServer inubit;
    private TestWiring wiring;
    private McpTestClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (wiring != null) {
            wiring.close();
        }
        if (inubit != null) {
            inubit.stop();
        }
    }

    @Test
    void noEnvironmentValueIsEverUsed() {
        inubit = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        inubit.stubFor(get(urlPathEqualTo("/ibis/rest/healthcheck"))
            .willReturn(RestFixtures.response("healthcheck", "json")));
        inubit.stubFor(get(urlPathEqualTo("/ibis/rest/system/info"))
            .willReturn(RestFixtures.response("system_info", "xml")));
        String yaml = """
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            groups:
              - name: dev
                versionLine: V8_1
                tls:
                  trustStore: %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%d
            """.formatted(temp.resolve("audit"), temp.resolve("workspace"),
            TestCertificates.get().trustStore(), inubit.httpsPort());
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        Map<String, String> environment = new HashMap<>();
        List<String> environmentValues = new ArrayList<>();
        int i = 0;
        for (String name : config.credentialNameSet()) {
            String value = "env-value-" + (i++) + "-" + name.toLowerCase();
            environment.put(name, value);
            environmentValues.add(value);
        }
        assertThat(environment).containsKeys("INUBIT_ACME_DEV_USERNAME",
            "INUBIT_ACME_DEV_NODE1_PASSWORD", "INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD",
            "INUBIT_ACME_DEV_E2E_PASSWORD");
        environment.put("PATH", "/usr/bin");
        FakeCredentialStore store = new FakeCredentialStore()
            .put("INUBIT_ACME_DEV_USERNAME", STORE_USER)
            .put("INUBIT_ACME_DEV_PASSWORD", STORE_PASSWORD);
        SecretScrubber scrubber = new SecretScrubber();
        wiring = TestWiring.of(config, store, scrubber, path -> false, false,
            spec -> {
                throw new AssertionError("no StartCLI run expected: " + spec.command());
            }, environment);
        client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        client.initialize();

        JsonNode result = client.callTool("get_health",
            Map.of("target", "dev/node1", "includeSystemInfo", true), Duration.ofSeconds(30));

        List<String> authorizations = inubit.getAllServeEvents().stream()
            .map(event -> event.getRequest())
            .filter(request -> request.containsHeader("Authorization"))
            .map((LoggedRequest request) -> request.getHeader("Authorization"))
            .toList();
        assertThat(authorizations).isNotEmpty().containsOnly("Basic " + Base64.getEncoder()
            .encodeToString((STORE_USER + ":" + STORE_PASSWORD)
                .getBytes(StandardCharsets.UTF_8)));
        String requests = inubit.getAllServeEvents().stream()
            .map(event -> event.getRequest().toString()).reduce("", String::concat);
        for (String value : environmentValues) {
            assertThat(requests).doesNotContain(value);
            assertThat(result.toString()).doesNotContain(value);
        }
        assertThat(store.reads()).extracting(FakeCredentialStore.Read::account)
            .contains("INUBIT_ACME_DEV_PASSWORD");
    }

    @Test
    void theTrustStorePasswordTheEndToEndCredentialsAndStartCliStdinComeFromTheStoreOnly()
        throws Exception {
        // review T013-T014 r1: SC-002 for every kind and channel. dev/node1 has a password-
        // protected trust store (a wrong password breaks TLS) and runs an end-to-end test;
        // cli/node1 kills a process through StartCLI, which gets the password on stdin
        inubit = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        inubit.stubFor(get(urlPathEqualTo("/ibis/rest/healthcheck"))
            .willReturn(RestFixtures.response("healthcheck", "json")));
        inubit.stubFor(get(urlPathEqualTo("/ibis/rest/ready"))
            .willReturn(RestFixtures.response("ready", "json")));
        inubit.stubFor(get(urlPathEqualTo("/ibis/rest/system/info"))
            .willReturn(RestFixtures.response("system_info", "xml")));
        inubit.stubFor(post(urlPathEqualTo("/ibis/rest/log/queueLog")).willReturn(aResponse()
            .withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                {"queueLog":{"total":1,"success":true,"count":1,"row":[{"owner":"OWNERS",
                "moduleName":"M","workflowName":"W","globalPId":110219899,
                "startTime":1790861373734,"workflowId":110219899,
                "status":{"level":0,"content":"Error"}}]}}""")));
        inubit.stubFor(post(urlPathEqualTo(SERVICE)).willReturn(aResponse().withStatus(200)
            .withBody("<ok/>")));
        Path workspace = Files.createDirectories(temp.resolve("workspace/samples")).getParent();
        Files.writeString(workspace.resolve("samples/order.xml"), "<Envelope/>");
        Path cliHome = Files.createDirectories(temp.resolve("cli/bin")).getParent();
        Files.writeString(cliHome.resolve("bin/startcli.sh"), "#!/bin/sh\n");
        String yaml = """
            profile:
              name: acme
            auditDirectory: %1$s
            workspace: %2$s
            groups:
              - name: dev
                versionLine: V8_1
                tls:
                  trustStore: %3$s
                development:
                  enabled: true
                e2eTests: FREE
                e2e:
                  soap:
                    baseUrl: https://localhost:%5$d
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%5$d
              - name: cli
                versionLine: V8_1
                cli:
                  home: %6$s
                  javaHome: /opt/jdk-17
                tls:
                  trustStore: %4$s
                write:
                  enabled: true
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%5$d
            """.formatted(temp.resolve("audit"), workspace,
            TestCertificates.get().protectedTrustStore(), TestCertificates.get().trustStore(),
            inubit.httpsPort(), cliHome);
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        Map<String, String> environment = new HashMap<>();
        List<String> environmentValues = new ArrayList<>();
        int i = 0;
        for (String name : config.credentialNameSet()) {
            String value = "env-value-" + (i++) + "-" + name.toLowerCase();
            environment.put(name, value);
            environmentValues.add(value);
        }
        environment.put("PATH", "/usr/bin");
        FakeCredentialStore store = new FakeCredentialStore()
            .put("INUBIT_ACME_DEV_USERNAME", STORE_USER)
            .put("INUBIT_ACME_DEV_PASSWORD", STORE_PASSWORD)
            .put("INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD", TestCertificates.PASSWORD)
            .put("INUBIT_ACME_DEV_E2E_USERNAME", "store-e2e-user")
            .put("INUBIT_ACME_DEV_E2E_PASSWORD", "store-e2e-Password-2")
            .put("INUBIT_ACME_CLI_USERNAME", STORE_USER)
            .put("INUBIT_ACME_CLI_PASSWORD", "store-cli-Password-3");
        List<FakeProcessLauncher.FakeProcess> launches = new CopyOnWriteArrayList<>();
        ProcessLauncher startCli = spec -> {
            String command = String.join(" ", spec.command());
            FakeProcessLauncher launcher = command.contains("--execCommand")
                && command.contains("kill") ? FakeProcessLauncher.replaying("kill_ok")
                : FakeProcessLauncher.of("", "", 1);
            FakeProcessLauncher.FakeProcess process =
                (FakeProcessLauncher.FakeProcess) launcher.launch(spec);
            launches.add(process);
            return process;
        };
        SecretScrubber scrubber = new SecretScrubber();
        wiring = TestWiring.of(config, store, scrubber, Files::exists, false, startCli,
            environment);
        client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        client.initialize();

        List<JsonNode> results = new ArrayList<>();
        results.add(client.callTool("run_e2e_test", Map.of("node", "dev/node1",
            "envelope", "samples/order.xml", "path", SERVICE), Duration.ofSeconds(30)));
        JsonNode preview = client.callTool("kill_process", Map.of("node", "cli/node1",
            "processId", "110219899"), Duration.ofSeconds(30));
        results.add(preview);
        JsonNode killed = client.callTool("kill_process", Map.of("node", "cli/node1",
            "processId", "110219899", "confirmationCode", preview.path("structuredContent")
                .path("challenge").path("confirmationCode").asString()),
            Duration.ofSeconds(30));
        results.add(killed);

        assertThat(results.get(0).path("structuredContent").path("result").path("status")
            .asInt()).as("TLS with the store's trust store password: %s", results.get(0))
            .isEqualTo(200);
        inubit.verify(1, postRequestedFor(urlPathEqualTo(SERVICE)).withHeader("Authorization",
            equalTo("Basic " + Base64.getEncoder().encodeToString(
                "store-e2e-user:store-e2e-Password-2".getBytes(StandardCharsets.UTF_8)))));
        assertThat(killed.path("structuredContent").path("result").path("outcome").asString())
            .as(killed.toString()).isEqualTo("EXECUTED");
        String stdin = launches.stream()
            .map(process -> new String(process.stdinBytes(), StandardCharsets.UTF_8))
            .reduce("", String::concat);
        assertThat(stdin).contains("store-cli-Password-3");
        String requests = inubit.getAllServeEvents().stream()
            .map(event -> event.getRequest().toString()).reduce("", String::concat);
        String commands = launches.stream().map(process -> process.spec().command().toString())
            .reduce("", String::concat);
        for (String value : environmentValues) {
            assertThat(requests).doesNotContain(value);
            assertThat(stdin).doesNotContain(value);
            assertThat(commands).doesNotContain(value);
            assertThat(launches).allSatisfy(process ->
                assertThat(process.spec().environment()).doesNotContainValue(value));
            results.forEach(result -> assertThat(result.toString()).doesNotContain(value));
        }
    }
}
