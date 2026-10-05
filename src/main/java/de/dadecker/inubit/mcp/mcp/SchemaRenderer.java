package de.dadecker.inubit.mcp.mcp;

import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders the {@code description} strings of a tool schema with the profile's terminology
 * (002 research D-4): placeholders such as {@code {node}} are allowed there only. A placeholder
 * in any other string (e.g. an enum value) is a programming error and fails the server start,
 * as does an unknown placeholder.
 */
final class SchemaRenderer {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DESCRIPTION = "description";

    private final ProfileInfo profile;

    SchemaRenderer(ProfileInfo profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    /**
     * Returns {@code schema} (JSON text) with every {@code description} string rendered.
     *
     * @throws IllegalStateException if a placeholder is unknown or outside a description
     */
    String render(String schema) {
        JsonNode tree = JSON.readTree(schema);
        renderNode(tree, "$");
        return JSON.writeValueAsString(tree);
    }

    private void renderNode(JsonNode node, String path) {
        if (node instanceof ObjectNode object) {
            for (String name : List.copyOf(object.propertyNames())) {
                String childPath = path + "." + name;
                JsonNode value = object.get(name);
                if (name.equals(DESCRIPTION) && value.isString()) {
                    object.put(name, renderText(value.asString(), childPath));
                } else {
                    renderNode(value, childPath);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                renderNode(array.get(i), path + "[" + i + "]");
            }
        } else if (node.isString() && !Terminology.placeholders(node.asString()).isEmpty()) {
            throw new IllegalStateException("Placeholder outside a description at " + path
                + ": " + node.asString());
        }
    }

    private String renderText(String text, String path) {
        try {
            return profile.render(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Schema description at " + path + ": "
                + e.getMessage(), e);
        }
    }
}
