package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.live.NodeToolsLiveTestSupport.IoProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 024 T067: the offline parts of {@link NodeToolsLiveTest} — the parsing of
 * {@code INUBIT_LIVE_IO_PROCESS} and {@code INUBIT_LIVE_LOG_LEVEL}, the write target (never without
 * the approval, production refused), the opt-in check, the checks of the results, the lease in
 * {@code get_health}, the audit records and the summary lines. A plain unit test (not tagged
 * {@code live}): fake environments, a fake home and literal results; nothing is contacted.
 */
class NodeToolsLiveTestSupportTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final NodeId NODE = NodeId.of("test", "node1");
    private static final String LEASE = "3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01";
    private static final String SET_AUDIT = "8c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f";
    private static final String WARNING =
        "Read through an unsupported, undocumented INUBIT interface; diagnosis only";

    /** {@code test}: write and the log-level opt-in; {@code lab}: no write; {@code prod}. */
    private static final String PROFILE_YAML = """
        profile:
          name: acme
        groups:
          - name: test
            versionLine: V8_1
            write:
              enabled: true
            unsupported:
              logLevelWrite: true
            cli:
              home: %1$s
            nodes:
              - name: node1
                baseUrl: https://acme-test-1.example.test:8443
          - name: lab
            versionLine: V8_1
            unsupported:
              logLevelWrite: true
            cli:
              home: %1$s
            nodes:
              - name: node1
                baseUrl: https://acme-lab-1.example.test:8443
          - name: qa
            versionLine: V8_1
            write:
              enabled: true
            nodes:
              - name: node1
                baseUrl: https://acme-qa-1.example.test:8443
          - name: prod
            production: true
            versionLine: V8_1
            write:
              enabled: true
            unsupported:
              logLevelWrite: true
            cli:
              home: %1$s
            nodes:
              - name: node1
                baseUrl: https://acme-prod-1.example.test:8443
        """;

    @TempDir
    Path home;

    private final Map<String, String> env = new HashMap<>();

    private void profile() throws IOException {
        Path cliHome = Files.createDirectories(home.resolve("inubit-client"));
        Path file = home.resolve(".config/inubit-mcp/acme.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, PROFILE_YAML.formatted(cliHome));
        env.put("INUBIT_MCP_PROFILE", "acme");
    }

    private LiveTarget writeTarget() {
        return NodeToolsLiveTestSupport.writeTarget(env, home, false);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    // --- INUBIT_LIVE_IO_PROCESS ----------------------------------------------------------------

    @Test
    void withoutTheIoProcessTheIoPartIsSkipped() {
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.ioProcess(env))
            .isInstanceOf(TestAbortedException.class)
            .hasMessageContaining("INUBIT_LIVE_IO_PROCESS");
        env.put("INUBIT_LIVE_IO_PROCESS", " ");
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.ioProcess(env))
            .isInstanceOf(TestAbortedException.class);
    }

    @Test
    void theIoProcessIsSplitAtItsLastSlash() {
        env.put("INUBIT_LIVE_IO_PROCESS", "Orders-Inbound/4711");
        assertThat(NodeToolsLiveTestSupport.ioProcess(env))
            .isEqualTo(new IoProcess("Orders-Inbound", "4711"));

        env.put("INUBIT_LIVE_IO_PROCESS", "Sales/Orders Inbound/a1_B-2");
        assertThat(NodeToolsLiveTestSupport.ioProcess(env))
            .isEqualTo(new IoProcess("Sales/Orders Inbound", "a1_B-2"));
    }

    @Test
    void aMalformedIoProcessIsRefusedWithoutEchoingIt() {
        for (String value : List.of("Orders4711", "Orders/", "/4711", "Orders/47 11",
            "Orders@@@Inbound/4711", "Orders###Inbound/4711", "Orders\tInbound/4711",
            "Orders/" + "1".repeat(65), "O".repeat(201) + "/4711")) {
            env.put("INUBIT_LIVE_IO_PROCESS", value);
            assertThatThrownBy(() -> NodeToolsLiveTestSupport.ioProcess(env))
                .as(value)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("INUBIT_LIVE_IO_PROCESS")
                .hasMessageContaining("<workflow>/<processId>")
                .hasMessageNotContaining("Orders");
        }
    }

    // --- INUBIT_LIVE_LOG_LEVEL: the write part never runs without it ---------------------------

    @Test
    void withoutTheApprovalTheWritePartIsSkippedBeforeTheConfigurationIsRead() {
        // no configuration exists at all: a skip proves that nothing was read or contacted
        env.put("INUBIT_LIVE_NODE", "prod/node1");

        assertThatThrownBy(this::writeTarget)
            .isInstanceOf(TestAbortedException.class)
            .hasMessageContaining("INUBIT_LIVE_LOG_LEVEL");
        env.put("INUBIT_LIVE_LOG_LEVEL", " ");
        assertThatThrownBy(this::writeTarget)
            .isInstanceOf(TestAbortedException.class);
    }

    @Test
    void onlyTheExactApprovalRunsTheWritePart() {
        env.put("INUBIT_LIVE_NODE", "test/node1");
        for (String value : List.of("yes", "true", "APPROVED", "approve")) {
            env.put("INUBIT_LIVE_LOG_LEVEL", value);
            assertThatThrownBy(this::writeTarget)
                .as(value)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("INUBIT_LIVE_LOG_LEVEL")
                .hasMessageContaining("approved");
        }
    }

    @Test
    void theWritePartRefusesAProductionNodeEvenWithTheApproval() throws IOException {
        profile();
        env.put("INUBIT_LIVE_LOG_LEVEL", "approved");
        env.put("INUBIT_LIVE_NODE", "prod/node1");

        assertThatThrownBy(this::writeTarget)
            .isInstanceOf(AssertionError.class)
            .hasMessage("Refusing to run live tests against prod/node1: its group is"
                + " production: true (Constitution III)");
    }

    @Test
    void theWritePartNeedsWriteAccessAndTheLogLevelOptIn() throws IOException {
        profile();
        env.put("INUBIT_LIVE_LOG_LEVEL", "approved");

        env.put("INUBIT_LIVE_NODE", "lab/node1");
        assertThatThrownBy(this::writeTarget)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("lab/node1")
            .hasMessageContaining("write.enabled");
        env.put("INUBIT_LIVE_NODE", "qa/node1");
        assertThatThrownBy(this::writeTarget)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("qa/node1")
            .hasMessageContaining("unsupported.logLevelWrite");
        env.put("INUBIT_LIVE_NODE", " test/node1 ");
        assertThat(writeTarget().node()).isEqualTo(NODE);
    }

    // --- the read opt-ins ------------------------------------------------------------------

    @Test
    void aReadWithoutItsOptInFailsNamingTheSetting() throws IOException {
        profile();
        env.put("INUBIT_LIVE_NODE", "test/node1");
        LiveTarget live = LiveTarget.resolve(env, home, false);

        assertThatThrownBy(() -> NodeToolsLiveTestSupport.requireOptIn(live,
            "unsupported.nodeStatusRead", EffectiveNodeConfig::nodeStatusRead))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("test/node1")
            .hasMessageContaining("unsupported.nodeStatusRead: true");
        assertThatCode(() -> NodeToolsLiveTestSupport.requireOptIn(live,
            "unsupported.logLevelWrite", EffectiveNodeConfig::logLevelWrite))
            .doesNotThrowAnyException();
    }

    // --- list_cluster_nodes ----------------------------------------------------------------

    private static final String CLUSTER = """
        {"node":"test/node1","inubitNode":"node1","total":2,"truncated":false,
         "nodes":[{"name":"node1","status":"ACTIVE","version":"8.1.17"},
                  {"name":"node2","status":"UNKNOWN"}],
         "hints":[],"warnings":["%s"]}
        """.formatted(WARNING);

    @Test
    void theNodeListNamesTheAnsweringNodeAmongItsNodes() {
        assertThatCode(() -> NodeToolsLiveTestSupport.assertClusterNodes(json(CLUSTER), NODE))
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertClusterNodes(
            json(CLUSTER.replace("\"inubitNode\":\"node1\"", "\"inubitNode\":\"node9\"")), NODE))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("inubitNode")
            .hasMessageNotContaining("node9");
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertClusterNodes(
            json(CLUSTER.replace(WARNING, "other")), NODE))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("warnings");
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertClusterNodes(json(CLUSTER),
            NodeId.of("test", "node2")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("node");
    }

    @Test
    void theNodeDetailsMustBeAvailableAndMasked() {
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register("s3cr3t-Passw0rd");
        String details = """
            {"node":"test/node1","inubitNode":"node1","total":1,"truncated":false,
             "nodes":[{"name":"node1","status":"ACTIVE"}],
             "details":{"node":"node1","available":true,"truncated":false,
               "pairs":[{"key":"JDKVersion","value":"17.0.9"},{"key":"Url","value":"%s"}]},
             "hints":[],"warnings":[]}
            """;

        assertThatCode(() -> NodeToolsLiveTestSupport.assertDetails(
            json(details.formatted("https://acme.example.test")), scrubber))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertDetails(
            json(details.formatted("jdbc:x?password=s3cr3t-Passw0rd")), scrubber))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("1 of 2")
            .hasMessageNotContaining("s3cr3t");
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertDetails(
            json(details.formatted("x").replace("\"available\":true", "\"available\":false")),
            scrubber))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("available");
    }

    // --- get_node_config -------------------------------------------------------------------

    private static String config(String firstValue) {
        StringBuilder values = new StringBuilder();
        List<String> names = List.of("OperationMode", "ClusterName", "ZoneName", "NodeName",
            "LocalMaintenanceMode", "ActivateClusterManager", "ClusterDebug",
            "ContainerShutdownOnNodeFailure", "RegisterMBeans", "RegisterInternalMBeans",
            "RuntimeDataCacheXMXPercentage", "socketTimeoutInMillisecs", "indexEnabled",
            "iterationCount", "DataSourceLocation", "LogsDBDataSourceLocation",
            "RepositoryDataSourceLocation", "ClusterBindAddress", "noOfRetries", "retryInterval");
        for (int i = 0; i < names.size(); i++) {
            values.append(i == 0 ? "" : ",");
            values.append(i == 0
                ? "{\"name\":\"" + names.get(i) + "\",\"present\":true,\"value\":\"" + firstValue
                    + "\"}"
                : i == 1 ? "{\"name\":\"" + names.get(i) + "\",\"error\":{\"code\":\"INTERNAL\","
                    + "\"message\":\"x\"}}"
                : "{\"name\":\"" + names.get(i) + "\",\"present\":false}");
        }
        return "{\"node\":\"test/node1\",\"inubitNode\":\"node1\",\"values\":[" + values
            + "],\"warnings\":[\"" + WARNING + "\"]}";
    }

    @Test
    void theConfigurationValuesAreAllAllowedNamesInOrderAndMasked() {
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register("s3cr3t-Passw0rd");

        assertThatCode(() -> NodeToolsLiveTestSupport.assertNodeConfig(json(config("CLUSTER")),
            NODE, scrubber)).doesNotThrowAnyException();
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertNodeConfig(
            json(config("s3cr3t-Passw0rd")), NODE, scrubber))
            .isInstanceOf(AssertionError.class)
            .hasMessageNotContaining("s3cr3t");
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertNodeConfig(
            json(config("CLUSTER").replace("\"ZoneName\"", "\"NodeName\"")), NODE, scrubber))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("names");
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertNodeConfig(
            json(config("CLUSTER").replace("\"present\":true", "\"present\":false")), NODE,
            scrubber))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("present");
    }

    @Test
    void theConfigurationSummaryCountsAndNamesNoValue() {
        assertThat(NodeToolsLiveTestSupport.configSummary(NODE, json(config("CLUSTER"))))
            .isEqualTo("[live] get_node_config test/node1: 20 values, 1 present, 18 absent,"
                + " 1 failed");
    }

    // --- read_io_log -----------------------------------------------------------------------

    private static final IoProcess PROCESS = new IoProcess("Orders-Inbound", "4711");
    private static final String REF = "Q2Fh7tZk1oVYbP3x9w-aAA";
    private static final String LISTING = """
        {"node":"test/node1","inubitNode":"node1","workflow":"Orders-Inbound","processId":"4711",
         "total":2,"matching":2,"truncated":false,"hints":[],"warnings":["%s"],
         "entries":[{"ref":"%s","moduleName":"Order Connector","moduleId":"Module-0003",
                     "direction":"INPUT","index":1}]}
        """.formatted(WARNING, REF);
    private static final String MESSAGE = """
        {"node":"test/node1","inubitNode":"node1","workflow":"Orders-Inbound","processId":"4711",
         "total":2,"matching":2,"truncated":false,"hints":[],"warnings":["%s"],
         "entries":[],
         "message":{"ref":"%s","moduleName":"Order Connector","moduleId":"Module-0003",
           "direction":"INPUT","index":1,"kind":"text","charset":"UTF-8","sizeBytes":1834,
           "textChars":1790,"text":"<order><customer>%s</customer></order>","cut":false}}
        """;

    @Test
    void theIoListingIsOfTheRunAndHasAnEntry() {
        assertThatCode(() -> NodeToolsLiveTestSupport.assertIoListing(json(LISTING), NODE,
            PROCESS)).doesNotThrowAnyException();

        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertIoListing(json(LISTING), NODE,
            new IoProcess("Orders-Inbound", "4712")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("processId")
            .hasMessageNotContaining("4712");
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertIoListing(
            json(LISTING.replaceAll("(?s)\"entries\":\\[.*]}", "\"entries\":[]}")
                .replace("\"total\":2", "\"total\":0")), NODE, PROCESS))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("INUBIT_LIVE_IO_PROCESS")
            .hasMessageNotContaining("Orders");
    }

    @Test
    void theIoMessageIsTheRequestedOneAndMasked() {
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register("s3cr3t-Passw0rd");

        assertThatCode(() -> NodeToolsLiveTestSupport.assertIoMessage(
            json(MESSAGE.formatted(WARNING, REF, "acme")), REF, scrubber))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertIoMessage(
            json(MESSAGE.formatted(WARNING, REF, "s3cr3t-Passw0rd")), REF, scrubber))
            .isInstanceOf(AssertionError.class)
            .hasMessageNotContaining("s3cr3t");
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertIoMessage(
            json(MESSAGE.formatted(WARNING, "m0kq3T6cJYb1hX9Z2s_eRw", "acme")), REF, scrubber))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("ref");
    }

    @Test
    void theIoSummaryCountsAndNamesNoWorkflowModuleOrText() {
        String summary = NodeToolsLiveTestSupport.ioSummary(NODE, json(LISTING),
            json(MESSAGE.formatted(WARNING, REF, "acme")));

        assertThat(summary).isEqualTo("[live] read_io_log test/node1: 2 entries, one message:"
            + " text, 1834 bytes");
        assertThat(summary).doesNotContain("Orders", "4711", "Connector", "acme", REF);
    }

    // --- list_cluster_nodes summary --------------------------------------------------------

    @Test
    void theClusterSummaryCountsAndNamesNoNode() {
        assertThat(NodeToolsLiveTestSupport.clusterSummary(NODE, json(CLUSTER),
            Duration.ofMillis(1840), 37))
            .isEqualTo("[live] list_cluster_nodes test/node1: 2 node(s) in 1.8 s, details 37"
                + " pair(s)");
    }

    // --- get_health and the audit records of the write part --------------------------------

    @Test
    void getHealthShowsTheActiveLeaseWithTheOneLoggerAtInfo() {
        String health = """
            {"reports":[{"node":"test/node1","temporaryLogLevels":[
              {"leaseId":"%s","inubitNode":"node1",
               "loggers":[{"name":"%s","level":"%s"}],
               "setAt":"2026-11-12T08:15:31Z","expiresAt":"2026-11-12T08:16:31Z",
               "state":"%s"}]}]}
            """;
        String logger = NodeToolsLiveTestSupport.LOGGER;

        assertThatCode(() -> NodeToolsLiveTestSupport.assertLeaseShown(
            json(health.formatted(LEASE, logger, "INFO", "ACTIVE")), LEASE))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertLeaseShown(
            json(health.formatted(LEASE, logger, "INFO", "RESET_FAILED")), LEASE))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("state");
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertLeaseShown(
            json(health.formatted(LEASE, logger, "DEBUG", "ACTIVE")), LEASE))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("loggers");
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertLeaseShown(
            json("{\"reports\":[{\"node\":\"test/node1\"}]}"), LEASE))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("temporaryLogLevels");
    }

    @Test
    void thePreviewRaisesTheOneLoggerToInfoForOneMinute() {
        String challenge = """
            {"confirmationCode":"Zk3p0Qx8LmN2vB7cR1tY4w","expiresAt":"2026-11-12T08:20:30Z",
             "preview":{"node":"test/node1","inubitNode":"node1",
               "loggers":[{"name":"%s","level":"%s"}],"durationMinutes":%d,"openLease":null}}
            """;
        String logger = NodeToolsLiveTestSupport.LOGGER;

        assertThatCode(() -> NodeToolsLiveTestSupport.assertPreview(
            json(challenge.formatted(logger, "INFO", 1)))).doesNotThrowAnyException();
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertPreview(
            json(challenge.formatted(logger, "DEBUG", 1))))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("preview.loggers")
            .hasMessageNotContaining("com.inubit");
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertPreview(
            json(challenge.formatted(logger, "INFO", 10))))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("preview.durationMinutes");
    }

    @Test
    void theLeaseExpiresOneMinuteAfterTheConfirmedSet() {
        Instant before = Instant.parse("2026-11-12T08:15:30.400Z");
        Instant after = Instant.parse("2026-11-12T08:15:38.900Z");
        String result = "{\"expiresAt\":\"%s\"}";

        for (String inside : List.of("2026-11-12T08:16:29Z", "2026-11-12T08:16:35Z",
            "2026-11-12T08:16:40Z")) {
            assertThatCode(() -> NodeToolsLiveTestSupport.assertExpiresOneMinuteAfterTheSet(
                json(result.formatted(inside)), before, after)).as(inside)
                .doesNotThrowAnyException();
        }
        for (String outside : List.of("2026-11-12T08:16:28Z", "2026-11-12T08:16:41Z",
            "2026-11-12T08:25:35Z")) {
            assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertExpiresOneMinuteAfterTheSet(
                json(result.formatted(outside)), before, after))
                .as(outside)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("expiresAt");
        }
    }

    private static final String SET_PENDING ="{\"auditId\":\"" + SET_AUDIT + "\",\"capability\":"
        + "\"set_temporary_log_level\",\"step\":\"EXECUTE\",\"outcome\":\"PENDING\"}";
    private static final String SET_EXECUTED = "{\"auditId\":\"" + SET_AUDIT + "\",\"capability\":"
        + "\"set_temporary_log_level\",\"step\":\"EXECUTE\",\"outcome\":\"EXECUTED\","
        + "\"details\":{\"leaseId\":\"" + LEASE + "\",\"leaseOpen\":true}}";
    private static final String RESET_PENDING = "{\"auditId\":\"a1\",\"capability\":"
        + "\"reset_temporary_log_level\",\"step\":\"EXECUTE\",\"inputs\":{\"trigger\":\"expiry\","
        + "\"leaseId\":\"" + LEASE + "\"},\"outcome\":\"PENDING\"}";
    private static final String RESET_EXECUTED = RESET_PENDING.replace("PENDING", "EXECUTED");

    @Test
    void theAuditRecordsAreReadFromEveryMonthFile() throws IOException {
        Path audit = Files.createDirectories(home.resolve("audit"));
        Files.writeString(audit.resolve("audit-2026-10.jsonl"), SET_PENDING + "\n");
        Files.writeString(audit.resolve("audit-2026-11.jsonl"),
            SET_EXECUTED + "\n" + RESET_PENDING + "\n");
        Files.writeString(audit.resolve("other.txt"), "not a record\n");

        assertThat(NodeToolsLiveTestSupport.auditRecords(audit))
            .extracting(record -> record.path("outcome").asString())
            .containsExactly("PENDING", "EXECUTED", "PENDING");
    }

    @Test
    void theSetAndTheAutomaticResetAtExpiryAreAudited() {
        List<JsonNode> complete = List.of(json(SET_PENDING), json(SET_EXECUTED),
            json(RESET_PENDING), json(RESET_EXECUTED));

        assertThat(NodeToolsLiveTestSupport.assertAudited(complete, LEASE, SET_AUDIT)).isEqualTo(4);
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertAudited(
            List.of(json(SET_PENDING), json(SET_EXECUTED), json(RESET_PENDING)), LEASE, SET_AUDIT))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("reset_temporary_log_level")
            .hasMessageContaining("expiry");
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertAudited(
            List.of(json(SET_EXECUTED), json(RESET_PENDING), json(RESET_EXECUTED)), LEASE,
            SET_AUDIT))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("set_temporary_log_level")
            .hasMessageContaining("PENDING");
        assertThatThrownBy(() -> NodeToolsLiveTestSupport.assertAudited(complete,
            "00000000-0000-4000-8000-000000000000", SET_AUDIT))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void theWriteSummaryNamesNoLogger() {
        String summary = NodeToolsLiveTestSupport.writeSummary(NODE, Duration.ofSeconds(83), 4);

        assertThat(summary).isEqualTo("[live] set_temporary_log_level test/node1: 1 logger at INFO"
            + " for 1 min; automatic reset after 83 s, lease RESET, 4 audit records");
        assertThat(summary).doesNotContain("com.inubit");
    }
}
