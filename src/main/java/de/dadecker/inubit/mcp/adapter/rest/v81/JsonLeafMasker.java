package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.domain.model.Masked;
import de.dadecker.inubit.mcp.domain.model.SecretRules;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Masks the leaves of a JSON tree from INUBIT by {@link SecretRules} (feature 022 research D-19,
 * FR-010). A leaf is replaced by {@link Masked#MASK_TOKEN} when
 * <ul>
 * <li>its name or any name on its path is a secret name (an array element has the name of its
 * array), or</li>
 * <li>an object on its path has an {@code encrypted} flag that is {@code true} (boolean or text,
 * any case, surrounding white space ignored); the flag itself stays, or</li>
 * <li>its value is key material.</li>
 * </ul>
 * {@code null} leaves stay. The input tree is not changed.
 */
final class JsonLeafMasker {

    private static final String ENCRYPTED = "encrypted";
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** The masked copy and the number of masked leaves. */
    record Result(JsonNode masked, int maskedCount) {
    }

    private JsonLeafMasker() {
    }

    static Result mask(JsonNode tree) {
        int[] count = {0};
        JsonNode masked = copy(tree, "", "", false, count);
        return new Result(masked, count[0]);
    }

    private static JsonNode copy(JsonNode node, String name, String path, boolean encrypted,
        int[] count) {
        if (node.isObject()) {
            boolean flagged = encrypted || isTrue(node.get(ENCRYPTED));
            ObjectNode copy = NODES.objectNode();
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                String key = property.getKey();
                JsonNode value = property.getValue();
                boolean flag = key.equals(ENCRYPTED) && value.isValueNode();
                copy.set(key, copy(value, key, path + "/" + key, flagged && !flag, count));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = NODES.arrayNode();
            for (JsonNode element : node) {
                copy.add(copy(element, name, path, encrypted, count));
            }
            return copy;
        }
        if (node.isNull() || !SecretRules.isSecretPath(path)
            && !SecretRules.masks(name, Optional.empty(), encrypted, node.asString())) {
            return node;
        }
        count[0]++;
        return NODES.stringNode(Masked.MASK_TOKEN);
    }

    private static boolean isTrue(JsonNode flag) {
        return flag != null && flag.isValueNode()
            && "true".equalsIgnoreCase(flag.asString().strip());
    }
}
