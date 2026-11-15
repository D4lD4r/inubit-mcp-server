package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.nodetools.ResetLevelRequest;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.node.LogLevelResult;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@code reset_temporary_log_level} (feature 024, FR-044, contracts/mcp-tools-delta.md, research
 * D-15, D-25): resets every temporary log level on the INUBIT node one server reaches, or forgets a
 * lease whose automatic reset failed ({@code LogLevelService.reset}). The input schema admits
 * exactly the two forms ({@code node}, or {@code leaseId} with {@code forget: true}); the
 * arguments go to the service with the MCP client's {@code name/version} for the audit, and the
 * service repeats every check. The output is {@code {"challenge": …}} or {@code {"result": …}} as
 * for {@code restart_process}; refusals are tool errors.
 *
 * <p>Offered only for a server with effective write access and {@code unsupported.logLevelWrite}
 * (research D-6).
 */
public final class ResetTemporaryLogLevelTool implements ToolHandler {

    static final String DESCRIPTION = "Resets every temporary log level on the INUBIT node one"
        + " {node} reaches through StartCLI's documented trace --reset command (every temporary log"
        + " level on that node, also those other users set), and ends the MCP server's lease for"
        + " it; works also without a lease (to clean up an earlier change). With leaseId and"
        + " forget: true it instead ends a lease whose automatic reset failed, without contacting"
        + " INUBIT; the level may then stay raised until that node restarts. Confirmation as"
        + " restart_process. UNSUPPORTED: uses unsupported, undocumented INUBIT functionality (the"
        + " lookup of the answering node goes through an internal INUBIT interface) without vendor"
        + " support or stability guarantee, for diagnosis only; it may stop working after any"
        + " INUBIT update. Needs write access and unsupported.logLevelWrite. Address cluster nodes"
        + " directly, not through a load balancer.";

    private final Function<ResetLevelRequest, LogLevelResult> reset;

    /** @param reset the reset and forget of the profile ({@code LogLevelService::reset}) */
    public ResetTemporaryLogLevelTool(Function<ResetLevelRequest, LogLevelResult> reset) {
        this.reset = Objects.requireNonNull(reset, "reset");
    }

    @Override
    public String name() {
        return "reset_temporary_log_level";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    /** Destructive, non-idempotent, open world, as {@code restart_process} (research D-25). */
    @Override
    public ToolHints annotations() {
        return ToolHints.destructive("Reset temporary INUBIT log levels");
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        return handle(arguments, CallContext.NONE);
    }

    @Override
    public Object handle(Map<String, Object> arguments, CallContext context) {
        ToolArguments args = new ToolArguments(arguments);
        LogLevelResult result = reset.apply(new ResetLevelRequest(args.optionalString("node"),
            args.optionalString("leaseId"), args.optionalString("forget").map(Boolean::valueOf),
            args.optionalString("confirmationCode"), context.client()));
        return switch (result) {
            case LogLevelResult.Challenge challenge ->
                Map.of("challenge", LogLevelPayloads.Challenge.of(challenge));
            case LogLevelResult.Outcome outcome -> Map.of("result", Result.of(outcome));
        };
    }

    /**
     * {@code result}: whether a lease was open when the call ran; no loggers and no expiry
     * (contracts "Output").
     */
    record Result(NodeId node, String inubitNode, LogLevelResult.Status outcome,
        Optional<UUID> leaseId, boolean leaseOpen, String message, UUID auditId,
        List<String> warnings) {

        static Result of(LogLevelResult.Outcome outcome) {
            return new Result(outcome.node(), outcome.inubitNode(), outcome.outcome(),
                outcome.leaseId(), outcome.leaseOpen(), outcome.message(), outcome.auditId(),
                outcome.warnings());
        }
    }
}
