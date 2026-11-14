package de.dadecker.inubit.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.nodetools.LeaseStore;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import de.dadecker.inubit.mcp.testing.TraceMatrix;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 024 T054 (FR-040, research D-6, D-18): {@code set_temporary_log_level} and
 * {@code reset_temporary_log_level} through the production wiring with
 * {@link TraceMatrix#admittingEightOne()}, a test matrix that admits the log-level change on 8.1.
 * Group {@code lab} has write access and {@code unsupported.logLevelWrite}, so both tools are
 * announced; group {@code test} opts in too but has write access switched off, so every call for
 * it is refused with {@code WRITE_DISABLED} before any request, launch or lease.
 */
@Timeout(60)
class LogLevelWiringTest {

    @TempDir(factory = GitFreeTempDir.class)
    Path temp;

    @Test
    void aNodeWithoutWriteAccessIsRefusedBeforeAnyRequestLaunchOrLease() {
        WireMockServer inubit = TestCertificates.httpsWireMock(
            TestCertificates.get().localhostKeyStore());
        AtomicInteger launches = new AtomicInteger();
        try {
            String yaml = """
                profile:
                  name: acme
                auditDirectory: %s
                workspace: %s
                groups:
                  - name: test
                    versionLine: V8_1
                    write:
                      enabled: false
                    unsupported:
                      logLevelWrite: true
                    cli:
                      home: %s
                    nodes:
                      - name: node1
                        baseUrl: https://localhost:%d
                  - name: lab
                    versionLine: V8_1
                    write:
                      enabled: true
                    unsupported:
                      logLevelWrite: true
                    cli:
                      home: %s
                    nodes:
                      - name: node1
                        baseUrl: https://localhost:2
                """.formatted(temp.resolve("audit"), temp.resolve("workspace"),
                temp.resolve("client"), inubit.httpsPort(), temp.resolve("client"));
            ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
                .parse(yaml, temp.resolve("config.yaml")).config();
            ProcessLauncher launcher = spec -> {
                launches.incrementAndGet();
                throw new AssertionError("nothing is launched without write access");
            };

            try (TestWiring wiring = TestWiring.of(config, TestSecrets.store(Map.of(
                    "INUBIT_ACME_TEST_USERNAME", "jdoe",
                    "INUBIT_ACME_TEST_PASSWORD", TestSecrets.CONFIGURED_PASSWORD)),
                new SecretScrubber(), path -> true, false, launcher, Map.of(),
                TraceMatrix.admittingEightOne())) {
                List<Map<String, Object>> calls = List.of(
                    Map.of("node", "test/node1", "loggers", List.of("com.inubit.ibis.acme.Orders"),
                        "level", "DEBUG"),
                    Map.of("node", "test/node1"));
                List<String> tools = List.of("set_temporary_log_level",
                    "reset_temporary_log_level");

                for (int i = 0; i < tools.size(); i++) {
                    ToolHandler tool = tool(wiring, tools.get(i));
                    Map<String, Object> arguments = calls.get(i);
                    ToolErrorException refused = catchThrowableOfType(ToolErrorException.class,
                        () -> tool.handle(arguments));

                    assertThat(refused).as(tools.get(i)).isNotNull();
                    ToolError error = refused.error();
                    assertThat(error.code()).as(tools.get(i)).isEqualTo(ErrorCode.WRITE_DISABLED);
                    assertThat(error.node()).contains(NodeId.parse("test/node1"));
                    assertThat(error.message())
                        .isEqualTo("Write access is disabled for test/node1; nothing was changed");
                    assertThat(error.nextStep()).startsWith("To allow " + tools.get(i));
                    TestSecrets.assertNoLeak(error.toString());
                }
                assertThat(LeaseStore.defaultDirectory(wiring.userHome(), "acme"))
                    .as("no lease store").doesNotExist();
            }
            assertThat(inubit.findAll(anyRequestedFor(anyUrl()))).as("nothing was sent")
                .isEmpty();
        } finally {
            inubit.stop();
        }
        assertThat(launches).as("no launch").hasValue(0);
    }

    private static ToolHandler tool(TestWiring wiring, String name) {
        return wiring.toolHandlers().stream()
            .filter(handler -> handler.name().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError(name + " is announced"));
    }
}
