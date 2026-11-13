package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.testing.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T070 (feature 024, FR-003, FR-042, FR-043, D-31): docs/setup.md documents the four node-tool
 * opt-ins {@code unsupported.nodeStatusRead}, {@code ioLogRead}, {@code nodeConfigRead} and
 * {@code logLevelWrite} in the subsection "Unsupported functions" with the table rows, the YAML
 * example and the text of contracts/configuration-delta.md after the {@code serverLogRead} text
 * (with the cleanup-reset exception and the precondition to address one node directly); the
 * validation texts; section 6 shows the group lines with the new parts, the production warnings,
 * the warning without write access and the {@code Temporary log levels:} section.
 */
class NodeToolsDocsTest {

    private static final Path SETUP = Path.of("docs/setup.md");
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
            "StartCLI's documented `trace -nl`, for a limited time;"
                + " `reset_temporary_log_level` runs `trace -r`.",
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
}
