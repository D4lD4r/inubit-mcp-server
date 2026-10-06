package de.dadecker.inubit.mcp.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.VersionLine;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T029: adapter selection per server and version line (FR-029, Constitution V). {@code AUTO} is
 * resolved lazily, once per server, from {@code /system/info} (recorded 8.1.17 fixture).
 */
@Timeout(60)
class AdapterGatewayFactoryTest {

    private static final String SYSTEM_INFO = "/ibis/rest/system/info";
    private static final String HEALTHCHECK = "/ibis/rest/healthcheck";
    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId ENT2 = NodeId.parse("dev/node2");
    private static WireMockServer wireMock;

    private final SecretScrubber scrubber = new SecretScrubber();

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

    private static String fixture(String name) {
        try (InputStream in = AdapterGatewayFactoryTest.class.getResourceAsStream(
            "/fixtures/v8_1/rest/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String systemInfoWithVersion(String version) {
        return fixture("system_info.xml").replace("value=\"8.1.17\"", "value=\"" + version + "\"");
    }

    private static void systemInfo(int status, String body) {
        wireMock.stubFor(get(urlPathEqualTo(SYSTEM_INFO)).willReturn(aResponse()
            .withStatus(status).withHeader("Content-Type", "application/xml;charset=UTF-8")
            .withBody(body)));
    }

    private static EffectiveNodeConfig server(NodeId id, VersionLine line) {
        return TestNodeConfig.node().id(id.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).versionLine(line).build();
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T08:00:00Z"));

    private AdapterGatewayFactory factory(EffectiveNodeConfig... servers) {
        List<NodeId> ids = new ArrayList<>();
        for (EffectiveNodeConfig server : servers) {
            ids.add(server.id());
        }
        CredentialResolution credentials = new CredentialResolver(
            Map.of("INUBIT_DEV_USERNAME", "jdoe", "INUBIT_DEV_PASSWORD", "gateway-test-pw"),
            scrubber, "INUBIT").resolve(ids);
        return new AdapterGatewayFactory(List.of(servers), credentials, scrubber, clock,
            new CliRunner(new SystemProcessLauncher(), Map.of(), false, new CliResources("acme")));
    }

    private static int systemInfoCalls() {
        return wireMock.findAll(getRequestedFor(urlPathEqualTo(SYSTEM_INFO))).size();
    }

    @Test
    void configuredV81SelectsTheV81AdaptersWithoutCallingTheServer() {
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.V8_1))) {
            Gateway gateway = factory.forServer(DEV);

            assertThat(gateway.node()).isEqualTo(DEV);
            assertThat(gateway.adapterLine()).isEqualTo(Gateway.AdapterLine.V8_1);
            assertThat(gateway.detectedVersion()).isEmpty();
            assertThat(gateway.warnings()).isEmpty();
            assertThat(systemInfoCalls()).isZero();
        }
    }

