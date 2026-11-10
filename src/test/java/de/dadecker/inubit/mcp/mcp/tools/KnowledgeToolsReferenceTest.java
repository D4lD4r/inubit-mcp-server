package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.knowledge.KnowledgeAnnouncement;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeLimits;
import de.dadecker.inubit.mcp.config.KnowledgeStatus;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T039 (feature 019, FR-021): docs/tools.md and docs/setup.md describe {@code read_knowledge} and
 * {@code record_knowledge} as the code announces them — the descriptions, every parameter and
 * result field of the schemas with their bounds, the instructions paragraph, the bounds of
 * {@link KnowledgeLimits} and the {@code --check-config} line.
 */
class KnowledgeToolsReferenceTest {

    private static final Path TOOLS = Path.of("docs/tools.md");
    private static final Path SETUP = Path.of("docs/setup.md");
    private static final Path SCHEMAS = Path.of("src/main/resources/schemas");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern RANGE = Pattern.compile("(\\d+)-(\\d+) characters");
    private static final Pattern REPEAT = Pattern.compile("\\{(\\d+),(\\d+)}");

    /** The text with quote markers and line breaks folded into single spaces. */
    private static String folded(Path file) throws IOException {
        return fold(Files.readString(file).replaceAll("\\n> ?", " "));
    }

