package de.dadecker.inubit.mcp.adapter.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Feature 022 T058 (US4, research D-22; feature 017 research D-24): a REST write
 * ({@code POST /ready/{status}}) builds its request outside the node's write lock, hands it to the
 * HTTP client inside {@link de.dadecker.inubit.mcp.domain.port.WriteLock#handOff} and awaits the
 * answer outside; a refused hand-off sends nothing.
 */
@Timeout(30)
class InubitHttpClientWriteLockTest {

    private static final NodeId NODE = NodeId.parse("dev/node1");
    private static final String READY_OFF = "/ibis/rest/ready/off";
    private static final InubitVersion V8_1_17 = InubitVersion.parse("8.1.17").orElseThrow();
    private static final InubitVersion V9_0_7 = InubitVersion.parse("9.0.7").orElseThrow();
    private static WireMockServer wireMock;

    private final SecretScrubber scrubber = new SecretScrubber();
    private NodeVersions versions;

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
        versions = new NodeVersions(new MutableClock(Instant.parse("2026-11-17T08:00:00Z")),
            node -> VersionSetting.AUTO, node -> Duration.ofMinutes(10));
    }

    private InubitHttpClient client(Duration timeout) {
        return new InubitHttpClient(TestNodeConfig.node().id(NODE.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort()).timeout(timeout)
            .trustStore(TestCertificates.get().trustStore()).build(),
            Optional.of(new InubitHttpClient.Credentials("jdoe",
                scrubber.register("client-write-pw"))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
    }

    private static WritePermit permit(NodeId node, InubitVersion admitted) {
        return new WritePermit(node, Capability.SERVER_OPERATION, admitted);
    }

    @Test
    void anUnchangedVersionSendsTheRequestWithCredentials() {
        versions.detected(NODE, V8_1_17);
        wireMock.stubFor(post(urlEqualTo(READY_OFF)).willReturn(aResponse().withStatus(200)));

        try (InubitHttpClient client = client(Duration.ofSeconds(5))) {
            RestResponse response = client.post(RestEndpoint.READY_SWITCH, List.of("off"),
                permit(NODE, V8_1_17), versions);

            assertThat(response.status()).isEqualTo(200);
        }
        wireMock.verify(1, postRequestedFor(urlEqualTo(READY_OFF))
            .withHeader("Authorization", matching("Basic .+")));
    }

    @Test
    void aVersionChangeSinceTheAdmissionSendsNothing() {
        versions.detected(NODE, V9_0_7);

        try (InubitHttpClient client = client(Duration.ofSeconds(5))) {
            ToolError error = catchThrowableOfType(ToolErrorException.class,
                () -> client.post(RestEndpoint.READY_SWITCH, List.of("off"),
                    permit(NODE, V8_1_17), versions)).error();

            assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
            assertThat(error.message()).endsWith("nothing was sent");
            assertThat(error.node()).contains(NODE);
        }
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void aPermitOfAnotherNodeIsAProgrammingErrorAndSendsNothing() {
        versions.detected(NODE, V8_1_17);

        try (InubitHttpClient client = client(Duration.ofSeconds(5))) {
            assertThatThrownBy(() -> client.post(RestEndpoint.READY_SWITCH, List.of("off"),
                permit(NodeId.parse("dev/node2"), V8_1_17), versions))
                .isInstanceOf(IllegalArgumentException.class);
        }
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void anErrorStatusIsMappedAsForEveryRequest() {
        versions.detected(NODE, V8_1_17);
        wireMock.stubFor(post(urlEqualTo(READY_OFF)).willReturn(aResponse().withStatus(403)));

        try (InubitHttpClient client = client(Duration.ofSeconds(5))) {
            ToolError error = catchThrowableOfType(ToolErrorException.class,
                () -> client.post(RestEndpoint.READY_SWITCH, List.of("off"),
                    permit(NODE, V8_1_17), versions)).error();

            assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(error.node()).contains(NODE);
            assertThat(error.message()).contains("POST " + READY_OFF);
        }
    }

    @Test
    void aTimeoutIsReportedAsForEveryRequest() {
        versions.detected(NODE, V8_1_17);
        wireMock.stubFor(post(urlEqualTo(READY_OFF)).willReturn(aResponse().withStatus(200)
            .withFixedDelay(3000)));

        try (InubitHttpClient client = client(Duration.ofMillis(300))) {
            ToolError error = catchThrowableOfType(ToolErrorException.class,
                () -> client.post(RestEndpoint.READY_SWITCH, List.of("off"),
                    permit(NODE, V8_1_17), versions)).error();

            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(error.node()).contains(NODE);
        }
    }

    @Test
    void theAnswerIsAwaitedOutsideTheLock() throws Exception {
        versions.detected(NODE, V8_1_17);
        wireMock.stubFor(post(urlEqualTo(READY_OFF)).willReturn(aResponse().withStatus(200)
            .withFixedDelay(4000)));

        try (InubitHttpClient client = client(Duration.ofSeconds(10))) {
            CompletableFuture<RestResponse> sending = CompletableFuture.supplyAsync(
                () -> client.post(RestEndpoint.READY_SWITCH, List.of("off"),
                    permit(NODE, V8_1_17), versions));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (wireMock.findAll(postRequestedFor(urlEqualTo(READY_OFF))).isEmpty()) {
                assertThat(System.nanoTime()).as("request received in time").isLessThan(deadline);
                Thread.onSpinWait();
            }
            // the answer is delayed: an observation must not wait for it
            CompletableFuture.runAsync(() -> versions.observed(NODE, V9_0_7))
                .get(2, TimeUnit.SECONDS);

            assertThat(sending).isNotDone();
            assertThat(versions.effectiveVersion(NODE)).contains(V9_0_7);
            assertThat(sending.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
        }
    }
}
