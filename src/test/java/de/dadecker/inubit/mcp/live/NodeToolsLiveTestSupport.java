package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.fail;

import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The offline parts of {@link NodeToolsLiveTest} (feature 024, T067), tested by
 * {@link NodeToolsLiveTestSupportTest}: the variables {@code INUBIT_LIVE_IO_PROCESS} and
 * {@code INUBIT_LIVE_LOG_LEVEL}, the write target, the opt-in check, the checks of the results of
 * the five tools and of {@code get_health}, the audit records of the write part and the summary
 * lines. Failure messages and summary lines name variables, fields, counts and lease ids, never a
 * workflow, module, logger, node detail, configuration value or message text.
 */
final class NodeToolsLiveTestSupport {

    static final String IO_PROCESS_VARIABLE = "INUBIT_LIVE_IO_PROCESS";
    static final String LOG_LEVEL_VARIABLE = "INUBIT_LIVE_LOG_LEVEL";
    /** The only value of {@code INUBIT_LIVE_LOG_LEVEL} that runs the write part. */
    static final String APPROVED = "approved";
    /** The one logger the write part raises to {@code INFO} (the logger of the write spike). */
    static final String LOGGER = "com.inubit.ibis.cli";
    /** The rounding of {@code expiresAt} to whole seconds and the clock's granularity. */
    static final Duration EXPIRY_TOLERANCE = Duration.ofSeconds(2);
    static final String WARNING =
        "Read through an unsupported, undocumented INUBIT interface; diagnosis only";

    /** The {@code workflow} pattern of the {@code read_io_log} input schema. */
    private static final Pattern WORKFLOW =
        Pattern.compile("(?!.*(@@@|###))[^\\u0000-\\u001F\\u007F-\\u009F]{1,200}");
    /** The {@code processId} pattern of the {@code read_io_log} input schema. */
    private static final Pattern PROCESS_ID = Pattern.compile("[0-9A-Za-z_-]{1,64}");
    private static final Set<String> MESSAGE_KINDS = Set.of("text", "binary", "empty", "tooLarge");
    private static final String SET_TOOL = "set_temporary_log_level";
    private static final String RESET_TOOL = "reset_temporary_log_level";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private NodeToolsLiveTestSupport() {
    }

    /** The run of {@code INUBIT_LIVE_IO_PROCESS}, as {@code query_logs} returns it. */
    record IoProcess(String workflow, String processId) {
    }

    /**
     * {@code INUBIT_LIVE_IO_PROCESS=<workflow>/<processId>}, split at the last {@code /} (a
     * workflow name may contain one); skips the I/O part without it. Refused, without echoing the
     * value, unless both parts match the {@code read_io_log} input schema.
     */
    static IoProcess ioProcess(Map<String, String> environment) {
        String value = environment.get(IO_PROCESS_VARIABLE);
        Assumptions.assumeTrue(value != null && !value.isBlank(), IO_PROCESS_VARIABLE + " is not"
            + " set (<workflow>/<processId> of a run with input/output logging); the read_io_log"
            + " part is skipped");
        int slash = value.lastIndexOf('/');
        String workflow = slash < 0 ? "" : value.substring(0, slash);
        String processId = slash < 0 ? "" : value.substring(slash + 1);
        if (!WORKFLOW.matcher(workflow).matches() || !PROCESS_ID.matcher(processId).matches()) {
            fail(IO_PROCESS_VARIABLE + " must be <workflow>/<processId> as query_logs returns"
                + " them: a workflow name of 1 to 200 characters without control characters, @@@"
                + " or ###, and a processId of 1 to 64 letters, digits, _ or -");
        }
        return new IoProcess(workflow, processId);
    }

    /**
     * The target of the write part: skipped unless {@code INUBIT_LIVE_LOG_LEVEL} is set, before
     * anything is read; refused unless it is exactly {@value #APPROVED}; then {@link LiveTarget}
     * (production refused), whose node must have write access and
     * {@code unsupported.logLevelWrite}.
     */
    static LiveTarget writeTarget(Map<String, String> environment, Path home, boolean windows) {
        String value = environment.get(LOG_LEVEL_VARIABLE);
        Assumptions.assumeTrue(value != null && !value.isBlank(), LOG_LEVEL_VARIABLE + " is not"
            + " set (" + APPROVED + ", only after the owner approved this run); the log-level"
            + " write part is skipped");
        if (!value.strip().equals(APPROVED)) {
            fail(LOG_LEVEL_VARIABLE + " must be exactly " + APPROVED + " (only after the owner"
                + " approved this run), or unset to skip the log-level write part");
        }
        LiveTarget live = LiveTarget.resolve(environment, home, windows);
        if (!node(live).effectiveWriteEnabled()) {
            fail(live.node() + ": the log-level write part needs write access: set write.enabled:"
                + " true on its group (docs/live-tests.md)");
        }
        requireOptIn(live, "unsupported.logLevelWrite", EffectiveNodeConfig::logLevelWrite);
        return live;
    }

