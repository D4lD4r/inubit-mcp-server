package de.dadecker.inubit.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 020 T030 (FR-002, US2-2, SC-005): with the opt-in on group {@code test} only,
 * {@code read_queue_entry} is announced, and a call for a node of group {@code prod} is refused
 * with {@code NOT_CONFIGURED} before anything is sent: no version detection, no queueLog lookup
 * (zero requests to the WireMock INUBIT) and no helper launch.
 */
class ReadQueueEntryWiringTest {

    @TempDir
    Path temp;

    private WireMockServer inubit;

    @BeforeEach
    void start() {
        inubit = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterEach
    void stop() {
        inubit.stop();
    }

    @Test
    void aCallForAGroupWithoutTheOptInIsRefusedBeforeAnyRequestOrLaunch() {
        String baseUrl = "https://localhost:" + inubit.httpsPort();
        String yaml = """
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            groups:
              - name: test
                versionLine: V8_1
                unsupported:
                  queueEntryRead: true
                cli:
                  home: %s
                nodes:
                  - name: node1
                    baseUrl: %s
              - name: prod
                cli:
                  home: %s
                nodes:
                  - name: node1
                    baseUrl: %s
            """.formatted(temp.resolve("audit"), temp.resolve("workspace"),
            temp.resolve("client"), baseUrl, temp.resolve("client"), baseUrl);
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        AtomicInteger launches = new AtomicInteger();
        ProcessLauncher launcher = spec -> {
            launches.incrementAndGet();
            throw new AssertionError("nothing is launched for a group without the opt-in");
        };

        try (TestWiring wiring = TestWiring.of(config, TestSecrets.store(Map.of(
                "INUBIT_ACME_TEST_USERNAME", "jdoe",
                "INUBIT_ACME_TEST_PASSWORD", TestSecrets.CONFIGURED_PASSWORD,
                "INUBIT_ACME_PROD_USERNAME", "jdoe",
                "INUBIT_ACME_PROD_PASSWORD", TestSecrets.CONFIGURED_PASSWORD)),
            new SecretScrubber(), path -> true, false, launcher, Map.of())) {
            ToolHandler tool = wiring.toolHandlers().stream()
                .filter(handler -> handler.name().equals("read_queue_entry")).findFirst()
                .orElseThrow(() -> new AssertionError("read_queue_entry is announced"));

            ToolErrorException refused = catchThrowableOfType(ToolErrorException.class,
                () -> tool.handle(Map.of("target", "prod/node1", "processId", "1234")));

            assertThat(refused).as("refusal").isNotNull();
            ToolError error = refused.error();
            assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
            assertThat(error.node()).isEqualTo(Optional.of(NodeId.parse("prod/node1")));
            assertThat(error.message()).isEqualTo("read_queue_entry is not enabled for group"
                + " prod: unsupported.queueEntryRead is not set");
            assertThat(error.nextStep()).contains("unsupported.queueEntryRead: true", "prod");
            for (String text : new String[] {error.message(), error.likelyCause(),
                error.nextStep()}) {
                TestSecrets.assertNoLeak(text);
            }
        }
        assertThat(inubit.findAll(anyRequestedFor(anyUrl()))).as("nothing was sent").isEmpty();
        assertThat(launches).as("no helper launch").hasValue(0);
    }
}
