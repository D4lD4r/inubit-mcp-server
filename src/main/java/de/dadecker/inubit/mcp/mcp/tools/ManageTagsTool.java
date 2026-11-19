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
 * research D-18): sets or removes the active tag of an INUBIT user, or deletes a tag in one diagram
 * group of one server ({@code TagAdministrationService::execute}). The arguments,
 * validated by the SDK against the input schema, go to the service with the MCP client's
 * {@code name/version} for the audit; the service repeats every input check. The schema does not
 * require {@code scope}, so that a removal without one reaches the service and is refused with
 * {@code INVALID_INPUT} (AS13). The output is the service's {@link Response}:
 * {@code {"preview": ChangePreview}} or {@code {"result": ChangeResult, "warnings": […]}}.
 * Refusals are tool errors; a {@code CONFLICT} carries the fresh preview as
 * {@code error.details.preview}.
 *
 * <p>The description offers {@code deleteTag} only when its coverage rows are {@code reachable}
 * ({@link TagAdministrationService#deleteTagOffered}); otherwise it says that it is not offered
 * yet and why. It never offers {@code removeTag}, which INUBIT 8.1.17 runs only in a repository
 * path (research D-18, T055). Announced if some server has write access.
 */
public final class ManageTagsTool implements ToolHandler {

    private static final String ACTIVE_TAG = "Manage the tags of ONE INUBIT {node} (`node`:"
        + " `<{group}>/<{node}>`) with one `action`: setActiveTag (`user`, `tag`) sets the active"
        + " tag of an INUBIT user, removeActiveTag (`user`) removes it; the preview names the"
        + " user's current and requested active tag, and the result reads it again. An unknown"
        + " user gives NOT_FOUND.";
    private static final String DELETION = " With deleteTag (`tag`, `scope`), a tag is deleted"
        + " in exactly one technical diagram group of the inventory.owner of the {node} (`scope`"
        + " with `diagramGroup` and `diagramType`; the owner is sent with it); a call without"
        + " `scope` is refused with INVALID_INPUT before anything is read, and a `scope` with"
        + " `repositoryPath` is refused because the tagged repository files cannot be read for"
        + " the backup yet. The preview lists the workflow and module versions carrying the tag"
        + " in the diagram group and counts those outside it, which keep the tag; that list is"
        + " stored as a backup before anything is sent, and nothing is sent if the backup fails."
        + " The result reads both again.";
    private static final String DELETION_NOT_OFFERED = " Not offered yet: deleteTag. A check on"
        + " a test system must first show that INUBIT limits the deletion of a tag to the named"
        + " diagram group; until then such a call is refused.";
    private static final String REMOVAL_NOT_OFFERED = " Not offered: removeTag. INUBIT removes a"
        + " tag with it only in a repository path (a diagram group gives INVALID_INPUT), and a"
        + " repository path is refused because the tagged repository files cannot be read for"
        + " the backup yet.";
    private static final String CONFIRMATION = " Every call needs a `reason` (stored in the"
        + " audit log). On {nodes} with `confirmationMode` `SERVER` (the default), the first call"
        + " only returns a preview and a confirmation code; call again with the same inputs and"
        + " `confirmationCode` to execute. If the state changed meanwhile, the CONFLICT error"
        + " carries a new preview in its details. With `CLIENT` (never on production) the first"
        + " call executes. Needs write access; production only with write.productionOptIn.";

    /** The inputs of every action; the other arguments are the fields of the action. */
    private static final Set<String> COMMON_INPUTS = Set.of("node", "action", "reason",
        "confirmationCode");

    private final Function<Request, Response> execute;
    private final String description;

    /**
     * @param execute          the tag administration of the profile
     *                         ({@code TagAdministrationService::execute})
     * @param deleteTagOffered as {@link TagAdministrationService#deleteTagOffered} returns it
     */
    public ManageTagsTool(Function<Request, Response> execute, boolean deleteTagOffered) {
        this.execute = Objects.requireNonNull(execute, "execute");
        this.description = description(deleteTagOffered);
    }

    /** The description, offering {@code deleteTag} or not. */
    static String description(boolean deleteTagOffered) {
        return ACTIVE_TAG + (deleteTagOffered ? DELETION : DELETION_NOT_OFFERED)
            + REMOVAL_NOT_OFFERED + CONFIRMATION;
    }

    @Override
    public String name() {
        return TagAdministrationService.TOOL;
    }

    @Override
    public String descriptionText() {
        return description;
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
