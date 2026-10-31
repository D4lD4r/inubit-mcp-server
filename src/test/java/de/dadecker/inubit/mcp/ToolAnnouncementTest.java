package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T012 (feature 011, research D-12, US3-4): a read tool is announced only if some node may run
 * its capability; {@code list_nodes}, {@code get_health} and {@code check_artifacts} always.
 */
class ToolAnnouncementTest {

    private static final List<String> ALWAYS = List.of("list_nodes", "get_health",
        "check_artifacts");
    private static final List<String> READS = List.of("find_processes", "query_logs",
        "list_inventory", "get_inventory_item", "export_artifacts");

    /** The production matrix after the flip of the 6.1 capabilities (T040). */
    private static final CapabilityMatrix FLIPPED = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(6, 1, 6, 1),
            Map.of(Capability.MONITORING, SupportState.SUPPORTED,
                Capability.PROCESS_DIAGNOSIS, SupportState.SUPPORTED),
            Map.of(Capability.MONITORING, Set.of(Channel.CLI),
                Capability.PROCESS_DIAGNOSIS, Set.of(Channel.CLI)), true)),
        Map.of());

    @TempDir
    Path temp;

    /** The tools announced for groups {@code old} and {@code lab}, both with StartCLI. */
    private List<String> toolNames(String oldLine, String labLine, CapabilityMatrix matrix) {
        String yaml = """
            profile:
              name: acme
            workspace: %s
            groups:
              - name: old
                versionLine: %s
                cli:
                  home: %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
              - name: lab
                versionLine: %s
                cli:
                  home: %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:2
            """.formatted(temp.resolve("workspace"), oldLine, temp.resolve("cli-old"), labLine,
            temp.resolve("cli-lab"));
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        SecretScrubber scrubber = new SecretScrubber();
        ProcessLauncher failing = spec -> {
            throw new IOException("StartCLI must not be launched in this test");
        };
        try (Wiring wiring = new Wiring(config, new CredentialResolver(Map.of(), scrubber,
                config.credentialPrefix()).resolve(config.nodeIds()), scrubber,
            ClockProvider.system(), path -> false, false, failing, Map.of(), () -> {
                throw new AssertionError("no node reads from the Parameter Store");
            }, matrix)) {
            return wiring.toolHandlers().stream().map(ToolHandler::name).toList();
        }
    }

    @Test
    void onlySixOneNodesBeforeTheFlipAnnounceNoReadTool() {
        List<String> names = toolNames("V6_1", "V6_1", CapabilityMatrix.production());

        assertThat(names).containsAll(ALWAYS).doesNotContainAnyElementsOf(READS);
    }

    @Test
    void onlySixOneNodesAfterTheFlipAnnounceProcessDiagnosisOnly() {
        List<String> names = toolNames("V6_1", "V6_1", FLIPPED);

        assertThat(names).containsAll(ALWAYS).contains("find_processes")
            .doesNotContain("query_logs", "list_inventory", "get_inventory_item",
                "export_artifacts");
    }

    @Test
    void aMixedProfileAnnouncesEveryReadTool() {
        List<String> names = toolNames("V6_1", "V8_1", CapabilityMatrix.production());

        assertThat(names).containsAll(ALWAYS).containsAll(READS);
    }

    @Test
    void anAutoProfileAnnouncesEveryReadTool() {
        List<String> names = toolNames("AUTO", "AUTO", CapabilityMatrix.production());

        assertThat(names).containsAll(ALWAYS).containsAll(READS);
    }
}
