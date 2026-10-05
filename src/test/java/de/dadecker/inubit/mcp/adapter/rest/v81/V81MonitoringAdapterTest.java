package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.HealthStatus;
import de.dadecker.inubit.mcp.domain.model.LoadFigures;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort.Healthcheck;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort.Metrics;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort.Readiness;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T053: the 8.1 monitoring adapter against WireMock with the recorded and SYNTHETIC fixtures
 * (research R-5, R-10).
 */
@Timeout(30)
class V81MonitoringAdapterTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final String HEALTHCHECK = "/ibis/rest/healthcheck";
    private static final String READY = "/ibis/rest/ready";
    private static final String SYSTEM_INFO = "/ibis/rest/system/info";
    private static final String METRICS = "/ibis/rest/metrics";
    private static WireMockServer wireMock;

    private final SecretScrubber scrubber = new SecretScrubber();
    private final List<InubitHttpClient> clients = new ArrayList<>();

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

    @AfterEach
    void closeClients() {
        clients.forEach(InubitHttpClient::close);
    }

    private V81MonitoringAdapter adapter() {
        return adapter(config(Duration.ofSeconds(5)));
    }

    private static EffectiveNodeConfig config(Duration timeout) {
        return TestNodeConfig.node().id(ID.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(timeout).build();
    }

    private V81MonitoringAdapter adapter(EffectiveNodeConfig config) {
        InubitHttpClient client = new InubitHttpClient(config,
            Optional.of(new InubitHttpClient.Credentials("jdoe",
                scrubber.register("monitoring-test-pw"))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
        clients.add(client);
        return new V81MonitoringAdapter(ID, client);
    }

    private static void stub(String path, ResponseDefinitionBuilder response) {
        wireMock.stubFor(get(urlPathEqualTo(path)).willReturn(response));
    }

    private static void stub(String path, int status, String contentType, String body) {
        stub(path, aResponse().withStatus(status).withHeader("Content-Type", contentType)
            .withBody(body));
    }

    private static ErrorCode errorCodeOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            assertThat(e.error().node()).contains(ID);
            return e.error().code();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    // --- /healthcheck ---------------------------------------------------------------------

    @Test
    void healthcheckIsParsedFromTheRecordedFixtureWithoutCredentials() {
        stub(HEALTHCHECK, RestFixtures.response("healthcheck", "json"));

        Healthcheck health = adapter().healthcheck();

        assertThat(health.httpStatus()).isEqualTo(200);
        assertThat(health.status()).isEqualTo(HealthStatus.OK);
        assertThat(health.maintenanceMode()).contains(false);
        // "Thu Oct 01 15:58:45 CEST 2026" in Date.toString() format
        assertThat(health.timestamp()).contains(Instant.parse("2026-10-01T13:58:45Z"));
        assertThat(health.problem()).isEmpty();
        wireMock.verify(getRequestedFor(urlEqualTo(HEALTHCHECK))
            .withHeader("Authorization", absent()));
    }

    @Test
    void maintenanceModeOneIsOn() {
        stub(HEALTHCHECK, RestFixtures.response("healthcheck_maintenance", "json"));

        assertThat(adapter().healthcheck().maintenanceMode()).contains(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "\"1\"", "\"true\""})
    void otherMaintenanceFlagSpellingsAreOn(String flag) {
        stub(HEALTHCHECK, 200, "application/json",
            "{\"status\":\"OK\",\"maintenancemode\":" + flag + "}");

        Healthcheck health = adapter().healthcheck();

        assertThat(health.maintenanceMode()).contains(true);
        assertThat(health.timestamp()).isEmpty();
    }

    @Test
    void healthcheckWith503IsStillAnAnswerWithItsFlags() {
        stub(HEALTHCHECK, 503, "application/json",
            "{\"status\":\"OK\",\"maintenancemode\":1}");

        Healthcheck health = adapter().healthcheck();

        assertThat(health.httpStatus()).isEqualTo(503);
        assertThat(health.maintenanceMode()).contains(true);
    }

    @Test
    void errorStatusIsError() {
        stub(HEALTHCHECK, 200, "application/json",
            "{\"status\":\"ERROR\",\"maintenancemode\":0}");

        assertThat(adapter().healthcheck().status()).isEqualTo(HealthStatus.ERROR);
    }

    @Test
    void aNonJsonHealthcheckAnswerIsReachableButUnknown() {
        stub(HEALTHCHECK, 404, "text/html", "<html><body>Not Found</body></html>");

        Healthcheck health = adapter().healthcheck();

        assertThat(health.httpStatus()).isEqualTo(404);
        assertThat(health.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(health.maintenanceMode()).isEmpty();
        assertThat(health.problem()).get().asString().contains("HTTP 404");
    }

    @Test
    void anUnparseableTimestampIsLeftOut() {
        stub(HEALTHCHECK, 200, "application/json",
            "{\"status\":\"OK\",\"maintenancemode\":0,\"timestamp\":\"yesterday\"}");

        Healthcheck health = adapter().healthcheck();

        assertThat(health.status()).isEqualTo(HealthStatus.OK);
        assertThat(health.timestamp()).isEmpty();
    }

    @Test
    void aRefusedConnectionIsUnreachable() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        EffectiveNodeConfig closed = TestNodeConfig.node().id(ID.value())
            .baseUrl("https://localhost:" + port).trustStore(TestCertificates.get().trustStore())
            .timeout(Duration.ofSeconds(2)).build();

        assertThat(errorCodeOf(() -> adapter(closed).healthcheck()))
            .isEqualTo(ErrorCode.UNREACHABLE);
    }

    @Test
    void aHangingHealthcheckIsATimeout() {
        stub(HEALTHCHECK, RestFixtures.response("healthcheck", "json").withFixedDelay(3000));

        assertThat(errorCodeOf(() -> adapter(config(Duration.ofMillis(500))).healthcheck()))
            .isEqualTo(ErrorCode.TIMEOUT);
    }

    // --- /ready ----------------------------------------------------------------------------

    @Test
    void readyIsTrueOn200WithTheMessageAndWithoutCredentials() {
        stub(READY, RestFixtures.response("ready", "json"));

        Readiness ready = adapter().ready();

        assertThat(ready.ready()).isTrue();
        assertThat(ready.message()).contains("Ready to serve.");
        wireMock.verify(getRequestedFor(urlEqualTo(READY)).withHeader("Authorization", absent()));
    }

    @Test
    void readyIsFalseOn503() {
        stub(READY, RestFixtures.response("ready_not_ready", "json"));

        Readiness ready = adapter().ready();

        assertThat(ready.ready()).isFalse();
        assertThat(ready.message()).contains("Not ready to serve.");
    }

    @Test
    void readyWithoutJsonBodyHasNoMessage() {
        stub(READY, 503, "text/plain", "Service Unavailable");

        Readiness ready = adapter().ready();

        assertThat(ready.ready()).isFalse();
        assertThat(ready.message()).isEmpty();
    }

    @Test
    void otherReadyStatusesAreErrors() {
        stub(READY, 500, "text/html", "<html>boom</html>");

        assertThat(errorCodeOf(() -> adapter().ready()))
            .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    // --- /system/info -----------------------------------------------------------------------

    @Test
    void systemInfoIsParsedFromTheRecordedFixtureWithCredentials() {
        stub(SYSTEM_INFO, RestFixtures.response("system_info", "xml"));

        SystemInfo info = adapter().systemInfo();

        assertThat(info.version()).contains("8.1.17");
        assertThat(info.jdk()).contains("OpenJDK Runtime Environment 17.0.20.1 - 64 bit"
            + " (Amazon.com Inc.)");
        assertThat(info.os()).contains("Linux 6.12.107+deb13-cloud-amd64");
        assertThat(info.maxHeap()).contains("9216 MB");
        assertThat(info.tracingEnabled()).contains(true);
        assertThat(info.schedulerThreads()).contains(401);
        assertThat(info.raw())
            .containsEntry("FreeMemory", "2548")
            .containsEntry("TracingLevel", "info")
            .containsEntry("LicenseExpirationDate", "2053-12-26")
            .doesNotContainKeys("Version", "ServerJDKVersion", "ServerOSName", "ServerXMX",
                "TracingIsActive", "NumberSchedulerThreads");
        assertThat(info.raw().keySet()).first().isEqualTo("FreeMemory");
        wireMock.verify(getRequestedFor(urlEqualTo(SYSTEM_INFO))
            .withHeader("Authorization", matching("Basic .+")));
    }

    @Test
    void rawSystemInfoIsBounded() {
        StringBuilder xml = new StringBuilder("<SystemInformationList"
            + " xmlns=\"inubit.com/ibis/external/systeminformation\">"
            + "<SystemInformation name=\"Version\" value=\"8.1.17\"/>");
        for (int i = 0; i < SystemInfo.MAX_RAW_ENTRIES + 10; i++) {
            xml.append("<SystemInformation name=\"Key").append(i).append("\" value=\"")
                .append("x".repeat(SystemInfo.MAX_RAW_VALUE_CHARS + 50)).append("\"/>");
        }
        stub(SYSTEM_INFO, 200, "application/xml", xml.append("</SystemInformationList>")
            .toString());

        SystemInfo info = adapter().systemInfo();

        assertThat(info.version()).contains("8.1.17");
        assertThat(info.raw()).hasSize(SystemInfo.MAX_RAW_ENTRIES);
        assertThat(info.raw().values()).allSatisfy(value ->
            assertThat(value.length()).isLessThanOrEqualTo(SystemInfo.MAX_RAW_VALUE_CHARS));
    }

    @Test
    void namesThatCollideAfterTruncationAreKeptWithASuffix() {
        String prefix = "K".repeat(120);
        stub(SYSTEM_INFO, 200, "application/xml", "<SystemInformationList"
            + " xmlns=\"inubit.com/ibis/external/systeminformation\">"
            + "<SystemInformation name=\"" + prefix + "A\" value=\"1\"/>"
            + "<SystemInformation name=\"" + prefix + "B\" value=\"2\"/>"
            + "</SystemInformationList>");

        SystemInfo info = adapter().systemInfo();

        assertThat(info.raw()).hasSize(2).containsValues("1", "2");
        assertThat(info.raw().keySet()).allSatisfy(key ->
            assertThat(key.length()).isLessThanOrEqualTo(100));
    }

    @Test
    void systemInfoFailuresAreErrors() {
        stub(SYSTEM_INFO, 401, "text/html", "<html>HTTP Status 401</html>");
        assertThat(errorCodeOf(() -> adapter().systemInfo())).isEqualTo(ErrorCode.AUTH_FAILED);

        stub(SYSTEM_INFO, 200, "application/xml", "<SystemInformationList><unclosed>");
        assertThat(errorCodeOf(() -> adapter().systemInfo()))
            .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);

        stub(SYSTEM_INFO, 200, "application/xml", "<SomethingElse/>");
        assertThat(errorCodeOf(() -> adapter().systemInfo()))
            .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    // --- /metrics ---------------------------------------------------------------------------

    @Test
    void metricsAreParsedFromTheRecordedFixtureWithDerivedPercentages() {
        stub(METRICS, RestFixtures.response("metrics", "json"));

        Metrics metrics = adapter().metrics();

        assertThat(metrics).isInstanceOfSatisfying(Metrics.Available.class, available -> {
            LoadFigures load = available.figures();
            assertThat(load.usedMemoryMb()).isEqualTo(5500.0);
            assertThat(load.freeMemoryMb()).isEqualTo(3716.0);
            assertThat(load.maxMemoryMb()).isEqualTo(9216.0);
            assertThat(load.memoryUsedPercent()).contains(59.7);
            assertThat(load.threadsInUse()).isZero();
            assertThat(load.licensedThreads()).isEqualTo(999_999);
            assertThat(load.maxThreads()).isEqualTo(401);
            assertThat(load.blockingQueueEntries()).isZero();
            assertThat(load.maxBlockingQueueSize()).isEqualTo(200_000);
            assertThat(load.blockingQueuePercent()).contains(0.0);
        });
        wireMock.verify(getRequestedFor(urlEqualTo(METRICS + "?format=json"))
            .withHeader("Authorization", matching("Basic .+")));
    }

    @Test
    void unlicensedMetricsAreNotLicensedWithTheHttpStatus() {
        stub(METRICS, RestFixtures.response("metrics_unlicensed", "json"));

        assertThat(adapter().metrics()).isInstanceOfSatisfying(Metrics.NotLicensed.class,
            notLicensed -> assertThat(notLicensed.reason()).contains("HTTP 403"));
    }

    @ParameterizedTest
    @ValueSource(ints = {402, 404, 500})
    void otherHttpFailuresOfMetricsAreNotLicensed(int status) {
        stub(METRICS, status, "text/html", "<html>no</html>");

        assertThat(adapter().metrics()).isInstanceOfSatisfying(Metrics.NotLicensed.class,
            notLicensed -> assertThat(notLicensed.reason()).contains("HTTP " + status));
    }

    @Test
    void aNonJsonMetricsBodyIsNotLicensed() {
        stub(METRICS, 200, "text/html", "<html>License required</html>");

        assertThat(adapter().metrics()).isInstanceOfSatisfying(Metrics.NotLicensed.class,
            notLicensed -> assertThat(notLicensed.reason()).contains("HTTP 200", "JSON"));
    }

    @Test
    void metricsJsonWithoutLoadFiguresIsNotLicensed() {
        stub(METRICS, 200, "application/json", "{\"serverName\":\"x\"}");

        assertThat(adapter().metrics()).isInstanceOf(Metrics.NotLicensed.class);
    }

    @Test
    void unauthorizedUnavailableAndTimeoutOfMetricsAreErrors() {
        stub(METRICS, 401, "text/html", "<html>HTTP Status 401</html>");
        assertThat(errorCodeOf(() -> adapter().metrics())).isEqualTo(ErrorCode.AUTH_FAILED);

        stub(METRICS, 503, "text/plain", "Service Unavailable");
        assertThat(errorCodeOf(() -> adapter().metrics())).isEqualTo(ErrorCode.UNREACHABLE);

        stub(METRICS, RestFixtures.response("metrics", "json").withFixedDelay(3000));
        assertThat(errorCodeOf(() -> adapter(config(Duration.ofMillis(500))).metrics()))
            .isEqualTo(ErrorCode.TIMEOUT);
    }

    @Test
    void metricsQueryAsksForJson() {
        stub(METRICS, RestFixtures.response("metrics", "json"));

        adapter().metrics();

        wireMock.verify(getRequestedFor(urlPathEqualTo(METRICS))
            .withQueryParam("format", equalTo("json")));
    }
}
