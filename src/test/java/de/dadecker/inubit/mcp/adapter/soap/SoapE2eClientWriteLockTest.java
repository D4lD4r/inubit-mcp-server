package de.dadecker.inubit.mcp.adapter.soap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.E2ePort;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Feature 017 (T044, FR-018, research D-24): {@link SoapE2eClient#post} builds the request
 * outside the node's write lock, hands it to the HTTP client inside
 * {@link de.dadecker.inubit.mcp.domain.port.WriteLock#handOff} and awaits the answer outside;
 * a refused hand-off sends nothing.
 */
@Timeout(30)
class SoapE2eClientWriteLockTest {

    private static final NodeId NODE = NodeId.parse("dev/node1");
    private static final String PATH = "/ibis/ws/Service-01";
    private static final byte[] ENVELOPE = "<soapenv:Envelope/>".getBytes(StandardCharsets.UTF_8);
    private static final InubitVersion V8_1_17 = InubitVersion.parse("8.1.17").orElseThrow();
    private static final InubitVersion V9_0_7 = InubitVersion.parse("9.0.7").orElseThrow();
    private static WireMockServer wireMock;

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
        versions = new NodeVersions(new MutableClock(Instant.parse("2026-11-06T08:00:00Z")),
            node -> VersionSetting.AUTO, node -> Duration.ofMinutes(10));
    }

    private SoapE2eClient client() {
        EffectiveNodeConfig server = TestNodeConfig.node().id(NODE.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).build();
        return new SoapE2eClient(server, URI.create("https://localhost:" + wireMock.httpsPort()),
            Optional.empty(), Optional.empty(), versions);
    }

    private static E2ePort.Message message(Duration timeout) {
        return new E2ePort.Message(PATH, ENVELOPE, Optional.empty(), "test-id-0017", timeout);
    }

    private static WritePermit permit(NodeId node, InubitVersion admitted) {
        return new WritePermit(node, Capability.E2E_TEST, admitted);
    }

    @Test
    void anUnchangedVersionSendsTheMessage() {
        versions.detected(NODE, V8_1_17);
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
            .withBody("<ok/>")));

        try (SoapE2eClient client = client()) {
            E2ePort.Exchange exchange = client.post(message(Duration.ofSeconds(5)),
                permit(NODE, V8_1_17));

            assertThat(exchange.status()).contains(200);
        }
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aVersionChangeSinceTheAdmissionSendsNothing() {
        versions.detected(NODE, V9_0_7);
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));

        try (SoapE2eClient client = client()) {
            ToolError error = catchThrowableOfType(ToolErrorException.class,
                () -> client.post(message(Duration.ofSeconds(5)), permit(NODE, V8_1_17)))
                .error();

            assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
            assertThat(error.message()).isEqualTo("INUBIT on dev/node1 changed from 8.1.17 to"
                + " 9.0.7 during this call; the write was admitted for INUBIT 8.1; nothing was"
                + " sent");
            assertThat(error.node()).contains(NODE);
        }
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void aStaleVersionSendsNothing() {
        versions.detected(NODE, V8_1_17);
        versions.stale(NODE);

        try (SoapE2eClient client = client()) {
            ToolError error = catchThrowableOfType(ToolErrorException.class,
                () -> client.post(message(Duration.ofSeconds(5)), permit(NODE, V8_1_17)))
                .error();

            assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
            assertThat(error.message()).isEqualTo("INUBIT on dev/node1 may run another version"
                + " than 8.1.17 since this call was admitted; nothing was sent");
        }
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void aPermitOfAnotherNodeIsAProgrammingErrorAndSendsNothing() {
        versions.detected(NODE, V8_1_17);

        try (SoapE2eClient client = client()) {
            assertThatThrownBy(() -> client.post(message(Duration.ofSeconds(5)),
                permit(NodeId.parse("dev/node2"), V8_1_17)))
                .isInstanceOf(IllegalArgumentException.class);
        }
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void aPathLeavingTheBaseIsRefusedBeforeTheLock() {
        versions.detected(NODE, V9_0_7);

        try (SoapE2eClient client = client()) {
            ToolError error = catchThrowableOfType(ToolErrorException.class,
                () -> client.post(new E2ePort.Message("../admin", ENVELOPE, Optional.empty(),
                    "test-id-0017", Duration.ofSeconds(5)), permit(NODE, V8_1_17))).error();

            assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        }
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void theAnswerIsAwaitedOutsideTheLock() throws Exception {
        versions.detected(NODE, V8_1_17);
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
            .withFixedDelay(4000)));

        try (SoapE2eClient client = client()) {
            CompletableFuture<E2ePort.Exchange> sending = CompletableFuture.supplyAsync(
                () -> client.post(message(Duration.ofSeconds(10)), permit(NODE, V8_1_17)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (wireMock.findAll(postRequestedFor(urlEqualTo(PATH))).isEmpty()) {
                assertThat(System.nanoTime()).as("request received in time").isLessThan(deadline);
                Thread.onSpinWait();
            }
            // the answer is delayed: an observation must not wait for it
            CompletableFuture.runAsync(() -> versions.observed(NODE, V9_0_7))
                .get(2, TimeUnit.SECONDS);

            assertThat(sending).isNotDone();
            assertThat(versions.effectiveVersion(NODE)).contains(V9_0_7);
            assertThat(sending.get(10, TimeUnit.SECONDS).status()).contains(200);
        }
    }

    @Test
    void aTimeoutIsStillAnExchangeMarkedTimedOut() {
        versions.detected(NODE, V8_1_17);
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
            .withFixedDelay(3000)));

        try (SoapE2eClient client = client()) {
            E2ePort.Exchange exchange = client.post(message(Duration.ofMillis(300)),
                permit(NODE, V8_1_17));

            assertThat(exchange.timedOut()).isTrue();
            assertThat(exchange.status()).isEmpty();
        }
    }
}
