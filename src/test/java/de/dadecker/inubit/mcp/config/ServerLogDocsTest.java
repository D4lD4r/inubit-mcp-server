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
 * T045 (feature 021, FR-004, US2-5, D-25): docs/setup.md documents
 * {@code unsupported.serverLogRead} in the subsection "Unsupported functions" with the table row,
 * the YAML example and the FR-004 text of contracts/configuration-delta.md after the
 * {@code queueEntryRead} text; the download budget names {@code cliExportTimeout} with its
 * built-in value; the example loads; section 6 shows the group lines with an opt-in as
 * {@code ConfigSummaryTest} pins them.
 */
class ServerLogDocsTest {

    private static final Path SETUP = Path.of("docs/setup.md");
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
}
