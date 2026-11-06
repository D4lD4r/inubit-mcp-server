package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.ArchiveExportRequest;
import de.dadecker.inubit.mcp.application.ArchiveExportService;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.Map;
import java.util.Objects;

/**
 * {@code export_archives} (feature 015, contracts/mcp-tools-delta.md, research R-1): exports the
 * technical workflows of one diagram group of one server as one Workbench-importable archive per
 * workflow into a local folder ({@link ArchiveExportService}). A group target uses its first
 * server in config order. The archives carry the workflows' secrets in clear text; the service
 * checks the acknowledgement and the folder and audits every call that passed the input schema.
 * Writes local files only: read-only for INUBIT, the workspace is not touched.
 */
public final class ExportArchivesTool implements ToolHandler {

    static final String DESCRIPTION = "Export the technical workflows of one diagram group of one"
        + " {group} or {node} as one Workbench-importable archive per workflow"
        + " (`<name>.diagram.zip`) into a local folder you name. The archives contain the"
        + " workflows' secrets IN CLEAR TEXT; set acknowledgeClearTextSecrets to true only when"
        + " the user wants that. The folder must be an absolute path outside the workspace, the"
        + " server's directories (including its configuration directory), git working trees and"
        + " network paths; existing files are never replaced. Read-only for INUBIT; does not"
        + " touch the workspace.";

    private final ArchiveExportService service;

    /** @param service the archive export of the profile */
    public ExportArchivesTool(ArchiveExportService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String name() {
        return "export_archives";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    /** Local write: not read-only, not destructive, not idempotent, open world (R-1). */
    @Override
    public ToolHints annotations() {
        return ToolHints.localWrite("Export Workbench archives of workflows");
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        return handle(arguments, CallContext.NONE);
    }

    /** The arguments are validated by the SDK against {@code export_archives.input.json}. */
    @Override
    public Object handle(Map<String, Object> arguments, CallContext context) {
        ToolArguments args = new ToolArguments(arguments);
        return service.export(new ArchiveExportRequest(args.string("target"),
            args.optionalString("owner"), args.string("diagramGroup"), args.strings("workflows"),
            args.string("targetFolder"), args.flag("acknowledgeClearTextSecrets"),
            args.optionalString("reason"), context.client()));
    }
}
