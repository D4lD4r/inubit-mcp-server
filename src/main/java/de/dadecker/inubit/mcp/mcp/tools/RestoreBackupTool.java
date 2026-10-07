package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.ImportService;
import de.dadecker.inubit.mcp.application.ImportService.Response;
import de.dadecker.inubit.mcp.application.ImportService.RestoreRequest;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.Map;
import java.util.Objects;

/**
 * {@code restore_backup} (feature 004, contracts/mcp-tools-delta.md, US2): re-imports the
 * backup of an earlier call of the development tools through {@link ImportService#restore},
 * limited to the artifacts that call changed, with the import's checks, conflict detection,
 * verification and rollback. The arguments are validated by the SDK against
 * {@code restore_backup.input.json}; the output is {@code {"challenge": …}} (the preview under
 * server confirmation) or {@code {"result": …}}. Refusals before anything is sent are tool
 * errors.
 *
 * <p>Registered only if at least one node is a development stage (FR-001).
 */
public final class RestoreBackupTool implements ToolHandler {

    static final String DESCRIPTION = "Re-import the backup taken by an earlier development"
        + " call on ONE {node}, limited to the artifacts that call changed; same checks, conflict"
        + " detection, verification and rollback.";

    private final ImportService service;

    public RestoreBackupTool(ImportService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String name() {
        return "restore_backup";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    /** Destructive, non-idempotent, open world (FR-026, Constitution I). */
    @Override
    public ToolHints annotations() {
        return ToolHints.destructive("Restore a backup on a development INUBIT node");
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        return handle(arguments, CallContext.NONE);
    }

    @Override
    public Object handle(Map<String, Object> arguments, CallContext context) {
        ToolArguments args = new ToolArguments(arguments);
        return map(service.restore(new RestoreRequest(args.string("node"),
            args.string("backupRef"), args.optionalString("reason").orElse(""),
            args.optionalString("confirmationCode"), context.client())));
    }

    /** {@code {"challenge": …}} or {@code {"result": …}}. */
    static Map<String, Object> map(Response response) {
        return switch (response) {
            case Response.Challenge challenge -> Map.of("challenge", challenge.preview());
            case Response.WriteChallenge challenge -> Map.of("challenge", challenge.preview());
            case Response.Completed completed -> Map.of("result", completed.outcome());
        };
    }
}
