package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.tools.GetNodeConfigTool;
import de.dadecker.inubit.mcp.mcp.tools.ListClusterNodesTool;
import de.dadecker.inubit.mcp.mcp.tools.ReadIoLogTool;
import de.dadecker.inubit.mcp.mcp.tools.ResetTemporaryLogLevelTool;
import de.dadecker.inubit.mcp.mcp.tools.SetTemporaryLogLevelTool;
import de.dadecker.inubit.mcp.testing.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T070 (feature 024, FR-003, FR-042, FR-043, D-31): docs/setup.md documents the four node-tool
 * opt-ins {@code unsupported.nodeStatusRead}, {@code ioLogRead}, {@code nodeConfigRead} and
 * {@code logLevelWrite} in the subsection "Unsupported functions" with the table rows, the YAML
 * example and the text of contracts/configuration-delta.md after the {@code serverLogRead} text
 * (with the cleanup-reset exception and the precondition to address one node directly); the
 * validation texts; section 6 shows the group lines with the new parts, the production warnings,
 * the warning without write access and the {@code Temporary log levels:} section.
 *
 * <p>T071 (FR-003, D-25, D-31, contracts/mcp-tools-delta.md): docs/tools.md has the sections of
 * the five node tools, each starting with the description as announced and a link to setup
 * "Unsupported functions", with the short forms of D-25 in description and section; every input
 * and output field; limits, variants, masking, errors, leases and automatic resets; the documented
 * 8.1/9.0 states equal {@link CapabilityMatrix#production()} (a flip forces a docs update); the
 * listed configuration names equal {@link NodeConfigName}; the tool table, capability rows,
 * announcement rules, {@code list_nodes} names and {@code get_health} {@code temporaryLogLevels}.
 *
 * <p>T072 (FR-003, D-31): README.md lists the five tools and when they are offered; SECURITY.md
 * names the business and personal data of I/O messages and the masking limits, the owner-only
 * lease store, the cleanup resets while write access is off and the answering-node lookup through
 * an unsupported interface.
 */
class NodeToolsDocsTest {

    private static final Path SETUP = Path.of("docs/setup.md");
    private static final Path TOOLS = Path.of("docs/tools.md");
    private static final Path SCHEMAS = Path.of("src/main/resources/schemas");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path HOME = Platform.absolutePath("/home/test");
    private static final Path SOURCE = HOME.resolve(".config/inubit-mcp/acme.yaml");
    private static final Pattern YAML_BLOCK = Pattern.compile("```yaml\n(.*?)```", Pattern.DOTALL);
    private static final Pattern TEXT_BLOCK = Pattern.compile("```text\n(.*?)```", Pattern.DOTALL);
    private static final String READ_OPENING = "**`unsupported.nodeStatusRead`,"
        + " `unsupported.ioLogRead` and `unsupported.nodeConfigRead` use unsupported INUBIT"
        + " functionality.**";
    private static final String WRITE_OPENING =
        "**`unsupported.logLevelWrite` changes a running INUBIT node.**";

    /** The text with line breaks folded into single spaces. */
    private static String folded(String text) {
        return text.replaceAll("\\s+", " ");
    }

    /** The raw part of docs/setup.md from {@code heading} up to the next level-2/3 heading. */
    private static String section(String heading) throws IOException {
        String text = Files.readString(SETUP);
        assertThat(text).contains("\n" + heading + "\n");
        String rest = text.substring(text.indexOf("\n" + heading + "\n") + heading.length() + 2);
        Matcher next = Pattern.compile("(?m)^#{2,3} ").matcher(rest);
        return next.find() ? rest.substring(0, next.start()) : rest;
    }

    private static String block(Pattern kind, String text, String containing) {
        Matcher blocks = kind.matcher(text);
        while (blocks.find()) {
            if (blocks.group(1).contains(containing)) {
                return blocks.group(1);
            }
        }
        throw new AssertionError("no block containing " + containing);
    }

    @Test
    void setupHasTheTableRowsOfTheFourKeys() throws IOException {
        String unsupported = folded(section("### Unsupported functions"));

        assertThat(unsupported).contains(
            "| `unsupported.nodeStatusRead` | group | `false` | offers `list_cluster_nodes` for the"
                + " nodes of this group (unsupported interface) |",
            "| `unsupported.ioLogRead` | group | `false` | offers `read_io_log` (unsupported"
                + " interface; messages can contain business and personal data) |",
            "| `unsupported.nodeConfigRead` | group | `false` | offers `get_node_config`"
                + " (unsupported interface; fixed list of values) |",
            "| `unsupported.logLevelWrite` | group | `false` | offers `set_temporary_log_level` and"
                + " `reset_temporary_log_level` for nodes of this group **with write access**"
                + " (`write.enabled`, and `write.productionOptIn` on production); the"
                + " answering-node lookup is unsupported |",
            "All are independent of each other and of `unsupported.queueEntryRead` /"
                + " `serverLogRead`.");
    }

    @Test
    void theReadTextFollowsTheServerLogReadTextWithItsBoldSentences() throws IOException {
        String unsupported = folded(section("### Unsupported functions"));
        int serverLogRead = unsupported.indexOf(
            "**`unsupported.serverLogRead` uses unsupported INUBIT functionality.**");
        assertThat(serverLogRead).isNotNegative();
        assertThat(unsupported.indexOf(READ_OPENING)).isGreaterThan(serverLogRead);

        String text = unsupported.substring(unsupported.indexOf(READ_OPENING),
            unsupported.indexOf(WRITE_OPENING));
        assertThat(text).contains(
            "([`list_cluster_nodes`](tools.md#list_cluster_nodes),"
                + " [`read_io_log`](tools.md#read_io_log),"
                + " [`get_node_config`](tools.md#get_node_config))",
            "**No vendor support, no stability guarantee.**",
            "after **any** INUBIT update, including patch releases.",
            "**Diagnosis only.**",
            "**INUBIT checks no right beyond the login** for `list_cluster_nodes` and"
                + " `get_node_config`: every INUBIT user the profile logs in with can read them."
                + " `read_io_log` is the exception: INUBIT requires a right of the configured user"
                + " for the listing and refuses it without that right (`FORBIDDEN`).",
            "**`read_io_log`: business and personal data.**",
            "but masking does not cover business and personal data.",
            "**`get_node_config`: fixed list.** Only the 20 listed values can be read;",
            "**Cluster nodes.** Each tool reads from the node the configured address reaches and"
                + " names it; address cluster nodes directly, not through a load balancer.");
    }

    @Test
    void theWriteTextStatesTheResetTheLeasesTheCleanupExceptionAndTheNodePrecondition()
        throws IOException {
        String unsupported = folded(section("### Unsupported functions"));
        assertThat(unsupported.indexOf(WRITE_OPENING))
            .isGreaterThan(unsupported.indexOf(READ_OPENING));

        String text = unsupported.substring(unsupported.indexOf(WRITE_OPENING));
        assertThat(text).contains(
            "StartCLI's documented `trace --nodelevel`, for a limited time;"
                + " `reset_temporary_log_level` runs `trace --reset`.",
            "they need `write.enabled` (and `write.productionOptIn` on production) and follow the"
                + " node's confirmation mode.",
            "**The reset restores every temporary log level on that node**, also levels other"
                + " users set.",
            "The persistent trace settings are never changed.",
            "**Automatic reset.** Every change is recorded as a lease in"
                + " `~/.inubit-mcp/<profile>/log-levels/` (owner-only).",
            "**Cleanup resets run also when write access is off.** If `write.enabled`,"
                + " `write.productionOptIn` or `unsupported.logLevelWrite` is removed while a lease"
                + " is open, the tools are no longer offered, but the MCP server still resets the"
                + " recorded nodes (a deliberate exception to the read-only default, decided so"
                + " that a raised level never stays without limit).",
            "`reset_temporary_log_level` with `leaseId` and `forget: true` ends it without"
                + " contacting INUBIT.",
            "**Address the node directly.** The MCP server asks which INUBIT node answers before"
                + " every change and reset and refuses when another node answers than the open"
                + " lease names; behind a load balancer the lookup and the change can still reach"
                + " different nodes.",
            "Every set, reset and automatic reset is written to the audit log.",
            "on a group with `production: true` the server starts with a warning.");
    }

    @Test
    void theClientAndTimeoutsAreNamedWithTheirBuiltInValues() throws IOException {
        String text = folded(section("### Unsupported functions"));
        text = text.substring(text.indexOf(WRITE_OPENING));

        assertThat(text).contains("`cliTimeout` (built-in "
            + Defaults.BUILTIN_CLI_TIMEOUT.toSeconds() + " s) for the node list, the configuration"
            + " values, the I/O listing and each log-level StartCLI run; `cliExportTimeout`"
            + " (built-in " + Defaults.BUILTIN_CLI_EXPORT_TIMEOUT.toSeconds() + " s) for reading"
            + " one I/O message.");
    }

    @Test
    void theValidationTextsAreTheOnesTheLoaderProduces() throws IOException {
        String text = folded(section("### Unsupported functions"));

        assertThat(text).contains(
            "any key under `unsupported` other than `queueEntryRead`, `serverLogRead`,"
                + " `nodeStatusRead`, `ioLogRead`, `nodeConfigRead` and `logLevelWrite`",
            "`Invalid value at groups[0].unsupported.ioLogRead in <source>: expected true or"
                + " false`",
            "`Unknown key 'gcRun' at groups[0].unsupported in <source>`",
            "`Unknown key 'unsupported' at groups[0].nodes[0] in <source>`");
    }

    @Test
    void theSetupExampleLoadsAndOptsInItsGroupWithWriteAccess() throws IOException {
        String example = block(YAML_BLOCK, section("### Unsupported functions"),
            "logLevelWrite: true");

        ProfileConfig config = new ConfigLoader(Map.of(), HOME, false)
            .parse("profile:\n  name: acme\n" + example, SOURCE).config();

        assertThat(config.resolvableNodes()).singleElement().satisfies(node -> {
            assertThat(node.id()).isEqualTo(NodeId.of("test", "node1"));
            for (String key : NodeToolOptIns.KEYS) {
                assertThat(NodeToolOptIns.of(node, key)).as(key).isTrue();
            }
            assertThat(node.effectiveWriteEnabled()).isTrue();
            assertThat(node.queueEntryRead()).isFalse();
            assertThat(node.serverLogRead()).isFalse();
        });
    }

    @Test
    void sectionSixShowsTheGroupLinesAndTheWarningsAsConfigValidatorProducesThem()
        throws IOException {
        String check = section("## 6. Check the configuration");

        assertThat(block(TEXT_BLOCK, check, "nodeStatusRead: enabled (unsupported)")).isEqualTo(
            "Group test: archives: allowed, nodeStatusRead: enabled (unsupported),"
                + " logLevelWrite: enabled (unsupported)\n"
                + "Group prod (production): archives: refused (no archives.productionOptIn),"
                + " nodeStatusRead: off, logLevelWrite: off\n");
        assertThat(folded(check)).contains(
            "`queueEntryRead`, `serverLogRead`, `nodeStatusRead`, `ioLogRead`, `nodeConfigRead`,"
                + " `logLevelWrite`",
            "`unsupported.nodeStatusRead is enabled on production group prod: the cluster's node"
                + " list is read through an undocumented INUBIT interface without vendor support`",
            "`unsupported.ioLogRead is enabled on production group prod: module input and output"
                + " messages are read through an undocumented INUBIT interface without vendor"
                + " support; they can contain business and personal data`",
            "`unsupported.nodeConfigRead is enabled on production group prod: node configuration"
                + " values are read through an undocumented INUBIT interface without vendor"
                + " support`",
            "`unsupported.logLevelWrite is enabled on production group prod: temporary log levels"
                + " of production nodes can be changed; the answering node is looked up through an"
                + " undocumented INUBIT interface`",
            "`unsupported.logLevelWrite has no effect on group test: write access is off (cleanup"
                + " resets of recorded leases still run)`");
    }

    @Test
    void sectionSixShowsTheTemporaryLogLevelsAndTheUnreadableStoreWarning() throws IOException {
        String check = section("## 6. Check the configuration");

        assertThat(block(TEXT_BLOCK, check, "Temporary log levels:")).isEqualTo(
            "Temporary log levels:\n"
                + "  test/node1 (INUBIT node node1): 1 logger(s), until 2026-11-12T08:25:31Z,"
                + " ACTIVE\n"
                + "  legacy/node2 (INUBIT node node2): 2 logger(s), until 2026-11-10T09:00:00Z,"
                + " RESET_FAILED (server no longer configured; forgotten automatically on"
                + " 2026-11-17)\n");
        assertThat(folded(check)).contains("`--check-config` only reads",
            "`The temporary log level leases in <path> cannot be read (<reason>); log-level"
                + " changes are refused until the file is fixed or moved`");
    }

    // ---- docs/tools.md (T071) -------------------------------------------------------------

    private static final List<ToolHandler> NODE_TOOLS = List.of(
        new ListClusterNodesTool(request -> null), new ReadIoLogTool(request -> null),
        new GetNodeConfigTool(request -> null), new SetTemporaryLogLevelTool(request -> null),
        new ResetTemporaryLogLevelTool(request -> null));
    private static final Map<String, Capability> CAPABILITY = Map.of(
        "list_cluster_nodes", Capability.CLUSTER_NODES_READ,
        "read_io_log", Capability.IO_LOG_READ,
        "get_node_config", Capability.NODE_CONFIG_READ,
        "set_temporary_log_level", Capability.LOG_LEVEL_WRITE,
        "reset_temporary_log_level", Capability.LOG_LEVEL_WRITE);
    private static final Map<Capability, String> LIST_NODES_NAME = Map.of(
        Capability.CLUSTER_NODES_READ, "cluster_nodes_read",
        Capability.IO_LOG_READ, "io_log_read",
        Capability.NODE_CONFIG_READ, "node_config_read",
        Capability.LOG_LEVEL_WRITE, "log_level_write");
    private static final Map<String, String> VERSION_OF_LINE = Map.of(
        "8.1", "8.1.17", "9.0", "9.0.10");

    /** docs/tools.md with quote markers and line breaks folded into single spaces. */
    private static String tools() throws IOException {
        return Files.readString(TOOLS).replaceAll("\\n> ?", " ").replaceAll("\\s+", " ");
    }

    /** The folded section {@code ## `<tool>`} of docs/tools.md, without its heading. */
    private static String toolSection(String tool) throws IOException {
        String tools = tools();
        String heading = "## `" + tool + "`";
        assertThat(tools).contains(" " + heading + " ");
        String rest = tools.substring(tools.indexOf(" " + heading + " ") + heading.length() + 1);
        int end = rest.indexOf(" ## ");
        return end < 0 ? rest : rest.substring(0, end);
    }

    /** The property names of {@code schema}, also inside items, variants and {@code $defs}. */
    private static List<String> fields(JsonNode schema) {
        List<String> names = new ArrayList<>();
        if (!schema.isObject()) {
            return names;
        }
        JsonNode properties = schema.path("properties");
        properties.propertyNames().forEach(name -> {
            names.add(name);
            names.addAll(fields(properties.get(name)));
        });
        names.addAll(fields(schema.path("items")));
        schema.path("oneOf").forEach(variant -> names.addAll(fields(variant)));
        schema.path("anyOf").forEach(variant -> names.addAll(fields(variant)));
        JsonNode defs = schema.path("$defs");
        defs.propertyNames().forEach(name -> names.addAll(fields(defs.get(name))));
        return names;
    }

    /** The sentence a section states for {@code capability} on {@code line} in the matrix. */
    private static String stateSentence(Capability capability, String line) {
        CapabilityMatrix matrix = CapabilityMatrix.production();
        Optional<InubitVersion> version = InubitVersion.parse(VERSION_OF_LINE.get(line));
        String name = "`" + LIST_NODES_NAME.get(capability) + "`";
        if (matrix.state(version, capability) == SupportState.SUPPORTED) {
            return name + " is supported on INUBIT " + line;
        }
        assertThat(matrix.note(version, capability)).as(capability + " on " + line)
            .contains("not yet verified");
        return name + " is not yet verified for INUBIT " + line;
    }

    /** The 8.1/9.0 cell of the capability table for {@code capability}. */
    private static String stateCell(Capability capability) {
        CapabilityMatrix matrix = CapabilityMatrix.production();
        List<String> states = new ArrayList<>();
        for (String line : List.of("8.1", "9.0")) {
            states.add(matrix.state(InubitVersion.parse(VERSION_OF_LINE.get(line)), capability)
                == SupportState.SUPPORTED ? "supported" : "refused (not yet verified)");
        }
        return states.equals(List.of("supported", "supported")) ? "supported (8.1 and 9.0)"
            : "8.1: " + states.get(0) + "; 9.0: " + states.get(1);
    }

    @Test
    void toolsStartsEachSectionWithTheDescriptionAsAnnouncedAndTheShortForms() throws IOException {
        String raw = Files.readString(TOOLS);
        for (ToolHandler tool : NODE_TOOLS) {
            String description = tool.descriptionText();
            String section = toolSection(tool.name());

            assertThat(raw).as(tool.name()).contains("\n## `" + tool.name() + "`\n\n> [acme] ");
            assertThat(section).as(tool.name())
                .startsWith(" [acme] " + Terminology.DEFAULT.render(description) + " ")
                .contains("[setup.md, Unsupported functions](setup.md#unsupported-functions)");
            List<String> shortForms = new ArrayList<>(List.of("unsupported", "undocumented",
                "without vendor support", "diagnosis only", "any inubit update"));
            if (tool.name().equals("read_io_log")) {
                shortForms.addAll(List.of("business and personal data",
                    "a right of the configured user"));
            }
            if (tool.name().endsWith("_temporary_log_level")) {
                shortForms.add("every temporary log level on that node, also those other users"
                    + " set");
            }
            for (String text : List.of(description, section)) {
                assertThat(text.toLowerCase(Locale.ROOT)).as(tool.name())
                    .contains(shortForms.toArray(String[]::new));
            }
        }
    }

    @Test
    void toolsNamesEveryInputAndOutputField() throws IOException {
        for (ToolHandler tool : NODE_TOOLS) {
            List<String> names = new ArrayList<>(fields(
                JSON.readTree(SCHEMAS.resolve(tool.name() + ".input.json").toFile())));
            names.addAll(fields(
                JSON.readTree(SCHEMAS.resolve(tool.name() + ".output.json").toFile())));
            assertThat(names).as("the scan finds the fields of " + tool.name())
                .contains("inubitNode", "warnings");

            String section = toolSection(tool.name());
            assertThat(names).allSatisfy(name -> assertThat(section)
                .as(tool.name() + ": " + name).contains("`" + name + "`"));
        }
    }

    @Test
    void toolsStatesTheProductionMatrixStateOfEveryNodeTool() throws IOException {
        String tools = tools();
        for (Map.Entry<String, Capability> tool : CAPABILITY.entrySet()) {
            String section = toolSection(tool.getKey());
            for (String line : List.of("8.1", "9.0")) {
                String stated = stateSentence(tool.getValue(), line);
                assertThat(section).as(tool.getKey() + " on " + line).contains(stated);
                String name = "`" + LIST_NODES_NAME.get(tool.getValue()) + "`";
                assertThat(section).as("no contrary statement for " + line).doesNotContain(
                    stated.endsWith("verified for INUBIT " + line)
                        ? name + " is supported on INUBIT " + line
                        : name + " is not yet verified for INUBIT " + line);
            }
            assertThat(tools).as("capability row of " + tool.getKey()).containsPattern(
                "\\| `" + LIST_NODES_NAME.get(tool.getValue()) + "` \\|[^|]+\\|[^|]+\\| "
                    + Pattern.quote(stateCell(tool.getValue())) + " \\|[^|]+\\| refused \\|");
            String notOffered = "As long as `" + LIST_NODES_NAME.get(tool.getValue())
                + "` is supported on no INUBIT version, `" + tool.getKey() + "` is not offered";
            if (CapabilityMatrix.production().possiblySupported(tool.getValue())) {
                assertThat(section).doesNotContain("is supported on no INUBIT version");
            } else {
                assertThat(section).contains(notOffered);
            }
        }
    }

    @Test
    void toolsListsExactlyTheTwentyConfigurationNames() throws IOException {
        String section = toolSection("get_node_config");
        String marker = "**The fixed list** (in this order):";
        assertThat(section).contains(marker);
        String list = section.substring(section.indexOf(marker) + marker.length());
        list = list.substring(0, list.indexOf(". "));
        List<String> listed = new ArrayList<>();
        Matcher names = Pattern.compile("`([A-Za-z]+)`").matcher(list);
        while (names.find()) {
            listed.add(names.group(1));
        }

        assertThat(listed).containsExactlyElementsOf(Arrays.stream(NodeConfigName.values())
            .map(NodeConfigName::wireName).toList());
    }

    @Test
    void toolsStatesTheReadToolsLimitsVariantsMaskingAndErrors() throws IOException {
        assertThat(toolSection("list_cluster_nodes")).contains(
            "`readOnlyHint: true`", "`openWorldHint: true`",
            "`nodeName` 1–128 characters of `A-Z`, `a-z`, `0-9`, `_`, `.`, `:` and `-`",
            "at most 200 nodes", "at most 500 pairs", "2,000 characters", "256 KiB",
            "`ACTIVE`, `MAINTENANCE`, `OFFLINE`, `UNKNOWN`",
            "Only part of the node details was returned",
            "The node details contain internal addresses and file paths of the INUBIT"
                + " installation",
            "the known credentials of every configured node",
            "**INUBIT checks no right beyond the login**", "address cluster nodes directly",
            "| `INVALID_INPUT` |", "| `TARGET_UNKNOWN` |", "| `NOT_CONFIGURED` |",
            "| `UNSUPPORTED_VERSION` |", "| `CLI_UNAVAILABLE` |",
            "| `AUTH_FAILED`, `TLS_ERROR`, `UNREACHABLE`, `FORBIDDEN` |", "| `TIMEOUT` |",
            "| `MAINTENANCE_MODE`, credential codes |", "| `UNEXPECTED_RESPONSE` |",
            "| `INTERNAL` |",
            "`list_cluster_nodes is not enabled for group test: unsupported.nodeStatusRead is"
                + " not set`",
            "**Example prompt**", "list_cluster_nodes(target:");
        assertThat(toolSection("read_io_log")).contains(
            "`readOnlyHint: true`", "`openWorldHint: true`",
            "`workflow` 1–200 characters", "`@@@`", "`###`",
            "`processId` 1–64 characters", "`module` 1–200 characters",
            "`maxChars` 1–100,000, default 20,000", "at most 500 entries",
            "`text`", "`binary`", "`empty`", "`tooLarge`", "limit 10 MiB",
            "No logged message for this run: input/output logging may be off for the process or"
                + " its modules, the messages may already be removed, or the run may have"
                + " executed on another cluster node",
            "Some entries have an unexpected form; they are listed with their raw module part",
            "The message is empty or no longer available", "Binary content is not returned",
            "masking does not cover business and personal data",
            "`cliTimeout`", "`cliExportTimeout`",
            "`test/node1 refused the input/output logging for the configured user`",
            "| `INVALID_INPUT` |", "| `FORBIDDEN` |", "| `TIMEOUT` |",
            "| `UNEXPECTED_RESPONSE` |", "As [`list_cluster_nodes`](#list_cluster_nodes)",
            "**Example prompt**", "read_io_log(target:");
        assertThat(toolSection("get_node_config")).contains(
            "`readOnlyHint: true`", "`openWorldHint: true`",
            "`present: false`", "not present on this node", "`error`",
            "2,000 characters", "Any other name is refused", "nothing is sent",
            "never changes a value", "As [`list_cluster_nodes`](#list_cluster_nodes)",
            "| `INVALID_INPUT` |", "**Example prompt**", "get_node_config(target:");
    }

    @Test
    void toolsStatesTheLogLevelRulesLeasesAndAutomaticResets() throws IOException {
        for (String tool : List.of("set_temporary_log_level", "reset_temporary_log_level")) {
            assertThat(toolSection(tool)).as(tool).contains(
                "`destructiveHint: true`", "`idempotentHint: false`",
                "the reset restores every temporary level on that node, also other users'",
                "[Leases and automatic resets](#leases-and-automatic-resets)",
                "| `PRODUCTION_PROTECTED`, `WRITE_DISABLED` |", "| `NOT_CONFIGURED` |",
                "| `PRECONDITION_FAILED` |", "| `CONFIRMATION_INVALID` |",
                "**Example prompt**", tool + "(");
        }
        assertThat(toolSection("set_temporary_log_level")).contains(
            "only the exactly named logger changes",
            "`trace --nodelevel <level> --package '<logger>'`",
            "1–5 distinct names below `com.inubit.ibis`", "`TRACE`, `DEBUG`, `INFO`, `WARN`,"
                + " `ERROR`", "`durationMinutes` 1–60, default 10", "at most 50 loggers",
            "counted from the executed change", "`outcome: FAILED`",
            "The persistent trace settings are never changed",
            "| `INVALID_INPUT` |", "| `UNSUPPORTED_VERSION` |", "| `UNEXPECTED_RESPONSE` |",
            "| `INTERNAL` |");
        assertThat(toolSection("reset_temporary_log_level")).contains(
            "`trace --reset`", "`leaseId` and `forget: true`", "without contacting INUBIT",
            "`leaseOpen`", "whether a lease was open when the call ran",
            "only a lease whose reset failed can be forgotten",
            "ends automatically 7 days after its expiry", "`FORGOTTEN`");
        assertThat(toolSection("reset_temporary_log_level")).contains(
            " ### Leases and automatic resets ",
            "`~/.inubit-mcp/<profile>/log-levels/leases.json`", "every 30 s",
            "at most once per 60 s", "when the MCP server shuts down", "at its next start",
            "also when write access is switched off meanwhile", "`RESET_FAILED`",
            "7 days after its expiry", "removed 7 days after", "`lastAttempt.at`",
            "the instant the attempt began",
            "[`get_health`](#get_health) shows every open lease of the node in"
                + " `temporaryLogLevels`");
    }

    @Test
    void toolsListsTheToolsTheirCapabilitiesAnnouncementRulesAndHealthLeases()
        throws IOException {
        String tools = tools();

        assertThat(tools).contains("All twenty-nine tools",
            "the five node tools of feature 024 [their delta](../specs/"
                + "024-unsupported-node-tools/contracts/mcp-tools-delta.md)",
            "| [`list_cluster_nodes`](#list_cluster_nodes) |", "| [`read_io_log`](#read_io_log) |",
            "| [`get_node_config`](#get_node_config) |",
            "| [`set_temporary_log_level`](#set_temporary_log_level) |",
            "| [`reset_temporary_log_level`](#reset_temporary_log_level) |",
            "`list_cluster_nodes`, `read_io_log` and `get_node_config` are **offered only if**",
            "`set_temporary_log_level` and `reset_temporary_log_level` are **offered only if**",
            "Feature 024: `list_cluster_nodes` is offered only if",
            "`reading the cluster's node list`", "`reading module input/output logging`",
            "`reading node configuration values`", "`changing temporary log levels`",
            "`cluster_nodes_read` only for nodes whose group has `unsupported.nodeStatusRead:"
                + " true`",
            "`io_log_read` only for nodes whose group has `unsupported.ioLogRead: true`",
            "`node_config_read` only for nodes whose group has `unsupported.nodeConfigRead:"
                + " true`",
            "`log_level_write` only for nodes with write access whose group has"
                + " `unsupported.logLevelWrite: true`",
            "| `temporaryLogLevels` |",
            "`The temporary log level leases could not be read (<reason>)`");
    }

    // ---- README.md and SECURITY.md (T072) -------------------------------------------------

    @Test
    void readmeListsTheFiveToolsAndWhenTheyAreOffered() throws IOException {
        String readme = folded(Files.readString(Path.of("README.md")));
        String unsupported = "through an **unsupported, undocumented INUBIT interface**, for"
            + " diagnosis only, opt-in per group";

        assertThat(readme).contains(
            "| `list_cluster_nodes` | the members of the INUBIT cluster of ONE node",
            unsupported + " (`unsupported.nodeStatusRead`) | a helper on the INUBIT client |",
            "| `read_io_log` | the logged input and output messages of ONE process run on ONE node",
            "**business and personal data** that masking does not cover",
            unsupported + " (`unsupported.ioLogRead`) | a helper on the INUBIT client |",
            "| `get_node_config` | configuration values of ONE node from a fixed list of 20 names"
                + " without secrets",
            unsupported + " (`unsupported.nodeConfigRead`) | a helper on the INUBIT client |",
            "| `set_temporary_log_level` | raise the log level of one to five INUBIT loggers on ONE"
                + " node",
            "reset automatically, also while write access is off; the answering node is looked up "
                + unsupported + " (`unsupported.logLevelWrite`) | StartCLI `trace --nodelevel`;"
                + " a helper on the INUBIT client (answering node) |",
            "| `reset_temporary_log_level` | reset **every temporary log level on ONE node, also"
                + " those other users set**",
            "or forget a lease whose automatic reset failed; the answering node is looked up "
                + unsupported + " (`unsupported.logLevelWrite`) | StartCLI `trace --reset`; a"
                + " helper on the INUBIT client (answering node); forgetting: none |",
            "| `list_cluster_nodes`, `read_io_log`, `get_node_config` | a node's group has"
                + " `unsupported.nodeStatusRead`, `unsupported.ioLogRead` or"
                + " `unsupported.nodeConfigRead: true` respectively, the node is not pinned to an"
                + " INUBIT version that refuses the tool, and it has an INUBIT client home |",
            "| `set_temporary_log_level`, `reset_temporary_log_level` | a node with effective write"
                + " access (`write.enabled`) has `unsupported.logLevelWrite: true` on its group, is"
                + " not pinned to an INUBIT version that refuses `log_level_write`, and has an"
                + " INUBIT client home |");
    }

    @Test
    void securityNamesTheIoDataTheLeaseStoreTheCleanupResetsAndTheNodeLookup()
        throws IOException {
        assertThat(folded(Files.readString(Path.of("SECURITY.md")))).contains(
            "**Unsupported node tools** (`list_cluster_nodes`, `read_io_log`, `get_node_config`,"
                + " `set_temporary_log_level`, `reset_temporary_log_level`)",
            "**can contain business and personal data**",
            "masking does not cover business and personal data",
            "a credential under a neutral name, in encoded content or in free text may still"
                + " appear",
            "The three reads are not audited",
            "`~/.inubit-mcp/<profile>/log-levels/` (owner-only; names, levels, ids and times"
                + " only",
            "**cleanup resets run also while write access is off**",
            "a deliberate exception to the read-only default, approved by the project owner",
            "every set, reset and automatic reset is audited",
            "asks which INUBIT node answers through an unsupported, undocumented INUBIT interface"
                + " (the helper on the INUBIT client)");
    }
}
