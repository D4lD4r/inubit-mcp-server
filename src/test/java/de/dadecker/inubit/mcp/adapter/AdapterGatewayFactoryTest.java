package de.dadecker.inubit.mcp.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.ParameterNames;
import de.dadecker.inubit.mcp.config.SecretPlan;
import de.dadecker.inubit.mcp.config.SecretSourceConfig;
import de.dadecker.inubit.mcp.config.StoreLocation;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.domain.port.VersionObserver;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeParameterStore;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.RecordingVersionObserver;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
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

    private static EffectiveNodeConfig server(NodeId id, VersionSetting line) {
        return TestNodeConfig.node().id(id.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).versionLine(line).build();
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T08:00:00Z"));
    private final RecordingVersionObserver versions = new RecordingVersionObserver();

    private AdapterGatewayFactory factory(EffectiveNodeConfig... servers) {
        return factory(PortSetRegistry.v81Default(), versions, servers);
    }

    private AdapterGatewayFactory factory(PortSetRegistry portSets, VersionObserver observer,
        EffectiveNodeConfig... servers) {
        List<NodeId> ids = new ArrayList<>();
        for (EffectiveNodeConfig server : servers) {
            ids.add(server.id());
        }
        CredentialResolution credentials = new CredentialResolver(
            Map.of("INUBIT_DEV_USERNAME", "jdoe", "INUBIT_DEV_PASSWORD", "gateway-test-pw"),
            scrubber, "INUBIT").resolve(ids);
        return new AdapterGatewayFactory(List.of(servers), TestSecrets.environment(credentials),
            scrubber, clock,
            new CliRunner(new SystemProcessLauncher(), Map.of(), false, new CliResources("acme")),
            observer, portSets);
    }

    private static int systemInfoCalls() {
        return wireMock.findAll(getRequestedFor(urlPathEqualTo(SYSTEM_INFO))).size();
    }

    @Test
    void configuredV81SelectsTheV81AdaptersWithoutCallingTheServer() {
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.V8_1))) {
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
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO),
            server(ENT2, VersionSetting.AUTO))) {
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
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO));
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
    void configuredV9xUsesTheV81AdaptersWithoutAVersionWarning() {
        // feature 009 (T013): version warnings come from the version gate, not the gateway
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.V9_X))) {
            Gateway gateway = factory.forServer(DEV);

            assertThat(gateway.adapterLine()).isEqualTo(Gateway.AdapterLine.V8_1);
            assertThat(gateway.warnings()).isEmpty();
            assertThat(systemInfoCalls()).isZero();
        }
    }

    /** Feature 009 T005: until the version gate exists, a pin behaves like its line. */
    @Test
    void aPinnedVersionBehavesLikeItsLineWithoutCallingTheServer() {
        VersionSetting pinned81 = VersionSetting.parse("8.1.17").orElseThrow();
        VersionSetting pinned90 = VersionSetting.parse("9.0.7-HF1").orElseThrow();
        try (AdapterGatewayFactory factory = factory(server(DEV, pinned81),
            server(ENT2, pinned90))) {
            Gateway on81 = factory.forServer(DEV);
            Gateway on90 = factory.forServer(ENT2);

            assertThat(on81.adapterLine()).isEqualTo(Gateway.AdapterLine.V8_1);
            assertThat(on81.detectedVersion()).isEmpty();
            assertThat(on81.warnings()).isEmpty();
            assertThat(on90.adapterLine()).isEqualTo(Gateway.AdapterLine.V8_1);
            assertThat(on90.warnings()).as("no version warning (T013)").isEmpty();
            assertThat(systemInfoCalls()).isZero();
        }
    }

    @Test
    void detected9xUsesTheV81AdaptersAndReportsTheVersionWithoutAWarning() {
        systemInfo(200, systemInfoWithVersion("9.0.2"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            Gateway gateway = factory.forServer(DEV);

            assertThat(gateway.adapterLine()).isEqualTo(Gateway.AdapterLine.V8_1);
            assertThat(gateway.detectedVersion()).contains("9.0.2");
            assertThat(gateway.warnings()).as("no version warning (T013)").isEmpty();
            assertThat(versions.events()).containsExactly("detected dev/node1 9.0.2");
        }
    }

    @Test
    void detectionFailureFallsBackToV81CachedForSixtySecondsThenRetried() {
        systemInfo(401, "<html><body>HTTP Status 401</body></html>");
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            Gateway fallback = factory.forServer(DEV);

            assertThat(fallback.adapterLine()).isEqualTo(Gateway.AdapterLine.V8_1);
            assertThat(fallback.detectedVersion()).isEmpty();
            assertThat(fallback.warnings()).as("no \"assuming 8.1\" warning (T013)").isEmpty();
            assertThat(versions.events()).containsExactly("detectionFailed dev/node1 AUTH_FAILED");

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
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            Gateway fallback = factory.forServer(DEV);
            for (int i = 0; i < 5; i++) {
                clock.advance(Duration.ofSeconds(10));
                assertThat(factory.forServer(DEV)).isSameAs(fallback);
            }

            assertThat(systemInfoCalls()).isEqualTo(1);
            assertThat(versions.events()).containsExactly("detectionFailed dev/node1 "
                + (status == 401 ? "AUTH_FAILED" : "FORBIDDEN"));
        }
    }

    @Test
    void anUnreachableServerIsAlsoNotProbedAgainWithinTheWindow() {
        wireMock.stubFor(get(urlPathEqualTo(SYSTEM_INFO)).willReturn(aResponse()
            .withFault(Fault.CONNECTION_RESET_BY_PEER)));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            Gateway fallback = factory.forServer(DEV);
            int callsOfTheDetection = systemInfoCalls(); // the JDK client may retry a reset GET
            clock.advance(Duration.ofSeconds(30));

            assertThat(factory.forServer(DEV)).isSameAs(fallback);
            assertThat(versions.events()).containsExactly("detectionFailed dev/node1 UNREACHABLE");
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
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
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
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
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
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.V8_1))) {
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
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
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
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.V8_1))) {
            assertThat(factory.forServer(DEV).artifacts()).isNotNull()
                .isSameAs(factory.artifacts(DEV));
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }
    }

    @Test
    void theV81GatewayProvidesTheImportAndTagPorts() throws NoSuchMethodException {
        // feature 004 (T014): the 8.1 gateway overrides the CLI_UNAVAILABLE defaults
        assertThat(V81Gateway.class.getDeclaredMethod("imports").getDeclaringClass())
            .isEqualTo(V81Gateway.class);
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.V8_1))) {
            // feature 009 T007: the 8.1 adapters behind the stale-reporting decorators
            assertThat(factory.forServer(DEV).imports()).isNotNull()
                .isSameAs(factory.imports(DEV))
                .isInstanceOf(StaleReportingPorts.Imports.class);
            // T021
            assertThat(factory.forServer(DEV).tags()).isNotNull()
                .isSameAs(factory.tags(DEV))
                .isInstanceOf(StaleReportingPorts.Tags.class);
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }
    }

    @Test
    void theGatewayHandsOutTheSameInventoryPort() {
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.V8_1))) {
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
            TestSecrets.environment(credentials), scrubber, clock,
            new CliRunner(launcher, Map.of("PATH", "/usr/bin"), false, new CliResources("acme")),
            versions, PortSetRegistry.v81Default())) {

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
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            factory.monitoring(DEV).systemInfo();

            Gateway known = factory.knownGateway(DEV).orElseThrow();
            assertThat(known.detectedVersion()).contains("8.1.17");
            assertThat(known.warnings()).isEmpty();
            assertThat(factory.forServer(DEV)).isSameAs(known);
            assertThat(systemInfoCalls()).as("no second /system/info").isEqualTo(1);
        }
    }

    @Test
    void anUnsupportedVersionFromTheMonitoringIsReportedWithoutAWarning() {
        systemInfo(200, systemInfoWithVersion("9.0.2"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            factory.monitoring(DEV).systemInfo();

            Gateway known = factory.knownGateway(DEV).orElseThrow();
            assertThat(known.detectedVersion()).contains("9.0.2");
            assertThat(known.warnings()).as("no version warning (T013)").isEmpty();
            assertThat(versions.events()).containsExactly("detected dev/node1 9.0.2");
        }
    }

    @Test
    void aFailedSystemInfoOfTheMonitoringIsCachedAsFallbackAndBlocksFurtherLogins() {
        systemInfo(401, "<html>HTTP Status 401</html>");
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/metrics")).willReturn(aResponse()
            .withStatus(401)));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            MonitoringPort monitoring = factory.monitoring(DEV);
            assertThatThrownBy(monitoring::systemInfo).isInstanceOf(ToolErrorException.class);
            assertThatThrownBy(monitoring::metrics)
                .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                    e.error().message()).contains("avoid account lockout"));

            Gateway known = factory.knownGateway(DEV).orElseThrow();
            assertThat(known.detectedVersion()).isEmpty();
            assertThat(known.warnings()).as("no \"assuming 8.1\" warning (T013)").isEmpty();
            assertThat(versions.events()).containsExactly("detectionFailed dev/node1 AUTH_FAILED");
            assertThat(factory.forServer(DEV)).isSameAs(known);
            assertThat(monitoring.credentialsConfirmed()).isFalse();
            assertThat(authenticatedCalls()).as("one failed login only").isEqualTo(1);
        }
    }

    @Test
    void successfulAuthenticatedCallsConfirmTheCredentials() {
        systemInfo(200, fixture("system_info.xml"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.V8_1))) {
            MonitoringPort monitoring = factory.monitoring(DEV);
            assertThat(monitoring.credentialsConfirmed()).isFalse();

            monitoring.systemInfo();

            assertThat(monitoring.credentialsConfirmed()).isTrue();
            assertThat(factory.forServer(DEV).monitoring()).isSameAs(monitoring);
        }
    }

    @Test
    void configuredLinesAreKnownWithoutCallingTheServer() {
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.V8_1),
            server(ENT2, VersionSetting.V9_X))) {
            assertThat(factory.knownGateway(DEV)).get()
                .extracting(Gateway::warnings).asList().isEmpty();
            assertThat(factory.knownGateway(ENT2).orElseThrow().warnings())
                .as("no version warning (T013)").isEmpty();
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }
    }

    @Test
    void anInterruptedDetectionIsNotCached() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo(SYSTEM_INFO)).willReturn(aResponse()
            .withFixedDelay(3000).withBody(fixture("system_info.xml"))));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
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
            assertThat(versions.events()).as("nor is it reported").isEmpty();
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
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            factory.forServer(DEV);
            assertThat(versions.events())
                .containsExactly("detectionFailed dev/node1 MAINTENANCE_MODE");
        }
        wireMock.verify(1, getRequestedFor(urlEqualTo(HEALTHCHECK)));
    }

    @Test
    void systemInfoWithoutVersionFallsBackAndIsAFailedDetection() {
        systemInfo(200,
            "<SystemInformationList xmlns=\"inubit.com/ibis/external/systeminformation\">"
            + "<SystemInformation name=\"FreeMemory\" value=\"1\"/></SystemInformationList>");
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            assertThat(factory.forServer(DEV).detectedVersion()).isEmpty();
            assertThat(versions.events())
                .containsExactly("detectionFailed dev/node1 UNEXPECTED_RESPONSE");
        }
    }

    @Test
    void anUnusableTrustStoreIsATlsErrorOfTheServer() {
        EffectiveNodeConfig broken = TestNodeConfig.node().id(DEV.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(java.nio.file.Path.of("does-not-exist.p12"))
            .versionLine(VersionSetting.AUTO).build();
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
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.V8_1))) {
            assertThatThrownBy(() -> factory.forServer(ENT2))
                .isInstanceOfSatisfying(ToolErrorException.class, e ->
                    assertThat(e.error().code()).isEqualTo(ErrorCode.TARGET_UNKNOWN));
        }
    }

    // --- feature 009 T007: detection port, reporting and stale triggers ----------------------

    private static void queueLog(int status, String body) {
        wireMock.stubFor(post(urlPathEqualTo("/ibis/rest/log/queueLog")).willReturn(aResponse()
            .withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static void modelList(int status, String body) {
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/model/models")).willReturn(aResponse()
            .withStatus(status).withHeader("Content-Type", "application/xml").withBody(body)));
    }

    private static ErrorCode codeOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            return e.error().code();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    @Test
    void detectOnAnUndetectedNodeSendsOneRequestAndCompletesTheSlotsDetection() {
        systemInfo(200, fixture("system_info.xml"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            InubitVersion version = factory.detect(DEV);

            assertThat(version.raw()).isEqualTo("8.1.17");
            assertThat(factory.forServer(DEV).detectedVersion()).contains("8.1.17");
            assertThat(systemInfoCalls()).as("forServer uses the detection").isEqualTo(1);
            assertThat(versions.events()).containsExactly("detected dev/node1 8.1.17");
        }
    }

    @Test
    void detectBypassesTheDetectedGatewayAndDetectsAgain() {
        systemInfo(200, fixture("system_info.xml"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            factory.forServer(DEV);
            assertThat(factory.detect(DEV).raw()).isEqualTo("8.1.17");
            assertThat(systemInfoCalls()).as("not answered from the slot's cache").isEqualTo(2);

            systemInfo(200, systemInfoWithVersion("9.0.2"));
            InubitVersion upgraded = factory.detect(DEV);

            assertThat(upgraded.major()).isEqualTo(9);
            assertThat(systemInfoCalls()).isEqualTo(3);
            assertThat(factory.knownGateway(DEV).orElseThrow().detectedVersion())
                .as("the adapter selection follows the re-detection").contains("9.0.2");
            assertThat(versions.events()).containsExactly("detected dev/node1 8.1.17",
                "detected dev/node1 8.1.17", "detected dev/node1 9.0.2");
        }
    }

    @Test
    void aFailedDetectIsThrownAndReportedAndTheSlotsBackOffOnlyHoldsTheAdapterSelection() {
        systemInfo(500, "<html>internal error</html>");
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            assertThat(codeOf(() -> factory.detect(DEV)))
                .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
            Gateway fallback = factory.forServer(DEV);
            assertThat(fallback.detectedVersion()).isEmpty();
            assertThat(systemInfoCalls()).as("forServer keeps the slot's 60 s fallback")
                .isEqualTo(1);

            clock.advance(Duration.ofSeconds(10));
            systemInfo(200, fixture("system_info.xml"));
            assertThat(factory.detect(DEV).raw())
                .as("detect is not held back by the slot's fallback").isEqualTo("8.1.17");

            assertThat(systemInfoCalls()).isEqualTo(2);
            assertThat(factory.forServer(DEV).detectedVersion()).contains("8.1.17");
            assertThat(versions.events()).containsExactly(
                "detectionFailed dev/node1 UNEXPECTED_RESPONSE", "detected dev/node1 8.1.17");
        }
    }

    @Test
    void aFailedReDetectionKeepsTheDetectedGateway() {
        systemInfo(200, fixture("system_info.xml"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            Gateway detected = factory.forServer(DEV);
            systemInfo(401, "<html>HTTP Status 401</html>");

            assertThat(codeOf(() -> factory.detect(DEV))).isEqualTo(ErrorCode.AUTH_FAILED);

            assertThat(factory.forServer(DEV)).isSameAs(detected);
            assertThat(versions.events()).containsExactly("detected dev/node1 8.1.17",
                "detectionFailed dev/node1 AUTH_FAILED");
        }
    }

    @Test
    void aVersionWithoutMajorMinorIsAFailedDetection() {
        systemInfo(200, systemInfoWithVersion("unknown"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            assertThatThrownBy(() -> factory.detect(DEV))
                .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                    assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                    assertThat(e.error().node()).contains(DEV);
                    assertThat(e.error().message()).contains("'unknown'");
                });
            assertThat(versions.events())
                .containsExactly("detectionFailed dev/node1 UNEXPECTED_RESPONSE");
        }
    }

    @Test
    void theSlotReportsOnlyItsAutoDetections() {
        systemInfo(200, fixture("system_info.xml"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO),
            server(ENT2, VersionSetting.V8_1))) {
            factory.forServer(DEV);
            factory.forServer(DEV);
            factory.forServer(ENT2);
            factory.monitoring(ENT2).systemInfo(); // observations are HealthService's (T016)

            assertThat(versions.events()).containsExactly("detected dev/node1 8.1.17");
        }
    }

    @Test
    void theMonitoringsSystemInfoReportsTheSlotsFirstDetectionOnly() {
        systemInfo(200, fixture("system_info.xml"));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            MonitoringPort monitoring = factory.monitoring(DEV);
            monitoring.systemInfo();
            monitoring.systemInfo();

            assertThat(versions.events()).containsExactly("detected dev/node1 8.1.17");
        }
    }

    @Test
    void theMonitoringsFailedSystemInfoReportsTheSlotsFailedDetection() {
        systemInfo(401, "<html>HTTP Status 401</html>");
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.AUTO))) {
            assertThat(codeOf(factory.monitoring(DEV)::systemInfo))
                .isEqualTo(ErrorCode.AUTH_FAILED);

            assertThat(versions.events()).containsExactly("detectionFailed dev/node1 AUTH_FAILED");
        }
    }

    @Test
    void aParserUnexpectedResponseMarksTheNodeStaleAndIsRethrown() {
        queueLog(200, "{\"somethingElse\":{}}");
        modelList(200, "<unexpected/>");
        systemInfo(200, "not xml");
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.V8_1))) {
            assertThat(codeOf(() -> factory.processes(DEV).findByProcessId("1",
                clock.instant(), Duration.ofMinutes(60)))).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
            assertThat(codeOf(() -> factory.forServer(DEV).inventory().listDiagrams("OWNERS")))
                .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
            assertThat(codeOf(factory.monitoring(DEV)::systemInfo))
                .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);

            assertThat(versions.events()).containsExactly("stale dev/node1", "stale dev/node1",
                "stale dev/node1");
        }
    }

    @Test
    void a406MarksTheNodeStale() {
        queueLog(406, "Not Acceptable");
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.V8_1))) {
            assertThat(codeOf(() -> factory.processes(DEV).findByProcessId("1",
                clock.instant(), Duration.ofMinutes(60)))).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);

            assertThat(versions.events()).containsExactly("stale dev/node1");
        }
    }

    @Test
    void aMissingEndpointMarksTheNodeStaleButAMissingResourceDoesNot() {
        modelList(404, "<html>not found</html>");
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/model/modelByName/Missing"))
            .willReturn(aResponse().withStatus(404)));
        try (AdapterGatewayFactory factory = factory(server(DEV, VersionSetting.V8_1))) {
            assertThat(codeOf(() -> factory.inventory(DEV).listDiagrams("OWNERS")))
                .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
            assertThat(codeOf(() -> factory.inventory(DEV).diagramDetail("OWNERS", "Missing")))
                .isEqualTo(ErrorCode.NOT_FOUND);

            assertThat(versions.events()).containsExactly("stale dev/node1");
        }
    }

    // --- feature 009 T024: port-set selection by effective version (research D-16) ----------

    private static final NodeId ENT3 = NodeId.parse("dev/node3");
    private static final VersionRange V7_9 = new VersionRange(7, 9, 7, 9);
    private static final VersionRange V9_0 = new VersionRange(9, 0, 9, 0);

    /** Every port factory call, e.g. {@code 7.9 dev/node1}. */
    private final List<String> built = new CopyOnWriteArrayList<>();

    /** A test-only port set whose ports throw {@code UnsupportedOperationException(name)}. */
    private PortSetRegistry.Factory fakePortSet(String name) {
        return node -> {
            built.add(name + " " + node.server().id());
            return new PortSet(fake(ProcessQueryPort.class, name), fake(LogPort.class, name),
                fake(InventoryPort.class, name), fake(ProcessControlPort.class, name),
                fake(ArtifactPort.class, name), fake(ImportPort.class, name),
                fake(TagPort.class, name));
        };
    }

    private static <T> T fake(Class<T> type, String name) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException(name);
            }));
    }

    /** The name of the port set behind a handed-out (stale-reporting) log port. */
    private static String portSetOf(LogPort port) {
        try {
            port.validate(null);
        } catch (UnsupportedOperationException e) {
            return e.getMessage();
        }
        throw new AssertionError("not a test-only port set");
    }

    private NodeVersions nodeVersions(EffectiveNodeConfig... servers) {
        Map<NodeId, VersionSetting> lines = new java.util.HashMap<>();
        for (EffectiveNodeConfig server : servers) {
            lines.put(server.id(), server.versionLine());
        }
        return new NodeVersions(clock, lines::get, id -> Duration.ofMinutes(10));
    }

    @Test
    void theDefaultPortSetServes81_90AndUnknownVersions() {
        EffectiveNodeConfig on81 = server(DEV, VersionSetting.parse("8.1.17").orElseThrow());
        EffectiveNodeConfig on90 = server(ENT2, VersionSetting.parse("9.0.7").orElseThrow());
        EffectiveNodeConfig unknown = server(ENT3, VersionSetting.AUTO);
        PortSetRegistry portSets = new PortSetRegistry(fakePortSet("default"))
            .register(V7_9, fakePortSet("7.9"));
        try (AdapterGatewayFactory factory = factory(portSets, nodeVersions(on81, on90, unknown),
            on81, on90, unknown)) {
            assertThat(portSetOf(factory.logs(DEV))).isEqualTo("default");
            assertThat(portSetOf(factory.logs(ENT2))).isEqualTo("default");
            assertThat(portSetOf(factory.logs(ENT3))).isEqualTo("default");
            assertThat(built).containsExactly("default dev/node1", "default dev/node2",
                "default dev/node3");
            assertThat(wireMock.getAllServeEvents()).as("the selection detects nothing")
                .isEmpty();
        }
    }

    @Test
    void productionRegistersOnlyThe81PortSetAsTheDefaultForEveryVersion() {
        PortSetRegistry production = PortSetRegistry.v81Default();
        for (String version : List.of("7.9.1", "8.1.17", "9.0.7", "10.2")) {
            PortSetRegistry.Selection selection =
                production.select(InubitVersion.parse(version));
            assertThat(selection.range()).as(version).isEmpty();
            assertThat(selection.factory()).as(version).isSameAs(production.select(
                Optional.empty()).factory());
        }
        modelList(404, "<html>not found</html>");
        EffectiveNodeConfig on90 = server(DEV, VersionSetting.parse("9.0.7").orElseThrow());
        try (AdapterGatewayFactory factory = factory(production, nodeVersions(on90), on90)) {
            assertThat(codeOf(() -> factory.inventory(DEV).listDiagrams("OWNERS")))
                .as("the 8.1 model list endpoint").isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
            wireMock.verify(1, getRequestedFor(urlPathEqualTo("/ibis/rest/model/models")));
        }
    }

    @Test
    void aRegisteredRangeIsSelectedAfterTheDetection() {
        systemInfo(200, systemInfoWithVersion("7.9.3"));
        EffectiveNodeConfig node = server(DEV, VersionSetting.AUTO);
        PortSetRegistry portSets = new PortSetRegistry(fakePortSet("default"))
            .register(V7_9, fakePortSet("7.9"));
        try (AdapterGatewayFactory factory = factory(portSets, nodeVersions(node), node)) {
            assertThat(portSetOf(factory.logs(DEV))).as("before the detection").isEqualTo("default");

            factory.detect(DEV);

            assertThat(portSetOf(factory.logs(DEV))).isEqualTo("7.9");
            assertThat(portSetOf(factory.forServer(DEV).logs())).as("the gateway's ports")
                .isEqualTo("7.9");
            assertThat(factory.processes(DEV)).as("kept within the range")
                .isSameAs(factory.processes(DEV));
            assertThat(built).containsExactly("default dev/node1", "7.9 dev/node1");
            assertThat(systemInfoCalls()).as("only the detection").isEqualTo(1);
        }
    }

    @Test
    void aReDetectionIntoAnotherRangeRebuildsThePortSet() {
        systemInfo(200, systemInfoWithVersion("7.9.3"));
        EffectiveNodeConfig node = server(DEV, VersionSetting.AUTO);
        PortSetRegistry portSets = new PortSetRegistry(fakePortSet("default"))
            .register(V7_9, fakePortSet("7.9")).register(V9_0, fakePortSet("9.0"));
        try (AdapterGatewayFactory factory = factory(portSets, nodeVersions(node), node)) {
            factory.detect(DEV);
            LogPort on79 = factory.logs(DEV);
            assertThat(portSetOf(on79)).isEqualTo("7.9");

            systemInfo(200, systemInfoWithVersion("7.9.4"));
            factory.detect(DEV);
            assertThat(factory.logs(DEV)).as("same range: not rebuilt").isSameAs(on79);

            systemInfo(200, systemInfoWithVersion("9.0.2"));
            factory.detect(DEV);
            assertThat(portSetOf(factory.logs(DEV))).isEqualTo("9.0");
            assertThat(portSetOf(factory.forServer(DEV).logs())).isEqualTo("9.0");

            systemInfo(200, fixture("system_info.xml"));
            factory.detect(DEV);
            assertThat(portSetOf(factory.logs(DEV))).as("8.1.17: the default")
                .isEqualTo("default");

            assertThat(built).containsExactly("7.9 dev/node1", "9.0 dev/node1",
                "default dev/node1");
        }
    }

    @Test
    void theSelectedPortSetIsStillWrappedByTheStaleTrigger() {
        EffectiveNodeConfig node = server(DEV, VersionSetting.parse("7.9.3").orElseThrow());
        PortSetRegistry portSets = PortSetRegistry.v81Default().register(V7_9,
            fakePortSet("7.9"));
        try (AdapterGatewayFactory factory = factory(portSets, nodeVersions(node), node)) {
            assertThat(factory.logs(DEV)).isInstanceOf(StaleReportingPorts.Logs.class);
            assertThat(portSetOf(factory.logs(DEV))).isEqualTo("7.9");
        }
    }

    // --- feature 010 T011: secrets of AWS nodes, fetched on first use (R-7, FR-006a) -------

    private static final StoreLocation EU = new StoreLocation("eu-central-1", Optional.empty());
    private static final String AWS_USERNAME = "/test/dev/node1/username";
    private static final String AWS_PASSWORD = "/test/dev/node1/password";
    private static final String AWS_TRUST = "/test/dev/node1/truststore-password";

    private final FakeParameterStore store = new FakeParameterStore();

    private NodeSecrets awsSecrets(Optional<String> trustStorePassword) {
        return NodeSecrets.aws(new SecretPlan(DEV,
            new SecretSourceConfig.AwsParameterStore(EU.region(), EU.awsProfile()),
            SecretPlan.Origin.NODE, Optional.empty(), Optional.of(new ParameterNames(
                Optional.of(AWS_USERNAME), Optional.of(AWS_PASSWORD), trustStorePassword,
                Optional.empty(), Optional.empty()))), store, scrubber, clock);
    }

    private AdapterGatewayFactory awsFactory(EffectiveNodeConfig server, NodeSecrets secrets) {
        return new AdapterGatewayFactory(List.of(server), Map.of(server.id(), secrets), scrubber,
            clock, new CliRunner(new SystemProcessLauncher(), Map.of(), false,
                new CliResources("acme")), versions, PortSetRegistry.v81Default());
    }

    private void putAwsCredentials() {
        store.put(EU, AWS_USERNAME, "test-user-1").put(EU, AWS_PASSWORD, "test-password-1");
    }

    private static void collect(Runnable call, List<ToolErrorException> failures) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            failures.add(e);
        }
    }

    @Test
    void anAwsNodesFirstAuthenticatedCallFetchesOnceAndUsesTheStoredValues() {
        systemInfo(200, fixture("system_info.xml"));
        putAwsCredentials();
        store.put(EU, AWS_TRUST, TestCertificates.PASSWORD);
        // the trust store needs the fetched password: it is part of the TLS context
        EffectiveNodeConfig node = TestNodeConfig.node().id(DEV.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().protectedTrustStore())
            .versionLine(VersionSetting.AUTO).build();
        try (AdapterGatewayFactory factory = awsFactory(node, awsSecrets(Optional.of(AWS_TRUST)))) {
            assertThat(store.calls()).as("nothing is fetched at construction").isZero();

            Gateway gateway = factory.forServer(DEV);
            factory.monitoring(DEV);
            factory.logs(DEV);
            factory.detect(DEV);

            assertThat(gateway.detectedVersion()).contains("8.1.17");
            assertThat(store.requests()).singleElement().satisfies(request -> assertThat(
                request.names()).containsExactly(AWS_USERNAME, AWS_PASSWORD, AWS_TRUST));
        }
        String basic = "Basic " + Base64.getEncoder().encodeToString(
            "test-user-1:test-password-1".getBytes(StandardCharsets.UTF_8));
        wireMock.verify(2, getRequestedFor(urlPathEqualTo(SYSTEM_INFO))
            .withHeader("Authorization", equalTo(basic)));
    }

    @Test
    void aFailedFetchFailsTheCallBeforeAnyInubitRequest() {
        systemInfo(200, fixture("system_info.xml"));
        store.put(EU, AWS_USERNAME, "test-user-1")
            .fail(EU, AWS_PASSWORD, FailureKind.ACCESS_DENIED, "test detail");
        try (AdapterGatewayFactory factory = awsFactory(server(DEV, VersionSetting.AUTO),
            awsSecrets(Optional.empty()))) {
            List<ToolErrorException> failures = new ArrayList<>();
            collect(() -> factory.forServer(DEV), failures);
            collect(() -> factory.detect(DEV), failures);
            collect(() -> factory.monitoring(DEV), failures);
            collect(() -> factory.processes(DEV), failures);
            collect(() -> factory.inventory(DEV), failures);

            assertThat(failures).hasSize(5).allSatisfy(e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
                assertThat(e.error().message()).contains("dev/node1", AWS_PASSWORD,
                    "access denied");
            });
            assertThat(store.calls()).as("the back-off answers the later calls").isEqualTo(1);
        }
        assertThat(wireMock.getAllServeEvents()).isEmpty();
        assertThat(versions.events()).as("no detection was attempted").isEmpty();
    }

    @Test
    void pathsThatDoNotContactTheNodeFetchNothing() {
        putAwsCredentials();
        try (AdapterGatewayFactory factory = awsFactory(server(DEV, VersionSetting.V8_1),
            awsSecrets(Optional.empty()))) {
            assertThat(factory.knownGateway(DEV)).as("unknown until the secrets are there")
                .isEmpty();
            assertThatThrownBy(() -> factory.forServer(ENT2))
                .isInstanceOfSatisfying(ToolErrorException.class, e ->
                    assertThat(e.error().code()).isEqualTo(ErrorCode.TARGET_UNKNOWN));
            assertThat(store.calls()).isZero();

            factory.logs(DEV);

            assertThat(store.calls()).isEqualTo(1);
            assertThat(factory.knownGateway(DEV)).isPresent();
        }
        assertThat(store.calls()).isEqualTo(1);
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void aCallerInterruptedWhileAnotherCallersFetchIsPendingReturnsAtOnce() throws Exception {
        putAwsCredentials();
        store.delay(EU, Duration.ofSeconds(30));
        try (AdapterGatewayFactory factory = awsFactory(server(DEV, VersionSetting.AUTO),
            awsSecrets(Optional.empty()))) {
            List<ToolErrorException> first = new CopyOnWriteArrayList<>();
            List<ToolErrorException> second = new CopyOnWriteArrayList<>();
            Thread fetching = Thread.ofVirtual().start(() -> collect(() -> factory.forServer(DEV),
                first));
            while (store.calls() == 0) {
                Thread.onSpinWait();
            }
            // detect takes the same slot lock as forServer; it waits for the secrets before
            Thread waiting = Thread.ofVirtual().start(() -> collect(() -> factory.detect(DEV),
                second));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (waiting.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            waiting.interrupt();

            assertThat(waiting.join(Duration.ofSeconds(5))).as("returns at once").isTrue();
            assertThat(second).singleElement().satisfies(e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT));
            assertThat(fetching.isAlive()).as("the first caller still waits").isTrue();
            fetching.interrupt();
            assertThat(fetching.join(Duration.ofSeconds(5))).isTrue();
            assertThat(first).singleElement().satisfies(e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT));
            assertThat(store.calls()).isEqualTo(1);
        }
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }
}

