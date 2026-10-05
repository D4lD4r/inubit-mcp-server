package de.dadecker.inubit.mcp.config;

import java.util.HashMap;
import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLParser;

/**
 * Builds a Jackson tree from YAML and resolves anchors and aliases ({@code &a} / {@code *a}),
 * which Jackson's YAML parser only reports as object ids. Anchors are supported on mappings and
 * sequences; the parser does not report anchors on scalars, so an alias to one is "undefined".
 * Duplicate keys are rejected, and the number of nodes is bounded so that alias expansion cannot
 * explode ("billion laughs").
 */
final class YamlTreeReader {

    static final int MAX_NODES = 100_000;

    private final YAMLMapper mapper;
    private final JsonNodeFactory nodes = JsonNodeFactory.instance;

    YamlTreeReader(YAMLMapper mapper) {
        this.mapper = mapper;
    }

    /** Reads the first document; returns {@code null} for an empty document. */
    JsonNode read(String yaml) {
        try (JsonParser parser = mapper.createParser(yaml)) {
            JsonToken token = parser.nextToken();
            if (token == null) {
                return null;
            }
            JsonNode root = new Session((YAMLParser) parser).value(token);
            if (parser.nextToken() != null) {
                throw new YamlStructureException("Only one YAML document is allowed (found '---')");
            }
            return root;
        }
    }

    private final class Session {

        private final YAMLParser parser;
        private final Map<String, JsonNode> anchors = new HashMap<>();
        private int nodeCount;

        Session(YAMLParser parser) {
            this.parser = parser;
        }

        JsonNode value(JsonToken token) {
            String anchor = token == JsonToken.VALUE_STRING && parser.isCurrentAlias()
                ? null
                : parser.getObjectId();
            JsonNode node = switch (token) {
                case START_OBJECT -> object();
                case START_ARRAY -> array();
                case VALUE_STRING -> parser.isCurrentAlias() ? alias(parser.getString()) : count(
                    nodes.stringNode(parser.getString()));
                case VALUE_NUMBER_INT -> count(switch (parser.getNumberType()) {
                    case INT -> nodes.numberNode(parser.getIntValue());
                    case LONG -> nodes.numberNode(parser.getLongValue());
                    default -> nodes.numberNode(parser.getBigIntegerValue());
                });
                case VALUE_NUMBER_FLOAT -> count(nodes.numberNode(parser.getDecimalValue()));
                case VALUE_TRUE -> count(nodes.booleanNode(true));
                case VALUE_FALSE -> count(nodes.booleanNode(false));
                case VALUE_NULL -> count(nodes.nullNode());
                default -> throw error("Unsupported YAML content");
            };
            if (anchor != null) {
                anchors.put(anchor, node);
            }
            return node;
        }

        private ObjectNode object() {
            ObjectNode object = count(nodes.objectNode());
            JsonToken token;
            while ((token = parser.nextToken()) == JsonToken.PROPERTY_NAME) {
                String key = parser.currentName();
                if ("<<".equals(key)) {
                    throw error("Merge keys ('<<') are not supported; put an anchor on the whole"
                        + " mapping instead (e.g. tls: *shared)");
                }
                if (object.has(key)) {
                    throw error("Duplicate key '" + key + "'");
                }
                object.set(key, value(parser.nextToken()));
            }
            expect(token, JsonToken.END_OBJECT);
            return object;
        }

        private ArrayNode array() {
            ArrayNode array = count(nodes.arrayNode());
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
                if (token == null) {
                    throw error("Unexpected end of YAML");
                }
                array.add(value(token));
            }
            return array;
        }

        private JsonNode alias(String name) {
            JsonNode target = anchors.get(name);
            if (target == null) {
                throw error("Undefined alias '*" + name + "' (anchors are supported on mappings"
                    + " and sequences only)");
            }
            JsonNode copy = target.deepCopy();
            nodeCount += size(copy);
            checkNodeCount();
            return copy;
        }

        private <T extends JsonNode> T count(T node) {
            nodeCount++;
            checkNodeCount();
            return node;
        }

        private void checkNodeCount() {
            if (nodeCount > MAX_NODES) {
                throw error("YAML document too large (more than " + MAX_NODES + " nodes)");
            }
        }

        private void expect(JsonToken actual, JsonToken expected) {
            if (actual != expected) {
                throw error("Unexpected YAML structure");
            }
        }

        private YamlStructureException error(String message) {
            TokenStreamLocation location = parser.currentTokenLocation();
            return new YamlStructureException(message + " at line " + location.getLineNr()
                + ", column " + location.getColumnNr());
        }
    }

    private static int size(JsonNode node) {
        int size = 1;
        for (JsonNode child : node) {
            size += size(child);
        }
        return size;
    }

    /** A structural problem found while building the tree; the message holds no values. */
    static final class YamlStructureException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        YamlStructureException(String message) {
            super(message);
        }
    }
}
