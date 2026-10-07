package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ArtifactClass;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult.NodeOutcome;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult.State;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodePlan;
import de.dadecker.inubit.mcp.domain.model.NodePlan.Kind;
import de.dadecker.inubit.mcp.domain.model.NodePlan.PlannedArtifact;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.ImportArchivePort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.ReleaseArchivePort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deploys a release on one target node (feature 005, FR-014–FR-019, research D-7, D-9):
 *
 * <ol>
 *   <li><b>re-check</b>: the node's state ({@link ReleasePlanner#nodeState}) must be the state of
 *       the plan, right before writing; otherwise {@code CONFLICT}, the node is not written;
 *   <li>a node with nothing to import is {@code UNCHANGED} and only gets the tag;
 *   <li><b>backup</b> of the raw exports of the re-check (groups, modules, repository files) as
 *       a {@code DEPLOYMENT} backup, then a {@code PENDING} audit record (fail closed);
 *   <li><b>imports</b> in the order of D-7, each only with new, changed and layout-only
 *       artifacts: the repository files (repository mode, never key material), the modules no
 *       deployed workflow runs (module archive), then per diagram group one workflow archive
 *       per intended active flag (inactive first) with the deployed modules its workflows run.
 *       Every archive carries the node's own secrets (built from the node's exports) and the
 *       check-in comment {@code deploy <tag> from <source>}; workflow and module protocols must
 *       name exactly the sent artifacts;
 *   <li><b>verification</b> by re-export: every deployed workflow and module equals the release
 *       (reviewed content), carries the intended {@code IsActive} and the reason in its
 *       check-in comment; every deployed repository file is byte for byte the release's;
 *   <li>on any failure from the first import on: <b>rollback</b> of the node's changed
 *       artifacts and repository files from the backup, verified ({@code ROLLED_BACK} or
 *       {@code ROLLBACK_FAILED}); created artifacts stay and are listed;
 *   <li>on success: the group-scoped <b>tag</b> ({@link DiagramGroupTagger}; a tag failure
 *       keeps the deployment) and the <b>ledger</b> entries of the verified state.
 * </ol>
 */
public final class NodeDeployer {

    private static final Logger LOG = LoggerFactory.getLogger(NodeDeployer.class);
    private static final String REPORTS = ".reports";
    private static final int MAX_AUDITED_NAMES = 20;
    private static final DateTimeFormatter COMMENT_TIME =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    /**
     * @param root    the workspace root (reports)
     * @param profile the profile every audit record carries
     */
    public record Dependencies(ReleasePlanner planner, ReleaseArchivePort releases,
        ImportArchivePort archives, ArchiveCodecPort codec, Function<NodeId, ImportPort> imports,
        Function<NodeId, TagPort> tags, Function<NodeId, ImportService.Account> accounts,
        BackupStore backups, DeploymentLedger ledger, AuditPort audit, String profile,
        Clock clock, Supplier<UUID> ids, Path root) {
        public Dependencies {
            Objects.requireNonNull(planner, "planner");
            Objects.requireNonNull(releases, "releases");
            Objects.requireNonNull(archives, "archives");
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(imports, "imports");
            Objects.requireNonNull(tags, "tags");
            Objects.requireNonNull(accounts, "accounts");
            Objects.requireNonNull(backups, "backups");
            Objects.requireNonNull(ledger, "ledger");
            Objects.requireNonNull(audit, "audit");
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(ids, "ids");
            Objects.requireNonNull(root, "root");
        }
    }

    /**
     * What one node ended in, and the verified state of a deployed or unchanged node (for the
     * workspace history).
     */
    public record Deployed(NodeOutcome outcome, Optional<ReleasePlanner.NodeState> verified,
        List<String> reports, List<String> warnings) {
        public Deployed {
            Objects.requireNonNull(outcome, "outcome");
            verified = verified == null ? Optional.empty() : verified;
            reports = List.copyOf(reports);
            warnings = List.copyOf(warnings);
        }
    }

