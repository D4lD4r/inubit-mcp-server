package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.WorkspaceService;
import de.dadecker.inubit.mcp.application.WorkspaceService.ExportRequest;
import de.dadecker.inubit.mcp.application.WorkspaceService.ExportResult;
import de.dadecker.inubit.mcp.application.WorkspaceService.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.HistoryEntry;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code export_artifacts} (contracts/mcp-tools-delta.md, US1): exports technical workflows by
 * diagram group, or single modules, from one server into the workspace and records the export
 * in the workspace history ({@link WorkspaceService#export}). A group target uses its first
 * server in config order, which the result names. Read-only for INUBIT, but not idempotent: each
 * call that changes files records a history entry. The change list is bounded by
 * {@code resultLimits.maxItems}; when longer, the full list goes to
 * {@code .reports/export-<commit>.txt} (research D-10).
 */
public final class ExportArtifactsTool implements ToolHandler {

    static final String DESCRIPTION = "Export technical workflows (by diagram group) or single"
        + " modules from one {group} or {node} into the local workspace as readable files, record"
        + " the export in the workspace history and list what changed. Only technical workflows"
        + " are exported (no system diagrams or other diagram types). Secrets are replaced by"
        + " placeholders. Read-only for INUBIT.";

    private final WorkspaceService workspace;
    private final TargetResolver targets;
    private final Function<NodeId, Optional<String>> owners;
    private final ResultLimiter limiter;

    /**
     * @param workspace the workspace of the profile
     * @param targets   resolves the target ids
     * @param owners    the effective {@code inventory.owner} of each node; empty if none is set
     * @param limiter   the profile's result limits
     */
    public ExportArtifactsTool(WorkspaceService workspace, TargetResolver targets,
        Function<NodeId, Optional<String>> owners, ResultLimiter limiter) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    @Override
    public String name() {
        return "export_artifacts";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("Export INUBIT artifacts into the workspace", true, false);
    }

    /** The arguments are validated by the SDK against {@code export_artifacts.input.json}. */
    @Override
    public Object handle(Map<String, Object> arguments) {
        ToolArguments args = new ToolArguments(arguments);
        NodeId node = targets.resolve(args.string("target")).get(0);
        String owner = args.optionalString("owner").or(() -> owners.apply(node))
            .orElseThrow(() -> new ToolErrorException(ToolError.of(ErrorCode.NOT_CONFIGURED,
                targets.terms().render("{Node} ") + node + " has no inventory.owner and the call"
                    + " names no owner",
                targets.terms().render("inventory.owner is not set for this {node}, its {group}"
                    + " or in defaults"),
                "Pass owner, or set inventory.owner for " + node + " or in defaults")
                .withNode(node)));
        List<ModuleRef> modules = new ArrayList<>();
        if (arguments.get("modules") instanceof Collection<?> list) {
            for (Object module : list) {
                Map<?, ?> fields = (Map<?, ?>) module;
                modules.add(new ModuleRef(String.valueOf(fields.get("name")),
                    Optional.ofNullable(fields.get("pluginType")).map(String::valueOf)));
            }
        }
        ExportResult result = workspace.export(new ExportRequest(node, owner,
            args.strings("diagramGroups"), modules));
        return Result.of(result, limiter.maxItems(), workspace::writeReport);
    }

    /** The result of contracts/mcp-tools-delta.md. */
    record Result(NodeId node, String owner, String workspace,
        Optional<LocalChanges> localChanges, Optional<String> commit, boolean unchanged,
        Counts counts, List<PathChange> changes, boolean truncated, Optional<String> fullList,
        int secretsReplaced, List<String> warnings) {

        record LocalChanges(String commit, int files) {
        }

        record Counts(long added, long modified, long deleted) {
        }

        interface Reports {

            String write(String fileName, List<String> lines);
        }

        static Result of(ExportResult result, int maxItems, Reports reports) {
            List<PathChange> all = result.export().map(HistoryEntry::changes).orElse(List.of());
            boolean truncated = all.size() > maxItems;
            Optional<String> fullList = truncated
                ? Optional.of(reports.write("export-" + result.export().orElseThrow().commit()
                    + ".txt", all.stream().map(change -> change.kind() + " " + change.path())
                    .toList()))
                : Optional.empty();
            return new Result(result.node(), result.owner(), result.workspace().toString(),
                result.localChanges().map(entry -> new LocalChanges(entry.commit(),
                    entry.changes().size())),
                result.export().map(HistoryEntry::commit), result.unchanged(),
                new Counts(count(all, PathChange.Kind.ADDED),
                    count(all, PathChange.Kind.MODIFIED), count(all, PathChange.Kind.DELETED)),
                truncated ? all.subList(0, maxItems) : all, truncated, fullList,
                result.secretsReplaced(), result.warnings());
        }

        private static long count(List<PathChange> changes, PathChange.Kind kind) {
            return changes.stream().filter(change -> change.kind() == kind).count();
        }
    }
}
