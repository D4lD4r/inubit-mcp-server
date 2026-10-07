package de.dadecker.inubit.mcp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 002 review U1/R3/R4: the raw templates must read correctly with any terminology, including
 * nouns of other languages ("Umgebung") and names that start with a vowel ("instance"). A
 * placeholder renders the configured name exactly, so a template must not
 *
 * <ul>
 *   <li>put the article "a"/"an" directly before a placeholder (its choice depends on the
 *       name; invariant determiners such as "the", "one", "each", "all" are fine), or
 *   <li>inflect a placeholder ({@code {node}s}, {@code {node}'s}, {@code {group}-level}).
 * </ul>
 *
 * <p>Scanned statically: every Java string literal of {@code src/main/java} that contains a
 * placeholder (adjacent literals joined with {@code +} count as one text: tool descriptions,
 * titles, the instructions, {@code render("…")}, {@code termError/termWarning} templates) and
 * every {@code description} of the 16 schema files.
 */
class TemplateGrammarTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path SCHEMAS = Path.of("src/main/resources/schemas");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\p{Alpha}+\\}");
    private static final Pattern ARTICLE_BEFORE_PLACEHOLDER =
        Pattern.compile("(?i)\\ban?\\s+\\{\\w+\\}");
    private static final Pattern INFLECTED_PLACEHOLDER = Pattern.compile("\\}(s|'s|-)\\b");

    /** A string literal, possibly followed by {@code + "…"} continuations. */
    private static final Pattern LITERALS =
        Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"(?:\\s*\\+\\s*\"(?:[^\"\\\\\\n]|\\\\.)*\")*");
    private static final Pattern ONE_LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    private static final Pattern COMMENTS =
        Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    private static List<String> javaTemplates() throws IOException {
        List<String> templates = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))
                ::iterator) {
                String code = withoutComments(Files.readString(file));
                Matcher literals = LITERALS.matcher(code);
                while (literals.find()) {
                    StringBuilder text = new StringBuilder();
                    Matcher one = ONE_LITERAL.matcher(literals.group());
                    while (one.find()) {
                        text.append(one.group(1));
                    }
                    if (PLACEHOLDER.matcher(text).find()) {
                        templates.add(SOURCES.relativize(file) + ": " + text);
                    }
                }
            }
        }
        return templates;
    }

    /** Comments removed, string literals kept (a "//" inside a literal is no comment). */
    private static String withoutComments(String code) {
        StringBuilder out = new StringBuilder();
        Matcher literal = ONE_LITERAL.matcher(code);
        int from = 0;
        while (literal.find()) {
            out.append(COMMENTS.matcher(code.substring(from, literal.start())).replaceAll(" "));
            out.append(literal.group());
            from = literal.end();
        }
        out.append(COMMENTS.matcher(code.substring(from)).replaceAll(" "));
        return out.toString();
    }

    private static List<String> schemaTemplates() throws IOException {
        List<String> templates = new ArrayList<>();
        try (Stream<Path> files = Files.list(SCHEMAS)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".json"))
                ::iterator) {
                collect(JSON.readTree(Files.readString(file)), file.getFileName().toString(),
                    templates);
            }
        }
        return templates;
    }

    private static void collect(JsonNode node, String path, List<String> out) {
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                JsonNode value = node.get(name);
                if (name.equals("description") && value.isString()) {
                    out.add(path + ": " + value.asString());
                } else {
                    collect(value, path + "." + name, out);
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collect(node.get(i), path + "[" + i + "]", out);
            }
        }
    }

    @Test
    void theScanFindsTheTemplates() throws IOException {
        assertThat(javaTemplates()).as("tool descriptions, titles, instructions, messages")
            .hasSizeGreaterThan(30)
            .anySatisfy(t -> assertThat(t).contains("mcp/tools/GetHealthTool.java"))
            .anySatisfy(t -> assertThat(t).contains("mcp/McpServerFactory.java"))
            .anySatisfy(t -> assertThat(t).contains("application/TargetResolver.java"))
            .anySatisfy(t -> assertThat(t).contains("config/ConfigValidator.java"));
        // feature 004 adds import_artifacts, restore_backup, set_active .{input,output}.json
        assertThat(Files.list(SCHEMAS).count()).isEqualTo(26);
        assertThat(schemaTemplates()).filteredOn(t -> PLACEHOLDER.matcher(t).find())
            .hasSizeGreaterThan(15);
    }

    @Test
    void noArticleStandsDirectlyBeforeAPlaceholder() throws IOException {
        List<String> all = new ArrayList<>(javaTemplates());
        all.addAll(schemaTemplates());

        assertThat(all).filteredOn(t -> ARTICLE_BEFORE_PLACEHOLDER.matcher(t).find())
            .as("\"a {node}\" reads \"a Umgebung\" or \"a instance\"; use one/each/the/its")
            .isEmpty();
    }

    @Test
    void noPlaceholderIsInflected() throws IOException {
        List<String> all = new ArrayList<>(javaTemplates());
        all.addAll(schemaTemplates());

        assertThat(all).filteredOn(t -> INFLECTED_PLACEHOLDER.matcher(t).find())
            .as("{node}s, {node}'s and {group}-… do not work for every name; use {nodes}")
            .isEmpty();
    }

    @Test
    void theGuardPatternsMatchWhatTheyShould() {
        assertThat(ARTICLE_BEFORE_PLACEHOLDER.matcher("on a {node}").find()).isTrue();
        assertThat(ARTICLE_BEFORE_PLACEHOLDER.matcher("An {group} id").find()).isTrue();
        assertThat(ARTICLE_BEFORE_PLACEHOLDER.matcher("a single {node}").find()).isFalse();
        assertThat(ARTICLE_BEFORE_PLACEHOLDER.matcher("one {node} of the {group}").find())
            .isFalse();
        assertThat(INFLECTED_PLACEHOLDER.matcher("all {node}s").find()).isTrue();
        assertThat(INFLECTED_PLACEHOLDER.matcher("the {node}'s id").find()).isTrue();
        assertThat(INFLECTED_PLACEHOLDER.matcher("{group}-level").find()).isTrue();
        assertThat(INFLECTED_PLACEHOLDER.matcher("`<{group}>/<{node}>` and {nodes}.").find())
            .isFalse();
    }
}
