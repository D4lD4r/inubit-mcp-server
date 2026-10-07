package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.ImportService;
import de.dadecker.inubit.mcp.application.ImportService.ImportRequest;
import de.dadecker.inubit.mcp.application.ImportService.Response;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code import_artifacts} (feature 004, contracts/mcp-tools-delta.md, US1): imports the changed
 * workflows of one diagram group (with their changed or new modules), or changed single modules,
 * from the workspace into one development node through {@link ImportService}, which enforces the
 * whole sequence. The arguments are validated by the SDK against
 * {@code import_artifacts.input.json}; the output is {@code {"challenge": …}} (the preview under
 * server confirmation) or {@code {"result": …}}. Refusals before anything is sent are tool
 * errors.
 *
 * <p>Registered only if at least one node is a development stage (FR-001).
 */
public final class ImportArtifactsTool implements ToolHandler {

    static final String DESCRIPTION = "Import the changed workflows of ONE diagram group (with"
        + " their changed or new modules), or changed single modules, from the workspace into ONE"
        + " development {node}. The server checks the files, refuses on conflicts (changed on the"
        + " server or open in the Workbench), backs up, imports only what changed, verifies by"
        + " re-export and rolls back on failure. Secrets are taken from the {node}.";

    private final ImportService service;

    public ImportArtifactsTool(ImportService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String name() {
        return "import_artifacts";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    /** Destructive, non-idempotent, open world (FR-026, Constitution I). */
    @Override
    public ToolHints annotations() {
        return ToolHints.destructive("Import workspace changes into a development INUBIT node");
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        return handle(arguments, CallContext.NONE);
    }

    @Override
    public Object handle(Map<String, Object> arguments, CallContext context) {
        ToolArguments args = new ToolArguments(arguments);
        List<ImportScope.Module> modules = new ArrayList<>();
        if (arguments.get("modules") instanceof Collection<?> list) {
            for (Object module : list) {
                Map<?, ?> fields = (Map<?, ?>) module;
                modules.add(new ImportScope.Module(String.valueOf(fields.get("name")),
                    Optional.ofNullable(fields.get("pluginType")).map(String::valueOf)));
            }
        }
        Response response = service.importArtifacts(new ImportRequest(args.string("node"),
            args.optionalString("owner"), args.optionalString("diagramGroup"), modules,
            args.optionalString("reason").orElse(""), args.optionalString("confirmationCode"),
            context.client()));
        return switch (response) {
            case Response.Challenge challenge -> Map.of("challenge", challenge.preview());
            case Response.WriteChallenge challenge -> Map.of("challenge", challenge.preview());
            case Response.Completed completed -> Map.of("result", completed.outcome());
        };
    }
}
