package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T029 (feature 020, US2-4, FR-003, FR-004, FR-017): docs/setup.md documents
 * {@code unsupported.queueEntryRead} in the subsection "Unsupported functions" after "Workbench
 * archives" with the table, the YAML example and the FR-004 text of
 * contracts/configuration-delta.md; the example loads; section 6 shows the group lines of the
 * complete example profile with an opt-in as {@link ConfigSummary} renders them; the overview
 * lists {@code unsupported} among the group-only settings.
 *
 * <p>T040 (FR-004, FR-019, contracts/mcp-tools-delta.md): docs/tools.md has the section
 * {@code ## read_queue_entry} with the short warning first and the short form of the description,
 * names every input and output field, the errors, candidate, masking and bounds rules, the
 * capability row and the announcement rule; its 9.0 state matches the production matrix; README
 * lists the tool.
 */
class QueueEntryDocsTest {

    private static final Path SETUP = Path.of("docs/setup.md");
    private static final Path TOOLS = Path.of("docs/tools.md");
    private static final Path SCHEMAS = Path.of("src/main/resources/schemas");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path HOME = Platform.absolutePath("/home/test");
    private static final Path SOURCE = HOME.resolve(".config/inubit-mcp/acme.yaml");
    private static final Pattern YAML_BLOCK = Pattern.compile("```yaml\n(.*?)```", Pattern.DOTALL);
    private static final Pattern TEXT_BLOCK = Pattern.compile("```text\n(.*?)```", Pattern.DOTALL);
    private static final WorkspaceDirectory.Preparer NO_DISK =
        workspace -> new WorkspaceDirectory.Usable(false);

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

    private static ConfigLoader loader() {
        return new ConfigLoader(Map.of(), HOME, false);
    }

    @Test
    void setupHasTheUnsupportedFunctionsSubsectionAfterTheWorkbenchArchives() throws IOException {
        String setup = Files.readString(SETUP);

        assertThat(setup.indexOf("\n### Workbench archives\n")).isPositive()
            .isLessThan(setup.indexOf("\n### Unsupported functions\n"));
        assertThat(setup.indexOf("\n### Unsupported functions\n"))
            .isLessThan(setup.indexOf("\n### Audit log\n"));
    }

    @Test
    void setupContainsTheTableAndTheBoldSentencesOfTheFr004Text() throws IOException {
        String unsupported = folded(section("### Unsupported functions"));

        assertThat(unsupported).contains(
            "| `unsupported.queueEntryRead` | group | `false` | offers `read_queue_entry` for the"
                + " nodes of this group, which reads Queue Manager entries through an"
                + " **unsupported, undocumented** INUBIT interface",
            "independent of `write.*`, `archives.*` and `production` |",
            "**`unsupported.queueEntryRead` uses unsupported INUBIT functionality.**",
            "**No vendor support, no stability guarantee.**",
            "after **any** INUBIT update, including patch releases.",
            "**Diagnosis only.**",
            "**Business data leaves the server.**",
            "**Credentials in unusual places** (for example under a neutral element name such as"
                + " INUBIT's `<Property name=\"password\">`, or inside binary or encoded content)"
                + " **may still appear in the result.**",
            "**Read-only.**",
            "[reference](tools.md#read_queue_entry)",
            "on a group with `production: true` the server starts with a warning.");
    }

    @Test
    void theSetupExampleLoadsAndOptsInOnlyItsGroup() throws IOException {
        String example = block(YAML_BLOCK, section("### Unsupported functions"),
            "queueEntryRead: true");

        ProfileConfig config = loader().parse("profile:\n  name: acme\n" + example, SOURCE)
            .config();

        assertThat(config.groups()).singleElement().satisfies(group -> assertThat(
            group.unsupported().queueEntryRead()).contains(true));
        assertThat(config.resolvableNodes()).singleElement().satisfies(node -> {
            assertThat(node.id()).isEqualTo(NodeId.of("test", "node1"));
            assertThat(node.queueEntryRead()).isTrue();
        });
    }

    @Test
    void sectionSixShowsTheGroupLinesOfTheCompleteExampleWithAnOptInAsRendered()
        throws IOException {
        String complete = block(YAML_BLOCK, Files.readString(SETUP), "x-tls: &tls");
        String optIn = complete.replace("  - name: test\n    tls: *tls\n",
            "  - name: test\n    tls: *tls\n    unsupported:\n      queueEntryRead: true\n");
        assertThat(optIn).isNotEqualTo(complete);
        LoadedConfig loaded = loader().parse(optIn, SOURCE);
        ValidationReport report = new ConfigValidator(path -> true, Map.of(), false,
            Path.of("/var/tmp"), file -> List.of(), NO_DISK).validate(loaded);
        EntryCheck.Result entries = EntryCheck.run(loaded.config().resolvableNodes(),
            TestSecrets.store(Map.of()), Duration.ofSeconds(5));
        String rendered = new ConfigSummary(path -> true, false).render(loaded, report, Map.of(),
            Map.of(), ParameterCheck.Result.NONE, entries);

        String check = section("## 6. Check the configuration");
        String example = block(TEXT_BLOCK, check, "queueEntryRead: enabled (unsupported)");

        assertThat(groupLines(example)).containsExactly(
            "Umgebung test: archives: allowed, queueEntryRead: enabled (unsupported)",
            "Umgebung prod (production): archives: refused (no archives.productionOptIn),"
                + " queueEntryRead: off")
            .containsExactlyElementsOf(groupLines(rendered));
        assertThat(folded(check)).contains("[Unsupported functions](#unsupported-functions)");
    }

    private static List<String> groupLines(String summary) {
        return summary.lines().filter(line -> line.startsWith("Umgebung ")).toList();
    }

    @Test
    void theOverviewListsUnsupportedAmongTheGroupOnlySettings() throws IOException {
        assertThat(folded(section("## 3. Configuration file"))).contains(
            "`deploy`, `archives` and `unsupported` only per group",
            "[Unsupported functions](#unsupported-functions)");
    }

    /** The folded {@code read_queue_entry} section of docs/tools.md. */
    private static String toolSection() throws IOException {
        String tools = folded(Files.readString(TOOLS));
        String heading = "## `read_queue_entry`";
        assertThat(tools).contains(" " + heading + " ");
        String rest = tools.substring(tools.indexOf(heading) + heading.length());
        int end = rest.indexOf(" ## ");
        return end < 0 ? rest : rest.substring(0, end);
    }

    private static List<String> fields(JsonNode schema, String prefix) {
        List<String> names = new ArrayList<>();
        JsonNode properties = schema.path("properties");
        properties.propertyNames().forEach(name -> {
            names.add(prefix + name);
            JsonNode child = properties.get(name);
            names.addAll(fields(child, ""));
            names.addAll(fields(child.path("items"), ""));
        });
        return names;
    }

    @Test
    void toolsStartsTheSectionWithTheShortWarningAndQuotesTheShortForm() throws IOException {
        String section = toolSection();

        assertThat(Files.readString(TOOLS)).contains(
            "\n## `read_queue_entry`\n\n> **Unsupported INUBIT functionality.**");
        assertThat(section).startsWith(" **Unsupported INUBIT functionality.**");
        String warning = section.substring(0, section.indexOf(" [acme] "));
        assertThat(warning).contains("undocumented", "without vendor support",
            "after any INUBIT update", "For diagnosis only", "business data",
            "[setup.md, Unsupported functions](setup.md#unsupported-functions)");
        assertThat(section).contains("[acme] Reads the content of one INUBIT Queue Manager entry",
            "UNSUPPORTED: uses an undocumented internal INUBIT interface",
            "without vendor support or stability guarantee", "after any INUBIT update",
            "for diagnosis only", "Returns business data of the entry");
    }

    @Test
    void toolsNamesEveryInputAndOutputField() throws IOException {
        List<String> names = new ArrayList<>(fields(
            JSON.readTree(SCHEMAS.resolve("read_queue_entry.input.json").toFile()), ""));
        names.addAll(fields(
            JSON.readTree(SCHEMAS.resolve("read_queue_entry.output.json").toFile()), ""));
        assertThat(names).as("the scan finds the fields").contains("maxChars", "errorTexts",
            "returnedChars", "nameTruncated", "masked", "detected", "reason");

        String section = toolSection();
        assertThat(names).allSatisfy(name -> assertThat(section).as(name)
            .contains("`" + name + "`"));
        assertThat(section).as("variable kinds follow the data stream (FR-010)")
            .contains("`kind` (`text`, `empty` or `binary`)", "an empty value has `sizeBytes` (0)");
    }

    @Test
    void toolsDescribesCandidatesErrorsMaskingAndBounds() throws IOException {
        assertThat(toolSection()).contains(
            "`readOnlyHint: true`", "`destructiveHint: false`", "`idempotentHint: true`",
            "`openWorldHint: true`", "**offered only if**", "`unsupported.queueEntryRead: true`",
            "**Which entry is read**", "repeat the call with `fileName`",
            "without a queue file (fileSize 0); there is no content to read",
            "Read their error texts with query_logs (logType queueLog) for process 1234",
            "**partial result**", "**Masking**", "`${secret:variables.<name>}`",
            "`${secret:data.<name>}`", "`${secret:authorization}`", "**Residual risk:**",
            "**may still appear in the result**", "**Bounds**", "at most 200 variables",
            "at most 2,000 characters", "at most 40,000 characters", "10 MiB", "`cliTimeout`",
            "| `NOT_CONFIGURED` |", "| `NOT_FOUND` |", "| `INVALID_INPUT` |",
            "| `UNSUPPORTED_VERSION` |", "| `CLI_UNAVAILABLE` |", "| `FORBIDDEN` |",
            "| `UNEXPECTED_RESPONSE` |", "the unsupported INUBIT interface may have changed",
            "Nothing is changed", "Reads are not audited", "**Example prompt**",
            "read_queue_entry(target:");
    }

    @Test
    void toolsStatesTheNineZeroStateOfTheProductionMatrix() throws IOException {
        SupportState nineZero = CapabilityMatrix.production().state(
            InubitVersion.parse("9.0.10"), Capability.QUEUE_ENTRY_READ);
        String section = toolSection();
        String tools = folded(Files.readString(TOOLS));

        if (nineZero == SupportState.SUPPORTED) {
            assertThat(section).contains("`queue_entry_read` is supported on INUBIT 8.1 and 9.0")
                .doesNotContain("not yet verified");
            assertThat(tools).contains("| `read_queue_entry` | `queue_entry_read` | read"
                + " (unsupported vendor interface) | REST lookup + helper on the INUBIT client |"
                + " supported (8.1 and 9.0) |");
        } else {
            assertThat(section).contains("not yet verified for INUBIT 9.0");
        }
    }

    @Test
    void toolsListsTheToolItsCapabilityAndItsAnnouncementRule() throws IOException {
        String tools = folded(Files.readString(TOOLS));

        assertThat(tools).contains("All twenty-three tools",
            "| [`read_queue_entry`](#read_queue_entry) | the content of ONE Queue Manager entry in"
                + " `ERROR`",
            "`read_queue_entry` is **offered only if** a node's group has"
                + " `unsupported.queueEntryRead: true`",
            "`queue_entry_read` only for nodes whose group has `unsupported.queueEntryRead:"
                + " true`",
            "[its delta](../specs/020-queue-entry-read/contracts/"
                + "mcp-tools-delta.md)");
        assertThat(folded(Files.readString(Path.of("README.md"))))
            .contains("| `read_queue_entry` |", "| `read_queue_entry` | a node's group has"
                + " `unsupported.queueEntryRead: true`");
    }
}
