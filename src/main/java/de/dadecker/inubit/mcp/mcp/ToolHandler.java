package de.dadecker.inubit.mcp.mcp;

import java.util.Map;

/**
 * One MCP tool (contracts/mcp-tools.md). {@link McpServerFactory} registers it with the SDK, which
 * validates the arguments against the input schema before {@link #handle} is called and the
 * structured result against the output schema afterwards; {@link ResultMapper} turns the returned
 * payload or a {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException} into the MCP
 * result.
 *
 * <p>Handlers live in {@code mcp.tools} and delegate to application services; they never talk to
 * INUBIT directly (Constitution V).
 */
public interface ToolHandler {

    /** The tool name, e.g. {@code get_health}; also selects the schema resources. */
    String name();

    /** The description the model reads to choose the tool (Constitution IV). */
    String descriptionText();

    /** Classpath resource of the input schema; default {@code schemas/<name>.input.json}. */
    default String inputSchemaResource() {
        return SchemaResources.inputResource(name());
    }

    /** Classpath resource of the output schema; default {@code schemas/<name>.output.json}. */
    default String outputSchemaResource() {
        return SchemaResources.outputResource(name());
    }

    /** Title and read-only / destructive / idempotent / open-world hints. */
    ToolHints annotations();

    /**
     * Runs the tool.
     *
     * @param arguments the validated arguments (empty if the client sent none)
     * @return the payload: a record or map that serializes to a JSON object matching the output
     *     schema
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException for an actionable
     *     failure of the whole call (per-server failures belong into the payload)
     */
    Object handle(Map<String, Object> arguments);

    /**
     * Runs the tool with what is known about the calling MCP client (e.g. for the audit log of
     * the write tools); by default the context is ignored.
     *
     * @see #handle(Map)
     */
    default Object handle(Map<String, Object> arguments, CallContext context) {
        return handle(arguments);
    }
}
