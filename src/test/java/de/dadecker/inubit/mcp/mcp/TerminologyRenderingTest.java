package de.dadecker.inubit.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * 002-T012: the announced tool contract with the production wiring and a custom terminology
 * (FR-003, FR-007, SC-006, research D-4). Every tool description starts with the profile prefix;
 * every description, title and schema {@code description} is rendered (no placeholder left) and
 * uses only the configured display names.
 *
 * <p>Level words are checked case-insensitively on word boundaries: the previous names
 * {@code stage(s)}/{@code server(s)} and the default names {@code group(s)}/{@code node(s)}.
 * Code spans ({@code `…`}) are checked too, except the reviewed field-name spans of
 * {@link #FIELD_SPANS}; {@link #ALLOWED} lists the phrases where such a word means something else
 * than a configured level (002 review U3). Every entry of both lists is reviewed.
 */
@Timeout(60)
class TerminologyRenderingTest {

    /**
     * Reviewed phrases that keep a level word because they do not name a configured level.
     * Matched case-insensitively and removed before the check.
     */
    private static final List<String> ALLOWED = List.of(
        // the product this process is
        "MCP server",
        // INUBIT's own grouping of diagrams and modules (002 review G2), not the configured group
        "diagram/module group",
        "diagram group",
        "module group",
        // a Workbench user group that owns diagrams and modules (inventory.owner)
        "user group",
        // the elements of an INUBIT workflow that reference a module
        "workflow nodes");

    /**
     * Code spans that name a machine-readable input or result field, a tool or an enum value
     * (FR-008: the same for every profile). Removed before the check, exactly as written.
     */
    private static final List<String> FIELD_SPANS = List.of("`target`", "`node`", "`group`",
        "`query_logs`", "`text`", "`%`", "`_`", "`confirmationMode`", "`SERVER`");

    private static final Pattern LEVEL_WORD =
        Pattern.compile("(?i)\\b(stages?|servers?|groups?|nodes?)\\b");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\p{Alpha}+\\}");

    private static final String YAML = """
        profile:
          name: acme
        %s
        terminology:
          group: { singular: Umgebung, plural: Umgebungen }
          node: { singular: Knoten, plural: Knoten }
        groups:
          - name: test
            write:
              enabled: true
            nodes:
              - name: node1
                baseUrl: https://node1.test.example.test:8443
              - name: node2
                baseUrl: https://node2.test.example.test:8443
        """;

    private static JsonNode tools(String description) {
        String yaml = YAML.formatted(description.isEmpty() ? ""
            : "  description: \"" + description + "\"");
        ProfileConfig config = new ConfigLoader(Map.of(), Path.of("/home/test"), false)
            .parse(yaml, Path.of("/home/test/acme.yaml")).config();
        SecretScrubber scrubber = new SecretScrubber();
        CredentialResolution credentials = new CredentialResolver(Map.of(
            "INUBIT_ACME_TEST_USERNAME", "user", "INUBIT_ACME_TEST_PASSWORD", "rendering-test-pw"),
            scrubber, config.credentialPrefix()).resolve(config.nodeIds());
        try (TestWiring wiring = TestWiring.of(config, credentials, scrubber, path -> false,
                false);
            McpTestClient client = McpTestClient.start(wiring.serverFactory("0.0.0-test"))) {
            client.initialize();
            return client.listTools().path("tools");
        }
    }

    /** Every {@code description} string anywhere below {@code schema}. */
    private static void schemaDescriptions(JsonNode schema, String path, List<String> out) {
        if (schema.isObject()) {
            schema.properties().forEach(entry -> {
                if (entry.getKey().equals("description") && entry.getValue().isString()) {
                    out.add(path + ": " + entry.getValue().asString());
                } else {
                    schemaDescriptions(entry.getValue(), path + "." + entry.getKey(), out);
                }
            });
        } else if (schema.isArray()) {
            for (int i = 0; i < schema.size(); i++) {
                schemaDescriptions(schema.get(i), path + "[" + i + "]", out);
            }
        }
    }

    /**
     * The text of a {@code "<path>: <text>"} entry with code spans and the allowed phrases
     * removed.
     */
    private static String withoutAllowed(String entry) {
        String rest = entry.substring(entry.indexOf(": ") + 2);
        for (String span : FIELD_SPANS) {
            rest = rest.replace(span, " ");
        }
        for (String phrase : ALLOWED) {
            rest = allowed(phrase).matcher(rest).replaceAll(" ");
        }
        return rest;
    }

    /** The phrase on word boundaries, ignoring case. */
    private static Pattern allowed(String phrase) {
        return Pattern.compile("\\b" + Pattern.quote(phrase) + "\\b", Pattern.CASE_INSENSITIVE);
    }

    private static List<String> texts(JsonNode tools) {
        List<String> texts = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asString();
            texts.add(name + ".description: " + tool.path("description").asString());
            texts.add(name + ".title: " + tool.path("title").asString());
            texts.add(name + ".annotations.title: "
                + tool.path("annotations").path("title").asString());
            schemaDescriptions(tool.path("inputSchema"), name + ".inputSchema", texts);
            schemaDescriptions(tool.path("outputSchema"), name + ".outputSchema", texts);
        }
        return texts;
    }

    @Test
    void allNineToolDescriptionsStartWithTheProfilePrefix() {
        JsonNode tools = tools("ACME test");

        assertThat(tools).hasSize(9);
        for (JsonNode tool : tools) {
            assertThat(tool.path("description").asString())
                .as(tool.path("name").asString()).startsWith("[acme: ACME test] ");
        }
    }

    @Test
    void withoutDescriptionThePrefixIsTheProfileName() {
        JsonNode tools = tools("");

        assertThat(tools).hasSize(9);
        for (JsonNode tool : tools) {
            assertThat(tool.path("description").asString())
                .as(tool.path("name").asString()).startsWith("[acme] ")
                .doesNotStartWith("[acme: ");
        }
    }

    @Test
    void noPlaceholderIsLeftAnywhereInTheToolList() {
        JsonNode tools = tools("ACME test");

        assertThat(PLACEHOLDER.matcher(tools.toString()).find())
            .as("placeholder in %s", tools).isFalse();
    }

    @Test
    void descriptionsTitlesAndSchemaDescriptionsUseOnlyTheConfiguredTerms() {
        List<String> texts = texts(tools("ACME test"));

        assertThat(texts).hasSizeGreaterThan(60);
        assertThat(texts).as("level words outside code spans and the allowed phrases")
            .filteredOn(text -> LEVEL_WORD.matcher(withoutAllowed(text)).find())
            .isEmpty();
    }

    @Test
    void theConfiguredTermsAreUsedExactlyAsConfigured() {
        String all = String.join("\n", texts(tools("ACME test")));

        assertThat(all).as("002 review U1/R1").contains("Knoten", "Umgebung", "Umgebungen",
            "`<Umgebung>/<Knoten>`").doesNotContain("knoten", "umgebung");
    }

    @Test
    void theAllowListsAreNeededEntryByEntry() {
        String all = String.join("\n", texts(tools("ACME test")));

        assertThat(ALLOWED).as("an unused allowed phrase is removed from the list")
            .allSatisfy(phrase -> assertThat(allowed(phrase).matcher(all).find()).isTrue());
        assertThat(FIELD_SPANS).as("an unused field span is removed from the list")
            .allSatisfy(span -> assertThat(all).contains(span));
    }

    @Test
    void aDescriptionWithBracesAppearsVerbatimInThePrefix() {
        JsonNode tools = tools("Test {node} profile");

        for (JsonNode tool : tools) {
            assertThat(tool.path("description").asString())
                .as("002 review U3: the description is no template")
                .startsWith("[acme: Test {node} profile] ");
        }
    }

    @Test
    void theListInventoryGroupFilterMeansTheInubitGroup() {
        JsonNode listInventory = null;
        for (JsonNode tool : tools("ACME test")) {
            if (tool.path("name").asString().equals("list_inventory")) {
                listInventory = tool;
            }
        }

        assertThat(listInventory).isNotNull();
        assertThat(listInventory.path("inputSchema").path("properties").path("group")
            .path("description").asString())
            .as("002 review G2")
            .isEqualTo("INUBIT diagram/module group (not the configured Umgebung)");
    }
}
