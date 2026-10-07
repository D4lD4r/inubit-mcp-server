package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.TagService;
import de.dadecker.inubit.mcp.application.TagService.Response;
import de.dadecker.inubit.mcp.application.TagService.TagRequest;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.Map;
import java.util.Objects;

/**
 * {@code tag_artifacts} (feature 004, contracts/mcp-tools-delta.md, US4): tags the current
 * versions of the technical workflows of the given diagram groups (and their modules) through
 * {@link TagService}, which refuses blank groups and existing tags and verifies the result. The
 * arguments are validated by the SDK against {@code tag_artifacts.input.json}; the output is
 * {@code {"challenge": …}} (the preview under server confirmation) or {@code {"result": …}}.
 * Refusals before anything is sent are tool errors.
 *
 * <p>Registered only if at least one node is a development stage (FR-001).
 */
public final class TagArtifactsTool implements ToolHandler {

    static final String DESCRIPTION = "Tag the current versions of the technical workflows (and"
        + " their modules) of the given diagram groups of an owner on ONE development {node}."
        + " Never owner-wide; an existing tag is never moved.";

    private final TagService service;

    public TagArtifactsTool(TagService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String name() {
        return "tag_artifacts";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    /** Destructive, non-idempotent, open world (FR-026, Constitution I). */
    @Override
    public ToolHints annotations() {
        return ToolHints.destructive("Tag diagram groups on a development INUBIT node");
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        return handle(arguments, CallContext.NONE);
    }

    @Override
    public Object handle(Map<String, Object> arguments, CallContext context) {
        ToolArguments args = new ToolArguments(arguments);
        Response response = service.tag(new TagRequest(args.string("node"),
            args.optionalString("owner"), args.strings("diagramGroups"), args.string("tag"),
            args.optionalString("reason").orElse(""), args.optionalString("confirmationCode"),
            context.client()));
        return switch (response) {
            case Response.Challenge challenge -> Map.of("challenge", challenge.preview());
            case Response.Completed completed -> Map.of("result", completed.outcome());
        };
    }
}
