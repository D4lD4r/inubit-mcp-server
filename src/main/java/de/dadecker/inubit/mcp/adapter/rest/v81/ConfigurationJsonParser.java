package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView.Leaf;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.Masked;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.ResultJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses the 8.1 {@code GET /ibis/rest/configurations} answer into a {@link ConfigurationView}
 * (feature 022, US1). The configuration is the object below the root key {@value #ROOT}, as the
 * recording of an 8.1.17 server answers (T014, fixture {@code admin-configurations.json}) and the
 * PUT body has it; {@value #DOCUMENTED_ROOT} (sic), the root key of the documented GET answer, is
 * accepted too (research D-23, "Live recording T014").
 *
 * <ul>
 *   <li>The section names are the keys of that object, in document order.
 *   <li>A section's leaves are its scalar values, flattened to {@code {path, value}}. The path is
 *       a JSON pointer below the section ({@code /SMTPProperties/ErrorHost} gives
 *       {@code /ErrorHost}; {@code ~} and {@code /} in a key become {@code ~0} and {@code ~1});
 *       array elements are addressed by index; a section that is a single value has the path
 *       {@code ""}; empty objects and arrays have no leaves. A JSON {@code null} is shown as
 *       {@code null}. A path is cut to {@link ItemBounds#MAX_FIELD_CHARS} with the truncation
 *       marker after masking, so a leaf is masked by its full path.
 *   <li>The whole configuration is masked by {@link JsonLeafMasker} first, so a section name
 *       counts as part of the path. A leaf it masked is {@link Leaf#masked}; a value that already
 *       reads {@link Masked#MASK_TOKEN} is reported as masked too. The 8.1 recording has no
 *       sibling {@code type} or {@code encrypted} fields, so no {@code type} is passed to the
 *       secret rules.
 *   <li>A value is cut to {@link ItemBounds#MAX_MESSAGE_CHARS} with the truncation marker. The
 *       leaves are bounded by the caller's result limits (its {@code ResultLimiter}'s
 *       {@code maxItems} and {@code maxChars}, by {@link ResultJson#size}); the view is
 *       {@code truncated} when leaves were left out.
 * </ul>
 */
final class ConfigurationJsonParser {

    static final String ROOT = "ServerConfiguration";
    static final String DOCUMENTED_ROOT = "SeverConfiguration";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ConfigurationJsonParser() {
    }

    /**
     * The section names if {@code section} is empty, otherwise the leaves of that section.
     *
     * @param maxValues the most leaves returned, at least 1
     * @param maxChars  the most chars the returned leaves take together as JSON
     * @throws IllegalArgumentException if {@code maxValues} is below 1, which the result
     *     limiter the limits come from never allows
     * @throws ToolErrorException {@code NOT_FOUND} (with the server id) if the configuration has
     *     no section named {@code section} (case-sensitive); {@code UNEXPECTED_RESPONSE} if the
     *     body is not a JSON object with an object below one of the two root keys
     */
    static ConfigurationView parse(NodeId server, byte[] body, Optional<String> section,
        int maxValues, int maxChars) {
        if (maxValues < 1) {
            throw new IllegalArgumentException("maxValues must be >= 1: " + maxValues);
        }
        JsonNode configuration = configuration(server, body);
        if (section.isEmpty()) {
            List<String> names = new ArrayList<>();
            configuration.properties().forEach(property -> names.add(property.getKey()));
            return ConfigurationView.sections(names);
        }
        String name = section.get();
        if (!configuration.has(name)) {
            throw notFound(server, name);
        }
        List<Leaf> leaves = new ArrayList<>();
        flatten(JsonLeafMasker.mask(configuration).masked().get(name), "", leaves);
        return bounded(name, leaves, maxValues, maxChars);
    }

    private static JsonNode configuration(NodeId server, byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            throw unexpected(server);
        }
        if (root == null || !root.isObject()) {
            throw unexpected(server);
        }
        JsonNode configuration = root.has(ROOT) ? root.get(ROOT) : root.get(DOCUMENTED_ROOT);
        if (configuration == null || !configuration.isObject()) {
            throw unexpected(server);
        }
        return configuration;
    }

    private static void flatten(JsonNode node, String path, List<Leaf> leaves) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                flatten(property.getValue(), path + "/" + escape(property.getKey()), leaves);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                flatten(node.get(index), path + "/" + index, leaves);
            }
        } else if (node.isString() && node.asString().equals(Masked.MASK_TOKEN)) {
            leaves.add(Leaf.masked(RowValues.cut(path, ItemBounds.MAX_FIELD_CHARS)));
        } else {
            String value = node.isNull() ? "null" : node.asString();
            leaves.add(Leaf.shown(RowValues.cut(path, ItemBounds.MAX_FIELD_CHARS),
                RowValues.cut(value, ItemBounds.MAX_MESSAGE_CHARS)));
        }
    }

    /** A key as a JSON pointer segment (RFC 6901). */
    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    private static ConfigurationView bounded(String section, List<Leaf> leaves, int maxValues,
        int maxChars) {
        int count = 0;
        int chars = 0;
        for (Leaf leaf : leaves) {
            int size = ResultJson.size(leaf);
            if (count == maxValues || chars + size > maxChars) {
                break;
            }
            count++;
            chars += size;
        }
        return ConfigurationView.section(section, leaves.subList(0, count),
            count < leaves.size());
    }

    private static ToolErrorException notFound(NodeId server, String section) {
        return new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
            "The configuration of " + server + " has no section '" + section + "'",
            "Section names are case-sensitive and differ between INUBIT versions",
            "List the section names with get_server_settings, part configuration, without"
                + " configurationSection")
            .withNode(server));
    }

    private static ToolErrorException unexpected(NodeId server) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
            "The INUBIT configuration of " + server + " is not a JSON object below '" + ROOT
                + "' or '" + DOCUMENTED_ROOT + "'",
            "The endpoint answered with an unexpected document (another INUBIT version, a proxy"
                + " page, or a configuration format this version does not know)",
            "Check the INUBIT version line of the server against the supported versions")
            .withNode(server));
    }
}