    /** Fails naming {@code setting} unless the live node's group sets it. */
    static void requireOptIn(LiveTarget live, String setting,
        Predicate<EffectiveNodeConfig> optIn) {
        if (!optIn.test(node(live))) {
            fail(live.node() + ": its group must set " + setting + ": true (docs/live-tests.md)");
        }
    }

    private static EffectiveNodeConfig node(LiveTarget live) {
        return live.loaded().config().effectiveNodes().stream()
            .filter(candidate -> candidate.id().equals(live.node()))
            .findFirst().orElseThrow();
    }

    /** The structured {@code result} of {@code tool} matches its output schema. */
    static void assertMatchesSchema(String tool, JsonNode result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = JSON.readValue(SchemaResources.forClasspath()
            .load("schemas/" + tool + ".output.json"), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> content = JSON.treeToValue(result, Map.class);
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, content);
        if (!validation.valid()) {
            fail(tool + ": the result does not match the output schema: "
                + validation.errorMessage());
        }
    }

    /**
     * A {@code list_cluster_nodes} result names {@code node}, lists at least one node, among them
     * the answering {@code inubitNode}, and carries the unsupported-interface warning.
     */
    static void assertClusterNodes(JsonNode result, NodeId node) {
        if (!result.path("node").asString().equals(node.value())) {
            fail("node: the result names another node than " + node);
        }
        List<String> names = result.path("nodes").valueStream()
            .map(listed -> listed.path("name").asString()).toList();
        if (names.isEmpty() || result.path("total").asInt() < names.size()) {
            fail("nodes: " + names.size() + " listed, total " + result.path("total").asInt());
        }
        if (!names.contains(result.path("inubitNode").asString(""))) {
            fail("inubitNode: the answering node is not among the " + names.size()
                + " listed nodes");
        }
        assertWarned(result);
    }

    /**
     * A {@code list_cluster_nodes} result with {@code nodeName}: the details of the answering node
     * are available, have pairs, and no pair value contains a secret {@code scrubber} knows.
     */
    static void assertDetails(JsonNode result, SecretScrubber scrubber) {
        JsonNode details = result.path("details");
        if (!details.path("available").asBoolean()) {
            fail("details: not available for the answering node");
        }
        if (!details.path("node").asString().equals(result.path("inubitNode").asString())) {
            fail("details.node: the details are not those of the answering node");
        }
        List<String> values = details.path("pairs").valueStream()
            .map(pair -> pair.path("value").asString("")).toList();
        if (values.isEmpty()) {
            fail("details.pairs: none returned");
        }
        assertMasked("details.pairs", values, scrubber);
    }

    /**
     * A {@code get_node_config} result without {@code names}: all allowed names in list order,
     * each present, absent or failed, at least one present, no value with a known secret.
     */
    static void assertNodeConfig(JsonNode result, NodeId node, SecretScrubber scrubber) {
        if (!result.path("node").asString().equals(node.value())) {
            fail("node: the result names another node than " + node);
        }
        List<String> expected = Arrays.stream(NodeConfigName.values())
            .map(NodeConfigName::wireName).toList();
        List<String> names = result.path("values").valueStream()
            .map(value -> value.path("name").asString()).toList();
        if (!names.equals(expected)) {
            fail("values: the names are not the " + expected.size() + " allowed ones in list"
                + " order (" + names.size() + " returned)");
        }
        List<JsonNode> present = result.path("values").valueStream()
            .filter(value -> value.path("present").asBoolean()).toList();
        if (present.isEmpty()) {
            fail("values: not one is present on the node");
        }
        assertMasked("values", present.stream().map(value -> value.path("value").asString(""))
            .toList(), scrubber);
        assertWarned(result);
    }

