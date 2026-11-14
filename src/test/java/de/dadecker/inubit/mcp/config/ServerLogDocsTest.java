package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.mcp.tools.DownloadServerLogTool;
import de.dadecker.inubit.mcp.testing.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * T045 (feature 021, FR-004, US2-5, D-25): docs/setup.md documents
 * {@code unsupported.serverLogRead} in the subsection "Unsupported functions" with the table row,
 * the YAML example and the FR-004 text of contracts/configuration-delta.md after the
 * {@code queueEntryRead} text; the download budget names {@code cliExportTimeout} with its
 * built-in value; the example loads; section 6 shows the group lines with an opt-in as
 * {@code ConfigSummaryTest} pins them.
 *
 * <p>T057 (FR-004, D-20, D-25, contracts/mcp-tools-delta.md): docs/tools.md has the section
 * {@code ## download_server_log} starting with the description as announced and a link to setup
 * "Unsupported functions"; it names every input and output field, the limits, excerpt, masking,
 * folder, memory and error rules; the capability rows, the announcement rule and the
 * {@code list_nodes} names; its 9.0 state matches the production matrix.
 *
 * <p>T058 (D-25): README.md lists the tool and the condition under which it is announced.
 */
class ServerLogDocsTest {

    private static final Path SETUP = Path.of("docs/setup.md");
    private static final Path TOOLS = Path.of("docs/tools.md");
    private static final Path SCHEMAS = Path.of("src/main/resources/schemas");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path HOME = Platform.absolutePath("/home/test");
    private static final Path SOURCE = HOME.resolve(".config/inubit-mcp/acme.yaml");
    private static final Pattern YAML_BLOCK = Pattern.compile("```yaml\n(.*?)```", Pattern.DOTALL);
    private static final Pattern TEXT_BLOCK = Pattern.compile("```text\n(.*?)```", Pattern.DOTALL);
    private static final String OPENING =
        "**`unsupported.serverLogRead` uses unsupported INUBIT functionality.**";

    /** The text with quote markers and line breaks folded into single spaces. */
    private static String folded(String text) {
        return text.replaceAll("\\n> ?", " ").replaceAll("\\s+", " ");
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
    void setupHasTheTableRowAndTheBoldSentencesOfTheFr004TextAfterTheQueueEntryReadText()
        throws IOException {
        String unsupported = folded(section("### Unsupported functions"));

        assertThat(unsupported).contains(
            "| `unsupported.serverLogRead` | group | `false` | allows `download_server_log` with"
                + " `log: webservice` for the nodes of this group, which reads `webservice.log`"
                + " through an **unsupported, undocumented** INUBIT interface",
            "independent of `unsupported.queueEntryRead`, `write.*`, `archives.*` and"
                + " `production` |");
        int queueEntryRead =
            unsupported.indexOf("**Read-only.** The tool uses only the read request");
        assertThat(queueEntryRead).isNotNegative();
        assertThat(unsupported.indexOf(OPENING)).isGreaterThan(queueEntryRead);

        String text = unsupported.substring(unsupported.indexOf(OPENING));
        assertThat(text).contains(
            "[reference](tools.md#download_server_log)",
            "**No vendor support, no stability guarantee.**",
            "after **any** INUBIT update, including patch releases.",
            "**Diagnosis only.**",
            "**Clear-text content.**",
            "connectors **in clear text** (business data,",
            "**Read-only on INUBIT.**",
            "**Cluster nodes.** The log comes from the node the configured address reaches;"
                + " address cluster nodes directly, not through a load balancer.",
            "on a group with `production: true` the server starts with a warning.");
    }

    @Test
    void theDownloadBudgetNamesCliExportTimeoutWithItsBuiltInValue() throws IOException {
        String text = folded(section("### Unsupported functions"));
        assertThat(text).contains(OPENING);
        text = text.substring(text.indexOf(OPENING));

        assertThat(text).contains("A download is bounded by the node's `cliExportTimeout`"
            + " (built-in " + Defaults.BUILTIN_CLI_EXPORT_TIMEOUT.toSeconds()
            + " s); raise it for large logs.");
    }

    @Test
    void theSetupExampleLoadsAndOptsInOnlyItsGroup() throws IOException {
        String example = block(YAML_BLOCK, section("### Unsupported functions"),
            "serverLogRead: true");

        ProfileConfig config = new ConfigLoader(Map.of(), HOME, false)
            .parse("profile:\n  name: acme\n" + example, SOURCE).config();

        assertThat(config.groups()).singleElement().satisfies(group -> assertThat(
            group.unsupported().serverLogRead()).contains(true));
        assertThat(config.resolvableNodes()).singleElement().satisfies(node -> {
            assertThat(node.id()).isEqualTo(NodeId.of("test", "node1"));
            assertThat(node.serverLogRead()).isTrue();
            assertThat(node.queueEntryRead()).isFalse();
        });
    }

    @Test
    void sectionSixShowsTheGroupLinesWithAnOptInAsConfigSummaryTestPinsThem() throws IOException {
        String check = section("## 6. Check the configuration");

        assertThat(block(TEXT_BLOCK, check, "serverLogRead: enabled (unsupported)")).isEqualTo(
            "Group test: archives: allowed, serverLogRead: enabled (unsupported)\n"
                + "Group prod (production): archives: refused (no archives.productionOptIn),"
                + " serverLogRead: off\n");
        assertThat(folded(check)).contains("`unsupported.serverLogRead is enabled on production"
            + " group prod: the web service log is read through an undocumented INUBIT interface"
            + " without vendor support`");
    }

    /** The folded {@code download_server_log} section of docs/tools.md. */
    private static String toolSection() throws IOException {
        String tools = folded(Files.readString(TOOLS));
        String heading = "## `download_server_log`";
        assertThat(tools).contains(" " + heading + " ");
        String rest = tools.substring(tools.indexOf(heading) + heading.length());
        int end = rest.indexOf(" ## ");
        return end < 0 ? rest : rest.substring(0, end);
    }

    /** The property names of {@code schema}, also inside items and {@code oneOf} variants. */
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
        return names;
    }

    @Test
    void toolsStartsTheSectionWithTheDescriptionAsAnnouncedAndLinksToSetup() throws IOException {
        String description = new DownloadServerLogTool(request -> null).descriptionText();
        String section = toolSection();

        assertThat(Files.readString(TOOLS)).contains(
            "\n## `download_server_log`\n\n> [acme] Downloads the trace log (trace.log)");
        assertThat(section).startsWith(" [acme] " + Terminology.DEFAULT.render(description) + " ");
        assertThat(section).contains(
            "[setup.md, Unsupported functions](setup.md#unsupported-functions)");
        for (String text : List.of(description, section)) {
            assertThat(text.toLowerCase(Locale.ROOT)).contains("clear text",
                "business data and credentials", "supported trace download", "unsupported",
                "undocumented", "without vendor support", "any inubit update", "never replaced");
        }
    }

    @Test
    void toolsNamesEveryInputAndOutputField() throws IOException {
        List<String> names = new ArrayList<>(fields(
            JSON.readTree(SCHEMAS.resolve("download_server_log.input.json").toFile())));
        names.addAll(fields(
            JSON.readTree(SCHEMAS.resolve("download_server_log.output.json").toFile())));
        assertThat(names).as("the scan finds the fields").contains("tailLines", "inubitNode",
            "wholeFile", "matchingLines", "droppedLines", "cutLines", "cut", "detected");

        String section = toolSection();
        assertThat(names).allSatisfy(name -> assertThat(section).as(name)
            .contains("`" + name + "`"));
    }

    @Test
    void toolsStatesTheLimitsExcerptMaskingFolderMemoryAndErrorRules() throws IOException {
        assertThat(toolSection()).contains(
            "`readOnlyHint: false`", "`destructiveHint: false`", "`idempotentHint: false`",
            "`openWorldHint: true`",
            "`tailLines` 1–2,000, default 200",
            "`contains` 1–200 characters, no control characters, plain text, case-insensitive,"
                + " matched against the masked lines",
            "`maxChars` 1–100,000, default 20,000",
            "the first whole line within the last 50 MiB",
            "the whole window is masked before lines are selected",
            "line numbers are absolute in the downloaded file", "oldest lines are dropped first",
            "a secret-named JSON value opened before the window is not detected",
            "the known credentials of every configured node",
            "`\"excerpt\": {\"kind\": \"withheld\"}`", "the file is still written",
            "[`export_archives`](#export_archives)",
            "`<group>-<node>-<log>-<yyyyMMdd-HHmmss>[-n].log`", "`-2` … `-999`",
            "never replaced", "≈ 250 MiB", "one excerpt at a time",
            "counts against `cliExportTimeout`", "address cluster nodes directly",
            "the server reads trace.log fully into its memory",
            "large logs need a larger `cliExportTimeout`",
            "| `INVALID_INPUT` |", "| `TARGET_UNKNOWN` |", "| `NOT_CONFIGURED` |",
            "| `UNSUPPORTED_VERSION` |", "| `NOT_FOUND` |", "| `CLI_UNAVAILABLE` |",
            "| `AUTH_FAILED` |", "| `FORBIDDEN` |", "| `TLS_ERROR`, `UNREACHABLE`, `TIMEOUT` |",
            "| `SECRET_UNRESOLVED` (or the node's other credential codes) |",
            "| `MAINTENANCE_MODE` |", "| `UNEXPECTED_RESPONSE` |", "| `INTERNAL` |",
            "no file of the call remains in `targetFolder`", "**Example prompt**",
            "download_server_log(target:");
    }

    @Test
    void toolsStatesTheNineZeroStateOfTheProductionMatrix() throws IOException {
        Optional<InubitVersion> nineZero = InubitVersion.parse("9.0.10");
        CapabilityMatrix matrix = CapabilityMatrix.production();
        String section = toolSection();
        String tools = folded(Files.readString(TOOLS));

        for (Capability capability : List.of(Capability.TRACE_LOG_READ,
            Capability.WEBSERVICE_LOG_READ)) {
            String name = capability == Capability.TRACE_LOG_READ ? "trace_log_read"
                : "webservice_log_read";
            if (matrix.state(nineZero, capability) == SupportState.SUPPORTED) {
                assertThat(section).contains("`" + name + "` is supported on INUBIT 8.1 and 9.0");
                assertThat(tools).containsPattern("\\| `" + name + "` \\|[^|]+\\|[^|]+\\|"
                    + " supported \\(8\\.1 and 9\\.0[;)]");
            } else {
                assertThat(section).contains("`" + name + "` is not yet verified for INUBIT 9.0");
            }
        }
        if (matrix.state(nineZero, Capability.TRACE_LOG_READ) == SupportState.SUPPORTED
            && matrix.state(nineZero, Capability.WEBSERVICE_LOG_READ) == SupportState.SUPPORTED) {
            assertThat(section).doesNotContain("not yet verified");
        }
        if (matrix.state(nineZero, Capability.WEBSERVICE_LOG_READ) == SupportState.SUPPORTED) {
            assertThat(section).as("the 9.0 web service state rests on a recorded 0-byte answer")
                .contains("recorded answer of 0 bytes",
                    "an empty and a missing web service log cannot be told apart");
        }
    }

    @Test
    void toolsListsTheToolItsCapabilitiesAndItsAnnouncementRule() throws IOException {
        String tools = folded(Files.readString(TOOLS));

        assertThat(tools).contains("All twenty-seven tools",
            "`download_server_log` of feature 021 [its delta](../specs/021-server-log-download/"
                + "contracts/mcp-tools-delta.md)",
            "| [`download_server_log`](#download_server_log) |",
            "`download_server_log` is **offered only if**",
            "Feature 021: `download_server_log` is offered only if",
            "`trace_log_read` for nodes with an INUBIT client home",
            "`webservice_log_read` only for nodes whose group has `unsupported.serverLogRead:"
                + " true`",
            "`downloading the trace log`", "`downloading the web service log`");
    }

    @Test
    void readmeListsTheToolAndWhenItIsOffered() throws IOException {
        assertThat(folded(Files.readString(Path.of("README.md")))).contains(
            "| `download_server_log` | the trace log or the web service log of ONE node",
            "**in clear text** (business data and credentials; only the excerpt is masked)",
            "the web service log through an **unsupported, undocumented INUBIT interface**, for"
                + " diagnosis only, opt-in per group (`unsupported.serverLogRead`)",
            "| `download_server_log` | a node has an INUBIT client home (StartCLI installation) and"
                + " either is not pinned to an INUBIT version that refuses the trace log download,"
                + " or its group has `unsupported.serverLogRead: true` and it is not pinned to a"
                + " version that refuses the web service log download |");
    }
}
