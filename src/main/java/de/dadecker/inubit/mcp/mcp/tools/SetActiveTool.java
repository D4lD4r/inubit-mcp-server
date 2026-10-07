package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.ImportService;
import de.dadecker.inubit.mcp.application.ImportService.ActivationRequest;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.Map;
import java.util.Objects;

/**
 * {@code set_active} (feature 004, contracts/mcp-tools-delta.md, US3): activates or
 * deactivates one workflow through {@link ImportService#setActive}, which sends only that
 * workflow, built from the server's current state. The arguments are validated by the SDK
 * against {@code set_active.input.json}; the output is {@code {"challenge": …}} (the preview
 * under server confirmation) or {@code {"result": …}}. Refusals before anything is sent are
 * tool errors.
 *
 * <p>Registered only if at least one node is a development stage (FR-001).
 */
public final class SetActiveTool implements ToolHandler {

    static final String DESCRIPTION = "Activate or deactivate ONE workflow on ONE development"
        + " {node} (INUBIT creates a new version).";

    private final ImportService service;

    public SetActiveTool(ImportService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String name() {
        return "set_active";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    /** Destructive, non-idempotent, open world (FR-026, Constitution I). */
    @Override
    public ToolHints annotations() {
        return ToolHints.destructive("Activate or deactivate a workflow on a development INUBIT"
            + " node");
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        return handle(arguments, CallContext.NONE);
    }

    @Override
    public Object handle(Map<String, Object> arguments, CallContext context) {
        ToolArguments args = new ToolArguments(arguments);
        return RestoreBackupTool.map(service.setActive(new ActivationRequest(
            args.string("node"), args.optionalString("owner"), args.string("diagramGroup"),
            args.string("workflow"), args.flag("active"),
            args.optionalString("reason").orElse(""), args.optionalString("confirmationCode"),
            context.client())));
    }
}
