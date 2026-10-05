package de.dadecker.inubit.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SchemaResourcesTest {

    private final SchemaResources schemas = SchemaResources.forClasspath();

    @Test
    void resourceNamesFollowTheToolName() {
        assertThat(SchemaResources.inputResource("get_health"))
            .isEqualTo("schemas/get_health.input.json");
        assertThat(SchemaResources.outputResource("get_health"))
            .isEqualTo("schemas/get_health.output.json");
    }

    @Test
    void loadsASchemaFromTheClasspath() {
        String schema = schemas.load(SchemaResources.inputResource(EchoTestTool.NAME));

        assertThat(schema).contains("\"additionalProperties\": false", "\"message\"");
    }

    @Test
    void aMissingSchemaFailsNamingTheResource() {
        assertThatThrownBy(() -> schemas.load("schemas/no_such_tool.input.json"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("schemas/no_such_tool.input.json");
    }

    @Test
    void aSchemaMustBeAJsonObject() {
        assertThatThrownBy(() -> schemas.load("schemas/broken/array.json"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("schemas/broken/array.json");
        assertThatThrownBy(() -> schemas.load("schemas/broken/malformed.json"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("schemas/broken/malformed.json");
    }
}
