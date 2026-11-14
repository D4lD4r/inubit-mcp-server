package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.nodetools.SetLevelRequest;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.node.LogLevelResult;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@code set_temporary_log_level} (feature 024, FR-040 … FR-043, contracts/mcp-tools-delta.md,
 * research D-14, D-25): raises the log level of one to five loggers on the INUBIT node one server
 * reaches for a limited time ({@code LogLevelService.set}). The arguments, validated by the SDK
 * against the input schema, go to the service with the MCP client's {@code name/version} for the
 * audit; the service repeats every input check. The output is {@code {"challenge": …}} or
 * {@code {"result": …}} as for {@code restart_process}; refusals are tool errors.
 *
 * <p>Offered only for a server with effective write access and {@code unsupported.logLevelWrite}
 * (research D-6).
 */
public final class SetTemporaryLogLevelTool implements ToolHandler {

    static final String DESCRIPTION = "Raises the log level of one to five INUBIT loggers (names"
        + " below com.inubit.ibis) on the INUBIT node one {node} reaches, for a limited time"
        + " (default 10 minutes, at most 60), through StartCLI's documented trace -nl command; then"
        + " download the trace log with download_server_log. The MCP server records a lease and"
        + " resets the node when it expires, when the MCP server shuts down, or at its next start,"
        + " also when write access is switched off meanwhile. The reset (StartCLI trace -r)"
        + " restores every temporary log level on that node, also those other users set; levels"
        + " can also be lost earlier (INUBIT restart, log configuration reload). Only the exactly"
        + " named logger changes. Confirmation as restart_process. UNSUPPORTED: uses unsupported,"
        + " undocumented INUBIT functionality (the lookup of the answering node goes through an"
        + " internal INUBIT interface) without vendor support or stability guarantee, for"
        + " diagnosis only; it may stop working after any INUBIT update. Needs write access and"
        + " unsupported.logLevelWrite. Address cluster nodes directly, not through a load"
        + " balancer.";

    private final Function<SetLevelRequest, LogLevelResult> set;

    /** @param set the log-level change of the profile ({@code LogLevelService::set}) */
    public SetTemporaryLogLevelTool(Function<SetLevelRequest, LogLevelResult> set) {
        this.set = Objects.requireNonNull(set, "set");
    }

    @Override
    public String name() {
        return "set_temporary_log_level";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    /** Destructive, non-idempotent, open world, as {@code restart_process} (research D-25). */
    @Override
    public ToolHints annotations() {
        return ToolHints.destructive("Raise INUBIT log levels temporarily");
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        return handle(arguments, CallContext.NONE);
    }

    @Override
    public Object handle(Map<String, Object> arguments, CallContext context) {
        ToolArguments args = new ToolArguments(arguments);
        OptionalInt durationMinutes = args.optionalInteger("durationMinutes")
            .map(OptionalInt::of).orElse(OptionalInt.empty());
        LogLevelResult result = set.apply(new SetLevelRequest(args.string("node"),
            args.strings("loggers"), args.string("level"), durationMinutes,
            args.optionalString("confirmationCode"), context.client()));
        return switch (result) {
            case LogLevelResult.Challenge challenge ->
                Map.of("challenge", LogLevelPayloads.Challenge.of(challenge));
            case LogLevelResult.Outcome outcome -> Map.of("result", Result.of(outcome));
        };
    }

    /**
     * {@code result}: the lease's loggers and, while it is open, its expiry (contracts "Output").
     */
    record Result(NodeId node, String inubitNode, LogLevelResult.Status outcome,
        Optional<UUID> leaseId, List<LogLevelPayloads.Logger> loggers, Optional<Instant> expiresAt,
        String message, UUID auditId, List<String> warnings) {

        static Result of(LogLevelResult.Outcome outcome) {
            return new Result(outcome.node(), outcome.inubitNode(), outcome.outcome(),
                outcome.leaseId(), LogLevelPayloads.Logger.of(outcome.loggers()),
                outcome.expiresAt(), outcome.message(), outcome.auditId(), outcome.warnings());
        }
    }
}
