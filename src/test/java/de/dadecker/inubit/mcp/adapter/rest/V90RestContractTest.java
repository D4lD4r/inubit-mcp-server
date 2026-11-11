package de.dadecker.inubit.mcp.adapter.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.matching.UrlPattern;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.V81InventoryAdapter;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.MediaRange;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81LogAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81MonitoringAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81VersionDetector;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.HealthStatus;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.LogType;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.Severity;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort.Healthcheck;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort.Metrics;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import de.dadecker.inubit.mcp.testing.V90Cases;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T030 (feature 017, FR-002, FR-007, FR-008, research D-10 to D-13): the 8.1 REST adapters
 * against the INUBIT 9.0 fixtures (opened only through {@link V90Cases}). The stubs behave like
 * 9.0: a request whose {@code Accept} is not the one of its parser gets HTTP 406 with a 9.0 JSON
 * error body; every error status maps to the 8.1 code; no request path contains {@code //},
 * {@code /./} or {@code /../}.
 */
@Timeout(60)
class V90RestContractTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String USER = "jdoe";
    private static final String PASSWORD = "v90-contract-pw";
    private static final String REST = "/ibis/rest";
    private static final String HEALTHCHECK = REST + "/healthcheck";
    private static final String READY = REST + "/ready";
    private static final String SYSTEM_INFO = REST + "/system/info";
    private static final String METRICS = REST + "/metrics";
    private static final String MODELS = REST + "/model/models";
    private static final String MODEL_BY_NAME = REST + "/model/modelByName/Sample%20diagram%2001";
    private static final String EXPORT = REST + "/model/export/Workflow-0101";
    private static final String SYSTEM_LOG = REST + "/log/systemLog";
    private static final String NOT_ACCEPTABLE = "{\"statusCode\":406,\"message\":\"Not"
        + " Acceptable\",\"path\":\"/ibis/rest\",\"timestamp\":\"2026-11-06T10:00:00.000+00:00\"}";

    private static WireMockServer wireMock;

    @TempDir
    Path cliHome;

    private final SecretScrubber scrubber = new SecretScrubber();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-06T08:00:00Z"));
    private EffectiveNodeConfig config;
    private NodeCredentials credentials;
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
        // INUBIT 9.0 answers a request without the matching Accept with 406 (research D-11)
        wireMock.stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(406)
            .withHeader("Content-Type", "application/json").withBody(NOT_ACCEPTABLE)));
        FakeStartCli.install(cliHome);
        config = TestNodeConfig.node().id(DEV.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).cliHome(cliHome)
            .timeout(Duration.ofSeconds(5)).build();
        credentials = TestSecrets.credentialStore(List.of(config), Map.of(
            "INUBIT_DEV_USERNAME", USER, "INUBIT_DEV_PASSWORD", PASSWORD), scrubber)
            .get(DEV).current(Duration.ofSeconds(5));
        probe = new V81MaintenanceProbe(config, Optional.empty(), scrubber);
        client = InubitHttpClient.create(config, credentials, probe, scrubber,
            new CredentialGuard(storeLabels("INUBIT", DEV), clock));
    }

    @AfterEach
    void tearDown() {
        client.close();
        probe.close();
        assertThat(wireMock.getAllServeEvents()).as("D-13: no path with //, /./ or /../")
            .extracting(event -> event.getRequest().getUrl())
            .noneMatch(url -> url.contains("//") || url.contains("/./")
                || url.contains("/../"));
    }

    private static void stubGet(String path, MediaRange accept, ResponseDefinitionBuilder answer) {
        stub(get(urlPathEqualTo(path)), accept, answer);
    }

    private static void stub(com.github.tomakehurst.wiremock.client.MappingBuilder request,
        MediaRange accept, ResponseDefinitionBuilder answer) {
        wireMock.stubFor(request.atPriority(1).withHeader("Accept", equalTo(accept.header()))
            .willReturn(answer));
    }

    private InventoryPort inventory() {
        return new V81InventoryAdapter(DEV, client, new CliExportRunner(config, credentials,
            client.credentialGuard(), TestCliRunners.forHost(FakeProcessLauncher.of("", "", 1),
                Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(scrubber)));
    }

    private static ToolError errorOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            assertThat(e.error().node()).contains(DEV);
            assertThat(e.error().toString() + e.getMessage()).doesNotContain(PASSWORD, USER + ":");
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    private static String message(String errorCase) {
        String body = new String(V90Cases.restBody(errorCase), StandardCharsets.UTF_8);
        int start = body.indexOf("\"message\"");
        int open = body.indexOf('"', body.indexOf(':', start) + 1);
        return body.substring(open + 1, body.indexOf('"', open + 1));
    }

    // --- the read endpoints --------------------------------------------------------------------

    @Test
    void theDetectorReadsTheVersionOf90() {
        stubGet(SYSTEM_INFO, MediaRange.XML, V90Cases.response("system_info"));

        assertThat(V81VersionDetector.detect(DEV, client)).isEqualTo("9.0.7");
    }

    @Test
    void theMonitoringAdapterReadsHealthReadySystemInfoAndMetrics() {
        stubGet(HEALTHCHECK, MediaRange.JSON, V90Cases.response("healthcheck"));
        stubGet(READY, MediaRange.JSON, V90Cases.response("ready"));
        stubGet(SYSTEM_INFO, MediaRange.XML, V90Cases.response("system_info"));
        stubGet(METRICS, MediaRange.JSON, V90Cases.response("metrics"));
        V81MonitoringAdapter monitoring = new V81MonitoringAdapter(DEV, client);

        Healthcheck health = monitoring.healthcheck();
        assertThat(health.httpStatus()).isEqualTo(200);
        assertThat(health.status()).isEqualTo(HealthStatus.OK);
        assertThat(health.maintenanceMode()).contains(false);
        assertThat(health.problem()).isEmpty();
        assertThat(monitoring.ready().ready()).isTrue();
        assertThat(monitoring.systemInfo().version()).contains("9.0.7");
        // INUBIT 9.0 bundles JDK 21 (docs/research/inubit-interfaces.md §4)
        assertThat(monitoring.systemInfo().jdk()).hasValueSatisfying(jdk ->
            assertThat(jdk).contains(" 21.0."));
        assertThat(monitoring.metrics()).isInstanceOfSatisfying(Metrics.Available.class,
            available -> assertThat(available.figures().maxMemoryMb()).isPositive());
    }

    @Test
    void theInventoryAdapterReadsModelsModelByNameAndTheExport() {
        stubGet(SYSTEM_INFO, MediaRange.XML, V90Cases.response("system_info"));
        stubGet(MODELS, MediaRange.XML, V90Cases.response("model_models_owner"));
        stubGet(MODEL_BY_NAME, MediaRange.XML, V90Cases.response("modelByName_sample"));
        stubGet(EXPORT, MediaRange.ANY, V90Cases.response("model_export_sample"));
        InventoryPort inventory = inventory();

        assertThat(inventory.listDiagrams("OWNERS")).isNotEmpty()
            .allSatisfy(item -> assertThat(item.node()).isEqualTo(DEV));
        assertThat(inventory.diagramDetail("OWNERS", "Sample diagram 01").modules()).isNotEmpty();
        assertThat(inventory.diagramMetadata("Workflow-0101").active()).contains(true);
    }

    @Test
    void theExportOf90ArrivesGzipEncodedAndIsReadAsTheArchive() throws IOException {
        // live check 2026-11-11 (9.0.10, F-3): Content-Type application/zip and, although no
        // Accept-Encoding is sent, Content-Encoding gzip around the ZIP
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gzipped)) {
            out.write(V90Cases.restBody("model_export_sample"));
        }
        stubGet(EXPORT, MediaRange.ANY, aResponse().withStatus(200)
            .withHeader("Content-Type", "application/zip").withHeader("Content-Encoding", "gzip")
            .withBody(gzipped.toByteArray()));

        assertThat(inventory().diagramMetadata("Workflow-0101").active()).contains(true);
    }

    @Test
    void theLogAdapterReadsTheLogResponsesOf90() {
        V81LogAdapter logs = new V81LogAdapter(DEV, client);
        stub(post(urlPathEqualTo(SYSTEM_LOG)), MediaRange.JSON, V90Cases.response("log_systemLog"));

        Page<LogEntry> page = logs.query(query());
        assertThat(page.items()).isNotEmpty();

        wireMock.resetMappings();
        stub(post(urlPathEqualTo(SYSTEM_LOG)), MediaRange.JSON,
            V90Cases.response("log_systemLog_filtered"));
        assertThat(logs.query(query()).items().get(0).severity()).isEqualTo(Severity.ERROR);
    }

    private static LogQuery query() {
        return new LogQuery(LogType.SYSTEM_LOG, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Set.of(), Optional.empty(), 0, 5);
    }

    // --- the error bodies of 9.0 (research D-12) --------------------------------------------

    @Test
    void unauthorizedIsAuthFailedAndIgnoresTheBody() {
        stubGet(SYSTEM_INFO, MediaRange.XML, V90Cases.response("rest_auth_failed"));

        ToolError error = errorOf(() -> V81VersionDetector.detect(DEV, client));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.excerpt()).isEmpty();
    }

    @Test
    void forbiddenIsForbidden() {
        stubGet(MODELS, MediaRange.XML, V90Cases.response("rest_forbidden"));

        ToolError error = errorOf(() -> inventory().listDiagrams("OWNERS"));

        assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void notFoundOfAResourceIsNotFound() {
        stubGet(MODEL_BY_NAME, MediaRange.XML, V90Cases.response("rest_not_found"));

        ToolError error = errorOf(() -> inventory().diagramDetail("OWNERS", "Sample diagram 01"));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void notFoundOfAnEndpointIsAMissingEndpoint() {
        stubGet(SYSTEM_INFO, MediaRange.XML, V90Cases.response("rest_not_found"));

        try {
            V81VersionDetector.detect(DEV, client);
        } catch (InubitHttpClient.EndpointMissing e) {
            assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
            return;
        }
        throw new AssertionError("expected EndpointMissing");
    }

    @Test
    void anInternalErrorIsUnexpectedWithTheMessageAsExcerpt() {
        stubGet(MODELS, MediaRange.XML, V90Cases.response("rest_internal_error"));

        ToolError error = errorOf(() -> inventory().listDiagrams("OWNERS"));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.excerpt()).contains(message("rest_internal_error"));
    }

    @Test
    void serviceUnavailableIsUnreachableWhenTheHealthcheckReportsNoMaintenance() {
        stubGet(HEALTHCHECK, MediaRange.JSON, V90Cases.response("healthcheck"));
        stubGet(MODELS, MediaRange.XML, V90Cases.response("rest_unavailable"));

        ToolError error = errorOf(() -> inventory().listDiagrams("OWNERS"));

        assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(error.excerpt()).contains(message("rest_unavailable"));
    }

    @Test
    void serviceUnavailableIsMaintenanceWhenTheHealthcheckReportsIt() {
        String maintenance = new String(V90Cases.restBody("healthcheck"), StandardCharsets.UTF_8)
            .replace("\"maintenancemode\": 0", "\"maintenancemode\": 1");
        assertThat(maintenance).contains("\"maintenancemode\": 1");
        stubGet(HEALTHCHECK, MediaRange.JSON, V90Cases.response("healthcheck").withBody(
            maintenance));
        stubGet(MODELS, MediaRange.XML, V90Cases.response("rest_unavailable"));

        assertThat(errorOf(() -> inventory().listDiagrams("OWNERS")).code())
            .isEqualTo(ErrorCode.MAINTENANCE_MODE);
    }

    @Test
    void theErrorFixturesCarryTheStatusOfTheirCase() {
        Map<String, Integer> statuses = Map.of("rest_auth_failed", 401, "rest_forbidden", 403,
            "rest_not_found", 404, "rest_internal_error", 500, "rest_unavailable", 503);
        statuses.forEach((name, status) -> {
            assertThat(V90Cases.text(name, "http")).as(name).contains("status: " + status);
            assertThat(new String(V90Cases.restBody(name), StandardCharsets.UTF_8)).as(name)
                .contains("\"statusCode\"", "\"message\"", "\"path\"", "\"timestamp\"");
        });
    }

    @Test
    void aWrongAcceptIsAnsweredWith406AsOn90() {
        UrlPattern path = urlPathMatching(SYSTEM_INFO);
        wireMock.stubFor(get(path).atPriority(1).withHeader("Accept", equalTo(
            MediaRange.JSON.header())).willReturn(V90Cases.response("system_info")));

        // the stub only serves JSON; the detector asks for XML and gets the 9.0 answer 406
        ToolError error = errorOf(() -> V81VersionDetector.detect(DEV, client));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.excerpt()).contains("Not Acceptable");
        assertThat(wireMock.getAllServeEvents()).extracting(ServeEvent::getRequest)
            .allSatisfy(request -> assertThat(request.getHeader("Accept"))
                .isEqualTo(MediaRange.XML.header()));
    }
}
