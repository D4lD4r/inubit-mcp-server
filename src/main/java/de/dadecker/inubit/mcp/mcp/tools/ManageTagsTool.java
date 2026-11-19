package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.TagAdministrationService;
import de.dadecker.inubit.mcp.application.TagAdministrationService.Request;
import de.dadecker.inubit.mcp.application.TagAdministrationService.Response;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code manage_tags} (feature 022, US4, FR-019a, contracts/mcp-tools-delta.md "manage_tags",
 * research D-18): sets or removes the active tag of an INUBIT user on one server
 * ({@code TagAdministrationService::execute}). The arguments, validated by the SDK against the
 * input schema, go to the service with the MCP client's {@code name/version} for the audit; the
 * service repeats every input check. The output is the service's {@link Response}:
 * {@code {"preview": ChangePreview}} or {@code {"result": ChangeResult, "warnings": […]}}.
 * Refusals are tool errors; a {@code CONFLICT} carries the fresh preview as
 * {@code error.details.preview}.
 *
 * <p>The description says that tag removal and deletion are not offered and why (excluded after
 * the live check T055, research D-18). Announced if some server has write access.
 */
public final class ManageTagsTool implements ToolHandler {

    private static final String ACTIVE_TAG = "Manage the tags of ONE INUBIT {node} (`node`:"
        + " `<{group}>/<{node}>`) with one `action`: setActiveTag (`user`, `tag`) sets the active"
        + " tag of an INUBIT user, removeActiveTag (`user`) removes it; the preview names the"
        + " user's current and requested active tag, and the result reads it again. An unknown"
        + " user gives NOT_FOUND.";
    private static final String REMOVAL_NOT_OFFERED = " Not offered: removing or deleting a"
        + " tag. INUBIT deletes a tag for the whole owner even when one diagram group is named,"
        + " and removes it only in a repository path whose tagged files cannot be read for a"
        + " backup.";
    private static final String CONFIRMATION = " Every call needs a `reason` (stored in the"
        + " audit log). On {nodes} with `confirmationMode` `SERVER` (the default), the first call"
        + " only returns a preview and a confirmation code; call again with the same inputs and"
        + " `confirmationCode` to execute. If the state changed meanwhile, the CONFLICT error"
        + " carries a new preview in its details. With `CLIENT` (never on production) the first"
        + " call executes. Needs write access; production only with write.productionOptIn.";

    /** The inputs of every action; the other arguments are the fields of the action. */
    private static final Set<String> COMMON_INPUTS = Set.of("node", "action", "reason",
        "confirmationCode");

    static final String DESCRIPTION = ACTIVE_TAG + REMOVAL_NOT_OFFERED + CONFIRMATION;

    private final Function<Request, Response> execute;

    /**
     * @param execute the tag administration of the profile
     *                ({@code TagAdministrationService::execute})
     */
    public ManageTagsTool(Function<Request, Response> execute) {
        this.execute = Objects.requireNonNull(execute, "execute");
    }

    @Override
    public String name() {
        return TagAdministrationService.TOOL;
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    /** Destructive, non-idempotent, open world (contracts "Annotations"). */
    @Override
    public ToolHints annotations() {
        return ToolHints.destructive("Manage the tags of an INUBIT {node}");
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
        return execute.apply(new Request(args.string("node"), args.string("action"), fields,
            args.string("reason"), args.optionalString("confirmationCode"), context.client()));
    }
}