    @Test
    void autoIsDetectedLazilyOnceAndCachedPerServer() {
        systemInfo(200, fixture("system_info.xml"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO),
            server(ENT2, VersionLine.AUTO))) {
            assertThat(systemInfoCalls()).as("nothing is called at construction").isZero();

            Gateway first = factory.forServer(DEV);
            Gateway second = factory.forServer(DEV);

            assertThat(second).isSameAs(first);
            assertThat(first.adapterLine()).isEqualTo(Gateway.AdapterLine.V8_1);
            assertThat(first.detectedVersion()).contains("8.1.17");
            assertThat(first.warnings()).isEmpty();
            assertThat(systemInfoCalls()).isEqualTo(1);

            factory.forServer(ENT2);
            factory.forServer(ENT2);
            assertThat(systemInfoCalls()).as("one detection per server").isEqualTo(2);
        }
        wireMock.verify(getRequestedFor(urlPathEqualTo(SYSTEM_INFO))
            .withHeader("Authorization", matching("Basic .+")));
    }

    @Test
    void concurrentCallsDetectOnlyOnce() throws Exception {
        systemInfo(200, fixture("system_info.xml"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO));
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Gateway>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> factory.forServer(DEV));
            }
            List<Gateway> gateways = new ArrayList<>();
            for (Future<Gateway> future : executor.invokeAll(calls)) {
                gateways.add(future.get());
            }

            assertThat(gateways).allSatisfy(g -> assertThat(g).isSameAs(gateways.get(0)));
            assertThat(systemInfoCalls()).isEqualTo(1);
        }
    }

    @Test
    void configuredV9xFallsBackToV81WithUnsupportedWarning() {
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.V9_X))) {
            Gateway gateway = factory.forServer(DEV);

            assertThat(gateway.adapterLine()).isEqualTo(Gateway.AdapterLine.V8_1);
            assertThat(gateway.warnings()).singleElement().asString().contains("unsupported");
            assertThat(systemInfoCalls()).isZero();
        }
    }

    @Test
    void detected9xFallsBackToV81WithUnsupportedWarning() {
        systemInfo(200, systemInfoWithVersion("9.0.2"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO))) {
            Gateway gateway = factory.forServer(DEV);

            assertThat(gateway.adapterLine()).isEqualTo(Gateway.AdapterLine.V8_1);
            assertThat(gateway.detectedVersion()).contains("9.0.2");
            assertThat(gateway.warnings()).singleElement().asString()
                .contains("9.0.2", "unsupported");
        }
    }

    @Test
    void detectionFailureFallsBackToV81WithWarningCachedForSixtySecondsThenRetried() {
        systemInfo(401, "<html><body>HTTP Status 401</body></html>");
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO))) {
            Gateway fallback = factory.forServer(DEV);

            assertThat(fallback.adapterLine()).isEqualTo(Gateway.AdapterLine.V8_1);
            assertThat(fallback.detectedVersion()).isEmpty();
            assertThat(fallback.warnings()).containsExactly(
                "INUBIT version could not be detected (AUTH_FAILED); assuming 8.1");

            systemInfo(200, fixture("system_info.xml"));
            clock.advance(AdapterGatewayFactory.FAILED_DETECTION_TTL.minusSeconds(1));
            assertThat(factory.forServer(DEV)).as("cached within the window").isSameAs(fallback);
            assertThat(systemInfoCalls()).isEqualTo(1);

            clock.advance(Duration.ofSeconds(1));
            Gateway detected = factory.forServer(DEV);
            assertThat(detected.detectedVersion()).contains("8.1.17");
            assertThat(detected.warnings()).isEmpty();
            clock.advance(Duration.ofHours(1));
            assertThat(factory.forServer(DEV)).as("a detected version is kept").isSameAs(detected);
            assertThat(systemInfoCalls()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void rejectedCredentialsAreNotRetriedWithinTheWindow(int status) {
        // every retry would be another failed login and could lock the account (Phase 2c R1)
        systemInfo(status, "<html><body>HTTP Status " + status + "</body></html>");
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO))) {
            Gateway fallback = factory.forServer(DEV);
            for (int i = 0; i < 5; i++) {
                clock.advance(Duration.ofSeconds(10));
                assertThat(factory.forServer(DEV)).isSameAs(fallback);
            }

            assertThat(systemInfoCalls()).isEqualTo(1);
            assertThat(fallback.warnings()).singleElement().asString()
                .contains(status == 401 ? "(AUTH_FAILED)" : "(FORBIDDEN)");
        }
    }

    @Test
    void anUnreachableServerIsAlsoNotProbedAgainWithinTheWindow() {
        wireMock.stubFor(get(urlPathEqualTo(SYSTEM_INFO)).willReturn(aResponse()
            .withFault(Fault.CONNECTION_RESET_BY_PEER)));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO))) {
            Gateway fallback = factory.forServer(DEV);
            int callsOfTheDetection = systemInfoCalls(); // the JDK client may retry a reset GET
            clock.advance(Duration.ofSeconds(30));

            assertThat(factory.forServer(DEV)).isSameAs(fallback);
            assertThat(fallback.warnings()).singleElement().asString()
                .contains("(UNREACHABLE)");
            assertThat(systemInfoCalls()).isEqualTo(callsOfTheDetection);
        }
    }

    // --- monitoring without detection (Phase 3 review H1) ------------------------------------

    private static int authenticatedCalls() {
        return wireMock.findAll(com.github.tomakehurst.wiremock.client.WireMock
            .anyRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
            .withHeader("Authorization", matching("Basic .+"))).size();
    }

    @Test
    void monitoringIsAvailableWithoutVersionDetection() {
        wireMock.stubFor(get(urlEqualTo(HEALTHCHECK)).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(fixture("healthcheck.json"))));
        systemInfo(200, fixture("system_info.xml"));
        wireMock.stubFor(get(urlPathEqualTo(SYSTEM_INFO)).willReturn(aResponse()
            .withFixedDelay(5000).withBody(fixture("system_info.xml"))));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO))) {
            long start = System.nanoTime();
            assertThat(factory.monitoring(DEV).healthcheck().status().name()).isEqualTo("OK");

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(
                Duration.ofSeconds(2));
            assertThat(systemInfoCalls()).isZero();
            assertThat(factory.knownGateway(DEV)).as("AUTO is not detected yet").isEmpty();
        }
    }

    @Test
    void processAndLogPortsAreAvailableWithoutVersionDetection() {
        // US2 uses the same non-blocking rule as the monitoring (Phase 3 review H1)
        wireMock.stubFor(get(urlPathEqualTo(SYSTEM_INFO)).willReturn(aResponse()
            .withFixedDelay(5000).withBody(fixture("system_info.xml"))));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO))) {
            long start = System.nanoTime();
            ProcessQueryPort processes = factory.processes(DEV);
            LogPort logs = factory.logs(DEV);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(
                Duration.ofSeconds(1));
            assertThat(systemInfoCalls()).isZero();
            assertThat(processes).isSameAs(factory.processes(DEV));
            assertThat(logs).isSameAs(factory.logs(DEV));
            assertThat(factory.knownGateway(DEV)).as("AUTO is not detected yet").isEmpty();
        }
    }

    @Test
    void theGatewayHandsOutTheSameUs2Ports() {
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.V8_1))) {
            Gateway gateway = factory.forServer(DEV);

            assertThat(gateway.processes()).isSameAs(factory.processes(DEV));
            assertThat(gateway.logs()).isSameAs(factory.logs(DEV));
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }
    }

    @Test
    void theInventoryPortIsAvailableWithoutVersionDetection() {
        // US3 uses the same non-blocking rule (design note 2 of the US3 handoff)
        wireMock.stubFor(get(urlPathEqualTo(SYSTEM_INFO)).willReturn(aResponse()
            .withFixedDelay(5000).withBody(fixture("system_info.xml"))));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO))) {
            long start = System.nanoTime();
            InventoryPort inventory = factory.inventory(DEV);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(
                Duration.ofSeconds(1));
            assertThat(inventory).isNotNull().isSameAs(factory.inventory(DEV));
            assertThat(systemInfoCalls()).isZero();
            assertThat(factory.knownGateway(DEV)).as("AUTO is not detected yet").isEmpty();
        }
    }

    @Test
    void theV81GatewayProvidesTheArtifactPort() throws NoSuchMethodException {
        // T020: the 8.1 gateway overrides the CLI_UNAVAILABLE default of Gateway.artifacts()
        assertThat(V81Gateway.class.getDeclaredMethod("artifacts").getDeclaringClass())
            .isEqualTo(V81Gateway.class);
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.V8_1))) {
            assertThat(factory.forServer(DEV).artifacts()).isNotNull()
                .isSameAs(factory.artifacts(DEV));
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }
    }

    @Test
    void theV81GatewayProvidesTheImportPortAndTheUserDirectory() throws NoSuchMethodException {
        // feature 004 (T014): the 8.1 gateway overrides the CLI_UNAVAILABLE defaults
        assertThat(V81Gateway.class.getDeclaredMethod("imports").getDeclaringClass())
            .isEqualTo(V81Gateway.class);
        assertThat(V81Gateway.class.getDeclaredMethod("users").getDeclaringClass())
            .isEqualTo(V81Gateway.class);
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.V8_1))) {
            assertThat(factory.forServer(DEV).imports()).isNotNull()
                .isSameAs(factory.imports(DEV))
                .isInstanceOf(de.dadecker.inubit.mcp.adapter.cli.v81.V81ImportAdapter.class);
            assertThat(factory.forServer(DEV).users()).isNotNull()
                .isSameAs(factory.users(DEV))
                .isInstanceOf(de.dadecker.inubit.mcp.adapter.rest.v81.V81UserDirectory.class);
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }
    }

    @Test
    void theGatewayHandsOutTheSameInventoryPort() {
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.V8_1))) {
            assertThat(factory.forServer(DEV).inventory()).isNotNull()
                .isSameAs(factory.inventory(DEV));
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }
    }

    @Test
    void theInventoryExportsUseTheGivenCliRunnerAndTheServersCredentialGuard(
        @TempDir Path cliHome) throws IOException {
        Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
        EffectiveNodeConfig config = TestNodeConfig.node().id(DEV.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).build();
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("login_failed");
        systemInfo(200, fixture("system_info.xml")); // the login before the export (review I3)
        CredentialResolution credentials = new CredentialResolver(
            Map.of("INUBIT_DEV_USERNAME", "jdoe", "INUBIT_DEV_PASSWORD", "gateway-test-pw"),
            scrubber, "INUBIT").resolve(List.of(DEV));
        try (AdapterGatewayFactory factory = new AdapterGatewayFactory(List.of(config),
            credentials, scrubber, clock,
            new CliRunner(launcher, Map.of("PATH", "/usr/bin"), false, new CliResources("acme")))) {

            assertThatThrownBy(() -> factory.inventory(DEV).listModules("OWNERS"))
                .isInstanceOfSatisfying(ToolErrorException.class, e ->
                    assertThat(e.error().code()).isEqualTo(ErrorCode.AUTH_FAILED));
            assertThatThrownBy(() -> factory.processes(DEV).findByProcessId("1",
                clock.instant(), Duration.ofMinutes(60)))
                .as("the CLI login failure pauses the REST logins of the server")
                .isInstanceOfSatisfying(ToolErrorException.class, e ->
                    assertThat(e.error().message()).contains("avoid account lockout"));
            assertThat(launcher.launchCount()).isEqualTo(1);
            assertThat(systemInfoCalls()).as("only the login before the export").isEqualTo(1);
        }
    }

    @Test
    void theSystemInfoOfTheMonitoringCompletesTheDetection() {
        systemInfo(200, fixture("system_info.xml"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO))) {
            factory.monitoring(DEV).systemInfo();

            Gateway known = factory.knownGateway(DEV).orElseThrow();
            assertThat(known.detectedVersion()).contains("8.1.17");
            assertThat(known.warnings()).isEmpty();
            assertThat(factory.forServer(DEV)).isSameAs(known);
            assertThat(systemInfoCalls()).as("no second /system/info").isEqualTo(1);
        }
    }

    @Test
    void anUnsupportedVersionFromTheMonitoringIsWarnedAbout() {
        systemInfo(200, systemInfoWithVersion("9.0.2"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO))) {
            factory.monitoring(DEV).systemInfo();

            assertThat(factory.knownGateway(DEV).orElseThrow().warnings()).singleElement()
                .asString().contains("9.0.2", "unsupported");
        }
    }

    @Test
    void aFailedSystemInfoOfTheMonitoringIsCachedAsFallbackAndBlocksFurtherLogins() {
        systemInfo(401, "<html>HTTP Status 401</html>");
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/metrics")).willReturn(aResponse()
            .withStatus(401)));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO))) {
            MonitoringPort monitoring = factory.monitoring(DEV);
            assertThatThrownBy(monitoring::systemInfo).isInstanceOf(ToolErrorException.class);
            assertThatThrownBy(monitoring::metrics)
                .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                    e.error().message()).contains("avoid account lockout"));

            Gateway known = factory.knownGateway(DEV).orElseThrow();
            assertThat(known.warnings()).containsExactly(
                "INUBIT version could not be detected (AUTH_FAILED); assuming 8.1");
            assertThat(factory.forServer(DEV)).isSameAs(known);
            assertThat(monitoring.credentialsConfirmed()).isFalse();
            assertThat(authenticatedCalls()).as("one failed login only").isEqualTo(1);
        }
    }

    @Test
    void successfulAuthenticatedCallsConfirmTheCredentials() {
        systemInfo(200, fixture("system_info.xml"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.V8_1))) {
            MonitoringPort monitoring = factory.monitoring(DEV);
            assertThat(monitoring.credentialsConfirmed()).isFalse();

            monitoring.systemInfo();

            assertThat(monitoring.credentialsConfirmed()).isTrue();
            assertThat(factory.forServer(DEV).monitoring()).isSameAs(monitoring);
        }
    }

    @Test
    void configuredLinesAreKnownWithoutCallingTheServer() {
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.V8_1),
            server(ENT2, VersionLine.V9_X))) {
            assertThat(factory.knownGateway(DEV)).get()
                .extracting(Gateway::warnings).asList().isEmpty();
            assertThat(factory.knownGateway(ENT2).orElseThrow().warnings()).singleElement()
                .asString().contains("unsupported");
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }
    }

    @Test
    void anInterruptedDetectionIsNotCached() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo(SYSTEM_INFO)).willReturn(aResponse()
            .withFixedDelay(3000).withBody(fixture("system_info.xml"))));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO))) {
            Thread detection = Thread.ofVirtual().start(() -> factory.forServer(DEV));
            Thread.sleep(300);
            detection.interrupt();
            detection.join(5000);
            Thread health = Thread.ofVirtual().start(() -> {
                try {
                    factory.monitoring(DEV).systemInfo();
                } catch (ToolErrorException e) {
                    // cancelled
                }
            });
            Thread.sleep(300);
            health.interrupt();
            health.join(5000);

            assertThat(factory.knownGateway(DEV)).as("a cancelled call is no failed detection")
                .isEmpty();
            systemInfo(200, fixture("system_info.xml"));
            assertThat(factory.forServer(DEV).detectedVersion()).contains("8.1.17");
        }
    }

    @Test
    void clientUsesTheV81MaintenanceProbe() {
        // With MaintenanceProbe.NONE a 503 would be UNREACHABLE (Phase 2b review N-1)
        systemInfo(503, "Service Unavailable");
        wireMock.stubFor(get(urlEqualTo(HEALTHCHECK)).willReturn(aResponse().withStatus(503)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"status\":\"OK\",\"maintenancemode\":1}")));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO))) {
            assertThat(factory.forServer(DEV).warnings()).singleElement().asString()
                .contains("(MAINTENANCE_MODE)");
        }
        wireMock.verify(1, getRequestedFor(urlEqualTo(HEALTHCHECK)));
    }

    @Test
    void systemInfoWithoutVersionFallsBackWithWarning() {
        systemInfo(200,
            "<SystemInformationList xmlns=\"inubit.com/ibis/external/systeminformation\">"
            + "<SystemInformation name=\"FreeMemory\" value=\"1\"/></SystemInformationList>");
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.AUTO))) {
            assertThat(factory.forServer(DEV).warnings()).singleElement().asString()
                .contains("(UNEXPECTED_RESPONSE)");
        }
    }

    @Test
    void anUnusableTrustStoreIsATlsErrorOfTheServer() {
        EffectiveNodeConfig broken = TestNodeConfig.node().id(DEV.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(java.nio.file.Path.of("does-not-exist.p12"))
            .versionLine(VersionLine.AUTO).build();
        try (AdapterGatewayFactory factory = factory(broken)) {
            assertThatThrownBy(() -> factory.forServer(DEV))
                .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                    assertThat(e.error().code()).isEqualTo(ErrorCode.TLS_ERROR);
                    assertThat(e.error().node()).contains(DEV);
                });
        }
    }

    @Test
    void anUnconfiguredServerIsUnknown() {
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionLine.V8_1))) {
            assertThatThrownBy(() -> factory.forServer(ENT2))
                .isInstanceOfSatisfying(ToolErrorException.class, e ->
                    assertThat(e.error().code()).isEqualTo(ErrorCode.TARGET_UNKNOWN));
        }
    }
}
