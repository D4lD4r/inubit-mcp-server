package de.dadecker.inubit.mcp;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.config.ConfigLoader;
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
 * T012 (feature 012, research D-12, US3-4): a read tool is announced only if some node may run
 * its capability; {@code list_nodes}, {@code get_health} and {@code check_artifacts} always.
 * Feature 020 (T030, FR-002, SC-002): {@code read_queue_entry} only for an opted-in node that may
 * run the capability and has a client home.
 */
class ToolAnnouncementTest {

    private static final List<String> ALWAYS = List.of("list_nodes", "get_health",
        "check_artifacts");
    private static final List<String> READS = List.of("find_processes", "query_logs",
        "list_inventory", "get_inventory_item", "export_artifacts", "export_archives");

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
        return toolNames(yaml, matrix);
    }

    /**
     * Feature 017 (T022, research D-7, plan review 1 finding 6): the tools announced for one node
     * with {@code versionLine}, a {@code defaults.cliClients."9.0"} entry and no other client.
     */
    private List<String> toolNamesWithNineEntry(String versionLine) {
        String yaml = """
            profile:
              name: acme
            workspace: %s
            defaults:
              cliClients:
                "9.0":
                  home: %s
            groups:
              - name: test
                versionLine: %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
            """.formatted(temp.resolve("workspace"), temp.resolve("cli-9.0"), versionLine);
        return toolNames(yaml, CapabilityMatrix.production());
    }

    private List<String> toolNames(String yaml, CapabilityMatrix matrix) {
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        SecretScrubber scrubber = new SecretScrubber();
        ProcessLauncher failing = spec -> {
            throw new IOException("StartCLI must not be launched in this test");
        };
        try (Wiring wiring = new Wiring(config, TestSecrets.store(Map.of()), scrubber,
            ClockProvider.system(), path -> false, false, failing, Map.of(), () -> {
                throw new AssertionError("no node reads from the Parameter Store");
            }, TestWiring.TEST_CONFIG_LOCATION, matrix)) {
            return wiring.toolHandlers().stream().map(ToolHandler::name).toList();
        }
    }

    /**
     * Feature 020 (T030, FR-002, research D-15): the tools announced for one group {@code test}
     * with {@code versionLine} and the group lines {@code groupLines} (indented as in the profile),
     * one node and a {@code defaults.cliClients."9.0"} entry only if {@code nineEntry}.
     */
    private List<String> queueEntryTools(String versionLine, String groupLines,
        boolean nineEntry) {
        String defaults = nineEntry ? """
            defaults:
              cliClients:
                "9.0":
                  home: %s
            """.formatted(temp.resolve("cli-9.0")) : "";
        String yaml = """
            profile:
              name: acme
            workspace: %s
            %sgroups:
              - name: test
                versionLine: %s
            %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
            """.formatted(temp.resolve("workspace"), defaults, versionLine, groupLines);
        return toolNames(yaml, CapabilityMatrix.production());
    }

    private String cliHome() {
        return "    cli:\n      home: " + temp.resolve("cli-test");
    }

    private static String optIn(String value) {
        return "    unsupported:\n      queueEntryRead: " + value;
    }

    @Test
    void withoutAnOptInTheToolListIsExactlyThatOfZeroEightZero() {
        List<String> release080 = List.of("list_nodes", "get_health", "find_processes",
            "query_logs", "list_inventory", "get_inventory_item", "export_artifacts",
            "export_archives", "check_artifacts");

        assertThat(queueEntryTools("AUTO", cliHome(), false)).containsExactlyElementsOf(release080);
        assertThat(queueEntryTools("AUTO", optIn("false") + "\n" + cliHome(), false))
            .containsExactlyElementsOf(release080);
    }

    @Test
    void anOptedInAutoNodeWithAClientHomeAnnouncesTheQueueEntryRead() {
        assertThat(queueEntryTools("AUTO", optIn("true") + "\n" + cliHome(), false))
            .containsExactly("list_nodes", "get_health", "find_processes", "query_logs",
                "read_queue_entry", "list_inventory", "get_inventory_item", "export_artifacts",
                "export_archives", "check_artifacts");
    }

    @Test
    void anOptedInEightOneNodeWithAClientHomeAnnouncesIt() {
        assertThat(queueEntryTools("V8_1", optIn("true") + "\n" + cliHome(), false))
            .contains("read_queue_entry");
    }

    @Test
    void anOptedInSixOneNodeDoesNotAnnounceIt() {
        assertThat(queueEntryTools("V6_1", optIn("true") + "\n" + cliHome(), false))
            .doesNotContain("read_queue_entry");
    }

    @Test
    void anOptedInNineZeroNodeWithItsClientAnnouncesIt() {
        // CapabilityMatrix.production(): 9.0 is supported since its recording (T038)
        assertThat(queueEntryTools("V9_0", optIn("true"), true))
            .contains("export_artifacts", "read_queue_entry");
    }

    @Test
    void anOptedInNodeWithoutAClientHomeDoesNotAnnounceIt() {
        assertThat(queueEntryTools("AUTO", optIn("true"), false))
            .doesNotContain("read_queue_entry", "export_artifacts");
    }

    @Test
    void theOptInAndTheClientHomeMustBeOnTheSameNode() {
        String yaml = """
            profile:
              name: acme
            workspace: %s
            groups:
              - name: test
                unsupported:
                  queueEntryRead: true
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
              - name: prod
                cli:
                  home: %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:2
            """.formatted(temp.resolve("workspace"), temp.resolve("cli-prod"));

        assertThat(toolNames(yaml, CapabilityMatrix.production())).contains("export_artifacts")
            .doesNotContain("read_queue_entry");
    }

    @Test
    void aNodePinnedToEightOneDoesNotAnnounceTheExportsForANineEntry() {
        assertThat(toolNamesWithNineEntry("V8_1")).doesNotContain("export_artifacts",
            "export_archives");
    }

    @Test
    void aNodePinnedToNineZeroAnnouncesTheExportsForItsEntry() {
        assertThat(toolNamesWithNineEntry("V9_0")).contains("export_artifacts",
            "export_archives");
    }

    @Test
    void anAutoNodeAnnouncesTheExportsForAnyEntry() {
        assertThat(toolNamesWithNineEntry("AUTO")).contains("export_artifacts",
            "export_archives");
    }

    @Test
    void aNodeOnNineWithoutMinorUsesOnlyTheVersionlessClient() {
        assertThat(toolNamesWithNineEntry("V9_X")).doesNotContain("export_artifacts",
            "export_archives");
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
                "export_artifacts", "export_archives");
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
