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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code export_archives} (feature 015): exports one diagram group of one node over StartCLI,
 * slices it into one Workbench archive per technical workflow and writes the archives into a
 * local folder outside the workspace (research R-2, R-13). The workspace is never touched, and
 * the raw export never reaches the workspace's archive codec.
 *
 * <p>Order (R-13): target and owner, the static checks (production opt-in, acknowledgement, names
 * and limits, folder rules) and a {@code REFUSED} record for their failures; then {@code
 * PENDING}, the version gate, the export, the export-dependent checks (first failure wins), the
 * writes and the outcome record. No archive is written before every check passed. The result's
 * module, missing-module and repository-path lists are bounded together by {@code
 * resultLimits.maxItems} (R-14).
 */
public final class ArchiveExportService {

    /** The most workflows one call archives. */
    public static final int MAX_WORKFLOWS = 50;

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveExportService.class);
    private static final int MAX_NAME = 200;
    private static final int MAX_REASON = 500;
    private static final String NO_RECORD = "audit record not written";
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u001F\\u007F]");
    private static final Pattern GROUP =
        Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$");
    private static final Pattern REASON = Pattern.compile("^[^\\r\\n]{1,500}$");

    private final TargetResolver targets;
    private final Function<NodeId, Optional<String>> owners;
    private final Function<NodeId, Optional<String>> accounts;
    private final Predicate<NodeId> production;
    private final Predicate<NodeId> productionOptIn;
    private final TargetFolderRules folders;
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
     * @param production whether a node's group is a production group
     * @param productionOptIn whether a node's group sets {@code archives.productionOptIn}
     *                  (research R-15; independent of {@code write.*})
     * @param folders   the rules of the target folder (research R-9, R-10)
     * @param artifacts the StartCLI export of a node
     * @param archives  slices an export into workflow archives
     * @param gate      admits the export ({@code WORKSPACE_EXPORT} over StartCLI)
     * @param writer    places the archives in the target folder
     * @param limiter   bounds the result's module, missing-module and repository-path lists
     *                  ({@code resultLimits.maxItems})
     * @param audit     what every call's audit shares
     */
    public ArchiveExportService(TargetResolver targets, Function<NodeId, Optional<String>> owners,
        Function<NodeId, Optional<String>> accounts, Predicate<NodeId> production,
        Predicate<NodeId> productionOptIn, TargetFolderRules folders,
        Function<NodeId, ArtifactPort> artifacts, WorkflowArchivePort archives, VersionGate gate,
        ArchiveFolderWriter writer, ResultLimiter limiter, ArchiveExportAudit.Dependencies audit) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.production = Objects.requireNonNull(production, "production");
        this.productionOptIn = Objects.requireNonNull(productionOptIn, "productionOptIn");
        this.folders = Objects.requireNonNull(folders, "folders");
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
     *     NOT_CONFIGURED}, {@code PRODUCTION_PROTECTED}, {@code INVALID_INPUT} for a missing
     *     acknowledgement, a name or limit of the input schema or a folder rule), {@code
     *     INTERNAL} if an audit record that must precede the export could not be written,
     *     {@code UNSUPPORTED_VERSION}, the errors of the StartCLI export and of slicing, and
     *     {@code INVALID_INPUT} for unknown workflow names, more than {@value #MAX_WORKFLOWS}
     *     workflows, a file name over 255 bytes or colliding file names; an error after the
     *     {@code PENDING} record whose final record could not be written carries a sentence
     *     naming the {@code auditId} (an unexpected runtime failure then becomes {@code
     *     INTERNAL}); an unexpected runtime failure once the writes started is always {@code
     *     INTERNAL} naming the target folder
     */
    public ArchiveExportResult export(ArchiveExportRequest request) {
        ArchiveExportAudit record = new ArchiveExportAudit(audit, request.target(),
            request.owner(), request.diagramGroup(), request.targetFolder(), request.workflows(),
            request.acknowledgeClearTextSecrets(), request.reason(), request.mcpClient());
        Static checked;
        try {
            checked = check(request, record);
        } catch (ToolErrorException e) {
            ToolErrorException refused = record.refused(e.error());
            String counts = e.error().code() + ", nothing written";
            if (refused.error().equals(e.error())) {
                logEnd(LogText.loggable(request.target()), AuditOutcome.REFUSED.name(), request,
                    counts, auditId(record));
            } else {
                logEnd(LogText.loggable(request.target()), ErrorCode.INTERNAL.name(), request,
                    counts, NO_RECORD);
            }
            throw refused;
        }
        try {
            record.pending();
        } catch (ToolErrorException e) {
            logEnd(checked.node().value(), ErrorCode.INTERNAL.name(), request,
                "nothing exported or written", NO_RECORD);
            throw e;
        }
        return run(request, record, checked);
    }

    /** The outcome of the static checks (R-13 steps 1–3); {@code warnings}: the folder's. */
    private record Static(NodeId node, String owner, Path folder, List<String> warnings) {
    }

    /**
     * R-13 steps 1–3, first failure wins: target and owner, then production (R-15), the
     * acknowledgement (FR-007), the names and limits of the input schema again (defence in
     * depth) and the folder rules (R-9, R-10).
     */
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
        if (production.test(node) && !productionOptIn.test(node)) {
            throw refused(ErrorCode.PRODUCTION_PROTECTED, node,
                node + targets.terms().render(" belongs to the production {group} ")
                    + node.group() + "; export_archives needs archives.productionOptIn there",
                targets.terms().render("The {group} is classified production: true and"
                    + " archives.productionOptIn is not set for it (write.enabled and"
                    + " write.productionOptIn do not unlock archives)"),
                targets.terms().render("Export the archives in the INUBIT Workbench; only if"
                    + " this {group} must allow it, set archives.productionOptIn: true for it and"
                    + " restart the MCP client"));
        }
        if (!request.acknowledgeClearTextSecrets()) {
            throw refused(ErrorCode.INVALID_INPUT, node, "acknowledgeClearTextSecrets is not"
                    + " true: the archives contain the workflows' secrets (passwords, keys,"
                    + " certificates, saved test messages) in clear text",
                "The call did not acknowledge that the archives hold secrets in clear text",
                "Pass acknowledgeClearTextSecrets: true once the user accepted that, and keep"
                    + " the archives as safe as the secrets they contain");
        }
        checkInputs(request, node);
        TargetFolderRules.CheckedFolder folder;
        try {
            folder = folders.check(request.targetFolder());
        } catch (ToolErrorException e) {
            throw new ToolErrorException(e.error().withNode(node));
        }
        return new Static(node, owner, folder.path(), folder.warnings());
    }

    /** The input schema's names and limits again (defence in depth; R-13 step 2). */
    private static void checkInputs(ArchiveExportRequest request, NodeId node) {
        if (!GROUP.matcher(request.diagramGroup()).matches()) {
            throw invalid(node, "diagramGroup is not 1 to " + MAX_NAME + " letters, digits,"
                + " '_', '.', '-' or spaces, starting with a letter, digit, '_' or '.'");
        }
        List<String> workflows = request.workflows();
        if (workflows.size() > MAX_WORKFLOWS) {
            throw invalid(node, "workflows has " + workflows.size() + " names; one call"
                + " archives at most " + MAX_WORKFLOWS);
        }
        if (new HashSet<>(workflows).size() != workflows.size()) {
            throw invalid(node, "workflows names a workflow more than once");
        }
        for (int i = 0; i < workflows.size(); i++) {
            String name = workflows.get(i);
            int length = name.codePointCount(0, name.length());
            if (length < 1 || length > MAX_NAME || CONTROL.matcher(name).find()) {
                throw invalid(node, "workflows[" + i + "] is not 1 to " + MAX_NAME
                    + " characters without control characters");
            }
        }
        if (request.reason().isPresent() && !REASON.matcher(request.reason().get()).matches()) {
            throw invalid(node, "reason is not one line of 1 to " + MAX_REASON + " characters");
        }
    }

    private static ToolErrorException invalid(NodeId node, String message) {
        return refused(ErrorCode.INVALID_INPUT, node, message,
            "The call does not match the tool's input schema",
            "Correct the parameter as the tool's input schema describes");
    }

    private static ToolErrorException refused(ErrorCode code, NodeId node, String message,
        String likelyCause, String nextStep) {
        return new ToolErrorException(ToolError.of(code, message, likelyCause, nextStep)
            .withNode(node));
    }

    private ArchiveExportResult run(ArchiveExportRequest request, ArchiveExportAudit record,
        Static checked) {
        NodeId node = checked.node();
        boolean checking = false;
        Prepared prepared;
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
            prepared = new Prepared(admission, sliced, selected);
        } catch (ToolErrorException e) {
            ToolError error = e.error();
            boolean refused = error.code() == ErrorCode.UNSUPPORTED_VERSION
                || checking && error.code() == ErrorCode.INVALID_INPUT;
            AuditOutcome outcome = refused ? AuditOutcome.REFUSED : AuditOutcome.FAILED;
            Optional<String> warning = record.outcome(outcome, error.code() + ": "
                + error.message());
            logEnd(node.value(), outcome.name(), request, error.code() + ", nothing written",
                auditId(record));
            if (warning.isEmpty()) {
                throw e;
            }
            throw new ToolErrorException(new ToolError(error.code(), error.message() + ". "
                + warning.get(), error.likelyCause(), error.nextStep(), error.node(),
                error.excerpt()));
        } catch (RuntimeException e) {
            throw unexpected(request, record, node, e, "", ", nothing written");
        }
        return write(request, record, checked, prepared);
    }

    /** What the writes need: the gate's warnings, the sliced export, the selected workflows. */
    private record Prepared(List<String> admission, SlicedExport sliced, List<String> selected) {
    }

    /**
     * Audits an unexpected failure as {@code FAILED} (the audit text and the INFO line followed by
     * what was written) and returns what to throw: the failure itself, or {@code INTERNAL}
     * carrying the outcome warning.
     */
    private static RuntimeException unexpected(ArchiveExportRequest request,
        ArchiveExportAudit record, NodeId node, RuntimeException e, String audited,
        String logged) {
        Optional<String> warning = failed(request, record, node, e, audited, logged);
        if (warning.isEmpty()) {
            return e;
        }
        // The tool layer's INTERNAL for an unexpected failure, carrying the outcome warning.
        LOG.error("{} failed unexpectedly", ArchiveExportAudit.CAPABILITY, e);
        return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL, "The tool "
                + ArchiveExportAudit.CAPABILITY + " failed unexpectedly ("
                + e.getClass().getSimpleName() + "). " + warning.get(),
            "An internal error of the INUBIT MCP server",
            "Retry; if it persists, check the MCP server log on stderr and report the"
                + " problem").withNode(node));
    }

    /**
     * Audits an unexpected failure once the writes started like {@link #unexpected} and always
     * returns {@code INTERNAL}: archives with clear-text secrets may already be in the folder, so
     * the message names it ({@code written}: what was written, {@code null} while writing) and
     * carries the outcome warning; never the failure's own message.
     */
    private static ToolErrorException unexpectedWrite(ArchiveExportRequest request,
        ArchiveExportAudit record, Static checked, RuntimeException e,
        ArchiveFolderWriter.Result written, String state) {
        Optional<String> warning = failed(request, record, checked.node(), e, state, state);
        LOG.error("{} failed unexpectedly", ArchiveExportAudit.CAPABILITY, e);
        String folder = TargetFolderRules.shown(checked.folder().toString());
        String failure = "The tool " + ArchiveExportAudit.CAPABILITY + " failed unexpectedly ("
            + e.getClass().getSimpleName() + ")";
        String message = written == null
            ? failure + " while writing; archives may already be in " + folder + "; they contain"
                + " secrets in clear text and must not be shared or committed as they are"
            : failure + " after the writes: " + counts(written.written().size(), written)
                + " in " + folder + "; the archives written there contain secrets in clear text"
                + " and must not be shared or committed as they are";
        return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
            message + warning.map(text -> ". " + text).orElse(""),
            "An internal error of the INUBIT MCP server",
            "Check " + folder + " for the archives written and keep or delete them as their"
                + " secrets require (existing files are never overwritten, so move them before"
                + " retrying into the same folder); report the problem with the MCP server log"
                + " on stderr").withNode(checked.node()));
    }

    /**
     * The {@code FAILED} outcome record and INFO line of an unexpected failure (its class name
     * followed by {@code audited} / {@code logged}); returns the outcome warning, if any.
     */
    private static Optional<String> failed(ArchiveExportRequest request,
        ArchiveExportAudit record, NodeId node, RuntimeException e, String audited,
        String logged) {
        String failure = e.getClass().getSimpleName();
        Optional<String> warning = record.outcome(AuditOutcome.FAILED, ErrorCode.INTERNAL + ": "
            + failure + audited);
        logEnd(node.value(), AuditOutcome.FAILED.name(), request, ErrorCode.INTERNAL + " "
            + failure + logged, auditId(record));
        return warning;
    }

    /**
     * The INFO line at the end of a call (R-13 step 8): node (a static refusal: the target as
     * requested), outcome (or the code the call returns instead), diagram group, counts or code,
     * and {@code auditId} (or that no record was written) only.
     */
    private static void logEnd(String node, String outcome, ArchiveExportRequest request,
        String counts, String audited) {
        LOG.info("{} on {}: {} (diagram group {}: {}; {})", ArchiveExportAudit.CAPABILITY, node,
            outcome, LogText.loggable(request.diagramGroup()), counts, audited);
    }

    private static String auditId(ArchiveExportAudit record) {
        return "audit id " + record.auditId();
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

    /**
     * The writes, the outcome record and the result. An unexpected failure once the writes
     * started and before the outcome record is audited {@code FAILED} with what was written and
     * ends in {@code INTERNAL} naming the folder; nothing after the outcome record is caught, so
     * it is never recorded twice.
     */
    private ArchiveExportResult write(ArchiveExportRequest request, ArchiveExportAudit record,
        Static checked, Prepared prepared) {
        SlicedExport sliced = prepared.sliced();
        ArchiveFolderWriter.Result written = null;
        Bounded bounded;
        List<FailedArchive> failed;
        try {
            written = writer.write(checked.folder(), prepared.selected().stream()
                .map(workflow -> new ArchiveFolderWriter.Archive(workflow,
                    () -> sliced.archive(workflow)))
                .toList());
            bounded = bound(written.written().stream()
                .map(archive -> written(checked.folder(), archive,
                    sliced.plan(archive.workflow())))
                .toList());
            failed = written.failed().stream()
                .map(f -> new FailedArchive(f.workflow(), f.code(), f.message())).toList();
        } catch (RuntimeException e) {
            String state = written == null ? " while writing" : " after the writes, "
                + counts(written.written().size(), written);
            throw unexpectedWrite(request, record, checked, e, written, state);
        }
        List<WrittenArchive> archiveResults = bounded.archives();
        AuditOutcome outcome = failed.isEmpty() ? AuditOutcome.EXECUTED
            : AuditOutcome.PARTIALLY_COMPLETED;
        String counts = counts(archiveResults.size(), written);
        List<String> warnings = new ArrayList<>(prepared.admission());
        warnings.addAll(checked.warnings());
        warnings.addAll(written.warnings());
        record.outcome(outcome, counts).ifPresent(warnings::add);
        logEnd(checked.node().value(), outcome.name(), request, counts, auditId(record));
        return new ArchiveExportResult(checked.node().value(), checked.owner(),
            request.diagramGroup(), checked.folder().toString(), record.auditId(),
            ArchiveExportResult.CLEAR_TEXT_SECRETS, archiveResults, failed,
            written.notWritten(), bounded.truncated(), warnings);
    }

    private static String counts(int archives, ArchiveFolderWriter.Result written) {
        return archives + " written, " + written.failed().size() + " failed, "
            + written.notWritten().size() + " not written";
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
}
