package de.dadecker.inubit.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 002-T017: the schema {@code description} strings are rendered with the terminology (D-4). */
class SchemaRendererTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRenderer RENDERER = new SchemaRenderer(new ProfileInfo("acme",
        Optional.empty(), new Terminology("Umgebung", "Umgebungen", "Knoten", "Knoten"),
        "INUBIT_ACME"));

    @Test
    void descriptionsAreRenderedAtEveryDepthAndEverythingElseIsKept() {
        String rendered = RENDERER.render("""
            {"type": "object", "description": "{Nodes} of one {group}",
             "properties": {
               "target": {"type": "string", "pattern": "^[a-z]{0,31}$",
                          "description": "Id of one {node} `<{group}>/<{node}>`"},
               "list": {"type": "array", "items": [{"description": "one {node}"}]},
               "description": {"type": "string", "description": "a property named description"}
             }}
            """);

        JsonNode schema = JSON.readTree(rendered);
        assertThat(schema.path("description").asString()).isEqualTo("Knoten of one Umgebung");
        JsonNode target = schema.path("properties").path("target");
        assertThat(target.path("description").asString())
            .isEqualTo("Id of one Knoten `<Umgebung>/<Knoten>`");
        assertThat(target.path("pattern").asString()).isEqualTo("^[a-z]{0,31}$");
        assertThat(schema.path("properties").path("list").path("items").path(0)
            .path("description").asString()).isEqualTo("one Knoten");
        assertThat(schema.path("properties").path("description").path("type").asString())
            .isEqualTo("string");
        assertThat(schema.path("properties").propertyNames())
            .containsExactly("target", "list", "description");
    }

    @Test
    void anUnknownPlaceholderFailsTheStart() {
        assertThatIllegalStateException()
            .isThrownBy(() -> RENDERER.render("{\"description\": \"a {stage}\"}"))
            .withMessageContaining("$.description", "{stage}");
    }

    @Test
    void aPlaceholderOutsideADescriptionFailsTheStart() {
        assertThatIllegalStateException()
            .isThrownBy(() -> RENDERER.render("{\"enum\": [\"a\", \"{node}\"]}"))
            .withMessageContaining("$.enum[1]", "outside a description");
    }
}
