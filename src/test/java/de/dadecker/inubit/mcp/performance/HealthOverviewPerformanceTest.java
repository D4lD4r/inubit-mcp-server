package de.dadecker.inubit.mcp.performance;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ConfigValidator;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.LoadedConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * T118 / SC-002: an overview of all configured environments (5 stages, 10 servers) returns
 * within 10 s even if one server does not answer.
 *
 * <p>Ten HTTPS WireMock servers behind the production wiring and a YAML configuration with the
 * <b>default</b> request timeout ({@code PT5S}) and {@code versionLine: AUTO}; the four health
 * endpoints of one server answer only after 30 s. {@code get_health} without {@code target}
 * must return within 10 s with nine healthy reports and one {@code TIMEOUT}.
 *
 * <p>Feature 011 (T027, FR-009): an eleventh node, pinned to INUBIT 6.1 and read through a fake
 * StartCLI (monitoring supported for 6.1 by a test matrix), whose session hangs; it is reported
 * with {@code TIMEOUT} within the same 10 s, and its baseUrl (a WireMock server) gets no request.
 */
@Timeout(120)
class HealthOverviewPerformanceTest {

    private static final Duration BUDGET = Duration.ofSeconds(10);
    private static final int HANG_MILLIS = 30_000;
    private static final List<String> STAGES = List.of("dev", "qa", "test", "staging", "prod");
    private static final String SLOW = "staging/node2";
    private static final String LEGACY = "legacy/node1";
    /** 8.1 as in production, 6.1 CLI-only with monitoring supported (as after T040). */
    private static final CapabilityMatrix WITH_61 = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(6, 1, 6, 1),
            Map.of(Capability.MONITORING, SupportState.SUPPORTED),
            Map.of(Capability.MONITORING, Set.of(Channel.CLI)), true),
        new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
            Arrays.stream(Capability.values()).collect(Collectors.toMap(
                capability -> capability, capability -> SupportState.SUPPORTED)))),
        Map.of(Capability.MONITORING, SupportState.UNVERIFIED));

    @TempDir
    Path home;

    private final List<WireMockServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(WireMockServer::stop);
    }

    @Test
    void anOverviewOfTenServersWithOneHangingReturnsWithinTenSeconds() throws Exception {
        StringBuilder yaml = new StringBuilder("profile:\n  name: acme\n")
            .append("x-tls: &tls\n  trustStore: \"")
            .append(TestCertificates.get().trustStore()).append("\"\ngroups:\n");
        Map<String, String> environment = new HashMap<>();
        for (String stage : STAGES) {
            yaml.append("  - name: ").append(stage).append('\n');
            if (stage.equals("prod")) {
                yaml.append("    production: true\n");
            }
            yaml.append("    tls: *tls\n    nodes:\n");
            for (String name : List.of("node1", "node2")) {
                WireMockServer server = TestCertificates.httpsWireMock(
                    TestCertificates.get().localhostKeyStore());
                servers.add(server);
                stubHealth(server, (stage + "/" + name).equals(SLOW) ? HANG_MILLIS : 0);
                yaml.append("      - name: ").append(name).append("\n        baseUrl: https://")
                    .append("localhost:").append(server.httpsPort()).append('\n');
            }
            String prefix = "INUBIT_ACME_" + stage.toUpperCase(java.util.Locale.ROOT);
            environment.put(prefix + "_USERNAME", "jdoe");
            environment.put(prefix + "_PASSWORD", "sc002-test-pw");
        }
        // feature 011: a 6.1 node whose StartCLI session hangs
        WireMockServer legacy = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        legacy.start();
        servers.add(legacy);
        Path cliHome = Files.createDirectories(home.resolve("cli/bin")).getParent();
        Files.writeString(cliHome.resolve("bin/startcli.sh"), "#!/bin/sh\n");
        Path javaHome = Files.createDirectories(home.resolve("java"));
        Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"1.8.0_292\"\n");
        yaml.append("  - name: legacy\n    versionLine: V6_1\n    nodes:\n")
            .append("      - name: node1\n        baseUrl: http://localhost:")
            .append(legacy.port()).append("\n        allowInsecureHttp: true\n")
            .append("        cli:\n          home: \"").append(cliHome).append("\"\n")
            .append("          javaHome: \"").append(javaHome).append("\"\n");
        environment.put("INUBIT_ACME_LEGACY_USERNAME", "jdoe");
        environment.put("INUBIT_ACME_LEGACY_PASSWORD", "sc002-test-pw");
        FakeProcessLauncher help = FakeProcessLauncher.replaying("v6_1", "help");
        FakeProcessLauncher version = FakeProcessLauncher.of("CLI 6.1.0\nSupported inubit"
            + " Process Engine versions from 4.0.1 to 6.1.0.\n", "", 0);
        FakeProcessLauncher session = FakeProcessLauncher.of("", "", 0).hanging();
        ProcessLauncher launcher = spec -> switch (spec.command().size() == 2
            ? spec.command().get(1) : "") {
            case "-h" -> help.launch(spec);
            case "-v" -> version.launch(spec);
            default -> session.launch(spec);
        };
        Path file = Files.writeString(home.resolve("config.yaml"), yaml);
        LoadedConfig loaded = new ConfigLoader(environment, home, false).loadFile(file);
        SecretScrubber scrubber = new SecretScrubber();
        CredentialResolution credentials = new CredentialResolver(environment, scrubber,
            loaded.config().credentialPrefix())
            .resolve(loaded.config().nodeIds());
        assertThat(ConfigValidator.forSystem().validate(loaded, credentials).errors())
            .isEmpty();
        assertThat(loaded.config().effectiveNodes()).hasSize(11)
            .allSatisfy(server -> assertThat(server.timeout()).as("default timeout")
                .isEqualTo(Duration.ofSeconds(5)));

        try (TestWiring wiring = TestWiring.of(loaded.config(), credentials, scrubber,
                Files::exists, false, launcher, Map.of("PATH", "/usr/bin"), WITH_61);
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(), scrubber)) {
            client.initialize();

            long start = System.nanoTime();
            JsonNode result = client.callTool("get_health", Map.of());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(elapsed).as("SC-002: all-servers overview").isLessThan(BUDGET);
            assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
            JsonNode reports = result.path("structuredContent").path("reports");
            assertThat(reports).hasSize(11);
            List<String> healthy = new ArrayList<>();
            List<String> timedOut = new ArrayList<>();
            for (JsonNode report : reports) {
                String id = report.path("node").asString();
                if (report.path("error").path("code").asString().equals("TIMEOUT")) {
                    assertThat(report.path("reachable").asBoolean(true)).isFalse();
                    timedOut.add(id);
                } else if (report.path("reachable").asBoolean()
                    && report.path("ready").asBoolean()
                    && report.path("status").asString().equals("OK")
                    && report.path("version").asString().equals("8.1.17")
                    && !report.has("error")) {
                    healthy.add(id);
                }
            }
            assertThat(healthy).as(reports.toString()).hasSize(9).doesNotContain(SLOW);
            assertThat(timedOut).containsExactly(SLOW, LEGACY);
            JsonNode legacyReport = reports.get(10);
            assertThat(legacyReport.path("node").asString()).isEqualTo(LEGACY);
            assertThat(legacyReport.path("unavailable").findValuesAsString("reason"))
                .as("the unfinished 6.1 parts").hasSize(3)
                .allSatisfy(reason -> assertThat(reason).startsWith("TIMEOUT: "));
            assertThat(session.launchCount()).isEqualTo(1);
            assertThat(legacy.getAllServeEvents()).as("no REST request to a 6.1 node").isEmpty();
            System.err.println("[SC-002] get_health over 11 servers (1 hanging, 1 hanging 6.1): "
                + elapsed.toMillis() + " ms");
        }
    }

    private static void stubHealth(WireMockServer server, int delayMillis) {
        stub(server, "/ibis/rest/healthcheck", "healthcheck", "json", delayMillis);
        stub(server, "/ibis/rest/ready", "ready", "json", delayMillis);
        stub(server, "/ibis/rest/system/info", "system_info", "xml", delayMillis);
        stub(server, "/ibis/rest/metrics", "metrics", "json", delayMillis);
    }

    private static void stub(WireMockServer server, String path, String fixture,
        String extension, int delayMillis) {
        server.stubFor(get(urlPathEqualTo(path))
            .willReturn(RestFixtures.response(fixture, extension).withFixedDelay(delayMillis)));
    }
}
