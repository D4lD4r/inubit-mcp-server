package de.dadecker.inubit.mcp;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import de.dadecker.inubit.mcp.testing.TraceMatrix;
import de.dadecker.inubit.mcp.testing.WebServiceMatrix;
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
 * Feature 019 (research D-15): {@code read_knowledge} always, right after {@code get_health},
 * and {@code record_knowledge} after it with {@code knowledge.recording} on (the default).
 * Feature 020 (T030, FR-002, SC-002): {@code read_queue_entry} only for an opted-in node that may
 * run the capability and has a client home. Feature 021 (T031, FR-002, D-17):
 * {@code download_server_log} for a node with a client home that may download the trace log;
 * T043: or for a node of a group with {@code unsupported.serverLogRead} with a client home that
 * may download the web service log. Feature 024 (T023, FR-002, SC-005, D-6):
 * {@code list_cluster_nodes} only for a node of a group with {@code unsupported.nodeStatusRead}
 * that may list the cluster's nodes and has a client home.
 */
class ToolAnnouncementTest {

    private static final List<String> ALWAYS = List.of("list_nodes", "get_health",
        "read_knowledge", "record_knowledge", "check_artifacts");
    private static final List<String> READS = List.of("find_processes", "query_logs",
        "list_inventory", "get_inventory_item", "export_artifacts", "export_archives",
        "find_partners");

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
            }, TestWiring.TEST_CONFIG_LOCATION, temp.resolve("home"), matrix)) {
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
        return groupTools(versionLine, groupLines, nineEntry, CapabilityMatrix.production());
    }

    /** As {@link #queueEntryTools}, with {@code matrix}. */
    private List<String> groupTools(String versionLine, String groupLines, boolean nineEntry,
        CapabilityMatrix matrix) {
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
        return toolNames(yaml, matrix);
    }

    private String cliHome() {
        return "    cli:\n      home: " + temp.resolve("cli-test");
    }

    private static String optIn(String value) {
        return "    unsupported:\n      queueEntryRead: " + value;
    }

    @Test
    void withoutAnOptInTheToolListIsUnchangedByFeature020() {
        // feature 021 T033: an AUTO node with a client home also announces download_server_log
        List<String> withoutFeature020 = List.of("list_nodes", "get_health",
            "read_knowledge", "record_knowledge", "find_processes", "query_logs",
            "download_server_log", "list_inventory", "get_inventory_item", "find_partners",
            "export_artifacts", "export_archives", "check_artifacts");

        assertThat(queueEntryTools("AUTO", cliHome(), false))
            .containsExactlyElementsOf(withoutFeature020);
        assertThat(queueEntryTools("AUTO", optIn("false") + "\n" + cliHome(), false))
            .containsExactlyElementsOf(withoutFeature020);
    }

    @Test
    void anOptedInAutoNodeWithAClientHomeAnnouncesTheQueueEntryRead() {
        assertThat(queueEntryTools("AUTO", optIn("true") + "\n" + cliHome(), false))
            .containsExactly("list_nodes", "get_health", "read_knowledge", "record_knowledge",
                "find_processes", "query_logs", "read_queue_entry", "download_server_log",
                "list_inventory", "get_inventory_item", "find_partners", "export_artifacts",
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
    void theKnowledgeToolsFollowGetHealthEvenWithoutAnyReadTool() {
        assertThat(toolNames("V6_1", "V6_1", CapabilityMatrix.production()).subList(0, 4))
            .containsExactly("list_nodes", "get_health", "read_knowledge", "record_knowledge");
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
                "export_artifacts", "export_archives", "find_partners");
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

    // --- feature 021 T031: download_server_log (FR-002, US1-6, SC-002, research D-17) ---------

    @Test
    void aClientHomeWithoutAnyUnsupportedSettingAnnouncesTheDownload() {
        // US1-6: no opt-in needed for the trace log
        assertThat(groupTools("V8_1", cliHome(), false, TraceMatrix.admittingEightOne()))
            .containsExactly("list_nodes", "get_health", "read_knowledge", "record_knowledge",
                "find_processes", "query_logs", "download_server_log", "list_inventory",
                "get_inventory_item", "find_partners", "export_artifacts", "export_archives",
                "check_artifacts");
        assertThat(groupTools("AUTO", cliHome(), false, TraceMatrix.admittingEightOne()))
            .as("an AUTO node may run 8.1").contains("download_server_log");
    }

    @Test
    void theDownloadIsOfferedInReadOnlyModeAlsoOnAProductionGroupWithoutAnyOptIn() {
        // FR-002: independent of write.*, archives.* and the production write opt-ins
        for (String groupLines : List.of(cliHome(), "    production: true\n" + cliHome(),
            "    write:\n      enabled: false\n" + cliHome())) {
            assertThat(groupTools("V8_1", groupLines, false, TraceMatrix.admittingEightOne()))
                .as(groupLines).contains("download_server_log")
                .doesNotContain("restart_process", "kill_process", "import_artifacts");
        }
    }

    @Test
    void withoutAClientHomeAnywhereTheDownloadIsNotAnnounced() {
        assertThat(groupTools("V8_1", "", false, TraceMatrix.admittingEightOne()))
            .doesNotContain("download_server_log", "export_artifacts");
    }

    @Test
    void nodesPinnedToSixOneOnlyDoNotAnnounceTheDownload() {
        assertThat(toolNames("V6_1", "V6_1", TraceMatrix.admittingEightOne()))
            .doesNotContain("download_server_log");
        assertThat(toolNames("V6_1", "V8_1", TraceMatrix.admittingEightOne()))
            .contains("download_server_log");
    }

    @Test
    void onTheProductionMatrixThe81And90RecordingsAnnounceTheDownload() {
        // T033: 8.1 admits the trace log, so an 8.1 and an AUTO node with a client home run it
        List<String> withDownload = List.of("list_nodes", "get_health", "read_knowledge",
            "record_knowledge", "find_processes", "query_logs", "download_server_log",
            "list_inventory", "get_inventory_item", "find_partners", "export_artifacts",
            "export_archives", "check_artifacts");
        assertThat(groupTools("V8_1", cliHome(), false, CapabilityMatrix.production()))
            .containsExactlyElementsOf(withDownload);
        assertThat(groupTools("AUTO", cliHome(), false, CapabilityMatrix.production()))
            .containsExactlyElementsOf(withDownload);
        // T046: 8.1 also admits the web service log, which adds no tool for an opted-in node
        assertThat(groupTools("V8_1", serverLogOptIn("true") + "\n" + cliHome(), false,
            CapabilityMatrix.production())).containsExactlyElementsOf(withDownload);
        // T051: 9.0 admits the trace log, so a node pinned to 9.0 with a 9.0 client runs it
        assertThat(groupTools("V9_0", "", true, CapabilityMatrix.production()))
            .containsExactlyElementsOf(withDownload);
        // T052: 9.0 also admits the web service log, which adds no tool for an opted-in node
        assertThat(groupTools("V9_0", serverLogOptIn("true"), true,
            CapabilityMatrix.production())).containsExactlyElementsOf(withDownload);
    }

    // --- feature 021 T043: the web service branch of download_server_log (FR-002, D-17) ----
    // the test matrix admits no trace log, so only the web service branch can announce the tool

    private static String serverLogOptIn(String value) {
        return "    unsupported:\n      serverLogRead: " + value;
    }

    /**
     * The tools announced for group {@code old} ({@code optedInLine}, {@code serverLogRead: true},
     * a client home only if {@code optedInClient}) next to group {@code lab} (8.1, client home,
     * no opt-in), with {@link WebServiceMatrix#admittingEightOneWithoutTrace()}.
     */
    private List<String> webServiceTools(String optedInLine, boolean optedInClient) {
        String client = optedInClient ? """
                cli:
                  home: %s
            """.formatted(temp.resolve("cli-old")) : "";
        String yaml = """
            profile:
              name: acme
            workspace: %s
            groups:
              - name: old
                versionLine: %s
                unsupported:
                  serverLogRead: true
            %s    nodes:
                  - name: node1
                    baseUrl: https://localhost:1
              - name: lab
                versionLine: V8_1
                cli:
                  home: %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:2
            """.formatted(temp.resolve("workspace"), optedInLine, client,
            temp.resolve("cli-lab"));
        return toolNames(yaml, WebServiceMatrix.admittingEightOneWithoutTrace());
    }

    @Test
    void anOptedInNodeWithAClientHomeAnnouncesTheDownloadForTheWebServiceLog() {
        CapabilityMatrix matrix = WebServiceMatrix.admittingEightOneWithoutTrace();
        assertThat(groupTools("V8_1", serverLogOptIn("true") + "\n" + cliHome(), false, matrix))
            .containsExactly("list_nodes", "get_health", "read_knowledge", "record_knowledge",
                "find_processes", "query_logs", "download_server_log", "list_inventory",
                "get_inventory_item", "find_partners", "export_artifacts", "export_archives",
                "check_artifacts");
        assertThat(groupTools("AUTO", serverLogOptIn("true") + "\n" + cliHome(), false, matrix))
            .as("an AUTO node may run 8.1").contains("download_server_log");
        assertThat(webServiceTools("V8_1", true)).contains("download_server_log");
    }

    @Test
    void withoutTheOptInTheWebServiceLogDoesNotAnnounceTheDownload() {
        CapabilityMatrix matrix = WebServiceMatrix.admittingEightOneWithoutTrace();
        assertThat(groupTools("V8_1", cliHome(), false, matrix))
            .contains("export_artifacts").doesNotContain("download_server_log");
        assertThat(groupTools("V8_1", serverLogOptIn("false") + "\n" + cliHome(), false, matrix))
            .contains("export_artifacts").doesNotContain("download_server_log");
    }

    @Test
    void anOptInOnlyOnAGroupPinnedToSixOneOrWithoutAClientHomeDoesNotAnnounceIt() {
        // lab has a client home on 8.1 but no opt-in
        assertThat(webServiceTools("V6_1", true)).doesNotContain("download_server_log");
        assertThat(webServiceTools("V8_1", false)).doesNotContain("download_server_log");
    }

    // --- feature 024 T023: list_cluster_nodes (FR-002, SC-005, research D-6) -----------------

    /** The T001 reference (SC-005): the tools of a client home on 8.1 or AUTO without opt-in. */
    private static final List<String> REFERENCE = List.of("list_nodes", "get_health",
        "read_knowledge", "record_knowledge", "find_processes", "query_logs",
        "download_server_log", "list_inventory", "get_inventory_item", "find_partners",
        "export_artifacts", "export_archives", "check_artifacts");

    private static String nodeStatusOptIn(String value) {
        return "    unsupported:\n      nodeStatusRead: " + value;
    }

    @Test
    void anOptedInNodeWithAClientHomeAnnouncesListClusterNodes() {
        // TraceMatrix admits every capability on 8.1 but the web service log, the cluster's
        // node list included
        CapabilityMatrix matrix = TraceMatrix.admittingEightOne();
        assertThat(groupTools("V8_1", nodeStatusOptIn("true") + "\n" + cliHome(), false,
            matrix)).containsExactly("list_nodes", "get_health", "read_knowledge",
                "record_knowledge", "find_processes", "query_logs", "download_server_log",
                "list_cluster_nodes", "list_inventory", "get_inventory_item", "find_partners",
                "export_artifacts", "export_archives", "check_artifacts");
        assertThat(groupTools("AUTO", nodeStatusOptIn("true") + "\n" + cliHome(), false,
            matrix)).as("an AUTO node may run 8.1").contains("list_cluster_nodes");
        assertThat(groupTools("V8_1", "    production: true\n    write:\n      enabled: false\n"
            + nodeStatusOptIn("true") + "\n" + cliHome(), false, matrix))
            .as("read-only, independent of write.*").contains("list_cluster_nodes");
    }

    @Test
    void withoutTheOptInOrAClientHomeListClusterNodesIsNotAnnounced() {
        CapabilityMatrix matrix = TraceMatrix.admittingEightOne();
        assertThat(groupTools("V8_1", cliHome(), false, matrix))
            .containsExactlyElementsOf(REFERENCE);
        assertThat(groupTools("V8_1", nodeStatusOptIn("false") + "\n" + cliHome(), false,
            matrix)).containsExactlyElementsOf(REFERENCE);
        assertThat(groupTools("V8_1", nodeStatusOptIn("true"), false, matrix))
            .doesNotContain("list_cluster_nodes", "export_artifacts");
    }

    @Test
    void aNodePinnedToSixOneDoesNotAnnounceListClusterNodes() {
        CapabilityMatrix matrix = TraceMatrix.admittingEightOne();
        assertThat(groupTools("V6_1", nodeStatusOptIn("true") + "\n" + cliHome(), false,
            matrix)).doesNotContain("list_cluster_nodes");
        // the opt-in on the 6.1 group, the 8.1 client home on another group without it
        String yaml = """
            profile:
              name: acme
            workspace: %s
            groups:
              - name: legacy
                versionLine: V6_1
                unsupported:
                  nodeStatusRead: true
                cli:
                  home: %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
              - name: test
                versionLine: V8_1
                cli:
                  home: %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:2
            """.formatted(temp.resolve("workspace"), temp.resolve("cli-legacy"),
            temp.resolve("cli-test"));
        assertThat(toolNames(yaml, matrix)).contains("download_server_log")
            .doesNotContain("list_cluster_nodes");
    }

    @Test
    void onTheProductionMatrixListClusterNodesIsNotAnnouncedBeforeTheFlip() {
        // until T025 the production matrix declares CLUSTER_NODES_READ unsupported on 8.1 and 9.0
        CapabilityMatrix production = CapabilityMatrix.production();
        assertThat(groupTools("V8_1", nodeStatusOptIn("true") + "\n" + cliHome(), false,
            production)).containsExactlyElementsOf(REFERENCE);
        assertThat(groupTools("AUTO", nodeStatusOptIn("true") + "\n" + cliHome(), false,
            production)).containsExactlyElementsOf(REFERENCE);
        assertThat(groupTools("V9_0", nodeStatusOptIn("true"), true, production))
            .containsExactlyElementsOf(REFERENCE);
    }
}
