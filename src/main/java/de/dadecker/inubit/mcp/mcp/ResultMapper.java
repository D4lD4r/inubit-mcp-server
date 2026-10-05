package de.dadecker.inubit.mcp.mcp;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps tool outcomes to MCP results (contracts/mcp-tools.md, Constitution VI).
 *
 * <ul>
 *   <li>Success: the payload becomes {@code structuredContent} (a JSON object) and the same JSON,
 *       compact, a single text block for clients without structured-output support. Empty
 *       {@code Optional}s and {@code null}s are omitted; ids are strings; timestamps ISO-8601.
 *   <li>Failure: a {@link ToolError} becomes {@code isError: true} with the text block
 *       {@code {"error": ToolError}}. There is deliberately no {@code structuredContent}: clients
 *       validate structured content against the tool's output schema, which an error does not
 *       match.
 *   <li>Any other exception becomes an {@code INTERNAL} error without message or stack trace;
 *       the exception is logged to stderr (scrubbed by the log encoder).
 *   <li>Every string value passes through the {@link SecretScrubber} (Constitution II) before
 *       either channel is built, so both carry the same scrubbed content. Object keys are not
 *       scrubbed: they are field names of the output schemas or INUBIT names (e.g.
 *       {@code systemInfo.raw}), and scrubbing them could merge or mangle keys.
 * </ul>
 */
public final class ResultMapper {

    private static final Logger LOG = LoggerFactory.getLogger(ResultMapper.class);

    private final SecretScrubber scrubber;
    private final JsonMapper json;

    public ResultMapper(SecretScrubber scrubber) {
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.json = ResultJson.mapper();
    }

    /** Runs {@code handler} and maps its payload or failure; never throws. */
    public CallToolResult invoke(ToolHandler handler, Map<String, Object> arguments) {
        return invoke(handler, arguments, CallContext.NONE);
    }

    /** As {@link #invoke(ToolHandler, Map)}, passing the MCP client's {@code context}. */
    public CallToolResult invoke(ToolHandler handler, Map<String, Object> arguments,
        CallContext context) {
        try {
            return success(handler.handle(arguments == null ? Map.of() : arguments,
                context == null ? CallContext.NONE : context));
        } catch (ToolErrorException e) {
            return error(e.error());
        } catch (RuntimeException e) {
            LOG.error("Tool {} failed unexpectedly", handler.name(), e);
            return error(internal("The tool " + handler.name() + " failed unexpectedly ("
                + e.getClass().getSimpleName() + ")"));
        }
    }

    /** {@code structuredContent} plus compact JSON text; {@code payload} must be a JSON object. */
    public CallToolResult success(Object payload) {
        JsonNode tree;
        try {
            tree = json.valueToTree(payload);
        } catch (JacksonException e) {
            LOG.error("Tool result cannot be serialized ({})", e.getClass().getSimpleName());
            return error(internal("The tool result cannot be serialized"));
        }
        if (tree == null || !tree.isObject()) {
            LOG.error("Tool result is not a JSON object");
            return error(internal("The tool result is not a JSON object"));
        }
        JsonNode scrubbed = scrub(tree);
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = json.treeToValue(scrubbed, Map.class);
        return CallToolResult.builder()
            .structuredContent(structured)
            .addTextContent(json.writeValueAsString(scrubbed))
            .isError(false)
            .build();
    }

    /** {@code isError: true} with the text {@code {"error": ToolError}}. */
    public CallToolResult error(ToolError error) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        ObjectNode node = payload.putObject("error");
        node.put("code", error.code().name());
        node.put("message", error.message());
        node.put("likelyCause", error.likelyCause());
        node.put("nextStep", error.nextStep());
        error.node().ifPresent(id -> node.put("node", id.value()));
        error.excerpt().ifPresent(excerpt -> node.put("excerpt", excerpt));
        return CallToolResult.builder()
            .addTextContent(json.writeValueAsString(scrub(payload)))
            .isError(true)
            .build();
    }

    private static ToolError internal(String message) {
        return ToolError.of(ErrorCode.INTERNAL, message,
            "An internal error of the INUBIT MCP server",
            "Retry; if it persists, check the MCP server log on stderr and report the problem");
    }


    /** A copy of {@code node} with every string value scrubbed. */
    private JsonNode scrub(JsonNode node) {
        if (node.isString()) {
            return JsonNodeFactory.instance.stringNode(scrubber.scrub(node.asString()));
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                copy.set(property.getKey(), scrub(property.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : node) {
                copy.add(scrub(element));
            }
            return copy;
        }
        return node;
    }
}
