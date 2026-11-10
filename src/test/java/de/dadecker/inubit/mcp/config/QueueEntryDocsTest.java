package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T029 (feature 020, US2-4, FR-003, FR-004, FR-017): docs/setup.md documents
 * {@code unsupported.queueEntryRead} in the subsection "Unsupported functions" after "Workbench
 * archives" with the table, the YAML example and the FR-004 text of
 * contracts/configuration-delta.md; the example loads; section 6 shows the group lines of the
 * complete example profile with an opt-in as {@link ConfigSummary} renders them; the overview
 * lists {@code unsupported} among the group-only settings.
 */
class QueueEntryDocsTest {

    private static final Path SETUP = Path.of("docs/setup.md");
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
}