    private final Dependencies d;

    public NodeDeployer(Dependencies dependencies) {
        this.d = Objects.requireNonNull(dependencies, "dependencies");
    }

    /** A failure of a step; its message never holds content or secrets. */
    private static final class Failed extends Exception {
        private static final long serialVersionUID = 1L;
        private final transient WriteOutcome.Failure failure;

        Failed(ErrorCode code, String step, String message) {
            super(message, null, false, false);
            this.failure = new WriteOutcome.Failure(code, step, message);
        }
    }

    /** One call's context. */
    private record Call(DeployGuard.Admitted admitted, ReleaseDiscovery.Release release,
        NodePlan plan, UUID auditId, Optional<String> mcpClient, String reason) {
        NodeId node() {
            return plan.node();
        }
    }

    /**
     * Deploys {@code release} on the node of {@code plan}; see the class description. Never
     * throws for a failure of the node (it is part of the outcome); only an audit record that
     * cannot be written before anything is sent is thrown ({@code INTERNAL}).
     */
    public Deployed deploy(DeployGuard.Admitted admitted, ReleaseDiscovery.Release release,
        NodePlan plan, UUID auditId, Optional<String> mcpClient) {
        Call call = new Call(admitted, release, plan, auditId, mcpClient, "deploy "
            + admitted.tag() + " from " + admitted.source());
        NodeId node = plan.node();
        ReleasePlanner.NodeState before;
        try {
            before = d.planner().nodeState(admitted, release, node);
        } catch (ToolErrorException e) {
            return notStarted(node, ErrorCode.CONFLICT, e.error().code() + ": "
                + e.error().message() + " (the node could not be read right before writing)");
        }
        if (!before.fingerprint().equals(plan.targetFingerprint())) {
            return notStarted(node, ErrorCode.CONFLICT, node + " changed since the preview (an"
                + " import, a publish or a workflow in edit mode); it was not written");
        }
        List<PlannedArtifact> deployed = plan.artifacts().stream()
            .filter(artifact -> artifact.artifactClass().deployed()).toList();
        if (deployed.isEmpty()) {
            List<String> warnings = new ArrayList<>();
            WriteOutcome.TagResult tag = tag(call, warnings);
            return new Deployed(new NodeOutcome(node, State.UNCHANGED, Optional.empty(),
                List.of(), List.of(), Optional.of(tag), Optional.empty(), Optional.empty()),
                Optional.of(before), List.of(), warnings);
        }
        List<String> names = deployed.stream().map(PlannedArtifact::name).toList();
        List<String> created = deployed.stream().filter(a -> a.artifactClass()
            == ArtifactClass.NEW).map(PlannedArtifact::name).toList();
        String backupRef = d.ids().get().toString();
        List<byte[]> backupExports = new ArrayList<>(before.raws());
        backupExports.addAll(before.repositoryExports().values());
        BackupStore.Manifest manifest = d.backups().write(new BackupStore.Manifest(backupRef,
            node, admitted.owner(), "deploy " + admitted.target() + " " + admitted.tag(), names,
            created, Map.of(), "PENDING", d.clock().instant(), List.of(),
            BackupStore.Manifest.Kind.DEPLOYMENT, List.copyOf(release.diagramGroups()),
            deployed.stream().filter(a -> a.kind() == Kind.REPOSITORY_FILE)
                .map(PlannedArtifact::name).toList(), Optional.of(admitted.tag()),
            Optional.of(admitted.source().value())), backupExports);
        Map<String, String> inputs = inputs(call, names, backupRef);
        audit(call, inputs, AuditOutcome.PENDING, "About to deploy " + names.size()
            + " artifact(s) of " + admitted.tag() + " on " + node);

        List<String> reports = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Optional<ReleasePlanner.NodeState> after = Optional.empty();
        WriteOutcome.Failure failure;
        try {
            send(call, deployed, before);
            try {
                after = Optional.of(d.planner().nodeState(admitted, release, node));
            } catch (ToolErrorException e) {
                throw new Failed(ErrorCode.VERIFY_MISMATCH, "verify", "The re-export of " + node
                    + " failed: " + e.error().code() + ": " + e.error().message());
            }
            List<String> differences = verify(call, deployed, after.get());
            if (differences.isEmpty()) {
                return success(call, deployed, manifest, after.get(), names, created, inputs,
                    warnings);
            }
            String report = report(call, "verify", differences);
            reports.add(report);
            throw new Failed(ErrorCode.VERIFY_MISMATCH, "verify", "The re-export of " + node
                + " differs from the release in " + differences.size() + " artifact(s); see "
                + report);
        } catch (Failed e) {
            failure = e.failure;
        } catch (RuntimeException e) {
            LOG.error("The deployment on {} failed unexpectedly", node, e);
            failure = new WriteOutcome.Failure(ErrorCode.IMPORT_FAILED, "import", "INTERNAL: an"
                + " unexpected failure (" + e.getClass().getSimpleName() + ") after the import"
                + " was started");
        }
        State state = rollback(call, deployed, before, after, reports);
        d.backups().update(manifest.with(state.name(), Map.of()));
        if (state == State.ROLLBACK_FAILED) {
            warnings.add("The rollback of " + node + " failed; its state before the deployment"
                + " is in the backup " + backupRef + " (restore_backup)");
        }
        audit(call, withRollback(inputs, state), AuditOutcome.FAILED, failure.code() + " at "
            + failure.step() + ": " + failure.message() + "; " + state);
        LOG.info("deploy_release on {}: FAILED at {} ({})", node, failure.step(), state);
        return new Deployed(new NodeOutcome(node, state, Optional.of(backupRef), names, created,
            Optional.empty(), Optional.of(failure), Optional.empty()), Optional.empty(), reports,
            warnings);
    }