    private static String fold(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String section(String heading) throws IOException {
        String reference = folded(TOOLS);
        assertThat(reference).contains(heading);
        String rest = reference.substring(reference.indexOf(heading) + heading.length());
        int end = rest.indexOf(" ## ");
        return end < 0 ? rest : rest.substring(0, end);
    }

    private static JsonNode schema(String file) throws IOException {
        return JSON.readTree(SCHEMAS.resolve(file).toFile());
    }

    private static List<String> properties(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.get("properties").propertyNames().forEach(names::add);
        return names;
    }

    private static String thousands(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    @Test
    void theDescriptionsAreQuotedAsRenderedAndTheToolsAreListed() throws IOException {
        String reference = folded(TOOLS);

        for (String description : new String[] {ReadKnowledgeTool.DESCRIPTION,
            RecordKnowledgeTool.DESCRIPTION}) {
            assertThat(reference).contains("[acme] " + Terminology.DEFAULT.render(description));
        }
        assertThat(reference).contains("| [`read_knowledge`](#read_knowledge) |",
            "| [`record_knowledge`](#record_knowledge) |", "All nineteen tools");
        assertThat(folded(Path.of("README.md"))).contains("| `read_knowledge` |",
            "| `record_knowledge` |", "`knowledge.recording: false`");
    }

    @Test
    void theAnnotationsAreDescribed() throws IOException {
        assertThat(section("## `read_knowledge`")).contains("`readOnlyHint: true`",
            "`destructiveHint: false`", "`idempotentHint: true`", "`openWorldHint: false`",
            "\"Search and read the local knowledge\"", "**Example prompt**", "read_knowledge(");
        assertThat(section("## `record_knowledge`")).contains("`readOnlyHint: false`",
            "`destructiveHint: false`", "`idempotentHint: false`", "`openWorldHint: false`",
            "\"Record a note in the local knowledge\"", "no confirmation code",
            "capability `record_knowledge`", "**Example prompt**", "record_knowledge(mode:");
    }

    @Test
    void everyParameterAndResultFieldOfReadKnowledgeIsNamed() throws IOException {
        JsonNode input = schema("read_knowledge.input.json");
        JsonNode output = schema("read_knowledge.output.json");
        JsonNode listing = output.get("oneOf").get(0);
        List<String> results = new ArrayList<>(properties(listing));
        results.addAll(properties(output.get("oneOf").get(1)));
        results.addAll(properties(output.get("$defs").get("hit")));
        assertThat(results).as("the scan finds the fields").contains("nextOffset", "writable",
            "ref", "masked", "lastWriter", "length");

        String section = section("## `read_knowledge`");
        assertThat(properties(input)).allSatisfy(name -> assertThat(section).as(name)
            .contains("| `" + name + "` |"));
        assertThat(results).allSatisfy(name -> assertThat(section).as(name)
            .contains("`" + name + "`"));

        JsonNode limit = input.get("properties").get("limit");
        assertThat(limit.get("maximum").asInt()).isEqualTo(KnowledgeLimits.SEARCH_LIMIT_MAX);
        assertThat(section).contains(limit.get("minimum").asInt() + "–"
            + limit.get("maximum").asInt() + ", default " + limit.get("default").asInt());
        assertThat(section).contains("1–" + input.get("properties").get("name").get("maxLength")
            .asInt() + " characters");
        for (String bounded : new String[] {"query", "topic"}) {
            Matcher repeat = REPEAT.matcher(input.get("properties").get(bounded).get("pattern")
                .asString());
            assertThat(repeat.find()).as(bounded).isTrue();
            assertThat(section).as(bounded)
                .contains(repeat.group(1) + "–" + repeat.group(2) + " characters");
        }
        assertThat(listing.get("properties").get("notes").get("maxItems").asInt())
            .isEqualTo(KnowledgeLimits.SEARCH_LIMIT_MAX);
        assertThat(output.get("oneOf").get(1).get("properties").get("content").get("maxLength")
            .asInt()).isEqualTo(KnowledgeLimits.PAGE_CHARS);
        assertThat(output.get("$defs").get("hit").get("properties").get("excerpt")
            .get("maxLength").asInt()).isEqualTo(KnowledgeLimits.EXCERPT_CHARS);
        assertThat(section).contains("valid until the next restart");
    }

    @Test
    void everyParameterAndResultFieldOfRecordKnowledgeIsNamed() throws IOException {
        JsonNode input = schema("record_knowledge.input.json");
        JsonNode output = schema("record_knowledge.output.json");
        String section = section("## `record_knowledge`");

        assertThat(properties(input)).allSatisfy(name -> assertThat(section).as(name)
            .containsPattern("\\| `" + name + "`( \\(required\\))? \\|"));
        assertThat(properties(output)).allSatisfy(name -> assertThat(section).as(name)
            .contains("`" + name + "`"));
        assertThat(input.get("properties").get("mode").get("enum").values())
            .allSatisfy(mode -> assertThat(section).contains("`" + mode.asString() + "`"));

        int ranges = 0;
        for (JsonNode property : input.get("properties").values()) {
            Matcher range = RANGE.matcher(property.get("description").asString());
            while (range.find()) {
                assertThat(section).contains(range.group(1) + "–" + range.group(2)
                    + " characters");
                ranges++;
            }
        }
        assertThat(ranges).as("the scan finds the bounds").isGreaterThanOrEqualTo(2);
        assertThat(section).contains("up to 20 unique topics", "up to 4 segments");
    }

    @Test
    void theBoundsAreTheBoundsOfTheCode() throws IOException {
        String tools = folded(TOOLS);
        String setup = folded(SETUP);
        List<String> both = List.of(
            KnowledgeLimits.NOTE_BYTES / 1024 + " KiB",
            KnowledgeLimits.READ_BYTES / (1024 * 1024) + " MiB",
            KnowledgeLimits.TOTAL_BYTES / (1024 * 1024) + " MiB",
            thousands(KnowledgeLimits.PAGE_CHARS) + " characters",
            thousands(KnowledgeLimits.LIST_CHARS) + " characters",
            "cut at " + KnowledgeLimits.LINE_CHARS + " characters",
            "at most " + KnowledgeLimits.LOCK_WAIT.toSeconds() + " s",
            "up to " + KnowledgeLimits.START_PREPARE.toSeconds() + " s");

        assertThat(tools).contains(both);
        assertThat(setup).contains(both);
        assertThat(tools).contains("at most " + KnowledgeLimits.EXCERPT_CHARS + " characters",
            KnowledgeLimits.HISTORY + " versions per note");
        assertThat(setup).contains("excerpt " + KnowledgeLimits.EXCERPT_CHARS,
            "the " + KnowledgeLimits.HISTORY + " newest versions per note");
    }

    @Test
    void theInstructionsParagraphIsQuotedAsBuilt() throws IOException {
        String section = section("## The local knowledge (feature 019)");
        String listed = KnowledgeAnnouncement.paragraph(true, new KnowledgeAnnouncement.Listed(2,
            List.of(new KnowledgeAnnouncement.Entry("partners/acme",
                    "ACME partner: endpoints and contacts", List.of("partner", "soap")),
                new KnowledgeAnnouncement.Entry("runbooks/queue-full",
                    "What to do when the queue is full", List.of())).iterator()));
        assertThat(section).contains(fold(listed));

        String empty = KnowledgeAnnouncement.paragraph(true, new KnowledgeAnnouncement.Empty());
        String readOnly = KnowledgeAnnouncement.paragraph(false, new KnowledgeAnnouncement.Empty());
        String unusable = KnowledgeAnnouncement.paragraph(true,
            new KnowledgeAnnouncement.Unusable("<problem>"));
        String notReady = KnowledgeAnnouncement.paragraph(true,
            new KnowledgeAnnouncement.NotReady(2, List.of("test/node1")))
            .replace(" 2 notes", " <n> notes");
        String emptySentence = empty.substring(empty.lastIndexOf(". ") + 2);
        assertThat(section).contains(fold(emptySentence), fold(tail(unusable, empty)),
            fold(tail(notReady, empty)));
        String readOnlyTail = tail(readOnly, empty);
        assertThat(readOnlyTail).endsWith(" " + emptySentence);
        assertThat(section).contains(fold(readOnlyTail.substring(0,
            readOnlyTail.length() - emptySentence.length() - 1)));
    }

    /** {@code text} after the longest prefix it shares with {@code other}, from a word start. */
    private static String tail(String text, String other) {
        int common = 0;
        while (common < Math.min(text.length(), other.length())
            && text.charAt(common) == other.charAt(common)) {
            common++;
        }
        return text.substring(text.lastIndexOf(' ', common) + 1);
    }

    @Test
    void setupDescribesTheKnowledgeAndTheCheckConfigLine() throws IOException {
        String line = new KnowledgeStatus(Path.of("/Users/jdoe/.inubit-mcp/acme/knowledge"),
            KnowledgeStatus.State.ABSENT, Optional.empty(), true, 0).line().replace('\\', '/');
        String setup = folded(SETUP);

        assertThat(Files.readString(SETUP)).contains("\n" + line + "\n");
        assertThat(setup).contains("### Local knowledge", "knowledge.directory",
            "~/.inubit-mcp/<profile.name>/knowledge", "recording: false",
            "`Knowledge: <folder> (ok | absent | not a directory | not readable), recording on|off,"
                + " <n> notes`",
            ".history/partners/acme.md/", "copy it over the note by hand", "run chmod 700",
            "reads the credentials of **every** node", "`PRECONDITION_FAILED`",
            "`record_knowledge`");
        assertThat(folded(Path.of("SECURITY.md"))).contains("**Local knowledge**",
            "**unencrypted**", "**pattern-based**", "owner-only");
    }
}
