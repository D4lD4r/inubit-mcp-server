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
import de.dadecker.inubit.mcp.domain.model.PathChange;
import de.dadecker.inubit.mcp.domain.model.Target;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.model.WritePreview;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import de.dadecker.inubit.mcp.domain.port.ArtifactInspectorPort;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.ImportArchivePort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
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
import java.util.Arrays;
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
 *   <li>with a confirmation code: the code must match the inputs and the workspace of its
 *       preview ({@code CONFIRMATION_INVALID} otherwise, "preview again");
 *   <li>the conflict check on a fresh export ({@link ConflictDetector}), repeated right before
 *       every import; every module a workflow references must be imported or exist on the
 *       target; with a code, a server state other than the previewed one is {@code CONFLICT};
 *   <li>the import archive with only the change set and the target's current secrets;
 *   <li>server confirmation without a code: the preview with a code, audited — nothing sent;
 *   <li>the backup (the raw export of the scope and a manifest), then the {@code PENDING}
 *       audit record (fail closed), then the StartCLI import — always with
 *       {@code --importUser <owner>}, for users and user groups alike (research D-26);
 *   <li>the protocol must name exactly the change set; the scope is exported again and every
 *       change-set artifact must equal its workspace file (reviewed content) with exactly the
 *       reason as the person-written segment of its check-in comment (workflows and module
 *       index entries);
 *   <li>success: only the change-set files and their {@code .meta/} records are replaced by the
 *       verified state and committed with the {@code Server-State} trailer; then, if a
 *       {@code tag} was requested (diagram-group imports only), the diagram group is tagged and
 *       verified like {@code tag_artifacts} ({@link DiagramGroupTagger}) within the same call
 *       and audit record — a tag failure never undoes the import (research D-26);
 *   <li>failure after anything was sent (a refused import, a protocol mismatch, a differing or
 *       failing re-export, a timeout whose re-export does not show the intended state, or an
 *       unexpected failure of the server itself, reported at the step it reached): the
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
    private static final String RETENTION = "backup_retention";
    private static final Pattern REASON = Pattern.compile("^[^#@\\p{Cntrl}]{1,500}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{22}$");
    /** The names StartCLI quoting can carry (research R-11, {@code CliCommand.VALUE}). */
    private static final Pattern TAG =
        Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$");
    private static final Pattern AUDIT_ID = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final String DIAGRAM_GROUP = "diagram group ";
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
        Function<NodeId, ImportPort> imports, Function<NodeId, TagPort> tags,
        Function<NodeId, InventoryPort> inventory,
        Function<NodeId, Optional<String>> defaultOwners,
        Function<NodeId, Account> accounts, WriteChallengeRegistry challenges,
        BackupStore backups, AuditPort audit, Clock clock, Supplier<UUID> ids,
        Optional<NodeDeployer> deployments) {

        /** Without a stage chain: deployment backups cannot be restored (feature 004). */
        public Dependencies(Path root, String profile, DevelopmentGuard guard,
            Function<NodeId, DevelopmentPolicy> policies, VersionHistoryPort history,
            ArtifactInspectorPort inspector, ArtifactCheckService checks, ArchiveCodecPort codec,
            ImportArchivePort archives, Function<NodeId, ArtifactPort> artifacts,
            Function<NodeId, ImportPort> imports, Function<NodeId, TagPort> tags,
            Function<NodeId, InventoryPort> inventory,
            Function<NodeId, Optional<String>> defaultOwners,
            Function<NodeId, Account> accounts, WriteChallengeRegistry challenges,
            BackupStore backups, AuditPort audit, Clock clock, Supplier<UUID> ids) {
            this(root, profile, guard, policies, history, inspector, checks, codec, archives,
                artifacts, imports, tags, inventory, defaultOwners, accounts, challenges,
                backups, audit, clock, ids, Optional.empty());
        }

        public Dependencies {
            deployments = deployments == null ? Optional.empty() : deployments;
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
            Objects.requireNonNull(tags, "tags");
            Objects.requireNonNull(inventory, "inventory");
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
     * {@code modules}; {@code owner} defaults to the node's {@code inventory.owner}; the optional
     * {@code tag} is set on the diagram group after the verified import (research D-26, only
     * with {@code diagramGroup}).
     */
    public record ImportRequest(String node, Optional<String> owner,
        Optional<String> diagramGroup, List<ImportScope.Module> modules, String reason,
        Optional<String> confirmationCode, Optional<String> mcpClient, Optional<String> tag) {

        public ImportRequest {
            node = node == null ? "" : node;
            owner = owner == null ? Optional.empty() : owner;
            diagramGroup = diagramGroup == null ? Optional.empty() : diagramGroup;
            modules = modules == null ? List.of() : List.copyOf(modules);
            reason = reason == null ? "" : reason;
            confirmationCode = confirmationCode == null ? Optional.empty() : confirmationCode;
            mcpClient = mcpClient == null ? Optional.empty() : mcpClient;
            tag = tag == null ? Optional.empty() : tag;
        }

        /** A request without a tag. */
        public ImportRequest(String node, Optional<String> owner, Optional<String> diagramGroup,
            List<ImportScope.Module> modules, String reason, Optional<String> confirmationCode,
            Optional<String> mcpClient) {
            this(node, owner, diagramGroup, modules, reason, confirmationCode, mcpClient,
                Optional.empty());
        }
    }

    /**
     * The arguments of {@code restore_backup} (research D-14): the audit id of an earlier call
     * of the development tools whose backup is re-imported.
     */
    public record RestoreRequest(String node, String backupRef, String reason,
        Optional<String> confirmationCode, Optional<String> mcpClient) {

        public RestoreRequest {
            node = node == null ? "" : node;
            backupRef = backupRef == null ? "" : backupRef;
            reason = reason == null ? "" : reason;
            confirmationCode = confirmationCode == null ? Optional.empty() : confirmationCode;
            mcpClient = mcpClient == null ? Optional.empty() : mcpClient;
        }
    }

    /**
     * The arguments of {@code set_active} (research D-15): one workflow of one diagram group;
     * {@code owner} defaults to the node's {@code inventory.owner}.
     */
    public record ActivationRequest(String node, Optional<String> owner, String diagramGroup,
        String workflow, boolean active, String reason, Optional<String> confirmationCode,
        Optional<String> mcpClient) {

        public ActivationRequest {
            node = node == null ? "" : node;
            owner = owner == null ? Optional.empty() : owner;
            diagramGroup = diagramGroup == null ? "" : diagramGroup;
            workflow = workflow == null ? "" : workflow;
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

        /** Nothing was sent; the preview of a restore or an activation and its code. */
        record WriteChallenge(WritePreview preview) implements Response {
            public WriteChallenge {
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
        Call call = new Call(Capability.IMPORT_ARTIFACTS, request.node(), request.reason(),
            request.confirmationCode(), request.mcpClient());
        call.requested.put("scope", request.diagramGroup().map(g -> "diagram group " + g)
            .orElseGet(() -> "modules " + String.join(", ", request.modules().stream()
                .map(ImportScope.Module::name).toList())));
        request.tag().ifPresent(tag -> call.requested.put("tag", tag));
        call.tag = request.tag();
        DevelopmentPolicy policy;
        try {
            validate(request);
            policy = d.guard().admit(request.node(), Capability.IMPORT_ARTIFACTS);
            call.policy = policy;
            call.owner = owner(request.owner(), policy.node(), Capability.IMPORT_ARTIFACTS);
        } catch (ToolErrorException e) {
            throw call.refuse(e.error());
        } catch (RuntimeException e) {
            throw call.refuse(unexpected("checking the request", e));
        }
        return underLock(call, () -> locked(call, policy, request));
    }

    /**
     * Runs {@code body} under the workspace lock (FR-027): a refusal is audited
     * {@code REFUSED}; once anything was sent, only the last resort of
     * {@link Call#failedAfterSending} can still throw.
     */
    private Response underLock(Call call, Supplier<Response> body) {
        try (WorkspaceLock lock = WorkspaceLock.acquire(d.root())) {
            return body.get();
        } catch (RuntimeException e) {
            if (call.sent != null) {
                throw call.failedAfterSending(e);
            }
            if (call.refused) {
                throw e;
            }
            throw e instanceof ToolErrorException tool ? call.refuse(tool.error())
                : call.refuse(unexpected("preparing the " + call.capability.toolName(), e));
        }
    }

    private Response locked(Call call, DevelopmentPolicy policy, ImportRequest request) {
        NodeId node = policy.node();
        sweep(call);
        d.history().init();
        commitLocalChanges();
        ImportScope scope = scope(call, request, node);
        ChangeSet changes = builder.build(scope);
        call.changes = changes;
        if (changes.isEmpty()) {
            UUID auditId = d.ids().get();
            List<String> warnings = new ArrayList<>(List.of("Nothing changed in the workspace"
                + " since the last server state of " + scope.describe() + "; nothing was sent"));
            Optional<WriteOutcome.TagResult> tag = notTagged(call, warnings, "nothing was"
                + " imported");
            call.append(auditId, AuditRecord.Step.EXECUTE, AuditOutcome.EXECUTED,
                "Nothing changed in " + scope.describe() + "; nothing was sent");
            return new Response.Completed(new WriteOutcome(auditId, WriteOutcome.Outcome.EXECUTED,
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                changes.notImported(), Optional.empty(), List.of(), List.of(), warnings, tag));
        }
        UUID auditId = d.ids().get();
        int warnings = check(node, changes, auditId);
        String inputs = inputFingerprint(node, call, request);
        String workspace = workspaceState(changes);
        Optional<String> previewedServer = Optional.empty();
        boolean server = policy.confirmation() == WritePolicy.Confirmation.SERVER;
        if (server && call.confirmationCode.isPresent()) {
            String previewed = d.challenges().redeem(call.confirmationCode.get(),
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
            files(changes), fresh.rawExports(), call.reason, account,
            takenNames(node, changes, call.owner, fresh.targetModules())));
        if (server && call.confirmationCode.isEmpty()) {
            return challenge(call, node, policy, changes, warnings, inputs,
                "workspace=" + workspace + ";server=" + fresh.fingerprint());
        }
        ImportPort.Mode mode = scope.diagramGroup().isPresent() ? ImportPort.Mode.WORKFLOW
            : ImportPort.Mode.MODULE;
        return execute(call, node, new Plan(changes, archive, mode, mode, this::readOrNull,
            "import", scope.describe(), List.of(), List.of()), auditId, fresh.rawExports(),
            account);
    }

    // --- set_active ----------------------------------------------------------------------------

    /**
     * {@code set_active} (US3, FR-020, research D-15, D-24, D-25): activates or deactivates one
     * workflow.
     *
     * <ol>
     *   <li>reason, code and names are checked, then the guard and the owner;
     *   <li>the workspace file of the workflow must be its last server state: unimported edits
     *       are {@code PRECONDITION_FAILED} (they would not be sent, D-24); without an exported
     *       state "export first";
     *   <li>the conflict check on that workflow only (changed on the server, edit mode);
     *   <li>a workflow already in the requested state sends nothing (no new version);
     *   <li>the archive holds that workflow alone, built from the FRESH server export with
     *       {@code IsActive} changed, and is imported with {@code --importWorkflowActive} or
     *       {@code --importWorkflowInactive}; the rest is the import sequence (backup, protocol,
     *       verification incl. {@code IsActive}, commit {@code activate|deactivate <node>: …},
     *       or a rollback with the original flag). INUBIT creates a new version (noted).
     * </ol>
     *
     * @throws ToolErrorException every refusal before anything is sent (after its audit record)
     */
    public Response setActive(ActivationRequest request) {
        Call call = new Call(Capability.SET_ACTIVE, request.node(), request.reason(),
            request.confirmationCode(), request.mcpClient());
        call.requested.put("scope", DIAGRAM_GROUP + request.diagramGroup());
        call.requested.put("workflow", request.workflow());
        call.requested.put("active", String.valueOf(request.active()));
        DevelopmentPolicy policy;
        try {
            validate(request.reason(), request.confirmationCode());
            if (request.diagramGroup().isBlank() || request.workflow().isBlank()) {
                throw invalid("Give diagramGroup and workflow", "set_active switches exactly"
                    + " one workflow", "Name the workflow and its diagram group");
            }
            policy = d.guard().admit(request.node(), Capability.SET_ACTIVE);
            call.policy = policy;
            call.owner = owner(request.owner(), policy.node(), Capability.SET_ACTIVE);
        } catch (ToolErrorException e) {
            throw call.refuse(e.error());
        } catch (RuntimeException e) {
            throw call.refuse(unexpected("checking the request", e));
        }
        return underLock(call, () -> activateLocked(call, policy, request));
    }

    private Response activateLocked(Call call, DevelopmentPolicy policy,
        ActivationRequest request) {
        NodeId node = policy.node();
        sweep(call);
        d.history().init();
        commitLocalChanges();
        String name = request.workflow();
        String file = WorkspacePath.workflow(node.group(), call.owner, request.diagramGroup(),
            name).toRelativePath().toString().replace('\\', '/');
        Optional<String> base = d.history().lastServerState(node.group(), file);
        byte[] current = readOrNull(file);
        if (base.isEmpty() || current == null) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The workflow " + name + " of diagram group " + request.diagramGroup()
                    + " is not in the workspace with an exported server state; nothing was sent",
                "set_active compares the workflow with its last export to detect conflicts",
                "export the diagram group first (export_artifacts), then repeat the call")
                .withNode(node));
        }
        if (!d.history().show(base.get(), file).map(before -> Arrays.equals(before, current))
            .orElse(false)) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The workspace file of " + name + " has unimported edits (" + file
                    + "); nothing was sent",
                "set_active sends the server's state of the workflow, so the edits would"
                    + " silently not be part of it (research D-24)",
                "Import the edits first (import_artifacts), or restore the file (git restore "
                    + file + "), then repeat the call").withNode(node));
        }
        ChangedArtifact workflow = new ChangedArtifact(ArtifactRef.workflow(node.group(),
            call.owner, request.diagramGroup(), name), ChangedArtifact.Kind.MODIFIED,
            List.of(file), base);
        ChangeSet changes = new ChangeSet(ImportScope.diagramGroup(node.group(), call.owner,
            request.diagramGroup()), base.get(), List.of(workflow), List.of(), List.of());
        call.changes = changes;
        UUID auditId = d.ids().get();
        String inputs = "sha256:" + sha256(String.join("\n", node.value(), call.owner,
            request.diagramGroup(), name, String.valueOf(request.active()), call.reason)
            .getBytes(StandardCharsets.UTF_8));
        boolean server = policy.confirmation() == WritePolicy.Confirmation.SERVER;
        Optional<String> previewed = Optional.empty();
        if (server && call.confirmationCode.isPresent()) {
            previewed = Optional.of(d.challenges().redeem(call.confirmationCode.get(),
                Capability.SET_ACTIVE, node, inputs));
        }
        ConflictDetector.Result fresh = detector.detect(node, changes, auditId, false);
        String state = ConflictDetector.fingerprint(artifactFiles(fresh.rendered(), workflow));
        if (previewed.isPresent() && !previewed.get().equals(state)) {
            throw changedSincePreview(node);
        }
        byte[] serverFile = fresh.rendered().get(file);
        boolean wasActive = d.archives().active(serverFile).orElse(false);
        if (wasActive == request.active()) {
            call.append(auditId, AuditRecord.Step.EXECUTE, AuditOutcome.EXECUTED, name
                + " is already " + activity(wasActive) + "; nothing was sent");
            return new Response.Completed(new WriteOutcome(auditId,
                WriteOutcome.Outcome.EXECUTED, Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), List.of(), List.of(), Optional.empty(), List.of(),
                List.of(), List.of("The workflow " + name + " is already " + activity(wasActive)
                    + " on " + node + "; nothing was sent")));
        }
        byte[] intended = d.archives().withActive(serverFile, request.active());
        SortedMap<String, byte[]> files = new TreeMap<>(fresh.rendered());
        files.put(file, intended);
        Account account = d.accounts().apply(node);
        ImportArchivePort.Archive archive = d.archives().assemble(build(changes, call, files,
            fresh.rawExports(), call.reason, account, Set.of()));
        String subject = "workflow " + name + " of diagram group " + request.diagramGroup();
        String change = activity(wasActive) + " → " + activity(request.active());
        if (server && call.confirmationCode.isEmpty()) {
            return writeChallenge(call, node, policy, subject, List.of(name), List.of(
                "IsActive: " + change, "INUBIT creates a new version of " + name), inputs,
                state);
        }
        return execute(call, node, new Plan(changes, archive, mode(request.active()),
            mode(wasActive), path -> path.equals(file) ? intended : fresh.rendered().get(path),
            request.active() ? "activate" : "deactivate", subject, List.of(), List.of(
                "INUBIT created a new version of " + name + " (" + change + ")")), auditId,
            fresh.rawExports(), account);
    }

    private static ImportPort.Mode mode(boolean active) {
        return active ? ImportPort.Mode.WORKFLOW_ACTIVE : ImportPort.Mode.WORKFLOW_INACTIVE;
    }

    private static String activity(boolean active) {
        return active ? "active" : "inactive";
    }

    // --- restore_backup ----------------------------------------------------------------------

    /**
     * {@code restore_backup} (US2, FR-019, research D-14, D-25 H7): re-imports the backup of an
     * earlier call of the development tools on the same node, limited to the artifacts that call
     * changed and that existed before it.
     *
     * <ol>
     *   <li>reason, code and {@code backupRef} (an audit id) are checked, then the guard; the
     *       backup must exist for this node — unknown, removed (retention sweep at the start)
     *       or foreign references are {@code NOT_FOUND} before anything is read from INUBIT;
     *   <li>the backup is rendered in memory (for the backup's owner);
     *   <li>the conflict check compares the scope's fresh export with the state the referenced
     *       call left — the intended-state hashes of its manifest, which a failed call records
     *       as its last verification saw them; an artifact without recorded state is
     *       {@code PRECONDITION_FAILED}, a difference or edit mode {@code CONFLICT};
     *   <li>the archive holds the backed-up artifacts with the target's current secrets and is
     *       imported with the backed-up active flag ({@code --importWorkflowActive|Inactive}
     *       when all its workflows share one); the rest is the import sequence with the
     *       restore's own backup, verification against the backed-up state, commit
     *       {@code restore <node>: …} or rollback;
     *   <li>artifacts the referenced call created stay and are reported (nothing is deleted).
     * </ol>
     *
     * @throws ToolErrorException every refusal before anything is sent (after its audit record)
     */
    public Response restore(RestoreRequest request) {
        Call call = new Call(Capability.RESTORE_BACKUP, request.node(), request.reason(),
            request.confirmationCode(), request.mcpClient());
        call.requested.put("backupRef", request.backupRef());
        DevelopmentPolicy policy;
        try {
            validate(request.reason(), request.confirmationCode());
            if (!AUDIT_ID.matcher(request.backupRef()).matches()) {
                throw invalid("Invalid backupRef: it is the audit id (a lower-case UUID) of an"
                        + " earlier call",
                    "backupRef names the backup of a call of import_artifacts, restore_backup or"
                        + " set_active",
                    "Use the backupRef of that call's result exactly as returned");
            }
            policy = admitRestore(request);
            call.policy = policy;
        } catch (ToolErrorException e) {
            throw call.refuse(e.error());
        } catch (RuntimeException e) {
            throw call.refuse(unexpected("checking the request", e));
        }
        return underLock(call, () -> restoreLocked(call, policy, request.backupRef()));
    }

    /**
     * The guard of {@code restore_backup}; feature 005 (T026): a node that is no development
     * stage is admitted for a {@code DEPLOYMENT} backup of its own if its group receives
     * deployments ({@link DevelopmentGuard#admitDeploymentRestore}). The backup is only looked
     * up locally here; the restore reads it again under the lock.
     */
    private DevelopmentPolicy admitRestore(RestoreRequest request) {
        try {
            return d.guard().admit(request.node(), Capability.RESTORE_BACKUP);
        } catch (ToolErrorException e) {
            if (e.error().code() != ErrorCode.NOT_DEVELOPMENT || d.deployments().isEmpty()
                || e.error().node().isEmpty()) {
                throw e;
            }
            NodeId node = e.error().node().get();
            boolean deployment = d.backups().find(request.backupRef())
                .filter(manifest -> manifest.node().equals(node))
                .filter(manifest -> manifest.kind() == BackupStore.Manifest.Kind.DEPLOYMENT)
                .isPresent();
            if (!deployment) {
                throw e;
            }
            return d.guard().admitDeploymentRestore(request.node());
        }
    }

    private Response restoreLocked(Call call, DevelopmentPolicy policy, String ref) {
        NodeId node = policy.node();
        sweep(call);
        BackupStore.Manifest manifest = d.backups().find(ref)
            .filter(found -> found.node().equals(node))
            .orElseThrow(() -> new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                "There is no backup " + ref + " of " + node + "; nothing was sent",
                "The reference is unknown, belongs to another node, or the backup was removed"
                    + " (backups are kept 30 days; the newest of each scope always)",
                "Use the backupRef of a recent call on this node; for older states use the"
                    + " version history in the Workbench").withNode(node)));
        call.owner = manifest.owner();
        call.requested.put("scope", manifest.scope());
        if (manifest.kind() == BackupStore.Manifest.Kind.DEPLOYMENT) {
            return restoreDeployment(call, policy, manifest);
        }
        d.history().init();
        commitLocalChanges();
        SortedMap<String, byte[]> before = d.codec().prepare(node.group(), call.owner,
            d.backups().exports(ref)).files();
        Optional<ChangeSet> restorable = restoreSet(node, manifest, before);
        if (restorable.isEmpty()) {
            UUID auditId = d.ids().get();
            call.append(auditId, AuditRecord.Step.EXECUTE, AuditOutcome.EXECUTED,
                "Nothing of the backup " + ref + " existed before its call; nothing was sent");
            List<String> warnings = new ArrayList<>(List.of("The call " + ref + " changed"
                + " no artifact that existed before it; nothing was sent"));
            keep(manifest.created(), warnings);
            return new Response.Completed(new WriteOutcome(auditId,
                WriteOutcome.Outcome.EXECUTED, Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), List.of(), List.of(), Optional.empty(),
                manifest.created(), List.of(), warnings));
        }
        ChangeSet changes = restorable.get();
        call.changes = changes;
        UUID auditId = d.ids().get();
        String inputs = "sha256:" + sha256((node.value() + "\n" + ref + "\n" + call.reason)
            .getBytes(StandardCharsets.UTF_8));
        boolean server = policy.confirmation() == WritePolicy.Confirmation.SERVER;
        Optional<String> previewed = Optional.empty();
        if (server && call.confirmationCode.isPresent()) {
            previewed = Optional.of(d.challenges().redeem(call.confirmationCode.get(),
                Capability.RESTORE_BACKUP, node, inputs));
        }
        Fresh fresh = fresh(node, changes);
        requireStateLeftBy(node, manifest, changes, fresh, auditId);
        if (previewed.isPresent() && !previewed.get().equals(fresh.fingerprint())) {
            throw changedSincePreview(node);
        }
        Account account = d.accounts().apply(node);
        ImportArchivePort.Archive archive = d.archives().assemble(build(changes, call, before,
            fresh.raw(), call.reason, account, Set.of()));
        if (server && call.confirmationCode.isEmpty()) {
            List<String> notes = new ArrayList<>(List.of("Re-imports the state before the call "
                + ref + " (backup taken " + manifest.takenAt() + "); each artifact gets a new"
                + " version"));
            if (!manifest.created().isEmpty()) {
                notes.add("Created by that call and not removed: " + String.join(", ",
                    manifest.created()));
            }
            return writeChallenge(call, node, policy, changes.scope().describe(),
                changes.modified(), notes, inputs, fresh.fingerprint());
        }
        return execute(call, node, new Plan(changes, archive, restoreMode(changes, before),
            restoreMode(changes, fresh.rendered()), before::get, "restore",
            changes.scope().describe(), manifest.created(), List.of()), auditId, fresh.raw(),
            account);
    }

    /**
     * Feature 005 (T026): the restore of a deployment backup on its node — always a preview
     * with a server code first (whatever {@code development.confirmation} says); with the code
     * the plan is made again and must show the previewed state, then {@link NodeDeployer}
     * re-imports the artifacts the deployment changed (workflows, modules, repository files)
     * from the backup with the node's current secrets and verifies them. What the deployment
     * created stays and is listed. The workspace is not changed (the next export records the
     * state).
     */
    private Response restoreDeployment(Call call, DevelopmentPolicy admitted,
        BackupStore.Manifest manifest) {
        DevelopmentPolicy policy = d.guard().forDeploymentRestore(admitted);
        call.policy = policy;
        NodeId node = policy.node();
        NodeDeployer deployer = d.deployments().orElseThrow(() -> new ToolErrorException(
            ToolError.of(ErrorCode.PRECONDITION_FAILED, "The backup " + manifest.auditId()
                + " is a deployment backup; nothing was sent",
                "No group of the configuration receives deployments any more",
                "Restore it with the stage chain configured, or in the Workbench")
                .withNode(node)));
        UUID auditId = d.ids().get();
        NodeDeployer.RestorePlan plan = deployer.restorePlan(manifest,
            d.backups().exports(manifest.auditId()), auditId);
        call.requested.put("changeSet", String.join(", ", plan.restored()));
        String inputs = "sha256:" + sha256((node.value() + "\n" + manifest.auditId() + "\n"
            + call.reason).getBytes(StandardCharsets.UTF_8));
        List<String> warnings = new ArrayList<>();
        if (!plan.created().isEmpty()) {
            warnings.add("Created by the deployment " + manifest.auditId() + " and not removed:"
                + " " + String.join(", ", plan.created()));
        }
        if (call.confirmationCode.isEmpty()) {
            List<String> notes = new ArrayList<>(List.of("Re-imports the state of " + node
                + " before the deployment " + manifest.tag().orElse("") + " (backup "
                + manifest.auditId() + ", taken " + manifest.takenAt() + "); each artifact gets"
                + " a new version"));
            notes.addAll(warnings);
            return writeChallenge(call, node, policy, "the deployment " + manifest.auditId(),
                plan.restored(), notes, inputs, plan.fingerprint());
        }
        String previewed = d.challenges().redeem(call.confirmationCode.get(),
            Capability.RESTORE_BACKUP, node, inputs);
        if (!previewed.equals(plan.fingerprint())) {
            throw changedSincePreview(node);
        }
        if (plan.restored().isEmpty()) {
            call.append(auditId, AuditRecord.Step.EXECUTE, AuditOutcome.EXECUTED, "Nothing of"
                + " the deployment " + manifest.auditId() + " differs from its backup; nothing"
                + " was sent");
            return new Response.Completed(new WriteOutcome(auditId,
                WriteOutcome.Outcome.EXECUTED, Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), List.of(), List.of(), Optional.empty(),
                plan.created(), List.of(), warnings));
        }
        call.backupRef = auditId.toString();
        NodeDeployer.Restored restored = deployer.restore(plan, auditId, () -> {
            try {
                d.audit().append(call.record(auditId, AuditRecord.Step.EXECUTE, call.inputs(),
                    AuditOutcome.PENDING, Optional.of("About to restore "
                        + plan.restored().size() + " artifact(s) of " + node + " from the"
                        + " backup " + manifest.auditId())));
            } catch (RuntimeException e) {
                call.refused = true;
                throw auditFailure(node, e, "nothing was sent");
            }
            call.sent = auditId;
        });
        warnings.addAll(restored.warnings());
        warnings.add("The workspace was not changed; the next export of " + node + " records"
            + " the restored state");
        boolean ok = restored.state() == de.dadecker.inubit.mcp.domain.model.DeploymentResult
            .State.ROLLED_BACK;
        call.rollback = ok ? null : "INCOMPLETE";
        appendFinal(call, node, auditId, ok ? AuditOutcome.EXECUTED : AuditOutcome.FAILED,
            (ok ? "Restored and verified " : "The restore is incomplete for ")
                + restored.restored().size() + " artifact(s) of " + node + " from the backup "
                + manifest.auditId());
        LOG.info("restore_backup on {}: {} (audit id {})", node, ok ? "EXECUTED" : "FAILED",
            auditId);
        return new Response.Completed(new WriteOutcome(auditId, ok
            ? WriteOutcome.Outcome.EXECUTED : WriteOutcome.Outcome.FAILED, ok ? Optional.empty()
                : Optional.of(new WriteOutcome.Failure(ErrorCode.VERIFY_MISMATCH, "verify",
                    "The re-export of " + node + " does not show the backed-up state; see the"
                        + " report")), Optional.empty(), Optional.of(restored.backupRef()),
            List.of(), restored.restored(), List.of(), Optional.empty(), plan.created(),
            restored.reports(), warnings));
    }

    /**
     * Review m-b: the import mode that brings back the active flags of {@code state} — with
     * {@code --importWorkflowActive|Inactive} if all workflows of the change set have the same
     * flag there (always for a {@code set_active} call), else plain {@code --importWorkflow}.
     */
    private ImportPort.Mode restoreMode(ChangeSet changes, SortedMap<String, byte[]> state) {
        if (changes.scope().diagramGroup().isEmpty()) {
            return ImportPort.Mode.MODULE;
        }
        Set<Boolean> flags = new HashSet<>();
        for (ChangedArtifact workflow : changes.workflows()) {
            byte[] file = state.get(workflow.paths().get(0));
            Optional<Boolean> active = file == null ? Optional.empty()
                : d.archives().active(file);
            if (active.isEmpty()) {
                return ImportPort.Mode.WORKFLOW;
            }
            flags.add(active.get());
        }
        return flags.size() == 1 ? mode(flags.iterator().next()) : ImportPort.Mode.WORKFLOW;
    }

    /**
     * The artifacts of the referenced call that existed before it, as rendered from its backup;
     * empty if there are none.
     */
    private static Optional<ChangeSet> restoreSet(NodeId node, BackupStore.Manifest manifest,
        SortedMap<String, byte[]> before) {
        String owner = manifest.owner();
        Optional<String> diagramGroup = manifest.scope().startsWith(DIAGRAM_GROUP)
            ? Optional.of(manifest.scope().substring(DIAGRAM_GROUP.length()))
            : Optional.empty();
        Set<String> names = new HashSet<>(manifest.changeSet());
        String base = "backup " + manifest.auditId();
        List<ChangedArtifact> workflows = new ArrayList<>();
        List<ChangedArtifact> modules = new ArrayList<>();
        for (String path : before.keySet()) {
            Optional<WorkspacePath> parsed = workspacePath(path)
                .filter(p -> p.owner().equals(owner));
            if (parsed.isEmpty() || !names.contains(parsed.get().segments().get(1))) {
                continue;
            }
            WorkspacePath artifact = parsed.get();
            String name = artifact.segments().get(1);
            if (artifact.kind() == WorkspacePath.Kind.WORKFLOW
                && diagramGroup.filter(artifact.segments().get(0)::equals).isPresent()) {
                workflows.add(new ChangedArtifact(ArtifactRef.workflow(node.group(), owner,
                    diagramGroup.get(), name), ChangedArtifact.Kind.MODIFIED, List.of(path),
                    Optional.of(base)));
            } else if (artifact.kind() == WorkspacePath.Kind.MODULE_INDEX) {
                String directory = path.substring(0, path.lastIndexOf('/'));
                List<String> paths = before.keySet().stream()
                    .filter(file -> file.startsWith(directory + "/")).toList();
                modules.add(new ChangedArtifact(ArtifactRef.module(node.group(), owner,
                    artifact.segments().get(0), name), ChangedArtifact.Kind.MODIFIED, paths,
                    Optional.of(base)));
            }
        }
        if (workflows.isEmpty() && modules.isEmpty()) {
            return Optional.empty();
        }
        ImportScope scope = diagramGroup.map(group -> ImportScope.diagramGroup(node.group(),
            owner, group)).orElseGet(() -> ImportScope.modules(node.group(), owner,
            modules.stream().map(module -> new ImportScope.Module(module.name(),
                module.ref().pluginType())).toList()));
        return Optional.of(new ChangeSet(scope, base, workflows, modules, List.of()));
    }

    /**
     * Research D-25 H7: every artifact must show the state the referenced call left; a
     * workflow in Workbench edit mode is a conflict as well.
     */
    private void requireStateLeftBy(NodeId node, BackupStore.Manifest manifest,
        ChangeSet changes, Fresh fresh, UUID auditId) {
        List<String> unknown = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> editMode = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (ChangedArtifact artifact : changes.artifacts()) {
            String left = manifest.intendedState().get(artifact.name());
            if (left == null) {
                unknown.add(artifact.name());
                continue;
            }
            String now = ConflictDetector.fingerprint(artifactFiles(fresh.rendered(), artifact));
            if (!left.equals(now)) {
                changed.add(artifact.name());
                lines.add("=== " + ConflictDetector.key(artifact.paths().get(0))
                    + ": differs from the state the call " + manifest.auditId() + " left ("
                    + left + " then, " + now + " now)");
            }
            if (artifact.ref().kind() == ArtifactRef.Kind.WORKFLOW
                && fresh.editMode().containsKey(artifact.name())) {
                editMode.add(artifact.name() + " (by " + fresh.editMode().get(artifact.name())
                    + ")");
            }
        }
        if (!unknown.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The state the call " + manifest.auditId() + " left is not recorded for "
                    + String.join(", ", unknown) + " (outcome " + manifest.outcome()
                    + "); nothing was sent",
                "Without that state a change on the server since the call cannot be told"
                    + " apart; the call did not finish or its last export failed",
                "Export the scope (export_artifacts), compare it with the version history in the"
                    + " Workbench and restore there if needed").withNode(node));
        }
        if (!changed.isEmpty() || !editMode.isEmpty()) {
            String report = report("conflict-" + auditId + ".diff", lines.isEmpty()
                ? List.of("=== edit mode: " + String.join(", ", editMode)) : lines);
            List<String> parts = new ArrayList<>();
            if (!changed.isEmpty()) {
                parts.add("changed on " + node + " since the call " + manifest.auditId() + ": "
                    + String.join(", ", changed));
            }
            if (!editMode.isEmpty()) {
                parts.add("in Workbench edit mode: " + String.join(", ", editMode));
            }
            throw new ToolErrorException(ToolError.of(ErrorCode.CONFLICT,
                "Nothing was sent: " + String.join("; ", parts) + ". Differences: " + report,
                "A colleague (or the Workbench) changed or opened the artifacts after that call;"
                    + " restoring now would overwrite their change without a warning",
                editMode.isEmpty() ? "Export the scope, decide with the colleague, then restore"
                    + " the state in the Workbench or import it"
                    : "Publish or discard the edit in the Workbench, then restore again")
                .withNode(node));
        }
    }

    /** The scope's current state on the node (research D-5), for restore and set_active. */
    private record Fresh(List<byte[]> raw, SortedMap<String, byte[]> rendered,
        Map<String, String> editMode, String fingerprint) {

        @Override
        public String toString() {
            return "Fresh[" + raw.size() + " exports, " + fingerprint + "]";
        }
    }

    private Fresh fresh(NodeId node, ChangeSet changes) {
        ImportScope scope = changes.scope();
        ArtifactPort port = d.artifacts().apply(node);
        List<byte[]> raw = new ArrayList<>();
        if (scope.diagramGroup().isPresent()) {
            raw.add(port.exportWorkflowGroup(scope.owner(), scope.diagramGroup().get()));
        } else {
            for (ChangedArtifact module : changes.modules()) {
                raw.add(port.exportModule(scope.owner(), module.ref().pluginType()
                    .orElseThrow(), module.name()));
            }
        }
        ArchiveCodecPort.PreparedExport prepared = d.codec().prepare(scope.group(),
            scope.owner(), raw);
        SortedMap<String, byte[]> rendered = prepared.files();
        SortedMap<String, byte[]> artifacts = new TreeMap<>();
        changes.artifacts().forEach(artifact -> artifacts.putAll(artifactFiles(rendered,
            artifact)));
        return new Fresh(raw, rendered, prepared.inEditMode(),
            ConflictDetector.fingerprint(artifacts));
    }

    private static ToolErrorException changedSincePreview(NodeId node) {
        return new ToolErrorException(ToolError.of(ErrorCode.CONFLICT,
            "The scope changed on " + node + " after the preview; nothing was sent",
            "A colleague imported or published in the scope meanwhile",
            "Export the scope again, check the change, then preview again").withNode(node));
    }

    private static Optional<WorkspacePath> workspacePath(String path) {
        if (path.startsWith(WorkspacePath.META_DIRECTORY + "/")) {
            return Optional.empty();
        }
        try {
            return Optional.of(WorkspacePath.parse(Path.of(path)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // --- preview -----------------------------------------------------------------------------

    private Response challenge(Call call, NodeId node, DevelopmentPolicy policy,
        ChangeSet changes, int warnings, String inputs, String previewState) {
        WriteChallengeRegistry.Issued issued = issue(call, node, policy, inputs, previewState,
            "the import of " + changes.scope().describe());
        return new Response.Challenge(new ImportPreview(node, changes.scope().describe(),
            changes.baseCommit(), changes.created(), changes.modified(), changes.notImported(),
            warnings, call.tag, issued.code(), issued.expiresAt(), "Nothing was sent. To"
                + " import " + changes.artifacts().size() + " artifact(s) of "
                + changes.scope().describe() + " into " + node + call.tag.map(tag -> " and then"
                    + " tag the diagram group with " + tag).orElse("") + ", show this preview to"
                + " the"
                + " user and, after their explicit approval, call import_artifacts again with"
                + " the same inputs and confirmationCode before " + issued.expiresAt() + "."));
    }

    /** Issues a code for the preview of {@code what}, audited {@code CHALLENGE_ISSUED}. */
    private WriteChallengeRegistry.Issued issue(Call call, NodeId node,
        DevelopmentPolicy policy, String inputs, String previewState, String what) {
        WriteChallengeRegistry.Issued issued = d.challenges().issue(call.capability, node,
            inputs, previewState, policy.confirmationTtl());
        Map<String, String> audited = call.inputs();
        audited.put(AuditRecord.ISSUED_CONFIRMATION_CODE, issued.code());
        try {
            d.audit().append(call.record(d.ids().get(), AuditRecord.Step.PREVIEW, audited,
                AuditOutcome.CHALLENGE_ISSUED, Optional.of("Preview of " + what
                    + ", code valid until " + issued.expiresAt())));
        } catch (RuntimeException e) {
            d.challenges().discard(issued.code());
            call.refused = true;
            throw auditFailure(node, e, "the preview was not issued");
        }
        LOG.info("{} on {}: CHALLENGE_ISSUED", call.capability.toolName(), node);
        return issued;
    }

    /** The preview of a restore or an activation (research D-2): nothing was sent. */
    private Response writeChallenge(Call call, NodeId node, DevelopmentPolicy policy,
        String scope, List<String> modify, List<String> notes, String inputs,
        String previewState) {
        WriteChallengeRegistry.Issued issued = issue(call, node, policy, inputs, previewState,
            call.capability.toolName() + " of " + scope);
        return new Response.WriteChallenge(new WritePreview(node, scope, modify, notes,
            issued.code(), issued.expiresAt(), "Nothing was sent. To run "
                + call.capability.toolName() + " for " + scope + " on " + node + ", show this"
                + " preview to the user and, after their explicit approval, call "
                + call.capability.toolName() + " again with the same inputs and"
                + " confirmationCode before " + issued.expiresAt() + "."));
    }

    // --- execution ---------------------------------------------------------------------------

    /**
     * What one writing call sends, how it is verified and how it is undone (import, restore,
     * set_active).
     *
     * @param mode         how the archive is imported
     * @param rollbackMode how the backup is re-imported on failure
     * @param expected     the reviewed content each file of the change set must have afterwards
     *                     ({@code null}: absent)
     * @param verb         the first word of the history entry ({@code import}, …)
     * @param subject      what the history entry and the messages name
     * @param keep         artifacts created by an earlier call that stay (reported, never
     *                     removed)
     * @param notes        warnings of a successful call
     */
    private record Plan(ChangeSet changes, ImportArchivePort.Archive archive,
        ImportPort.Mode mode, ImportPort.Mode rollbackMode, Function<String, byte[]> expected,
        String verb, String subject, List<String> keep, List<String> notes) {
    }

    /** The outcome of a rollback and the state its verification saw, if it ran. */
    private record Rollback(WriteOutcome.Rollback state,
        Optional<SortedMap<String, byte[]>> seen) {

        static Rollback of(WriteOutcome.Rollback state) {
            return new Rollback(state, Optional.empty());
        }
    }

    /**
     * The backup (the raw exports of the scope, research D-13), the {@code PENDING} audit
     * record (fail closed), then {@link #send}.
     */
    private Response execute(Call call, NodeId node, Plan plan, UUID auditId,
        List<byte[]> rawExports, Account account) {
        ChangeSet changes = plan.changes();
        String ref = auditId.toString();
        d.backups().write(new BackupStore.Manifest(ref, node, call.owner,
            changes.scope().describe(), names(changes.artifacts()), changes.created(), Map.of(),
            "PENDING", d.clock().instant(), List.of()), rawExports);
        call.backupRef = ref;
        try {
            d.audit().append(call.record(auditId, AuditRecord.Step.EXECUTE, call.inputs(),
                AuditOutcome.PENDING, Optional.of("About to send " + changes.artifacts().size()
                    + " artifact(s) of " + plan.subject() + " (" + plan.verb() + ")")));
        } catch (RuntimeException e) {
            call.refused = true;
            throw auditFailure(node, e, "nothing was sent");
        }
        call.sent = auditId;
        try {
            return send(call, node, plan, auditId, account);
        } catch (RuntimeException e) {
            return unexpectedAfterSending(call, node, plan, auditId, account, e);
        }
    }

    /** The import, its protocol, the verification, and the commit or the rollback. */
    private Response send(Call call, NodeId node, Plan plan, UUID auditId, Account account) {
        ChangeSet changes = plan.changes();
        ImportScope scope = changes.scope();
        List<String> warnings = new ArrayList<>();
        List<String> reports = new ArrayList<>();
        WriteOutcome.Failure failure = null;
        boolean timedOut = false;
        call.step = "import";
        try {
            ImportProtocol protocol = d.imports().apply(node).importArchive(
                plan.archive().zip(), plan.mode(), call.owner);
            call.step = "protocol";
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

        call.step = "verify";
        Exported current = export(node, changes);
        if (failure == null || timedOut) {
            if (current.failure().isPresent()) {
                if (failure == null) {
                    failure = new WriteOutcome.Failure(ErrorCode.VERIFY_MISMATCH, "verify",
                        "The re-export of " + scope.describe() + " failed: "
                            + current.failure().get());
                }
            } else {
                List<String> differences = verify(plan, current.files(), call.reason);
                if (differences.isEmpty()) {
                    if (timedOut) {
                        warnings.add("StartCLI timed out, but the re-export shows the import;"
                            + " it was verified");
                    }
                    return success(call, node, plan, auditId, current, warnings);
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
        Rollback rollback = rollback(call, node, plan, auditId, current, account);
        return failed(call, node, plan, auditId, failure, rollback, current, reports,
            warnings);
    }

    /**
     * Review m6: an unexpected failure after the import may have been sent is a {@code FAILED}
     * result like any other — failure {@code IMPORT_FAILED} at the step reached, the state
     * re-exported and rolled back from the backup if it changed, the backup named. Only if
     * not even that result can be produced does {@link Call#failedAfterSending} report an
     * {@code INTERNAL} tool error.
     */
    private Response unexpectedAfterSending(Call call, NodeId node, Plan plan, UUID auditId,
        Account account, RuntimeException e) {
        LOG.error("{} on {} failed unexpectedly at {} after sending (audit id {})",
            call.capability.toolName(), node, call.step, auditId, e);
        ChangeSet changes = plan.changes();
        WriteOutcome.Failure failure = new WriteOutcome.Failure(ErrorCode.IMPORT_FAILED,
            call.step, "INTERNAL: an unexpected failure (" + e.getClass().getSimpleName()
                + ") after the import was sent; the state was re-exported and rolled back from"
                + " the backup where it had changed");
        Exported current;
        try {
            current = export(node, changes);
        } catch (RuntimeException again) {
            LOG.error("The re-export on {} failed unexpectedly", node, again);
            current = new Exported(List.of(), new TreeMap<>(), Optional.of("INTERNAL: "
                + again.getClass().getSimpleName()));
        }
        Rollback rollback = rollback(call, node, plan, auditId, current, account);
        return failed(call, node, plan, auditId, failure, rollback, current, new ArrayList<>(),
            new ArrayList<>());
    }

    private Response success(Call call, NodeId node, Plan plan, UUID auditId,
        Exported current, List<String> warnings) {
        ChangeSet changes = plan.changes();
        call.step = "commit";
        writeBack(changes, current.files());
        Optional<String> commit = d.history().commitAll(plan.verb() + " " + node.value() + ": "
                + plan.subject() + " (" + changes.artifacts().size() + " artifacts) ["
                + auditId + "]",
            Map.of(VersionHistoryPort.SERVER_STATE, node.group().value()))
            .map(entry -> entry.commit());
        updateManifest(call, node, changes, auditId, "EXECUTED", current.files());
        String message = "Sent " + changes.artifacts().size() + " artifact(s) of "
            + plan.subject() + " (" + plan.verb() + ") and verified them" + commit.map(c -> " ("
                + c + ")").orElse("");
        List<String> reports = new ArrayList<>();
        Optional<WriteOutcome.TagResult> tag = Optional.empty();
        if (call.tag.isPresent()) {
            call.step = "tag";
            DiagramGroupTagger.Result result = tag(call, node, changes, auditId);
            tag = Optional.of(new WriteOutcome.TagResult(call.tag.get(), result.applied(),
                result.workflows(), result.modules(), result.failure()));
            reports.addAll(result.reports());
            call.tagApplied = String.valueOf(result.applied());
            message += result.applied() ? "; tagged the diagram group with " + call.tag.get()
                + " and verified it (" + result.workflows() + " workflow(s), " + result.modules()
                + " module(s))" : "; the tag " + call.tag.get() + " was not applied ("
                + result.failure().map(f -> f.code() + " at " + f.step()).orElse("") + ")";
            if (!result.applied()) {
                WriteOutcome.Failure failure = result.failure().orElseThrow();
                warnings.add("The import succeeded, but the tag " + call.tag.get() + " was not"
                    + " applied (" + failure.code() + " at " + failure.step() + ": "
                    + failure.message() + "). " + DiagramGroupTagger.retry(call.tag.get(),
                        result));
            }
        }
        appendFinal(call, node, auditId, AuditOutcome.EXECUTED, message);
        LOG.info("{} on {}: EXECUTED (audit id {})", call.capability.toolName(), node, auditId);
        warnings.addAll(plan.notes());
        keep(plan.keep(), warnings);
        return new Response.Completed(new WriteOutcome(auditId, WriteOutcome.Outcome.EXECUTED,
            Optional.empty(), commit, Optional.of(auditId.toString()), changes.created(),
            changes.modified(), changes.notImported(), Optional.empty(), plan.keep(), reports,
            warnings, tag));
    }

    /**
     * Research D-26: the requested tag on the imported diagram group, after the verified import
     * and its write-back; never throws (a tag failure is part of the result), so a tag problem
     * can never reach the rollback of the already verified and committed import (review m1).
     */
    private DiagramGroupTagger.Result tag(Call call, NodeId node, ChangeSet changes,
        UUID auditId) {
        String group = changes.scope().diagramGroup().orElseThrow();
        try {
            return new DiagramGroupTagger(d.root()).tag(node, d.tags().apply(node), call.owner,
                List.of(group), call.tag.get(), auditId);
        } catch (ToolErrorException e) {
            return new DiagramGroupTagger.Result(false, 0, 0, Optional.of(
                new WriteOutcome.Failure(ErrorCode.IMPORT_FAILED, "tag", e.error().code() + ": "
                    + e.error().message())), List.of(), List.of());
        } catch (RuntimeException e) {
            LOG.error("The tag after the import on {} failed unexpectedly", node, e);
            return new DiagramGroupTagger.Result(false, 0, 0, Optional.of(
                new WriteOutcome.Failure(ErrorCode.IMPORT_FAILED, "tag", "INTERNAL: an"
                    + " unexpected failure (" + e.getClass().getSimpleName() + ") before the"
                    + " tag was set")), List.of(), List.of());
        }
    }

    /** A requested tag that was not set because nothing (verified) was imported. */
    private static Optional<WriteOutcome.TagResult> notTagged(Call call, List<String> warnings,
        String why) {
        if (call.tag.isEmpty()) {
            return Optional.empty();
        }
        call.tagApplied = "false";
        warnings.add("The tag " + call.tag.get() + " was not set because " + why + "; tag the"
            + " diagram group with tag_artifacts if needed");
        return Optional.of(new WriteOutcome.TagResult(call.tag.get(), false, 0, 0,
            Optional.empty()));
    }

    /** The warning for created artifacts that stay: nothing is ever deleted. */
    private static void keep(List<String> created, List<String> warnings) {
        if (!created.isEmpty()) {
            warnings.add("Created and not removed (nothing is ever deleted): " + String.join(
                ", ", created) + " — delete them in the Workbench if needed");
        }
    }

    private Response failed(Call call, NodeId node, Plan plan, UUID auditId,
        WriteOutcome.Failure failure, Rollback outcome, Exported current,
        List<String> reports, List<String> warnings) {
        ChangeSet changes = plan.changes();
        WriteOutcome.Rollback rollback = outcome.state();
        WriteOutcome.Failure reported = failure;
        if (rollback == WriteOutcome.Rollback.FAILED) {
            reported = new WriteOutcome.Failure(failure.code(), failure.step(), failure.message()
                + "; the rollback failed: the backup " + auditId + " is kept — restore it with"
                + " restore_backup or in the Workbench");
        }
        List<String> createdNotRemoved = new ArrayList<>(changes.artifacts().stream()
            .filter(artifact -> artifact.kind() == ChangedArtifact.Kind.NEW)
            .filter(artifact -> current.failure().isPresent()
                || !artifactFiles(current.files(), artifact).isEmpty())
            .map(ChangedArtifact::name).toList());
        createdNotRemoved.addAll(plan.keep());
        keep(createdNotRemoved, warnings);
        // research D-25 H7: the state the call left, as its last verification saw it
        updateManifest(call, node, changes, auditId, "FAILED", outcome.seen()
            .orElse(current.files()));
        call.rollback = rollback.name();
        Optional<WriteOutcome.TagResult> tag = notTagged(call, warnings, "the import failed");
        appendFinal(call, node, auditId, AuditOutcome.FAILED, reported.code() + " at "
            + reported.step() + ": " + reported.message() + " (rollback " + rollback + ")");
        LOG.warn("{} on {}: FAILED at {} (audit id {}, rollback {})",
            call.capability.toolName(), node, reported.step(), auditId, rollback);
        return new Response.Completed(new WriteOutcome(auditId, WriteOutcome.Outcome.FAILED,
            Optional.of(reported), Optional.empty(), Optional.of(auditId.toString()),
            changes.created(), changes.modified(), changes.notImported(),
            Optional.of(rollback), createdNotRemoved, reports, warnings, tag));
    }

    /**
     * Research D-10, D-25: the modified artifacts go back to the backup's state with the
     * target's current secrets, if they changed; verified by another export.
     */
    private Rollback rollback(Call call, NodeId node, Plan plan, UUID auditId,
        Exported current, Account account) {
        ChangeSet changes = plan.changes();
        List<ChangedArtifact> existing = changes.artifacts().stream()
            .filter(artifact -> artifact.kind() == ChangedArtifact.Kind.MODIFIED).toList();
        if (existing.isEmpty()) {
            return Rollback.of(WriteOutcome.Rollback.NOT_NEEDED);
        }
        try {
            ImportScope scope = changes.scope();
            List<byte[]> backup = d.backups().exports(auditId.toString());
            SortedMap<String, byte[]> before = d.codec().prepare(scope.group(), call.owner,
                backup).files();
            if (current.failure().isEmpty() && sameState(existing, before, current.files())) {
                return Rollback.of(WriteOutcome.Rollback.NOT_NEEDED);
            }
            if (current.failure().isPresent()) {
                return Rollback.of(WriteOutcome.Rollback.FAILED);
            }
            ImportArchivePort.Archive archive = d.archives().assemble(build(new ChangeSet(scope,
                    changes.baseCommit(), existing.stream().filter(a -> a.ref().kind()
                        == ArtifactRef.Kind.WORKFLOW)
                        .toList(), existing.stream().filter(a -> a.ref().kind()
                        == ArtifactRef.Kind.MODULE)
                        .toList(), List.of()), call, before, current.raw(),
                "Rollback of " + plan.verb() + " " + auditId, account, Set.of()));
            try {
                d.imports().apply(node).importArchive(archive.zip(), plan.rollbackMode(),
                    call.owner);
            } catch (ToolErrorException e) {
                LOG.warn("The rollback import on {} failed: {}", node, e.error().code());
            }
            Exported after = export(node, changes);
            if (after.failure().isPresent()) {
                return Rollback.of(WriteOutcome.Rollback.FAILED);
            }
            return new Rollback(sameState(existing, before, after.files())
                ? WriteOutcome.Rollback.SUCCEEDED : WriteOutcome.Rollback.FAILED,
                Optional.of(after.files()));
        } catch (RuntimeException e) {
            LOG.error("The rollback on {} failed", node, e);
            return Rollback.of(WriteOutcome.Rollback.FAILED);
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
    private List<String> verify(Plan plan, SortedMap<String, byte[]> rendered, String reason) {
        ChangeSet changes = plan.changes();
        List<String> differences = new ArrayList<>();
        for (ChangedArtifact artifact : changes.artifacts()) {
            Set<String> paths = new TreeSet<>(artifact.paths());
            paths.addAll(artifactFiles(rendered, artifact).keySet());
            for (String path : paths) {
                byte[] expected = plan.expected().apply(path);
                byte[] actual = rendered.get(path);
                if (!d.archives().equivalent(path, expected, actual)) {
                    differences.add("=== " + path);
                    differences.add("--- intended: " + (expected == null ? "(missing)"
                        : expected.length + " bytes"));
                    differences.add("+++ " + changes.scope().describe() + " on the server: "
                        + (actual == null ? "(missing)" : actual.length + " bytes"));
                    StringBuilder diff = new StringBuilder();
                    ConflictDetector.LineDiff.append(diff, path, "intended", expected, actual);
                    differences.addAll(diff.toString().lines().toList());
                }
            }
            // review m2: workflows and module index entries, the exact person-written segment
            Optional<String> commented = artifact.ref().kind() == ArtifactRef.Kind.WORKFLOW
                ? Optional.of(artifact.paths().get(0))
                : artifactFiles(rendered, artifact).keySet().stream()
                    .filter(path -> path.endsWith("/index.xml")).findFirst();
            byte[] file = commented.map(rendered::get).orElse(null);
            if (file != null && d.archives().checkinComment(file)
                .filter(comment -> carriesReason(comment, reason)).isEmpty()) {
                differences.add("=== " + commented.get()
                    + ": the check-in comment does not carry exactly the reason");
            }
        }
        return differences;
    }

    /**
     * True if the person-written part of a check-in comment is exactly
     * {@code DefaultCommitCommentImport###<reason>###} (research D-11): the part before the
     * export suffix ({@code @@@…}), with the copies of the empty last segment that every export
     * appends ({@code ###…}) counted once. INUBIT puts one more {@code DefaultCommitCommentImport###}
     * in front of the comment of a <em>created module</em> (005 live acceptance), so one extra
     * prefix is accepted too.
     */
    static boolean carriesReason(String comment, String reason) {
        int suffix = comment.indexOf("@@@");
        String head = suffix < 0 ? comment : comment.substring(0, suffix);
        String person = head.replaceFirst("(###)+$", "###");
        String expected = COMMENT_PREFIX + reason + "###";
        return person.equals(expected) || person.equals(COMMENT_PREFIX + expected);
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
        // the export record names the source version (archive.properties): the scope's own
        // record, else the first in path order (review m4: never the walk order)
        exportRecord(changes).ifPresent(file -> {
            try {
                files.put(d.root().relativize(file).toString().replace('\\', '/'),
                    Files.readAllBytes(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return files;
    }

    private Optional<Path> exportRecord(ChangeSet changes) {
        ImportScope scope = changes.scope();
        Path exports = d.root().resolve(".meta").resolve(scope.group().value())
            .resolve(NameCodec.encode(scope.owner())).resolve("exports");
        List<Path> own = new ArrayList<>();
        scope.diagramGroup().ifPresent(group -> own.add(exports.resolve("workflows")
            .resolve(NameCodec.encode(group) + ".json")));
        changes.modules().forEach(module -> own.add(exports.resolve("modules")
            .resolve(NameCodec.encode(module.ref().pluginType().orElseThrow()))
            .resolve(NameCodec.encode(module.name()) + ".json")));
        Optional<Path> found = own.stream().filter(Files::isRegularFile).findFirst();
        if (found.isPresent() || !Files.isDirectory(exports)) {
            return found;
        }
        try (var walk = Files.walk(exports)) {
            return walk.filter(Files::isRegularFile).sorted().findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Names a new artifact must not use (D-25 H9): the owner's diagrams and modules on the target
     * — a new workflow or module against both (review m5). Only the owner's own artifacts are
     * listed (D-25 addendum): INUBIT matches names per owner.
     */
    private Set<String> takenNames(NodeId node, ChangeSet changes, String owner,
        Set<String> targetModules) {
        if (changes.created().isEmpty()) {
            return Set.of();
        }
        Set<String> taken = new HashSet<>(targetModules);
        d.inventory().apply(node).listDiagrams(owner).forEach(item -> taken.add(item.name()));
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
                    + " 30 days and not the newest of its scope)"), call.mcpClient));
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

    private static ImportScope scope(Call call, ImportRequest request, NodeId node) {
        return request.diagramGroup().map(group -> ImportScope.diagramGroup(node.group(),
            call.owner, group)).orElseGet(() -> ImportScope.modules(node.group(), call.owner,
            request.modules()));
    }

    private static void validate(ImportRequest request) {
        validate(request.reason(), request.confirmationCode());
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
        if (request.tag().isPresent() && !group) {
            throw invalid("A tag is only possible for a diagram group import, not for modules",
                "INUBIT tags only whole diagram groups (research D-26); an import of single"
                    + " modules is never tagged",
                "Import the modules without tag, or tag their diagram group with tag_artifacts");
        }
        if (request.tag().filter(tag -> !TAG.matcher(tag).matches()).isPresent()) {
            throw invalid("Invalid tag: it must match " + TAG.pattern(), "The tag is passed to"
                + " StartCLI in single quotes", "Use letters, digits, _ . - and spaces");
        }
    }

    /** The given owner, else {@code inventory.owner} of the node (research D-25 L2). */
    private String owner(Optional<String> owner, NodeId node, Capability capability) {
        return owner.or(() -> d.defaultOwners().apply(node)).orElseThrow(() ->
            new ToolErrorException(ToolError.of(ErrorCode.NOT_CONFIGURED,
                "No owner is given and inventory.owner is not set for " + node
                    + "; nothing was sent",
                capability.toolName() + " needs the owner of the artifacts",
                "Give owner, or set inventory.owner for the node").withNode(node)));
    }

    /** The reason and the code of every writing call. */
    private static void validate(String reason, Optional<String> confirmationCode) {
        if (!REASON.matcher(reason).matches() || reason.isBlank()) {
            throw invalid("Invalid reason: it must be 1-500 characters without #, @ and"
                    + " control characters",
                "The reason becomes the check-in comment; # and @ separate its segments",
                "Give a short plain-text reason");
        }
        if (confirmationCode.filter(code -> !CODE.matcher(code).matches()).isPresent()) {
            throw invalid("Invalid confirmationCode", "A confirmation code has 22 URL-safe"
                + " Base64 chars", "Use the code of the preview exactly as returned");
        }
    }

    private String inputFingerprint(NodeId node, Call call, ImportRequest request) {
        StringBuilder text = new StringBuilder(node.value()).append('\n').append(call.owner)
            .append('\n').append(request.diagramGroup().orElse("")).append('\n');
        request.modules().forEach(module -> text.append(module.name()).append('/')
            .append(module.pluginType().orElse("")).append('\n'));
        text.append("tag=").append(request.tag().orElse("")).append('\n');
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

        final Capability capability;
        final String node;
        final String reason;
        final Optional<String> confirmationCode;
        final Optional<String> mcpClient;
        /** The audited inputs of the request after the reason (scope, backupRef, …). */
        final Map<String, String> requested = new LinkedHashMap<>();
        DevelopmentPolicy policy;
        String owner;
        ChangeSet changes;
        String backupRef;
        String rollback;
        /** The tag to set after a verified diagram-group import (research D-26). */
        Optional<String> tag = Optional.empty();
        /** Whether the requested tag was set and verified, once known. */
        String tagApplied;
        boolean refused;
        /** The audit id of the execution once the import may have been sent. */
        UUID sent;
        /** The step reached after sending: import, protocol, verify, commit, tag. */
        String step = "import";

        Call(Capability capability, String node, String reason,
            Optional<String> confirmationCode, Optional<String> mcpClient) {
            this.capability = capability;
            this.node = node;
            this.reason = reason;
            this.confirmationCode = confirmationCode;
            this.mcpClient = mcpClient;
        }

        /** The sanitized inputs, in a fixed order; never content or secrets. */
        Map<String, String> inputs() {
            Map<String, String> inputs = new LinkedHashMap<>();
            inputs.put("reason", bounded(reason));
            requested.forEach((key, value) -> inputs.put(key, bounded(value)));
            if (owner != null) {
                inputs.put("owner", bounded(owner));
            }
            if (changes != null) {
                List<String> names = names(changes.artifacts());
                inputs.put("changeSet", names.size() <= MAX_AUDITED_NAMES
                    ? String.join(", ", names)
                    : String.join(", ", names.subList(0, MAX_AUDITED_NAMES)) + ", …+"
                        + (names.size() - MAX_AUDITED_NAMES));
            }
            confirmationCode.ifPresent(code -> inputs.put(AuditRecord.CONFIRMATION_CODE, code));
            if (backupRef != null) {
                // a restore names the backup it restores as backupRef, its own differently
                inputs.put(requested.containsKey("backupRef") ? "newBackupRef" : "backupRef",
                    backupRef);
            }
            if (rollback != null) {
                inputs.put("rollback", rollback);
            }
            if (tagApplied != null) {
                inputs.put("tagApplied", tagApplied);
            }
            return inputs;
        }

        AuditRecord record(UUID auditId, AuditRecord.Step step, Map<String, String> inputs,
            AuditOutcome outcome, Optional<String> reason) {
            Optional<NodeId> node = node();
            return new AuditRecord(auditId, d.clock().instant(), d.profile(),
                node.map(NodeId::value).orElse(bounded(this.node)), group(),
                capability.toolName(), step, inputs, node.map(id -> d.accounts().apply(id)
                    .user()), outcome, reason.map(Call::bounded), mcpClient);
        }

        void append(UUID auditId, AuditRecord.Step step, AuditOutcome outcome, String reason) {
            d.audit().append(record(auditId, step, inputs(), outcome, Optional.of(reason)));
        }

        /**
         * The last resort when not even the {@code FAILED} result of an unexpected failure
         * after sending can be produced ({@link #unexpectedAfterSending}): audited
         * {@code FAILED} with the same audit id, never as a refusal, and reported as an
         * {@code INTERNAL} tool error naming the backup that holds the state before.
         */
        ToolErrorException failedAfterSending(RuntimeException e) {
            LOG.error("{} failed unexpectedly after sending (audit id {})",
                capability.toolName(), sent, e);
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
            confirmationCode.ifPresent(d.challenges()::discard);
            boolean execute = confirmationCode.isPresent() || (policy != null
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
            LOG.info("{} on {}: REFUSED ({})", capability.toolName(), bounded(node),
                error.code());
            return new ToolErrorException(error);
        }

        private Optional<NodeId> node() {
            if (policy != null) {
                return Optional.of(policy.node());
            }
            try {
                NodeId id = NodeId.parse(node);
                return d.policies().apply(id) == null ? Optional.empty() : Optional.of(id);
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        private Optional<String> group() {
            try {
                return Optional.of(switch (Target.parse(node)) {
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
