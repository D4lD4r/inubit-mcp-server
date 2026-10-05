package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.ProcessControlService.ControlRequest;
import de.dadecker.inubit.mcp.application.ProcessControlService.Response;
import de.dadecker.inubit.mcp.application.ProcessControlService;
import de.dadecker.inubit.mcp.domain.model.ProcessAction;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.Map;
import java.util.Objects;

/**
 * The common part of {@code restart_process} and {@code kill_process}
 * (contracts/mcp-tools.md §7–8, US4): the arguments, validated by the SDK against the input
 * schema, go to {@link ProcessControlService} together with the MCP client's
 * {@code name/version} for the audit; the output is {@code {"challenge": …}} or
 * {@code {"result": …}}. Refusals are tool errors.
 *
 * <p>Registered only if at least one server has effective write access (Story 4 / AS 6).
 */
abstract class ProcessControlTool implements ToolHandler {

    private final ProcessControlService service;
    private final ProcessAction action;

    ProcessControlTool(ProcessControlService service, ProcessAction action) {
        this.service = Objects.requireNonNull(service, "service");
        this.action = Objects.requireNonNull(action, "action");
    }

    @Override
    public final String name() {
        return action.capability();
    }

    /** Destructive, non-idempotent, open world (FR-021, Constitution I). */
    @Override
    public final ToolHints annotations() {
        return ToolHints.destructive(title());
    }

    abstract String title();

    @Override
    public final Object handle(Map<String, Object> arguments) {
        return handle(arguments, CallContext.NONE);
    }

    @Override
    public final Object handle(Map<String, Object> arguments, CallContext context) {
        ToolArguments args = new ToolArguments(arguments);
        Response response = service.execute(new ControlRequest(action, args.string("node"),
            args.string("processId"), args.optionalString("confirmationCode"),
            args.optionalString("reason"), context.client()));
        return switch (response) {
            case Response.Challenge challenge -> Map.of("challenge", challenge.challenge());
            case Response.Completed completed -> Map.of("result", completed.result());
        };
    }
}
