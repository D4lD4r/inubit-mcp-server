package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult;
import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult.FailedArchive;
import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult.MissingModule;
import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult.WrittenArchive;
import de.dadecker.inubit.mcp.domain.model.ArchiveFileNames;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkflowArchivePlan;
import de.dadecker.inubit.mcp.domain.model.WorkflowArchivePlan.ModuleEntry;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.WorkflowArchivePort;
import de.dadecker.inubit.mcp.domain.port.WorkflowArchivePort.SlicedExport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code export_archives} (feature 015): exports one diagram group of one node over StartCLI,
 * slices it into one Workbench archive per technical workflow and writes the archives into a
 * local folder outside the workspace (research R-2, R-13). The workspace is never touched, and
 * the raw export never reaches the workspace's archive codec.
 *
 * <p>Order (R-13): target and owner, static checks and a {@code REFUSED} record for their
 * failures; then {@code PENDING}, the version gate, the export, the export-dependent checks (first
 * failure wins), the writes and the outcome record. No archive is written before every check
 * passed. The result's module, missing-module and repository-path lists are bounded together by
 * {@code resultLimits.maxItems} (R-14).
 */
public final class ArchiveExportService {

    /** The most workflows one call archives. */
    public static final int MAX_WORKFLOWS = 50;

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveExportService.class);
    private static final int MAX_LOGGED = 100;

    private final TargetResolver targets;
    private final Function<NodeId, Optional<String>> owners;
    private final Function<NodeId, Optional<String>> accounts;
    private final Function<NodeId, ArtifactPort> artifacts;
    private final WorkflowArchivePort archives;
    private final VersionGate gate;
    private final ArchiveFolderWriter writer;
    private final ResultLimiter limiter;
    private final ArchiveExportAudit.Dependencies audit;

    /**
     * @param targets   resolves the target (a group: its first node)
     * @param owners    the {@code inventory.owner} of a node, if configured
     * @param accounts  the INUBIT account of a node's server, for the audit
     * @param artifacts the StartCLI export of a node
     * @param archives  slices an export into workflow archives
     * @param gate      admits the export ({@code WORKSPACE_EXPORT} over StartCLI)
     * @param writer    places the archives in the target folder
     * @param limiter   bounds the result's module, missing-module and repository-path lists
     *                  ({@code resultLimits.maxItems})
     * @param audit     what every call's audit shares
     */
    public ArchiveExportService(TargetResolver targets, Function<NodeId, Optional<String>> owners,
        Function<NodeId, Optional<String>> accounts, Function<NodeId, ArtifactPort> artifacts,
        WorkflowArchivePort archives, VersionGate gate, ArchiveFolderWriter writer,
        ResultLimiter limiter, ArchiveExportAudit.Dependencies audit) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.archives = Objects.requireNonNull(archives, "archives");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /**
     * Writes the archives of the selected workflows; write failures are part of the result
     * (FR-011), everything before the first write is a tool error.
     *
     * @throws ToolErrorException a static refusal ({@code TARGET_UNKNOWN}, {@code
     *     NOT_CONFIGURED}, {@code INVALID_INPUT}), {@code INTERNAL} if an audit record that must
     *     precede the export could not be written, {@code UNSUPPORTED_VERSION}, the errors of the
     *     StartCLI export and of slicing, and {@code INVALID_INPUT} for unknown workflow names,
     *     more than {@value #MAX_WORKFLOWS} workflows, a file name over 255 bytes or colliding
     *     file names
     */
    public ArchiveExportResult export(ArchiveExportRequest request) {
        ArchiveExportAudit record = new ArchiveExportAudit(audit, request.target(),
            request.owner(), request.diagramGroup(), request.targetFolder(), request.workflows(),
            request.acknowledgeClearTextSecrets(), request.reason(), request.mcpClient());
        Static checked;
        try {
            checked = check(request, record);
        } catch (ToolErrorException e) {
            LOG.info("{} on {}: REFUSED ({})", ArchiveExportAudit.CAPABILITY,
                loggable(request.target()), e.error().code());
            throw record.refused(e.error());
        }
        record.pending();
        return run(request, record, checked);
    }

    /** The outcome of the static checks (R-13 steps 1–2). */
    private record Static(NodeId node, String owner, Path folder) {
    }

    private Static check(ArchiveExportRequest request, ArchiveExportAudit record) {
        NodeId node = targets.resolve(request.target()).get(0);
        record.node(node, accounts.apply(node));
        String owner = request.owner().or(() -> owners.apply(node))
            .orElseThrow(() -> new ToolErrorException(ToolError.of(ErrorCode.NOT_CONFIGURED,
                targets.terms().render("{Node} ") + node + " has no inventory.owner and the call"
                    + " names no owner",
                targets.terms().render("inventory.owner is not set for this {node}, its {group}"
                    + " or in defaults"),
                "Pass owner, or set inventory.owner for " + node + " or in defaults")
                .withNode(node)));
        record.owner(owner);
        return new Static(node, owner, folder(request.targetFolder(), node));
    }

    // Interim rule until the folder rules (R-9) replace it: an absolute path.
    private static Path folder(String targetFolder, NodeId node) {
        Path folder;
        try {
            folder = Path.of(targetFolder);
        } catch (InvalidPathException e) {
            folder = null;
        }
        if (folder == null || !folder.isAbsolute()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "The target folder '" + targetFolder + "' is not an absolute path",
                "targetFolder must be the absolute path of a local folder",
                "Pass the absolute path of the folder for the archives").withNode(node));
        }
        return folder;
    }

    private ArchiveExportResult run(ArchiveExportRequest request, ArchiveExportAudit record,
        Static checked) {
        NodeId node = checked.node();
        boolean checking = false;
        try {
            List<String> admission = gate.admit(ArchiveExportAudit.CAPABILITY, node,
                Capability.WORKSPACE_EXPORT, Capability.Kind.READ, Set.of(Channel.CLI))
                .warnings();
            SlicedExport sliced = withNode(node, () -> archives.slice(
                artifacts.apply(node).exportWorkflowGroup(checked.owner(), request.diagramGroup()),
                node.group(), checked.owner(), request.diagramGroup()));
            checking = true;
            List<String> selected = withNode(node, () -> select(request, sliced, record,
                checked.folder()));
            checking = false;
            return write(request, record, checked, sliced, selected, admission);
        } catch (ToolErrorException e) {
            ToolError error = e.error();
            boolean refused = error.code() == ErrorCode.UNSUPPORTED_VERSION
                || checking && error.code() == ErrorCode.INVALID_INPUT;
            AuditOutcome outcome = refused ? AuditOutcome.REFUSED : AuditOutcome.FAILED;
            LOG.info("{} on {}: {} ({}, audit id {})", ArchiveExportAudit.CAPABILITY, node,
                outcome, error.code(), record.auditId());
            Optional<String> warning = record.outcome(outcome, error.code() + ": "
                + error.message());
            if (warning.isEmpty()) {
                throw e;
            }
            throw new ToolErrorException(new ToolError(error.code(), error.message() + ". "
                + warning.get(), error.likelyCause(), error.nextStep(), error.node(),
                error.excerpt()));
        } catch (RuntimeException e) {
            LOG.info("{} on {}: FAILED ({}, audit id {})", ArchiveExportAudit.CAPABILITY, node,
                e.getClass().getSimpleName(), record.auditId());
            record.outcome(AuditOutcome.FAILED, ErrorCode.INTERNAL + ": "
                + e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * The export-dependent checks (R-13 step 6), first failure wins; returns the selected
     * workflows in the order of {@code workflows}, without it in export order. The audit, the
     * writes and the result's {@code archives} follow this order.
     */
    private static List<String> select(ArchiveExportRequest request, SlicedExport sliced,
        ArchiveExportAudit record, Path folder) {
        List<String> all = sliced.workflows();
        List<String> selected;
        if (request.workflows().isEmpty()) {
            if (all.size() > MAX_WORKFLOWS) {
                throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                    "The diagram group '" + request.diagramGroup() + "' has " + all.size()
                        + " technical workflows; one call archives at most " + MAX_WORKFLOWS,
                    "Without workflows every technical workflow of the diagram group is archived",
                    "Pass workflows with at most " + MAX_WORKFLOWS + " names and archive the"
                        + " others in further calls"));
            }
            selected = all;
        } else {
            Set<String> requested = new LinkedHashSet<>(request.workflows());
            List<String> unknown = requested.stream().filter(name -> !all.contains(name))
                .map(name -> "'" + name + "'").toList();
            if (!unknown.isEmpty()) {
                throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                    "The diagram group '" + request.diagramGroup() + "' has no technical"
                        + " workflow " + String.join(", ", unknown)
                        + " (names are case-sensitive)",
                    "The names are misspelled, differ in case, or belong to another diagram"
                        + " group",
                    "Pass the workflow names exactly as INUBIT shows them, or leave workflows out"
                        + " to archive every technical workflow of the diagram group"));
            }
            selected = List.copyOf(requested);
        }
        record.workflows(selected);
        List<String> files = selected.stream().map(ArchiveFileNames::fileName).toList();
        record.files(files);
        checkCollisions(selected, files);
        checkFolder(folder, selected, files);
        return selected;
    }

    private static void checkCollisions(List<String> selected, List<String> files) {
        Map<String, Integer> first = new HashMap<>();
        List<String> collisions = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Integer earlier = first.putIfAbsent(ArchiveFileNames.key(files.get(i)), i);
            if (earlier != null) {
                collisions.add("'" + selected.get(earlier) + "' and '" + selected.get(i)
                    + "' (" + files.get(earlier) + ", " + files.get(i) + ")");
            }
        }
        if (!collisions.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "These workflows get archive file names that are the same file on a"
                    + " case-insensitive file system: " + String.join("; ", collisions),
                "The workflow names differ only in case, in Unicode normalization or in"
                    + " characters a file name cannot hold",
                "Archive the colliding workflows in separate calls into different target"
                    + " folders"));
        }
    }

    private static void checkFolder(Path folder, List<String> selected, List<String> files) {
        if (files.isEmpty() || !Files.isDirectory(folder)) {
            return;
        }
        Map<String, String> existing = new HashMap<>();
        try (Stream<Path> entries = Files.list(folder)) {
            entries.forEach(entry -> {
                String name = entry.getFileName().toString();
                existing.putIfAbsent(ArchiveFileNames.key(name), name);
            });
        } catch (IOException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The target folder " + folder + " could not be listed ("
                    + e.getClass().getSimpleName() + "); nothing was written",
                "The folder is not readable or the file system reported an error",
                "Check the folder's permissions, or choose another target folder"));
        }
        List<String> collisions = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            String name = existing.get(ArchiveFileNames.key(files.get(i)));
            if (name != null) {
                collisions.add(files.get(i) + " of workflow '" + selected.get(i) + "' with "
                    + name);
            }
        }
        if (!collisions.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "The target folder " + folder + " already has a file of the same name: "
                    + String.join("; ", collisions) + "; existing files are never overwritten",
                "The archives were exported into this folder before, or another file has the"
                    + " name",
                "Choose another target folder, or move or rename the existing file"));
        }
    }

    private ArchiveExportResult write(ArchiveExportRequest request, ArchiveExportAudit record,
        Static checked, SlicedExport sliced, List<String> selected, List<String> admission) {
        ArchiveFolderWriter.Result written = writer.write(checked.folder(), selected.stream()
            .map(workflow -> new ArchiveFolderWriter.Archive(workflow,
                () -> sliced.archive(workflow)))
            .toList());
        Bounded bounded = bound(written.written().stream()
            .map(archive -> written(checked.folder(), archive, sliced.plan(archive.workflow())))
            .toList());
        List<WrittenArchive> archiveResults = bounded.archives();
        List<FailedArchive> failed = written.failed().stream()
            .map(f -> new FailedArchive(f.workflow(), f.code(), f.message())).toList();
        AuditOutcome outcome = failed.isEmpty() ? AuditOutcome.EXECUTED
            : AuditOutcome.PARTIALLY_COMPLETED;
        String counts = archiveResults.size() + " written, " + failed.size() + " failed, "
            + written.notWritten().size() + " not written";
        List<String> warnings = new ArrayList<>(admission);
        warnings.addAll(written.warnings());
        record.outcome(outcome, counts).ifPresent(warnings::add);
        LOG.info("{} on {}: {} (diagram group {}: {}; audit id {})",
            ArchiveExportAudit.CAPABILITY, checked.node(), outcome,
            loggable(request.diagramGroup()), counts, record.auditId());
        return new ArchiveExportResult(checked.node().value(), checked.owner(),
            request.diagramGroup(), checked.folder().toString(), record.auditId(),
            ArchiveExportResult.CLEAR_TEXT_SECRETS, archiveResults, failed,
            written.notWritten(), bounded.truncated(), warnings);
    }

    /** The archives with their lists bounded, and whether a list was cut. */
    private record Bounded(List<WrittenArchive> archives, boolean truncated) {
    }

    /**
     * Bounds the module, missing-module and repository-path lists of all archives together by
     * {@code resultLimits.maxItems} (R-14, FR-012): filled archive by archive in output order,
     * per archive modules, then missing modules, then repository paths; later items are dropped.
     * The counts, the archives and their warnings stay complete.
     */
    private Bounded bound(List<WrittenArchive> archives) {
        int left = limiter.maxItems();
        boolean truncated = false;
        List<WrittenArchive> bounded = new ArrayList<>(archives.size());
        for (WrittenArchive archive : archives) {
            List<ModuleEntry> modules = head(archive.modules(), left);
            left -= modules.size();
            List<MissingModule> missing = head(archive.missingModules(), left);
            left -= missing.size();
            List<String> paths = head(archive.repositoryPaths(), left);
            left -= paths.size();
            truncated |= modules.size() < archive.modules().size()
                || missing.size() < archive.missingModules().size()
                || paths.size() < archive.repositoryPaths().size();
            bounded.add(new WrittenArchive(archive.workflow(), archive.file(), archive.path(),
                archive.size(), archive.moduleCount(), modules, archive.missingModuleCount(),
                missing, archive.repositoryFileCount(), paths, archive.warnings()));
        }
        return new Bounded(bounded, truncated);
    }

    private static <T> List<T> head(List<T> items, int max) {
        return items.subList(0, Math.min(items.size(), max));
    }

    private static WrittenArchive written(Path folder, ArchiveFolderWriter.Written archive,
        WorkflowArchivePlan plan) {
        List<String> warnings = new ArrayList<>();
        if (!plan.missingModules().isEmpty()) {
            int count = plan.missingModules().size();
            warnings.add(count + (count == 1 ? " module" : " modules") + " used by the workflow"
                + " are not in the export (missingModules); the archive imports only where these"
                + " modules exist");
        }
        if (plan.workflowConnector()) {
            warnings.add("A node calls another workflow through a Workflow Connector; the called"
                + " workflow is not included, archive it separately");
        }
        return new WrittenArchive(archive.workflow(), archive.file(),
            folder.resolve(archive.file()).toAbsolutePath().toString(), archive.size(),
            plan.modules().size(), plan.modules(), plan.missingModules().size(),
            plan.missingModules().stream().map(MissingModule::new).toList(),
            plan.repositoryPaths().size(), plan.repositoryPaths(), warnings);
    }

    private static <T> T withNode(NodeId node, Supplier<T> action) {
        try {
            return action.get();
        } catch (ToolErrorException e) {
            if (e.error().node().isPresent()) {
                throw e;
            }
            throw new ToolErrorException(e.error().withNode(node));
        }
    }

    /** A request value for the log: bounded, without control characters. */
    private static String loggable(String value) {
        String bounded = value.length() <= MAX_LOGGED ? value : value.substring(0, MAX_LOGGED);
        return bounded.replaceAll("\\p{Cntrl}", "?");
    }
}