    /**
     * A {@code read_io_log} listing names {@code node} and {@code process} and has at least one
     * entry.
     */
    static void assertIoListing(JsonNode result, NodeId node, IoProcess process) {
        if (!result.path("node").asString().equals(node.value())) {
            fail("node: the result names another node than " + node);
        }
        if (!result.path("workflow").asString().equals(process.workflow())) {
            fail("workflow: the listing names another workflow than " + IO_PROCESS_VARIABLE);
        }
        if (!result.path("processId").asString().equals(process.processId())) {
            fail("processId: the listing names another process run than " + IO_PROCESS_VARIABLE);
        }
        if (result.path("entries").isEmpty()) {
            fail("entries: no logged message for the run of " + IO_PROCESS_VARIABLE + " (total "
                + result.path("total").asInt() + "); choose a run whose process and modules have"
                + " input/output logging switched on");
        }
        assertWarned(result);
    }

    /**
     * A {@code read_io_log} result with {@code message}: the message is {@code ref}, of a known
     * kind, and its text contains no secret {@code scrubber} knows.
     */
    static void assertIoMessage(JsonNode result, String ref, SecretScrubber scrubber) {
        JsonNode message = result.path("message");
        if (!message.path("ref").asString().equals(ref)) {
            fail("message.ref: another message than the requested ref");
        }
        if (!MESSAGE_KINDS.contains(message.path("kind").asString())) {
            fail("message.kind: not one of " + MESSAGE_KINDS);
        }
        assertMasked("message.text", List.of(message.path("text").asString("")), scrubber);
    }

    /**
     * The first report of a {@code get_health} result shows lease {@code leaseId} as
     * {@code ACTIVE} with {@link #LOGGER} at {@code INFO} only.
     */
    static void assertLeaseShown(JsonNode health, String leaseId) {
        JsonNode lease = health.path("reports").path(0).path("temporaryLogLevels").valueStream()
            .filter(shown -> shown.path("leaseId").asString().equals(leaseId))
            .findFirst()
            .orElseGet(() -> fail("temporaryLogLevels: get_health shows no lease " + leaseId));
        if (!lease.path("state").asString().equals("ACTIVE")) {
            fail("state: lease " + leaseId + " is " + lease.path("state").asString()
                + ", expected ACTIVE");
        }
        if (!oneLoggerAtInfo(lease.path("loggers"))) {
            fail("loggers: lease " + leaseId + " does not hold exactly the one logger at INFO");
        }
    }

    /**
     * The {@code preview} of a {@code set_temporary_log_level} challenge raises exactly
     * {@link #LOGGER} to {@code INFO} for 1 minute.
     */
    static void assertPreview(JsonNode challenge) {
        JsonNode preview = challenge.path("preview");
        if (!oneLoggerAtInfo(preview.path("loggers"))) {
            fail("preview.loggers: not exactly the one logger at INFO");
        }
        if (preview.path("durationMinutes").asInt() != 1) {
            fail("preview.durationMinutes: " + preview.path("durationMinutes").asInt()
                + ", expected 1");
        }
    }

    /**
     * The {@code expiresAt} of a {@code set_temporary_log_level} result is 1 minute after the
     * executed change, which happened between {@code before} and {@code after} (the confirmed
     * call); {@link #EXPIRY_TOLERANCE} on either side.
     */
    static void assertExpiresOneMinuteAfterTheSet(JsonNode result, Instant before,
        Instant after) {
        Instant expiresAt = Instant.parse(result.path("expiresAt").asString());
        Instant earliest = before.plus(Duration.ofMinutes(1)).minus(EXPIRY_TOLERANCE);
        Instant latest = after.plus(Duration.ofMinutes(1)).plus(EXPIRY_TOLERANCE);
        if (expiresAt.isBefore(earliest) || expiresAt.isAfter(latest)) {
            fail("expiresAt: " + expiresAt + " is not 1 minute after the set (between "
                + earliest + " and " + latest + ")");
        }
    }

    private static boolean oneLoggerAtInfo(JsonNode loggers) {
        return loggers.size() == 1 && loggers.path(0).path("name").asString().equals(LOGGER)
            && loggers.path(0).path("level").asString().equals("INFO");
    }