    // --- imports -------------------------------------------------------------------------------

    private void send(Call call, List<PlannedArtifact> deployed,
        ReleasePlanner.NodeState before) throws Failed {
        NodeId node = call.node();
        String owner = call.admitted().owner();
        ImportPort port = d.imports().apply(node);
        List<String> repository = deployed.stream().filter(a -> a.kind()
            == Kind.REPOSITORY_FILE).map(PlannedArtifact::name).toList();
        if (!repository.isEmpty()) {
            byte[] zip = d.releases().repositoryArchive(List.of(call.release().export()), owner,
                repository);
            try {
                port.importRepository(zip, owner);
            } catch (ToolErrorException e) {
                throw new Failed(ErrorCode.IMPORT_FAILED, "import", "The repository import"
                    + " failed: " + e.error().code() + ": " + e.error().message());
            }
        }
        Map<String, PlannedArtifact> modules = new LinkedHashMap<>();
        deployed.stream().filter(a -> a.kind() == Kind.MODULE).forEach(a ->
            modules.put(a.name(), a));
        List<PlannedArtifact> workflows = deployed.stream().filter(a -> a.kind()
            == Kind.WORKFLOW).toList();
        Map<String, List<String>> runs = new LinkedHashMap<>();
        for (PlannedArtifact workflow : workflows) {
            runs.put(workflow.name(), d.releases().modulesOf(d.planner().releaseFiles(
                call.admitted(), call.release(), workflow).values().iterator().next()));
        }
        Set<String> withWorkflows = new LinkedHashSet<>();
        runs.values().forEach(withWorkflows::addAll);
        List<PlannedArtifact> alone = modules.values().stream().filter(m ->
            !withWorkflows.contains(m.name())).toList();
        if (!alone.isEmpty()) {
            importArchive(call, port, before, Optional.empty(), List.of(), alone,
                ImportPort.Mode.MODULE);
        }
        Set<String> sent = new TreeSet<>();
        alone.forEach(m -> sent.add(m.name()));
        Map<String, List<PlannedArtifact>> byGroup = new TreeMap<>();
        workflows.forEach(w -> byGroup.computeIfAbsent(w.group().orElseThrow(),
            g -> new ArrayList<>()).add(w));
        for (Map.Entry<String, List<PlannedArtifact>> group : byGroup.entrySet()) {
            for (boolean active : List.of(false, true)) {
                List<PlannedArtifact> part = group.getValue().stream().filter(w ->
                    w.active().orElse(false) == active).toList();
                if (part.isEmpty()) {
                    continue;
                }
                List<PlannedArtifact> used = new ArrayList<>();
                for (PlannedArtifact workflow : part) {
                    for (String module : runs.get(workflow.name())) {
                        if (modules.containsKey(module) && sent.add(module)) {
                            used.add(modules.get(module));
                        }
                    }
                }
                importArchive(call, port, before, Optional.of(group.getKey()), part, used,
                    active ? ImportPort.Mode.WORKFLOW_ACTIVE : ImportPort.Mode.WORKFLOW_INACTIVE);
            }
        }
    }

