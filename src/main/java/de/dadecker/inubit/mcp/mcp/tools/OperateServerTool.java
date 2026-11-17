package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.ChangeChallenges;
import de.dadecker.inubit.mcp.application.ServerOperationService;
import de.dadecker.inubit.mcp.application.ServerOperationService.Request;
import de.dadecker.inubit.mcp.application.ServerOperationService.Response;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code operate_server} (feature 022, US4, contracts/mcp-tools-delta.md "operate_server",
 * research D-22): switches maintenance mode, readiness, trace, trace level or debug mode of one
 * server, triggers its midnight task or deletes its processes in state ERROR
 * ({@code ServerOperationService::execute}). The arguments, validated by the SDK against the input
 * schema, go to the service with the MCP client's {@code name/version} for the audit; the service
 * repeats every input check. The output is the service's {@link Response}:
 * {@code {"preview": ChangePreview}} or {@code {"result": ChangeResult, "warnings": […]}}.
 * Refusals are tool errors; a {@code CONFLICT} carries the fresh preview as
 * {@code error.details.preview} (contracts "Errors").
 *
 * <p>Announced if some server has write access.
 */
public final class OperateServerTool implements ToolHandler {

    static final String DESCRIPTION = "Operate ONE INUBIT {node} (`node`:"
        + " `<{group}>/<{node}>`) with one `action`: setMaintenance (`on`; with `on: true`"
        + " optionally `executeQueued`, sent as -eq: whether the processes already queued are"
        + " still executed), setReadiness (`ready`: what the readiness probe reports to a load"
        + " balancer, READY or NOT READY, without stopping the process engine), setTrace (`on`),"
        + " setTraceLevel (`level`), setDebug (`on`), triggerMidnightTask, deleteErrorProcesses."
        + " In maintenance mode the process engine executes no processes and INUBIT's REST API"
        + " answers every request with \"service unavailable\"; the readiness stays NOT READY"
        + " while maintenance mode is on. Tracing, the trace level and debug output stay as set"
        + " until they are set back. The midnight task is the daily task INUBIT otherwise starts"
        + " at its configured start time; triggering it runs it at once, and the vendor does not"
        + " document what it does. deleteErrorProcesses deletes every process in state ERROR;"
        + " this cannot be undone. Every call needs a `reason` (stored in the audit log). On"
        + " {nodes} with `confirmationMode` `SERVER` (the default), the first call only returns a"
        + " preview (state before, requested state, effects) and a confirmation code; call again"
        + " with the same inputs and `confirmationCode` to execute. If the state changed"
        + " meanwhile, the CONFLICT error carries a new preview in its details. With `CLIENT`"
        + " (never on production) the first call executes. The result re-reads the state. Needs"
        + " write access; production only with write.productionOptIn.";

    /** The inputs of every action; the other arguments are the fields of the action. */
    private static final Set<String> COMMON_INPUTS = Set.of("node", "action", "reason",
        "confirmationCode");

    private final Function<Request, Response> execute;

    /** @param execute the operation of the profile ({@code ServerOperationService::execute}) */
    public OperateServerTool(Function<Request, Response> execute) {
        this.execute = Objects.requireNonNull(execute, "execute");
    }

    @Override
    public String name() {
        return ServerOperationService.TOOL;
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    /** Destructive, non-idempotent, open world (contracts "Annotations"). */
    @Override
    public ToolHints annotations() {
        return ToolHints.destructive("Operate an INUBIT {node}");
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        return handle(arguments, CallContext.NONE);
    }

    @Override
    public Object handle(Map<String, Object> arguments, CallContext context) {
        ToolArguments args = new ToolArguments(arguments);
        Map<String, Object> fields = new LinkedHashMap<>(arguments);
        fields.keySet().removeAll(COMMON_INPUTS);
        try {
            return execute.apply(new Request(args.string("node"), args.string("action"), fields,
                args.string("reason"), args.optionalString("confirmationCode"),
                context.client()));
        } catch (ChangeChallenges.Conflict conflict) {
            throw new ToolErrorException(conflict.error(), Map.of("preview", conflict.preview()));
        }
    }
}