    /** The records of every {@code audit-*.jsonl} file in {@code auditDirectory}, file by file. */
    static List<JsonNode> auditRecords(Path auditDirectory) throws IOException {
        List<Path> files;
        try (Stream<Path> listed = Files.list(auditDirectory)) {
            files = listed.filter(file -> {
                String name = file.getFileName().toString();
                return name.startsWith("audit-") && name.endsWith(".jsonl");
            }).sorted().toList();
        }
        List<JsonNode> records = new ArrayList<>();
        for (Path file : files) {
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    records.add(JSON.readTree(line));
                }
            }
        }
        return records;
    }

    /**
     * The audit holds a {@code PENDING} and an {@code EXECUTED} record of the set call
     * {@code setAuditId} for lease {@code leaseId}, and a {@code PENDING} and an {@code EXECUTED}
     * record of the automatic reset of that lease at its expiry.
     *
     * @return the number of these records
     */
    static int assertAudited(List<JsonNode> records, String leaseId, String setAuditId) {
        List<JsonNode> set = records.stream()
            .filter(record -> record.path("capability").asString().equals(SET_TOOL)
                && record.path("auditId").asString().equals(setAuditId)
                && record.path("step").asString().equals("EXECUTE"))
            .toList();
        List<String> setOutcomes = outcomes(set);
        if (!setOutcomes.contains("PENDING") || !setOutcomes.contains("EXECUTED")) {
            fail(SET_TOOL + ": expected a PENDING and an EXECUTED record of audit " + setAuditId
                + ", found " + setOutcomes);
        }
        if (set.stream().filter(record -> record.path("outcome").asString().equals("EXECUTED"))
            .anyMatch(record -> !record.path("details").path("leaseId").asString()
                .equals(leaseId))) {
            fail(SET_TOOL + ": the EXECUTED record of audit " + setAuditId + " names another"
                + " lease than " + leaseId);
        }
        List<JsonNode> reset = records.stream()
            .filter(record -> record.path("capability").asString().equals(RESET_TOOL)
                && record.path("inputs").path("trigger").asString().equals("expiry")
                && record.path("inputs").path("leaseId").asString().equals(leaseId))
            .toList();
        List<String> resetOutcomes = outcomes(reset);
        if (!resetOutcomes.contains("PENDING") || !resetOutcomes.contains("EXECUTED")) {
            fail(RESET_TOOL + " (trigger expiry): expected a PENDING and an EXECUTED record for"
                + " lease " + leaseId + ", found " + resetOutcomes);
        }
        return set.size() + reset.size();
    }

    private static List<String> outcomes(List<JsonNode> records) {
        return records.stream().map(record -> record.path("outcome").asString()).toList();
    }

    /** {@code [live] list_cluster_nodes <node>: <n> node(s) in <s> s, details <k> pair(s)}. */
    static String clusterSummary(NodeId node, JsonNode result, Duration elapsed, int pairs) {
        return "[live] list_cluster_nodes " + node + ": " + result.path("total").asInt()
            + " node(s) in " + String.format(Locale.ROOT, "%.1f", elapsed.toMillis() / 1000.0)
            + " s, details " + pairs + " pair(s)";
    }

    /** {@code [live] get_node_config <node>: <n> values, <p> present, <a> absent, <f> failed}. */
    static String configSummary(NodeId node, JsonNode result) {
        List<JsonNode> values = result.path("values").valueStream().toList();
        long failed = values.stream().filter(value -> value.has("error")).count();
        long present = values.stream().filter(value -> value.path("present").asBoolean()).count();
        return "[live] get_node_config " + node + ": " + values.size() + " values, " + present
            + " present, " + (values.size() - present - failed) + " absent, " + failed
            + " failed";
    }

    /** {@code [live] read_io_log <node>: <n> entries, one message: <kind>, <b> bytes}. */
    static String ioSummary(NodeId node, JsonNode listing, JsonNode read) {
        JsonNode message = read.path("message");
        return "[live] read_io_log " + node + ": " + listing.path("total").asInt()
            + " entries, one message: " + message.path("kind").asString() + ", "
            + message.path("sizeBytes").asLong() + " bytes";
    }

    /** The summary of the write part; the logger is not named. */
    static String writeSummary(NodeId node, Duration untilReset, int auditRecords) {
        return "[live] " + SET_TOOL + " " + node + ": 1 logger at INFO for 1 min; automatic reset"
            + " after " + untilReset.toSeconds() + " s, lease RESET, " + auditRecords
            + " audit records";
    }

    private static void assertWarned(JsonNode result) {
        if (result.path("warnings").valueStream()
            .noneMatch(warning -> warning.asString().equals(WARNING))) {
            fail("warnings: the unsupported-interface warning is missing");
        }
    }

    /** Fails, counting, if any of {@code values} contains a secret {@code scrubber} knows. */
    private static void assertMasked(String field, List<String> values, SecretScrubber scrubber) {
        long unmasked = values.stream().filter(scrubber::containsSecret).count();
        if (unmasked > 0) {
            fail(field + ": " + unmasked + " of " + values.size() + " values contain a known"
                + " secret");
        }
    }
}