    private void importArchive(Call call, ImportPort port, ReleasePlanner.NodeState before,
        Optional<String> diagramGroup, List<PlannedArtifact> workflows,
        List<PlannedArtifact> modules, ImportPort.Mode mode) throws Failed {
        ImportArchivePort.Archive archive;
        try {
            archive = d.archives().assemble(build(call, call.release().files(), before.raws(),
                diagramGroup, workflows, modules, call.reason(), true));
        } catch (ToolErrorException e) {
            throw new Failed(e.error().code(), "import", "The import archive cannot be built: "
                + e.error().message());
        }
        ImportProtocol protocol;
        try {
            protocol = port.importArchive(archive.zip(), mode, call.admitted().owner());
        } catch (ToolErrorException e) {
            throw new Failed(ErrorCode.IMPORT_FAILED, "import", e.error().code() + ": "
                + e.error().message());
        }
        List<PlannedArtifact> sent = new ArrayList<>(workflows);
        sent.addAll(modules);
        Set<String> created = new TreeSet<>();
        Set<String> modified = new TreeSet<>();
        sent.forEach(a -> (a.artifactClass() == ArtifactClass.NEW ? created : modified)
            .add(a.name()));
        if (!new TreeSet<>(protocol.created()).equals(created)
            || !new TreeSet<>(protocol.modified()).equals(modified)
            || protocol.entries().stream().anyMatch(e -> e.action().isEmpty())) {
            throw new Failed(ErrorCode.IMPORT_FAILED, "protocol", "INUBIT's protocol does not"
                + " name exactly the sent artifacts (created " + protocol.created() + ","
                + " modified " + protocol.modified() + ")");
        }
    }

    private ImportArchivePort.Build build(Call call, SortedMap<String, byte[]> files,
        List<byte[]> targetExports, Optional<String> diagramGroup,
        List<PlannedArtifact> workflows, List<PlannedArtifact> modules, String reason,
        boolean fromRelease) {
        ImportService.Account account = d.accounts().apply(call.node());
        return new ImportArchivePort.Build(call.admitted().target(), call.admitted().owner(),
            diagramGroup, workflows.stream().map(w -> new ImportArchivePort.Artifact(w.name(),
                Optional.empty(), w.artifactClass() == ArtifactClass.NEW)).toList(),
            modules.stream().map(m -> new ImportArchivePort.Artifact(m.name(), m.group(),
                m.artifactClass() == ArtifactClass.NEW)).toList(), files, targetExports, reason,
            account.user(), account.host(), COMMENT_TIME.format(d.clock().instant()
                .atZone(ZoneId.systemDefault())), Set.of(), fromRelease);
    }

    // --- verification --------------------------------------------------------------------------

