package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.application.DevelopmentGuard.Capability;
import de.dadecker.inubit.mcp.domain.model.ArtifactRef;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ChangeSet;
import de.dadecker.inubit.mcp.domain.model.ChangedArtifact;
import de.dadecker.inubit.mcp.domain.model.CheckFinding;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportPreview;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.NameCodec;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.OwnerKind;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import de.dadecker.inubit.mcp.domain.model.Target;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import de.dadecker.inubit.mcp.domain.port.ArtifactInspectorPort;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.ImportArchivePort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The use case behind {@code import_artifacts} (feature 004, US1, FR-006 – FR-018, FR-025 –
 * FR-029; research D-1 – D-13, D-18, D-20, D-24, D-25). The server, not the assistant, enforces
 * the sequence:
 *
 * <ol>
 *   <li>inputs (scope, reason without {@code #}, {@code @} or control characters, code) and the
 *       {@link DevelopmentGuard} — nothing reads INUBIT before;
 *   <li>the workspace lock for the whole call (FR-027), the backup retention sweep (audited),
 *       uncommitted edits recorded as local changes;
 *   <li>the change set ({@link ChangeSetBuilder}); an empty one sends nothing (SC-003);
 *   <li>the checks of feature 003 on the change set — an ERROR refuses with a findings report;
 *   <li>the owner kind, only from positive evidence; user groups are refused
 *       ({@link OwnerKindResolver#admitForWrite});
 *   <li>with a confirmation code: the code must match the inputs and the workspace of its
 *       preview ({@code CONFIRMATION_INVALID} otherwise, "preview again");
 *   <li>the conflict check on a fresh export ({@link ConflictDetector}), repeated right before
 *       every import; every module a workflow references must be imported or exist on the
 *       target; with a code, a server state other than the previewed one is {@code CONFLICT};
 *   <li>the import archive with only the change set and the target's current secrets;
 *   <li>server confirmation without a code: the preview with a code, audited — nothing sent;
 *   <li>the backup (the raw export of the scope and a manifest), then the {@code PENDING}
 *       audit record (fail closed), then the StartCLI import;
 *   <li>the protocol must name exactly the change set; the scope is exported again and every
 *       change-set artifact must equal its workspace file (reviewed content) with the reason
 *       in its check-in comment;
 *   <li>success: only the change-set files and their {@code .meta/} records are replaced by the
 *       verified state and committed with the {@code Server-State} trailer;
 *   <li>failure after anything was sent (a refused import, a protocol mismatch, a differing or
 *       failing re-export, a timeout whose re-export does not show the intended state): the
 *       state is re-exported; if anything changed, the backup is re-imported with the target's
 *       current secrets and verified. The result is {@code FAILED} with
 *       {@code failure{code, step, message}}, the rollback state and the created artifacts that
 *       cannot be removed.
 * </ol>
 *
 * <p>Every refusal before anything is sent is a tool error, audited {@code REFUSED}.
 */
public final class ImportService {

    private static final Logger LOG = LoggerFactory.getLogger(ImportService.class);
    private static final String CAPABILITY = Capability.IMPORT_ARTIFACTS.toolName();
    private static final String RETENTION = "backup_retention";
    private static final Pattern REASON = Pattern.compile("^[^#@\\p{Cntrl}]{1,500}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{22}$");
    private static final int MAX_MODULES = 50;
    private static final int MAX_AUDITED_NAMES = 20;
    private static final int MAX_AUDITED_INPUT = 500;
    private static final String REPORTS = ".reports";
    private static final String COMMENT_PREFIX = "DefaultCommitCommentImport###";
    private static final DateTimeFormatter COMMENT_TIME =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    /** The INUBIT user and host of a node, for the check-in comment (research D-11). */
    public record Account(String user, String host) {
        public Account {
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(host, "host");
        }
    }

    /**
     * The collaborators.
     *
     * @param root          the workspace root
     * @param profile       the profile every audit record carries
     * @param policies      the development settings of each node
     * @param defaultOwners {@code inventory.owner} of each node
     * @param accounts      the check-in comment identity of each node
     */
    public record Dependencies(Path root, String profile, DevelopmentGuard guard,
        Function<NodeId, DevelopmentPolicy> policies, VersionHistoryPort history,
        ArtifactInspectorPort inspector, ArtifactCheckService checks, ArchiveCodecPort codec,
        ImportArchivePort archives, Function<NodeId, ArtifactPort> artifacts,
        Function<NodeId, ImportPort> imports, Function<NodeId, InventoryPort> inventory,
        OwnerKindResolver owners, Function<NodeId, Optional<String>> defaultOwners,
        Function<NodeId, Account> accounts, WriteChallengeRegistry challenges,
        BackupStore backups, AuditPort audit, Clock clock, Supplier<UUID> ids) {

        public Dependencies {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(guard, "guard");
            Objects.requireNonNull(policies, "policies");
            Objects.requireNonNull(history, "history");
            Objects.requireNonNull(inspector, "inspector");
            Objects.requireNonNull(checks, "checks");
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(archives, "archives");
            Objects.requireNonNull(artifacts, "artifacts");
            Objects.requireNonNull(imports, "imports");
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(owners, "owners");
            Objects.requireNonNull(defaultOwners, "defaultOwners");
            Objects.requireNonNull(accounts, "accounts");
            Objects.requireNonNull(challenges, "challenges");
            Objects.requireNonNull(backups, "backups");
            Objects.requireNonNull(audit, "audit");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(ids, "ids");
        }
    }

    /**
     * The arguments of {@code import_artifacts}: exactly one of {@code diagramGroup} and
     * {@code modules}; {@code owner} defaults to the node's {@code inventory.owner}.
     */
    public record ImportRequest(String node, Optional<String> owner,
        Optional<String> diagramGroup, List<ImportScope.Module> modules, String reason,
        Optional<String> confirmationCode, Optional<String> mcpClient) {

        public ImportRequest {
            node = node == null ? "" : node;
            owner = owner == null ? Optional.empty() : owner;
            diagramGroup = diagramGroup == null ? Optional.empty() : diagramGroup;
            modules = modules == null ? List.of() : List.copyOf(modules);
            reason = reason == null ? "" : reason;
            confirmationCode = confirmationCode == null ? Optional.empty() : confirmationCode;
            mcpClient = mcpClient == null ? Optional.empty() : mcpClient;
        }
    }

    /** Either the preview of the first step or the outcome of an execution. */
    public sealed interface Response {

        /** Nothing was sent; the preview and its code. */
        record Challenge(ImportPreview preview) implements Response {
            public Challenge {
                Objects.requireNonNull(preview, "preview");
            }
        }

        /** Something may have been sent (or nothing was to send): the outcome. */
        record Completed(WriteOutcome outcome) implements Response {
            public Completed {
                Objects.requireNonNull(outcome, "outcome");
            }
        }
    }

    private final Dependencies d;
    private final ChangeSetBuilder builder;
    private final ConflictDetector detector;

    public ImportService(Dependencies dependencies) {
        this.d = Objects.requireNonNull(dependencies, "dependencies");
        this.builder = new ChangeSetBuilder(d.root(), d.history(), d.inspector());
        this.detector = new ConflictDetector(d.root(), d.history(), d.codec(), d.artifacts(),
            d.inventory());
    }

    /**
     * Runs the sequence for one call (see the class description).
     *
     * @throws ToolErrorException every refusal before anything is sent (after its audit
     *     record), or {@code INTERNAL} if an audit record cannot be written
     */
    public Response importArtifacts(ImportRequest request) {
        Call call = new Call(request);
        DevelopmentPolicy policy;
        try {
            validate(request);
            policy = d.guard().admit(request.node(), Capability.IMPORT_ARTIFACTS);
            call.policy = policy;
            call.owner = request.owner().or(() -> d.defaultOwners().apply(policy.node()))
                .orElseThrow(() -> new ToolErrorException(ToolError.of(ErrorCode.NOT_CONFIGURED,
                    "No owner is given and inventory.owner is not set for " + policy.node()
                        + "; nothing was sent",
                    "import_artifacts needs the owner of the artifacts",
                    "Give owner, or set inventory.owner for the node").withNode(
                        policy.node())));
        } catch (ToolErrorException e) {
            throw call.refuse(e.error());
        } catch (RuntimeException e) {
            throw call.refuse(unexpected("checking the request", e));
        }
        try (WorkspaceLock lock = WorkspaceLock.acquire(d.root())) {
            return locked(call, policy);
        } catch (RuntimeException e) {
            if (call.sent != null) {
                throw call.failedAfterSending(e);
            }
            if (call.refused) {
                throw e;
            }
            throw e instanceof ToolErrorException tool ? call.refuse(tool.error())
                : call.refuse(unexpected("preparing the import", e));
        }
    }

    private Response locked(Call call, DevelopmentPolicy policy) {
        NodeId node = policy.node();
        sweep(call);
        d.history().init();
        commitLocalChanges();
        ImportScope scope = scope(call, node);
        ChangeSet changes = builder.build(scope);
        call.changes = changes;
        if (changes.isEmpty()) {
            UUID auditId = d.ids().get();
            call.append(auditId, AuditRecord.Step.EXECUTE, AuditOutcome.EXECUTED,
                "Nothing changed in " + scope.describe() + "; nothing was sent");
            return new Response.Completed(new WriteOutcome(auditId, WriteOutcome.Outcome.EXECUTED,
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                changes.notImported(), Optional.empty(), List.of(), List.of(),
                List.of("Nothing changed in the workspace since the last server state of "
                    + scope.describe() + "; nothing was sent")));
        }
        UUID auditId = d.ids().get();
        int warnings = check(node, changes, auditId);
        OwnerKind kind = d.owners().admitForWrite(node, call.owner);
        call.kind = kind;
        String inputs = inputFingerprint(node, call);
        String workspace = workspaceState(changes);
        Optional<String> previewedServer = Optional.empty();
        boolean server = policy.confirmation() == WritePolicy.Confirmation.SERVER;
        if (server && call.request.confirmationCode().isPresent()) {
            String previewed = d.challenges().redeem(call.request.confirmationCode().get(),
                Capability.IMPORT_ARTIFACTS, node, inputs);
            Map<String, String> state = parseState(previewed);
            if (!workspace.equals(state.get("workspace"))) {
                throw new ToolErrorException(ToolError.of(ErrorCode.CONFIRMATION_INVALID,
                    "The workspace changed between the preview and this call; nothing was"
                        + " sent and the code is used up: preview again",
                    "Files of the change set were edited after the preview",
                    "Call import_artifacts again without confirmationCode to preview again")
                    .withNode(node));
            }
            previewedServer = Optional.ofNullable(state.get("server"));
        }
        ConflictDetector.Result fresh = detector.detect(node, changes, auditId);
        requireReferencedModules(node, changes, fresh.targetModules());
        if (previewedServer.isPresent() && !previewedServer.get().equals(fresh.fingerprint())) {
            throw new ToolErrorException(ToolError.of(ErrorCode.CONFLICT,
                "The scope changed on " + node + " after the preview; nothing was sent",
                "A colleague imported or published in the scope meanwhile",
                "Export the scope again, check the change, then import again").withNode(node));
        }
        Account account = d.accounts().apply(node);
        ImportArchivePort.Archive archive = d.archives().assemble(build(changes, call,
            files(changes), fresh.rawExports(), call.request.reason(), account,
            takenNames(node, changes, call.owner, fresh.targetModules())));
        if (server && call.request.confirmationCode().isEmpty()) {
            return challenge(call, node, policy, changes, warnings, inputs,
                "workspace=" + workspace + ";server=" + fresh.fingerprint());
        }
        return execute(call, node, changes, auditId, fresh, archive, account);
    }

    // --- preview -----------------------------------------------------------------------------

    private Response challenge(Call call, NodeId node, DevelopmentPolicy policy,
        ChangeSet changes, int warnings, String inputs, String previewState) {
        WriteChallengeRegistry.Issued issued = d.challenges().issue(
            Capability.IMPORT_ARTIFACTS, node, inputs, previewState, policy.confirmationTtl());
        Map<String, String> audited = call.inputs();
        audited.put(AuditRecord.ISSUED_CONFIRMATION_CODE, issued.code());
        try {
            d.audit().append(call.record(d.ids().get(), AuditRecord.Step.PREVIEW, audited,
                AuditOutcome.CHALLENGE_ISSUED, Optional.of("Preview of the import of "
                    + changes.scope().describe() + ", code valid until " + issued.expiresAt())));
        } catch (RuntimeException e) {
            d.challenges().discard(issued.code());
            call.refused = true;
            throw auditFailure(node, e, "the preview was not issued");
        }
        LOG.info("import_artifacts on {}: CHALLENGE_ISSUED", node);
        return new Response.Challenge(new ImportPreview(node, changes.scope().describe(),
            changes.baseCommit(), changes.created(), changes.modified(), changes.notImported(),
            warnings, call.kind, issued.code(), issued.expiresAt(), "Nothing was sent. To"
                + " import " + changes.artifacts().size() + " artifact(s) of "
                + changes.scope().describe() + " into " + node + ", show this preview to the"
                + " user and, after their explicit approval, call import_artifacts again with"
                + " the same inputs and confirmationCode before " + issued.expiresAt() + "."));
    }

    // --- execution ---------------------------------------------------------------------------

    private Response execute(Call call, NodeId node, ChangeSet changes, UUID auditId,
        ConflictDetector.Result fresh, ImportArchivePort.Archive archive, Account account) {
        ImportScope scope = changes.scope();
        String ref = auditId.toString();
        d.backups().write(new BackupStore.Manifest(ref, node, call.owner, scope.describe(),
            names(changes.artifacts()), changes.created(), Map.of(), "PENDING",
            d.clock().instant(), List.of()), fresh.rawExports());
        call.backupRef = ref;
        try {
            d.audit().append(call.record(auditId, AuditRecord.Step.EXECUTE, call.inputs(),
                AuditOutcome.PENDING, Optional.of("About to import " + changes.artifacts().size()
                    + " artifact(s) of " + scope.describe())));
        } catch (RuntimeException e) {
            call.refused = true;
            throw auditFailure(node, e, "nothing was sent");
        }
        call.sent = auditId;
        ImportPort.Mode mode = scope.diagramGroup().isPresent() ? ImportPort.Mode.WORKFLOW
            : ImportPort.Mode.MODULE;
        List<String> warnings = new ArrayList<>();
        List<String> reports = new ArrayList<>();
        WriteOutcome.Failure failure = null;
        boolean timedOut = false;
        try {
            ImportProtocol protocol = d.imports().apply(node).importArchive(archive.zip(), mode,
                call.owner, call.kind);
            Optional<String> mismatch = protocolMismatch(changes, protocol);
            if (mismatch.isPresent()) {
                failure = new WriteOutcome.Failure(ErrorCode.IMPORT_FAILED, "protocol",
                    mismatch.get());
            }
        } catch (ToolErrorException e) {
            timedOut = e.error().code() == ErrorCode.TIMEOUT;
            failure = new WriteOutcome.Failure(ErrorCode.IMPORT_FAILED, "import",
                e.error().code() + ": " + e.error().message());
        } catch (RuntimeException e) {
            LOG.error("The import on {} failed unexpectedly", node, e);
            failure = new WriteOutcome.Failure(ErrorCode.IMPORT_FAILED, "import",
                "INTERNAL: the import failed unexpectedly (" + e.getClass().getSimpleName()
                    + ")");
        }

        Exported current = export(node, changes);
        if (failure == null || timedOut) {
            if (current.failure().isPresent()) {
                if (failure == null) {
                    failure = new WriteOutcome.Failure(ErrorCode.VERIFY_MISMATCH, "verify",
                        "The re-export of " + scope.describe() + " failed: "
                            + current.failure().get());
                }
            } else {
                List<String> differences = verify(changes, current.files(), call.request.reason());
                if (differences.isEmpty()) {
                    if (timedOut) {
                        warnings.add("StartCLI timed out, but the re-export shows the import;"
                            + " it was verified");
                    }
                    return success(call, node, changes, auditId, current, warnings);
                }
                if (failure == null) {
                    String report = report("verify-" + auditId + ".diff", differences);
                    reports.add(report);
                    failure = new WriteOutcome.Failure(ErrorCode.VERIFY_MISMATCH, "verify",
                        "The re-export differs from the intended state in "
                            + differences.stream().filter(line -> line.startsWith("=== "))
                                .count() + " file(s); see " + report);
                }
            }
        }
        if (current.failure().isPresent()) {
            current = export(node, changes);
        }
        WriteOutcome.Rollback rollback = rollback(call, node, changes, auditId, current,
            account);
        return failed(call, node, changes, auditId, failure, rollback, current, reports,
            warnings);
    }

    private Response success(Call call, NodeId node, ChangeSet changes, UUID auditId,
        Exported current, List<String> warnings) {
        writeBack(changes, current.files());
        Optional<String> commit = d.history().commitAll("import " + node.value() + ": "
                + changes.scope().describe() + " (" + changes.artifacts().size()
                + " artifacts) [" + auditId + "]",
            Map.of(VersionHistoryPort.SERVER_STATE, node.group().value()))
            .map(entry -> entry.commit());
        updateManifest(call, node, changes, auditId, "EXECUTED", current.files());
        String message = "Imported " + changes.artifacts().size() + " artifact(s) of "
            + changes.scope().describe() + " and verified them" + commit.map(c -> " (" + c
                + ")").orElse("");
        appendFinal(call, node, auditId, AuditOutcome.EXECUTED, message);
        LOG.info("import_artifacts on {}: EXECUTED (audit id {})", node, auditId);
        return new Response.Completed(new WriteOutcome(auditId, WriteOutcome.Outcome.EXECUTED,
            Optional.empty(), commit, Optional.of(auditId.toString()), changes.created(),
            changes.modified(), changes.notImported(), Optional.empty(), List.of(), List.of(),
            warnings));
    }

    private Response failed(Call call, NodeId node, ChangeSet changes, UUID auditId,
        WriteOutcome.Failure failure, WriteOutcome.Rollback rollback, Exported current,
        List<String> reports, List<String> warnings) {
        WriteOutcome.Failure reported = failure;
        if (rollback == WriteOutcome.Rollback.FAILED) {
            reported = new WriteOutcome.Failure(failure.code(), failure.step(), failure.message()
                + "; the rollback failed: the backup " + auditId + " is kept — restore it with"
                + " restore_backup or in the Workbench");
        }
        List<String> createdNotRemoved = changes.artifacts().stream()
            .filter(artifact -> artifact.kind() == ChangedArtifact.Kind.NEW)
            .filter(artifact -> current.failure().isPresent()
                || !artifactFiles(current.files(), artifact).isEmpty())
            .map(ChangedArtifact::name).toList();
        if (!createdNotRemoved.isEmpty()) {
            warnings.add("Created and not removed (nothing is ever deleted): " + String.join(
                ", ", createdNotRemoved) + " — delete them in the Workbench if needed");
        }
        updateManifest(call, node, changes, auditId, "FAILED", current.files());
        call.rollback = rollback.name();
        appendFinal(call, node, auditId, AuditOutcome.FAILED, reported.code() + " at "
            + reported.step() + ": " + reported.message() + " (rollback " + rollback + ")");
        LOG.warn("import_artifacts on {}: FAILED at {} (audit id {}, rollback {})", node,
            reported.step(), auditId, rollback);
        return new Response.Completed(new WriteOutcome(auditId, WriteOutcome.Outcome.FAILED,
            Optional.of(reported), Optional.empty(), Optional.of(auditId.toString()),
            changes.created(), changes.modified(), changes.notImported(),
            Optional.of(rollback), createdNotRemoved, reports, warnings));
    }

    /**
     * Research D-10, D-25: the modified artifacts go back to the backup's state with the
     * target's current secrets, if they changed; verified by another export.
     */
    private WriteOutcome.Rollback rollback(Call call, NodeId node, ChangeSet changes,
        UUID auditId, Exported current, Account account) {
        List<ChangedArtifact> existing = changes.artifacts().stream()
            .filter(artifact -> artifact.kind() == ChangedArtifact.Kind.MODIFIED).toList();
        if (existing.isEmpty()) {
            return WriteOutcome.Rollback.NOT_NEEDED;
        }
        try {
            ImportScope scope = changes.scope();
            List<byte[]> backup = d.backups().exports(auditId.toString());
            SortedMap<String, byte[]> before = d.codec().prepare(scope.group(), call.owner,
                backup).files();
            if (current.failure().isEmpty() && sameState(existing, before, current.files())) {
                return WriteOutcome.Rollback.NOT_NEEDED;
            }
            if (current.failure().isPresent()) {
                return WriteOutcome.Rollback.FAILED;
            }
            ImportArchivePort.Archive archive = d.archives().assemble(build(new ChangeSet(scope,
                    changes.baseCommit(), existing.stream().filter(a -> a.ref().kind()
                        == ArtifactRef.Kind.WORKFLOW)
                        .toList(), existing.stream().filter(a -> a.ref().kind()
                        == ArtifactRef.Kind.MODULE)
                        .toList(), List.of()), call, before, current.raw(),
                "Rollback of import " + auditId, account, Set.of()));
            try {
                d.imports().apply(node).importArchive(archive.zip(), scope.diagramGroup()
                    .isPresent() ? ImportPort.Mode.WORKFLOW : ImportPort.Mode.MODULE,
                    call.owner, call.kind);
            } catch (ToolErrorException e) {
                LOG.warn("The rollback import on {} failed: {}", node, e.error().code());
            }
            Exported after = export(node, changes);
            return after.failure().isEmpty() && sameState(existing, before, after.files())
                ? WriteOutcome.Rollback.SUCCEEDED : WriteOutcome.Rollback.FAILED;
        } catch (RuntimeException e) {
            LOG.error("The rollback on {} failed", node, e);
            return WriteOutcome.Rollback.FAILED;
        }
    }

    // --- steps -------------------------------------------------------------------------------

    /** The checks of feature 003 on the change set; an ERROR refuses (FR-007). */
    private int check(NodeId node, ChangeSet changes, UUID auditId) {
        List<CheckFinding> findings = d.checks().checkPaths(changes.paths(), false);
        List<CheckFinding> errors = findings.stream()
            .filter(f -> f.severity() == CheckFinding.Severity.ERROR).toList();
        if (!errors.isEmpty()) {
            List<String> lines = findings.stream().map(f -> f.severity() + " " + f.code() + " "
                + f.path() + f.location().map(l -> " " + l).orElse("") + ": " + f.message())
                .toList();
            String report = report("import-check-" + auditId + ".txt", lines);
            Set<String> codes = new TreeSet<>(errors.stream().map(CheckFinding::code).toList());
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The checks found " + errors.size() + " error(s) (" + String.join(", ", codes)
                    + "); findings: " + report + " ; nothing was sent",
                "The change set would break the artifacts in INUBIT, which does not validate"
                    + " imports",
                "Fix the findings (check_artifacts shows them), then import again")
                .withNode(node));
        }
        return findings.size();
    }

    /** Research D-25 (H6): a referenced module must be imported or exist on the target. */
    private void requireReferencedModules(NodeId node, ChangeSet changes,
        Set<String> targetModules) {
        Set<String> available = new HashSet<>(targetModules);
        changes.modules().forEach(module -> available.add(module.name()));
        Set<String> missing = new TreeSet<>();
        for (ChangedArtifact workflow : changes.workflows()) {
            d.inspector().workflow(d.root().resolve(workflow.paths().get(0))).nodes().stream()
                .filter(n -> n.moduleType().equals("technical"))
                .map(WorkflowGraph.Node::moduleName)
                .filter(name -> !available.contains(name)).forEach(missing::add);
        }
        if (!missing.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The workflows reference module(s) that are neither imported nor on " + node
                    + ": " + String.join(", ", missing) + "; nothing was sent",
                "INUBIT accepts such a workflow, but afterwards the whole diagram group can no"
                    + " longer be exported (spike)",
                "Create the module in the workspace (it is imported with the workflow) or fix"
                    + " the reference").withNode(node));
        }
    }

    /** The protocol must name exactly the change set (research D-8). */
    private static Optional<String> protocolMismatch(ChangeSet changes,
        ImportProtocol protocol) {
        Set<String> created = new TreeSet<>(protocol.created());
        Set<String> modified = new TreeSet<>(protocol.modified());
        Set<String> expectedCreated = new TreeSet<>(changes.created());
        Set<String> expectedModified = new TreeSet<>(changes.modified());
        List<String> other = protocol.entries().stream().filter(e -> e.action().isEmpty())
            .map(ImportProtocol.Entry::description).toList();
        if (created.equals(expectedCreated) && modified.equals(expectedModified)
            && other.isEmpty()) {
            return Optional.empty();
        }
        Set<String> unexpected = new TreeSet<>(created);
        unexpected.addAll(modified);
        unexpected.removeAll(expectedCreated);
        unexpected.removeAll(expectedModified);
        Set<String> missing = new TreeSet<>(expectedCreated);
        missing.addAll(expectedModified);
        missing.removeAll(created);
        missing.removeAll(modified);
        return Optional.of("INUBIT's protocol does not name exactly the sent artifacts"
            + (unexpected.isEmpty() ? "" : "; not sent but listed: " + String.join(", ",
                unexpected)) + (missing.isEmpty() ? "" : "; sent but missing or with another"
                + " action: " + String.join(", ", missing)) + (other.isEmpty() ? ""
                : "; other rows: " + String.join(" / ", other)));
    }

    /**
     * Research D-9: every change-set artifact's files equal the workspace (reviewed content),
     * and a workflow's check-in comment carries the reason.
     */
    private List<String> verify(ChangeSet changes, SortedMap<String, byte[]> rendered,
        String reason) {
        List<String> differences = new ArrayList<>();
        for (ChangedArtifact artifact : changes.artifacts()) {
            Set<String> paths = new TreeSet<>(artifact.paths());
            paths.addAll(artifactFiles(rendered, artifact).keySet());
            for (String path : paths) {
                byte[] expected = readOrNull(path);
                byte[] actual = rendered.get(path);
                if (!d.archives().equivalent(path, expected, actual)) {
                    differences.add("=== " + path);
                    differences.add("--- workspace: " + (expected == null ? "(missing)"
                        : expected.length + " bytes"));
                    differences.add("+++ " + changes.scope().describe() + " on the server: "
                        + (actual == null ? "(missing)" : actual.length + " bytes"));
                    StringBuilder diff = new StringBuilder();
                    ConflictDetector.LineDiff.append(diff, path, "workspace", expected, actual);
                    differences.addAll(diff.toString().lines().toList());
                }
            }
            if (artifact.ref().kind() == ArtifactRef.Kind
                .WORKFLOW) {
                byte[] file = rendered.get(artifact.paths().get(0));
                Optional<String> comment = file == null ? Optional.empty()
                    : d.archives().checkinComment(file);
                if (file != null && comment.filter(c -> c.contains(COMMENT_PREFIX + reason))
                    .isEmpty()) {
                    differences.add("=== " + artifact.paths().get(0)
                        + ": the check-in comment does not carry the reason");
                }
            }
        }
        return differences;
    }

    /** Research D-25 (H5): only the change-set files and their .meta records. */
    private void writeBack(ChangeSet changes, SortedMap<String, byte[]> rendered) {
        try {
            for (ChangedArtifact artifact : changes.artifacts()) {
                for (Map.Entry<String, byte[]> file : artifactFiles(rendered, artifact)
                    .entrySet()) {
                    write(file.getKey(), file.getValue());
                    byte[] meta = rendered.get(".meta/" + file.getKey() + ".json");
                    if (meta != null) {
                        write(".meta/" + file.getKey() + ".json", meta);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(String path, byte[] content) throws IOException {
        Path file = d.root().resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    /** The rendered files of {@code artifact}: its workflow file or its module directory. */
    private static SortedMap<String, byte[]> artifactFiles(SortedMap<String, byte[]> rendered,
        ChangedArtifact artifact) {
        String key = ConflictDetector.key(artifact.paths().get(0));
        SortedMap<String, byte[]> files = new TreeMap<>();
        rendered.forEach((path, content) -> {
            if (path.equals(key) || path.startsWith(key + "/")) {
                files.put(path, content);
            }
        });
        return files;
    }

    private boolean sameState(List<ChangedArtifact> artifacts, SortedMap<String, byte[]> before,
        SortedMap<String, byte[]> now) {
        for (ChangedArtifact artifact : artifacts) {
            Set<String> paths = new TreeSet<>(artifactFiles(before, artifact).keySet());
            paths.addAll(artifactFiles(now, artifact).keySet());
            for (String path : paths) {
                if (!d.archives().equivalent(path, before.get(path), now.get(path))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The scope's current state on the node, or why it cannot be read. */
    private record Exported(List<byte[]> raw, SortedMap<String, byte[]> files,
        Optional<String> failure) {
    }

    private Exported export(NodeId node, ChangeSet changes) {
        ImportScope scope = changes.scope();
        try {
            ArtifactPort port = d.artifacts().apply(node);
            List<byte[]> raw = new ArrayList<>();
            if (scope.diagramGroup().isPresent()) {
                raw.add(port.exportWorkflowGroup(scope.owner(), scope.diagramGroup().get()));
            } else {
                for (ChangedArtifact module : changes.modules()) {
                    try {
                        raw.add(port.exportModule(scope.owner(), module.ref().pluginType()
                            .orElseThrow(), module.name()));
                    } catch (ToolErrorException e) {
                        // review I3: a new module that was not created is simply absent
                        if (module.kind() != ChangedArtifact.Kind.NEW
                            || e.error().code() != ErrorCode.NOT_FOUND) {
                            throw e;
                        }
                    }
                }
            }
            return new Exported(raw, d.codec().prepare(scope.group(), scope.owner(), raw)
                .files(), Optional.empty());
        } catch (ToolErrorException e) {
            return new Exported(List.of(), new TreeMap<>(), Optional.of(e.error().code() + ": "
                + e.error().message()));
        }
    }

    private ImportArchivePort.Build build(ChangeSet changes, Call call,
        SortedMap<String, byte[]> files, List<byte[]> targetExports, String reason,
        Account account, Set<String> takenNames) {
        ImportScope scope = changes.scope();
        return new ImportArchivePort.Build(scope.group(), call.owner, scope.diagramGroup(),
            changes.workflows().stream().map(a -> new ImportArchivePort.Artifact(a.name(),
                Optional.empty(), a.kind() == ChangedArtifact.Kind.NEW)).toList(),
            changes.modules().stream().map(a -> new ImportArchivePort.Artifact(a.name(),
                a.ref().pluginType(), a.kind() == ChangedArtifact.Kind.NEW)).toList(),
            files, targetExports, reason, account.user(), account.host(),
            COMMENT_TIME.format(d.clock().instant()), takenNames);
    }

    /** The workspace files of the change set and their .meta records. */
    private SortedMap<String, byte[]> files(ChangeSet changes) {
        SortedMap<String, byte[]> files = new TreeMap<>();
        for (String path : changes.paths()) {
            byte[] content = readOrNull(path);
            if (content != null) {
                files.put(path, content);
            }
            byte[] meta = readOrNull(".meta/" + path + ".json");
            if (meta != null) {
                files.put(".meta/" + path + ".json", meta);
            }
        }
        // the export records name the source version (archive.properties)
        Path exports = d.root().resolve(".meta").resolve(changes.scope().group().value())
            .resolve(NameCodec.encode(changes.scope().owner())).resolve("exports");
        if (Files.isDirectory(exports)) {
            try (var walk = Files.walk(exports)) {
                for (Path file : walk.filter(Files::isRegularFile).limit(1).toList()) {
                    files.put(d.root().relativize(file).toString().replace('\\', '/'),
                        Files.readAllBytes(file));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    /** Names a new artifact must not use (D-25 H9): the owner's diagrams and modules. */
    private Set<String> takenNames(NodeId node, ChangeSet changes, String owner,
        Set<String> targetModules) {
        if (changes.created().isEmpty()) {
            return Set.of();
        }
        Set<String> taken = new HashSet<>(targetModules);
        if (changes.workflows().stream().anyMatch(w -> w.kind() == ChangedArtifact.Kind.NEW)) {
            d.inventory().apply(node).listDiagrams(owner).forEach(item -> taken.add(
                item.name()));
        }
        return taken;
    }

    private void updateManifest(Call call, NodeId node, ChangeSet changes, UUID auditId,
        String outcome, SortedMap<String, byte[]> rendered) {
        try {
            Map<String, String> intended = new TreeMap<>();
            for (ChangedArtifact artifact : changes.artifacts()) {
                SortedMap<String, byte[]> files = artifactFiles(rendered, artifact);
                if (!files.isEmpty()) {
                    intended.put(artifact.name(), ConflictDetector.fingerprint(files));
                }
            }
            BackupStore.Manifest manifest = d.backups().find(auditId.toString()).orElseThrow();
            d.backups().update(new BackupStore.Manifest(manifest.auditId(), manifest.node(),
                manifest.owner(), manifest.scope(), manifest.changeSet(), manifest.created(),
                intended, outcome, manifest.takenAt(), manifest.zips()));
        } catch (RuntimeException e) {
            LOG.error("The backup manifest {} of {} could not be updated ({})", auditId, node,
                e.getClass().getSimpleName());
        }
    }

    private void appendFinal(Call call, NodeId node, UUID auditId, AuditOutcome outcome,
        String reason) {
        try {
            d.audit().append(call.record(auditId, AuditRecord.Step.EXECUTE, call.inputs(),
                outcome, Optional.of(reason)));
        } catch (RuntimeException e) {
            LOG.error("The final audit record {} on {} could not be written ({})", auditId,
                node, e.getClass().getSimpleName());
        }
    }

    /** Backups older than 30 days, except the newest per scope; each removal is audited. */
    private void sweep(Call call) {
        BackupStore.Sweep sweep;
        try {
            sweep = d.backups().sweep();
        } catch (RuntimeException e) {
            LOG.warn("The backup retention sweep failed ({})", e.getClass().getSimpleName());
            return;
        }
        for (BackupStore.Removed removed : sweep.removed()) {
            Map<String, String> inputs = new LinkedHashMap<>();
            inputs.put("backupRef", removed.auditId());
            inputs.put("scope", removed.scope());
            inputs.put("owner", removed.owner());
            d.audit().append(new AuditRecord(d.ids().get(), d.clock().instant(), d.profile(),
                removed.node().value(), Optional.of(removed.node().group().value()), RETENTION,
                AuditRecord.Step.EXECUTE, inputs, Optional.empty(), AuditOutcome.EXECUTED,
                Optional.of("Removed the backup taken at " + removed.takenAt() + " (older than"
                    + " 30 days and not the newest of its scope)"), call.request.mcpClient()));
        }
        if (sweep.skipped() > 0) {
            LOG.warn("{} backup manifest(s) could not be trusted and were kept", sweep.skipped());
        }
    }

    private void commitLocalChanges() {
        List<PathChange> changes = d.history().status();
        boolean onlyGitignore = changes.stream().allMatch(change -> change.path()
            .equals(".gitignore") && change.kind() == PathChange.Kind.ADDED);
        if (!onlyGitignore) {
            d.history().commitAll("local changes: " + changes.size() + " files");
        }
    }

    private static ImportScope scope(Call call, NodeId node) {
        ImportRequest request = call.request;
        return request.diagramGroup().map(group -> ImportScope.diagramGroup(node.group(),
            call.owner, group)).orElseGet(() -> ImportScope.modules(node.group(), call.owner,
            request.modules()));
    }

    private static void validate(ImportRequest request) {
        if (!REASON.matcher(request.reason()).matches() || request.reason().isBlank()) {
            throw invalid("Invalid reason: it must be 1-500 characters without #, @ and"
                    + " control characters",
                "The reason becomes the check-in comment; # and @ separate its segments",
                "Give a short plain-text reason");
        }
        if (request.confirmationCode().filter(code -> !CODE.matcher(code).matches())
            .isPresent()) {
            throw invalid("Invalid confirmationCode", "A confirmation code has 22 URL-safe"
                + " Base64 chars", "Use the code of the preview exactly as returned");
        }
        boolean group = request.diagramGroup().filter(g -> !g.isBlank()).isPresent();
        if (group == !request.modules().isEmpty()) {
            throw invalid("Give either diagramGroup or modules, not both and not neither",
                "One import covers one diagram group or single modules",
                "Call import_artifacts once per scope");
        }
        if (request.modules().size() > MAX_MODULES) {
            throw invalid("At most " + MAX_MODULES + " modules per call",
                "The module import is bounded", "Split the modules into several calls");
        }
    }

    private String inputFingerprint(NodeId node, Call call) {
        ImportRequest request = call.request;
        StringBuilder text = new StringBuilder(node.value()).append('\n').append(call.owner)
            .append('\n').append(request.diagramGroup().orElse("")).append('\n');
        request.modules().forEach(module -> text.append(module.name()).append('/')
            .append(module.pluginType().orElse("")).append('\n'));
        return "sha256:" + sha256(text.append(request.reason()).toString()
            .getBytes(StandardCharsets.UTF_8));
    }

    private String workspaceState(ChangeSet changes) {
        StringBuilder text = new StringBuilder(changes.baseCommit()).append('\n');
        List<byte[]> contents = new ArrayList<>();
        for (ChangedArtifact artifact : changes.artifacts()) {
            text.append(artifact.kind()).append(' ').append(artifact.name()).append(' ')
                .append(artifact.base().orElse("")).append('\n');
            for (String path : artifact.paths()) {
                text.append(path).append('\n');
                contents.add(Optional.ofNullable(readOrNull(path)).orElse(new byte[0]));
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(text.toString().getBytes(StandardCharsets.UTF_8));
            contents.forEach(content -> {
                digest.update(content);
                digest.update((byte) 0);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> parseState(String state) {
        Map<String, String> parts = new LinkedHashMap<>();
        for (String part : state.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                parts.put(part.substring(0, equals), part.substring(equals + 1));
            }
        }
        return parts;
    }

    private byte[] readOrNull(String path) {
        Path file = d.root().resolve(path);
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String report(String name, List<String> lines) {
        Path file = d.root().resolve(REPORTS).resolve(name);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return REPORTS + "/" + name;
    }

    private static List<String> names(List<ChangedArtifact> artifacts) {
        return artifacts.stream().map(ChangedArtifact::name).toList();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolError unexpected(String step, RuntimeException e) {
        LOG.error("Unexpected failure while {}", step, e);
        return ToolError.of(ErrorCode.INTERNAL, "Unexpected failure while " + step + " ("
                + e.getClass().getSimpleName() + "); nothing was sent",
            "An internal error of the INUBIT MCP server",
            "Retry; if it persists, check the MCP server log on stderr and report the problem");
    }

    private static ToolErrorException invalid(String message, String likelyCause,
        String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message
            + "; nothing was sent", likelyCause, nextStep));
    }

    private static ToolErrorException auditFailure(NodeId node, RuntimeException e,
        String consequence) {
        LOG.error("Audit record could not be written for {} ({})", node,
            e.getClass().getSimpleName());
        return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
            "The audit record could not be written; " + consequence,
            "The audit directory is not writable, full, or not owned by this user",
            "Fix the audit directory (auditDirectory in the configuration), then retry")
            .withNode(node));
    }

    /** One call: its request, what is known so far and the refusal path. */
    private final class Call {

        final ImportRequest request;
        DevelopmentPolicy policy;
        String owner;
        OwnerKind kind;
        ChangeSet changes;
        String backupRef;
        String rollback;
        boolean refused;
        /** The audit id of the execution once the import may have been sent. */
        UUID sent;

        Call(ImportRequest request) {
            this.request = request;
        }

        /** The sanitized inputs, in a fixed order; never content or secrets. */
        Map<String, String> inputs() {
            Map<String, String> inputs = new LinkedHashMap<>();
            inputs.put("reason", bounded(request.reason()));
            inputs.put("scope", bounded(request.diagramGroup().map(g -> "diagram group " + g)
                .orElseGet(() -> "modules " + String.join(", ", request.modules().stream()
                    .map(ImportScope.Module::name).toList()))));
            if (owner != null) {
                inputs.put("owner", bounded(owner));
            }
            if (kind != null) {
                inputs.put("ownerKind", kind.name());
            }
            if (changes != null) {
                List<String> names = names(changes.artifacts());
                inputs.put("changeSet", names.size() <= MAX_AUDITED_NAMES
                    ? String.join(", ", names)
                    : String.join(", ", names.subList(0, MAX_AUDITED_NAMES)) + ", …+"
                        + (names.size() - MAX_AUDITED_NAMES));
            }
            request.confirmationCode().ifPresent(code ->
                inputs.put(AuditRecord.CONFIRMATION_CODE, code));
            if (backupRef != null) {
                inputs.put("backupRef", backupRef);
            }
            if (rollback != null) {
                inputs.put("rollback", rollback);
            }
            return inputs;
        }

        AuditRecord record(UUID auditId, AuditRecord.Step step, Map<String, String> inputs,
            AuditOutcome outcome, Optional<String> reason) {
            Optional<NodeId> node = node();
            return new AuditRecord(auditId, d.clock().instant(), d.profile(),
                node.map(NodeId::value).orElse(bounded(request.node())), group(), CAPABILITY,
                step, inputs, node.map(id -> d.accounts().apply(id).user()), outcome,
                reason.map(Call::bounded), request.mcpClient());
        }

        void append(UUID auditId, AuditRecord.Step step, AuditOutcome outcome, String reason) {
            d.audit().append(record(auditId, step, inputs(), outcome, Optional.of(reason)));
        }

        /**
         * An unexpected failure after the import may have been sent: audited {@code FAILED}
         * with the same audit id, never as a refusal; the backup holds the state before.
         */
        ToolErrorException failedAfterSending(RuntimeException e) {
            LOG.error("import_artifacts failed unexpectedly after sending (audit id {})", sent, e);
            String message = "Unexpected failure after the import was sent ("
                + e.getClass().getSimpleName() + "): the change set may have been imported"
                + " without verification; the backup " + sent + " holds the state before";
            try {
                d.audit().append(record(sent, AuditRecord.Step.EXECUTE, inputs(),
                    AuditOutcome.FAILED, Optional.of(ErrorCode.INTERNAL + ": " + message)));
            } catch (RuntimeException audit) {
                LOG.error("The final audit record {} could not be written ({})", sent,
                    audit.getClass().getSimpleName());
            }
            return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL, message,
                "An internal error of the INUBIT MCP server",
                "Export the scope and compare it; restore the backup with restore_backup if"
                    + " needed, and report the problem with the MCP server log")
                .withNode(policy.node()));
        }

        /** Audits {@code error} as {@code REFUSED}; discards a presented code. */
        ToolErrorException refuse(ToolError error) {
            refused = true;
            request.confirmationCode().ifPresent(d.challenges()::discard);
            boolean execute = request.confirmationCode().isPresent() || (policy != null
                && policy.confirmation() == WritePolicy.Confirmation.CLIENT);
            try {
                d.audit().append(record(d.ids().get(), execute ? AuditRecord.Step.EXECUTE
                    : AuditRecord.Step.PREVIEW, inputs(), AuditOutcome.REFUSED,
                    Optional.of(error.code() + ": " + error.message())));
            } catch (RuntimeException e) {
                LOG.error("Audit record of a refusal ({}) could not be written ({})",
                    error.code(), e.getClass().getSimpleName());
                return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                    "The audit record could not be written; the call was refused anyway ("
                        + error.code() + ": " + error.message() + ") and nothing was sent",
                    "The audit directory is not writable, full, or not owned by this user",
                    "Fix the audit directory (auditDirectory in the configuration), then"
                        + " retry"));
            }
            LOG.info("import_artifacts on {}: REFUSED ({})", bounded(request.node()),
                error.code());
            return new ToolErrorException(error);
        }

        private Optional<NodeId> node() {
            if (policy != null) {
                return Optional.of(policy.node());
            }
            try {
                NodeId id = NodeId.parse(request.node());
                return d.policies().apply(id) == null ? Optional.empty() : Optional.of(id);
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        private Optional<String> group() {
            try {
                return Optional.of(switch (Target.parse(request.node())) {
                    case Target.Group group -> group.id().value();
                    case Target.Node node -> node.id().group().value();
                });
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        private static String bounded(String value) {
            return value.length() <= MAX_AUDITED_INPUT ? value
                : value.substring(0, MAX_AUDITED_INPUT);
        }
    }
}