    /** The names of the deployed artifacts that differ from the release on the node. */
    private List<String> verify(Call call, List<PlannedArtifact> deployed,
        ReleasePlanner.NodeState after) {
        List<String> differences = new ArrayList<>();
        for (PlannedArtifact artifact : deployed) {
            SortedMap<String, byte[]> expected = d.planner().releaseFiles(call.admitted(),
                call.release(), artifact);
            SortedMap<String, byte[]> actual = d.planner().nodeFiles(call.admitted(),
                call.release(), artifact, after);
            if (!expected.keySet().equals(actual.keySet())) {
                differences.add(artifact.name() + ": files missing or added");
                continue;
            }
            boolean equal = true;
            for (Map.Entry<String, byte[]> file : expected.entrySet()) {
                byte[] other = actual.get(file.getKey());
                equal &= artifact.kind() == Kind.REPOSITORY_FILE
                    ? Arrays.equals(file.getValue(), other)
                    : d.releases().equivalent(file.getKey(), file.getValue(), other);
            }
            if (!equal) {
                differences.add(artifact.name() + ": content differs from the release");
                continue;
            }
            if (artifact.kind() == Kind.WORKFLOW) {
                byte[] file = actual.values().iterator().next();
                if (!d.archives().active(file).orElse(false).equals(artifact.active()
                    .orElse(false))) {
                    differences.add(artifact.name() + ": IsActive is not "
                        + artifact.active().orElse(false));
                }
                if (!comment(file, call.reason())) {
                    differences.add(artifact.name() + ": the check-in comment does not name "
                        + call.reason());
                }
            }
            if (artifact.kind() == Kind.MODULE) {
                Optional<byte[]> index = actual.entrySet().stream().filter(e -> e.getKey()
                    .endsWith("/index.xml")).map(Map.Entry::getValue).findFirst();
                if (index.isPresent() && !comment(index.get(), call.reason())) {
                    differences.add(artifact.name() + ": the check-in comment does not name "
                        + call.reason());
                }
            }
        }
        return differences;
    }

    private boolean comment(byte[] file, String reason) {
        return d.archives().checkinComment(file).filter(c -> ImportService.carriesReason(c,
            reason)).isPresent();
    }

    // --- rollback ------------------------------------------------------------------------------

    /**
     * Re-imports the node's changed artifacts and repository files from the backup (the state
     * of the re-check) where they differ now, and verifies them.
     */
    private State rollback(Call call, List<PlannedArtifact> deployed,
        ReleasePlanner.NodeState before, Optional<ReleasePlanner.NodeState> afterImport,
        List<String> reports) {
        NodeId node = call.node();
        try {
            ReleasePlanner.NodeState now = afterImport.isPresent() ? afterImport.get()
                : d.planner().nodeState(call.admitted(), call.release(), node);
            List<PlannedArtifact> changed = deployed.stream().filter(a -> a.artifactClass()
                != ArtifactClass.NEW && !same(call, a, before, now)).toList();
            if (changed.isEmpty()) {
                return State.ROLLED_BACK;
            }
            String reason = "rollback of deploy " + call.admitted().tag() + " " + call.auditId();
            ImportPort port = d.imports().apply(node);
            List<String> repository = changed.stream().filter(a -> a.kind()
                == Kind.REPOSITORY_FILE).map(PlannedArtifact::name).toList();
            if (!repository.isEmpty()) {
                port.importRepository(d.releases().repositoryArchive(List.copyOf(
                    before.repositoryExports().values()), call.admitted().owner(), repository),
                    call.admitted().owner());
            }
            SortedMap<String, byte[]> backup = new TreeMap<>(before.rendered());
            List<PlannedArtifact> modules = changed.stream().filter(a -> a.kind()
                == Kind.MODULE).toList();
            Map<String, List<PlannedArtifact>> workflows = new TreeMap<>();
            changed.stream().filter(a -> a.kind() == Kind.WORKFLOW).forEach(w ->
                workflows.computeIfAbsent(w.group().orElseThrow(), g -> new ArrayList<>())
                    .add(w));
            if (!modules.isEmpty()) {
                port.importArchive(d.archives().assemble(build(call, backup, now.raws(),
                    Optional.empty(), List.of(), modules, reason, false)).zip(),
                    ImportPort.Mode.MODULE, call.admitted().owner());
            }
            for (Map.Entry<String, List<PlannedArtifact>> group : workflows.entrySet()) {
                port.importArchive(d.archives().assemble(build(call, backup, now.raws(),
                    Optional.of(group.getKey()), group.getValue(), List.of(), reason, false))
                    .zip(), ImportPort.Mode.WORKFLOW, call.admitted().owner());
            }
            ReleasePlanner.NodeState restored = d.planner().nodeState(call.admitted(),
                call.release(), node);
            List<String> differences = changed.stream().filter(a -> !same(call, a, before,
                restored)).map(a -> a.name() + ": not back to its state before").toList();
            if (differences.isEmpty()) {
                return State.ROLLED_BACK;
            }
            reports.add(report(call, "rollback", differences));
            return State.ROLLBACK_FAILED;
        } catch (ToolErrorException e) {
            LOG.warn("The rollback on {} failed: {}", node, e.error().code());
            return State.ROLLBACK_FAILED;
        } catch (RuntimeException e) {
            LOG.error("The rollback on {} failed unexpectedly", node, e);
            return State.ROLLBACK_FAILED;
        }
    }

    /** True if {@code artifact} is the same in both states (flag included). */
    private boolean same(Call call, PlannedArtifact artifact, ReleasePlanner.NodeState a,
        ReleasePlanner.NodeState b) {
        SortedMap<String, byte[]> left = d.planner().nodeFiles(call.admitted(), call.release(),
            artifact, a);
        SortedMap<String, byte[]> right = d.planner().nodeFiles(call.admitted(), call.release(),
            artifact, b);
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> file : left.entrySet()) {
            byte[] other = right.get(file.getKey());
            boolean equal = artifact.kind() == Kind.REPOSITORY_FILE
                ? Arrays.equals(file.getValue(), other)
                : Arrays.equals(d.releases().canonical(file.getKey(), file.getValue()),
                    d.releases().canonical(file.getKey(), other));
            if (!equal) {
                return false;
            }
        }
        return true;
    }

    // --- success -------------------------------------------------------------------------------

    private Deployed success(Call call, List<PlannedArtifact> deployed,
        BackupStore.Manifest manifest, ReleasePlanner.NodeState after, List<String> names,
        List<String> created, Map<String, String> inputs, List<String> warnings) {
        NodeId node = call.node();
        Map<String, String> states = d.planner().artifactStates(call.admitted(), call.release(),
            deployed, after);
        d.backups().update(manifest.with("EXECUTED", states));
        WriteOutcome.TagResult tag = tag(call, warnings);
        Map<String, DeploymentLedger.Entry> entries = new TreeMap<>();
        states.forEach((key, fingerprint) -> entries.put(key, new DeploymentLedger.Entry(
            fingerprint, call.auditId().toString(), call.admitted().tag(),
            d.clock().instant())));
        d.ledger().record(node, entries);
        Map<String, String> finalInputs = new LinkedHashMap<>(inputs);
        finalInputs.put("tagApplied", String.valueOf(tag.applied()));
        audit(call, finalInputs, AuditOutcome.EXECUTED, "Deployed and verified " + names.size()
            + " artifact(s) on " + node + (tag.applied() ? "; tagged " + call.admitted().tag()
                : "; the tag was not applied"));
        LOG.info("deploy_release on {}: DEPLOYED", node);
        return new Deployed(new NodeOutcome(node, State.DEPLOYED,
            Optional.of(manifest.auditId()), names, created, Optional.of(tag), Optional.empty(),
            Optional.empty()), Optional.of(after), List.of(), warnings);
    }

    /**
     * The group-scoped tag of the release's deployed diagram groups (never owner-wide); a tag
     * failure is part of the result, with a retry hint.
     */
    private WriteOutcome.TagResult tag(Call call, List<String> warnings) {
        NodeId node = call.node();
        Set<String> groups = new TreeSet<>();
        call.plan().artifacts().stream().filter(a -> a.kind() == Kind.WORKFLOW
            && a.artifactClass() != ArtifactClass.EXCLUDED
            && a.artifactClass() != ArtifactClass.ONLY_ON_TARGET)
            .forEach(a -> groups.add(a.group().orElseThrow()));
        String tag = call.admitted().tag();
        DiagramGroupTagger.Result result;
        try {
            result = new DiagramGroupTagger(d.root()).tag(node, d.tags().apply(node),
                call.admitted().owner(), List.copyOf(groups), tag, call.auditId());
        } catch (RuntimeException e) {
            LOG.error("The tag on {} failed unexpectedly", node, e);
            result = new DiagramGroupTagger.Result(false, 0, 0, Optional.of(
                new WriteOutcome.Failure(ErrorCode.IMPORT_FAILED, "tag", "INTERNAL: "
                    + e.getClass().getSimpleName())), List.of(), List.of());
        }
        if (!result.applied()) {
            WriteOutcome.Failure failure = result.failure().orElseThrow();
            warnings.add("The deployment on " + node + " stays, but the tag " + tag + " was not"
                + " applied (" + failure.code() + " at " + failure.step() + "). "
                + DiagramGroupTagger.retry(tag, result));
        }
        return new WriteOutcome.TagResult(tag, result.applied(), result.workflows(),
            result.modules(), result.failure());
    }

    // --- helpers -------------------------------------------------------------------------------

    private static Deployed notStarted(NodeId node, ErrorCode code, String message) {
        return new Deployed(NodeOutcome.notStarted(node, Optional.of(new WriteOutcome.Failure(
            code, "recheck", message))), Optional.empty(), List.of(), List.of());
    }

    private Map<String, String> inputs(Call call, List<String> names, String backupRef) {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("tag", call.admitted().tag());
        inputs.put("source", call.admitted().source().value());
        inputs.put("owner", call.admitted().owner());
        inputs.put("artifacts", names.size() <= MAX_AUDITED_NAMES ? String.join(", ", names)
            : String.join(", ", names.subList(0, MAX_AUDITED_NAMES)) + ", …+"
                + (names.size() - MAX_AUDITED_NAMES));
        inputs.put("backupRef", backupRef);
        return inputs;
    }

    private static Map<String, String> withRollback(Map<String, String> inputs, State state) {
        Map<String, String> result = new LinkedHashMap<>(inputs);
        result.put("rollback", state.name());
        return result;
    }

    /**
     * Appends a node-level record. A {@code PENDING} record that cannot be written stops the
     * node before anything is sent ({@code INTERNAL}, fail closed); a final record that cannot be
     * written is logged, the outcome stands.
     */
    private void audit(Call call, Map<String, String> inputs, AuditOutcome outcome,
        String reason) {
        NodeId node = call.node();
        try {
            d.audit().append(new AuditRecord(call.auditId(), d.clock().instant(), d.profile(),
                node.value(), Optional.of(node.group().value()), DeployGuard.CAPABILITY,
                AuditRecord.Step.EXECUTE, inputs, Optional.of(d.accounts().apply(node).user()),
                outcome, Optional.of(reason.length() <= 500 ? reason : reason.substring(0, 500)),
                call.mcpClient()));
        } catch (RuntimeException e) {
            LOG.error("The {} audit record of {} could not be written ({})", outcome, node,
                e.getClass().getSimpleName());
            if (outcome == AuditOutcome.PENDING) {
                throw new ToolErrorException(de.dadecker.inubit.mcp.domain.model.ToolError.of(
                    ErrorCode.INTERNAL, "The audit record of " + node + " could not be written;"
                        + " nothing was sent to it",
                    "The audit directory is not writable, full, or not owned by this user",
                    "Fix the audit directory (auditDirectory in the configuration), then"
                        + " retry").withNode(node));
            }
        }
    }

    private String report(Call call, String kind, List<String> lines) {
        String name = REPORTS + "/deploy-" + call.auditId() + "/" + call.node().group() + "-"
            + call.node().name() + "-" + kind + ".txt";
        Path file = d.root().resolve(name);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return name;
    }
}
