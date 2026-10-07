package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ArtifactClass;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DeployMode;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodePlan;
import de.dadecker.inubit.mcp.domain.model.NodePlan.PlannedArtifact;
import de.dadecker.inubit.mcp.domain.model.NodePlan.Warning;
import de.dadecker.inubit.mcp.domain.model.NodePlan.WarningKind;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code deploy_release} (feature 005, US1, US2, research D-3, D-6). This stage implements the
 * preview — a call without confirmation code; nothing is ever written to INUBIT:
 *
 * <ol>
 *   <li>{@link DeployGuard}: one chained target group, tag, owner (refusals audited);
 *   <li>{@link DeployLock}: the target group, then the workspace ({@code DEPLOY_LOCKED},
 *       {@code PRECONDITION_FAILED}), held for the whole call;
 *   <li>{@link ReleaseDiscovery}: the release on every source node ({@code NOT_FOUND},
 *       {@code SOURCE_INCONSISTENT});
 *   <li>the checks of feature 003 on the release ({@link ReleasePlanner#checkRelease}): their
 *       ERROR findings are errors of every node plan (FR-012a);
 *   <li>one {@link ReleasePlanner#plan plan} per target node, in configuration order; a node
 *       that cannot be read fails the whole preview (no partial preview can be confirmed);
 *   <li>the ledger ({@link DeploymentLedger}): a changed or layout-only artifact whose current
 *       state this server did not write carries {@code OUTSIDE_CHAIN} ("changed on the target
 *       outside the chain", with "no earlier deployment by this server" if the node has no
 *       entry at all);
 *   <li>the preview state {@code release=<fingerprint>;groups=<list>;<node>=<fingerprint>…}
 *       and — only if every plan is executable — a code ({@link WriteChallengeRegistry},
 *       {@link WriteChallengeRegistry#DEPLOY_RELEASE}, keyed by the target group, valid
 *       {@code deployConfirmationTtl}), audited {@code CHALLENGE_ISSUED}; a preview that is
 *       not executable is audited {@code REFUSED} and has no code.
 * </ol>
 *
 * <p>Every failure after the guard is audited {@code REFUSED} with the same audit id as the
 * reports of the call. The confirmation cannot be turned off (FR-004).
 */
public final class DeployService {

    private static final Logger LOG = LoggerFactory.getLogger(DeployService.class);
    private static final int MAX_AUDITED_INPUT = 200;
    private static final String NOTE_SYSTEM_DIAGRAMS = "Only technical workflows are read:"
        + " system diagrams are never read and never deployed.";

    /**
     * @param root        the workspace root (reports, workspace lock)
     * @param deployments {@code ~/.inubit-mcp/<profile>/deployments} (deploy locks)
     * @param profile     the profile every audit record carries
     * @param ttl         {@code defaults.deployConfirmationTtl}
     */
    public record Dependencies(Path root, Path deployments, String profile, DeployGuard guard,
        ReleaseDiscovery discovery, ReleasePlanner planner, DeploymentLedger ledger,
        WriteChallengeRegistry challenges, Duration ttl, AuditPort audit, Clock clock,
        Supplier<UUID> ids, NodeDeployer deployer, VersionHistoryPort history) {
        public Dependencies {
            Objects.requireNonNull(deployer, "deployer");
            Objects.requireNonNull(history, "history");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(deployments, "deployments");
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(guard, "guard");
            Objects.requireNonNull(discovery, "discovery");
            Objects.requireNonNull(planner, "planner");
            Objects.requireNonNull(ledger, "ledger");
            Objects.requireNonNull(challenges, "challenges");
            Objects.requireNonNull(ttl, "ttl");
            Objects.requireNonNull(audit, "audit");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(ids, "ids");
        }
    }

    private final Dependencies d;

    public DeployService(Dependencies dependencies) {
        this.d = Objects.requireNonNull(dependencies, "dependencies");
    }

    /** The answer of {@code deploy_release}: a preview (no code given) or a result. */
    public record Response(Optional<DeploymentPreview> preview,
        Optional<DeploymentResult> result) {
        public Response {
            preview = preview == null ? Optional.empty() : preview;
            result = result == null ? Optional.empty() : result;
            if (preview.isPresent() == result.isPresent()) {
                throw new IllegalArgumentException("Either a preview or a result");
            }
        }
    }

    /**
     * {@code deploy_release}: without a code the {@link #preview}, with a code the
     * {@link #execute execution} of the previewed deployment.
     */
    public Response deploy(DeployGuard.Request request) {
        return request.confirmationCode().isEmpty()
            ? new Response(Optional.of(preview(request)), Optional.empty())
            : new Response(Optional.empty(), Optional.of(execute(request)));
    }

    /**
     * The confirmed deployment (FR-014–FR-020, research D-6, D-7, D-12):
     *
     * <ol>
     *   <li>the guard (the code's shape included), the locks, then the code is redeemed for
     *       {@code deploy_release} on the target group with the same inputs
     *       ({@code CONFIRMATION_INVALID});
     *   <li>discovery, release checks and the plan of every node are repeated; the preview state
     *       must be the redeemed one, otherwise {@code CONFLICT} before the first node (the tag
     *       moved, a node changed, a workflow was opened in edit mode);
     *   <li>a group-level {@code PENDING} record, then {@link NodeDeployer} node by node in
     *       configuration order; the first node that is not deployed or unchanged stops the
     *       deployment, the others stay {@code NOT_STARTED};
     *   <li>if every node is deployed or unchanged: the verified state is written into the
     *       workspace and committed as {@code deploy <target> ← <source>: <tag> [<auditId>]}
     *       with {@code Server-State: <target>}; otherwise nothing is committed;
     *   <li>a group-level {@code EXECUTED} or {@code FAILED} record.
     * </ol>
     *
     * @throws ToolErrorException every refusal before the first node is written (audited)
     */
    public DeploymentResult execute(DeployGuard.Request request) {
        try {
            return executeAudited(request);
        } catch (AuditFailed e) {
            throw e.error;
        }
    }

    private DeploymentResult executeAudited(DeployGuard.Request request) {
        DeployGuard.Admitted admitted = d.guard().admit(request);
        UUID auditId = d.ids().get();
        Map<String, String> inputs = inputs(admitted);
        inputs.put(AuditRecord.CONFIRMATION_CODE, request.confirmationCode().orElseThrow());
        boolean started = false;
        try (DeployLock lock = DeployLock.acquire(d.deployments(), admitted.target(), d.root())) {
            String previewed = d.challenges().redeem(request.confirmationCode().get(),
                WriteChallengeRegistry.DEPLOY_RELEASE, admitted.target(), inputFingerprint(
                    admitted.target(), admitted.tag(), admitted.owner()));
            ReleaseDiscovery.Release release = d.discovery().discover(admitted, auditId);
            inputs.put("diagramGroups", String.join(", ", release.diagramGroups()));
            inputs.put("package", release.fingerprint());
            List<NodePlan.PlanError> releaseErrors = d.planner().checkRelease(release, auditId);
            List<NodePlan> plans = new ArrayList<>();
            for (NodeId node : admitted.targetNodes()) {
                plans.add(d.planner().plan(admitted, release, node, auditId).with(List.of(),
                    releaseErrors));
            }
            if (!state(release, plans).equals(previewed)
                || !plans.stream().allMatch(NodePlan::executable)) {
                throw new ToolErrorException(ToolError.of(ErrorCode.CONFLICT,
                    "The release or a node of " + admitted.target() + " changed since the"
                        + " preview; nothing was sent and the code is used up",
                    "The tag moved on the source, a node was imported or published, or a"
                        + " workflow was opened in edit mode meanwhile",
                    "Call deploy_release again without confirmationCode for a new preview"));
            }
            boolean packageOnly = admitted.mode() == DeployMode.PACKAGE_ONLY;
            audit(auditId, admitted, inputs, AuditRecord.Step.EXECUTE, AuditOutcome.PENDING,
                (packageOnly ? "About to write the packages of " : "About to deploy ")
                    + admitted.tag() + " for " + admitted.targetNodes().size() + " node(s) of "
                    + admitted.target(), request.mcpClient());
            started = true;
            return run(admitted, release, plans, auditId, inputs, request.mcpClient());
        } catch (AuditFailed e) {
            throw e;
        } catch (ToolErrorException e) {
            if (started) {
                audit(auditId, admitted, inputs, AuditRecord.Step.EXECUTE, AuditOutcome.FAILED,
                    e.error().code() + ": " + e.error().message(), request.mcpClient());
            } else {
                audit(auditId, admitted, inputs, AuditRecord.Step.EXECUTE, AuditOutcome.REFUSED,
                    e.error().code() + ": " + e.error().message(), request.mcpClient());
            }
            throw e;
        }
    }

    private DeploymentResult run(DeployGuard.Admitted admitted,
        ReleaseDiscovery.Release release, List<NodePlan> plans, UUID auditId,
        Map<String, String> inputs, Optional<String> mcpClient) {
        List<DeploymentResult.NodeOutcome> outcomes = new ArrayList<>();
        List<String> reports = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Optional<ReleasePlanner.NodeState> verified = Optional.empty();
        boolean packageOnly = admitted.mode() == DeployMode.PACKAGE_ONLY;
        boolean stopped = false;
        for (NodePlan plan : plans) {
            if (stopped) {
                outcomes.add(DeploymentResult.NodeOutcome.notStarted(plan.node(),
                    Optional.empty()));
                continue;
            }
            NodeDeployer.Deployed deployed;
            try {
                // B1, T025: a package-only group only ever gets packages, never an import
                deployed = packageOnly
                    ? d.deployer().pack(admitted, release, plan, auditId, mcpClient)
                    : d.deployer().deploy(admitted, release, plan, auditId, mcpClient);
            } catch (RuntimeException e) {
                // the deployer turns every failure from the first import on into its outcome
                LOG.error("deploy_release on {} failed before writing", plan.node(), e);
                deployed = new NodeDeployer.Deployed(DeploymentResult.NodeOutcome.notStarted(
                    plan.node(), Optional.of(new WriteOutcome.Failure(ErrorCode.INTERNAL,
                        "deploy", "An unexpected failure (" + e.getClass().getSimpleName()
                            + ") before anything was sent to " + plan.node()))),
                    Optional.empty(), List.of(), List.of());
            }
            outcomes.add(deployed.outcome());
            reports.addAll(deployed.reports());
            warnings.addAll(deployed.warnings());
            DeploymentResult.State state = deployed.outcome().state();
            if (state == DeploymentResult.State.DEPLOYED
                || state == DeploymentResult.State.UNCHANGED) {
                verified = deployed.verified();
            } else if (state != DeploymentResult.State.PACKAGED) {
                stopped = true;
            }
        }
        Optional<String> commit = Optional.empty();
        if (packageOnly) {
            LOG.debug("deploy_release into {}: package only, nothing to commit",
                admitted.target());
        } else if (!stopped && verified.isPresent()) {
            try {
                commit = commit(admitted, auditId, verified.get());
            } catch (RuntimeException e) {
                // stage 3 review M2: the deployment stands; the next export records the state
                LOG.error("The state of {} could not be committed ({})", admitted.target(),
                    e.getClass().getSimpleName());
                warnings.add("The deployment is complete, but its state could not be committed"
                    + " to the workspace (" + e.getClass().getSimpleName() + "); the next"
                    + " export of " + admitted.target() + " records it");
            }
        } else {
            warnings.add("Not every node is deployed or unchanged, so nothing was committed to"
                + " the workspace; the next export of " + admitted.target() + " records its"
                + " state");
        }
        DeploymentResult.Outcome outcome = stopped ? DeploymentResult.Outcome.FAILED
            : packageOnly ? DeploymentResult.Outcome.PACKAGED
                : DeploymentResult.Outcome.EXECUTED;
        AuditOutcome audited = switch (outcome) {
            case EXECUTED -> AuditOutcome.EXECUTED;
            case PACKAGED -> AuditOutcome.PACKAGED;
            case FAILED -> AuditOutcome.FAILED;
        };
        Map<String, String> finalInputs = new LinkedHashMap<>(inputs);
        outcomes.forEach(node -> finalInputs.put(node.node().value(), node.state().name()
            + node.backupRef().map(ref -> " " + ref).orElse("")));
        try {
            audit(auditId, admitted, finalInputs, AuditRecord.Step.EXECUTE, audited,
                "Deployment of " + admitted.tag() + " into "
                    + admitted.target() + ": " + String.join(", ", outcomes.stream()
                        .map(node -> node.node() + " " + node.state()).toList()), mcpClient);
        } catch (AuditFailed e) {
            // the nodes are written: the result is the answer, the missing record a warning
            warnings.add("The final audit record " + auditId + " could not be written; the"
                + " node records hold each node's outcome");
        }
        LOG.info("deploy_release into {}: {}", admitted.target(), outcome);
        return new DeploymentResult(auditId, admitted.target(), admitted.source(),
            admitted.tag(), outcome, outcomes, commit, reports, warnings);
    }

    /** Writes the verified state of the target into the workspace and commits it (D-12). */
    private Optional<String> commit(DeployGuard.Admitted admitted, UUID auditId,
        ReleasePlanner.NodeState verified) {
        VersionHistoryPort history = d.history();
        history.init();
        List<PathChange> local = history.status();
        boolean onlyGitignore = local.stream().allMatch(change -> change.path()
            .equals(".gitignore") && change.kind() == PathChange.Kind.ADDED);
        if (!onlyGitignore) {
            history.commitAll("local changes: " + local.size() + " files");
        }
        try {
            for (Map.Entry<String, byte[]> file : verified.rendered().entrySet()) {
                Path path = d.root().resolve(file.getKey());
                java.nio.file.Files.createDirectories(path.getParent());
                java.nio.file.Files.write(path, file.getValue());
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return history.commitAll("deploy " + admitted.target() + " ← " + admitted.source()
                + ": " + admitted.tag() + " [" + auditId + "]",
            Map.of(VersionHistoryPort.SERVER_STATE, admitted.target().value()))
            .map(entry -> entry.commit());
    }

    /** The preview state of research D-6. */
    private static String state(ReleaseDiscovery.Release release, List<NodePlan> plans) {
        StringBuilder state = new StringBuilder("release=").append(release.fingerprint())
            .append(";groups=").append(String.join(",", release.diagramGroups()));
        plans.forEach(plan -> state.append(';').append(plan.node()).append('=')
            .append(plan.targetFingerprint()));
        return state.toString();
    }

    /**
     * The preview of {@code request} (a call without confirmation code); see the class
     * description.
     *
     * @throws ToolErrorException the refusals of {@link DeployGuard}, {@code DEPLOY_LOCKED},
     *     {@code PRECONDITION_FAILED}, {@code NOT_FOUND}, {@code SOURCE_INCONSISTENT} and the
     *     export failures of a node, each audited
     */
    public DeploymentPreview preview(DeployGuard.Request request) {
        try {
            return previewAudited(request);
        } catch (AuditFailed e) {
            throw e.error;
        }
    }

    private DeploymentPreview previewAudited(DeployGuard.Request request) {
        if (request.confirmationCode().isPresent()) {
            throw new IllegalArgumentException("A preview has no confirmation code");
        }
        DeployGuard.Admitted admitted = d.guard().admit(request);
        UUID auditId = d.ids().get();
        Map<String, String> inputs = inputs(admitted);
        try (DeployLock lock = DeployLock.acquire(d.deployments(), admitted.target(), d.root())) {
            return locked(admitted, auditId, inputs, request.mcpClient());
        } catch (AuditFailed e) {
            throw e.error; // never audited twice (stage 2 review n2)
        } catch (ToolErrorException e) {
            audit(auditId, admitted, inputs, AuditOutcome.REFUSED, e.error().code() + ": "
                + e.error().message(), request.mcpClient());
            LOG.info("deploy_release into {}: REFUSED ({})", admitted.target(),
                e.error().code());
            throw e;
        } catch (RuntimeException e) {
            ToolError error = ToolError.of(ErrorCode.INTERNAL, "The preview failed unexpectedly ("
                + e.getClass().getSimpleName() + "); nothing was sent",
                "An internal error of the INUBIT MCP server",
                "Report the problem with the MCP server log");
            LOG.error("deploy_release preview into {} failed", admitted.target(), e);
            audit(auditId, admitted, inputs, AuditOutcome.REFUSED, error.code() + ": "
                + error.message(), request.mcpClient());
            throw new ToolErrorException(error);
        }
    }

    private DeploymentPreview locked(DeployGuard.Admitted admitted, UUID auditId,
        Map<String, String> inputs, Optional<String> mcpClient) {
        ReleaseDiscovery.Release release = d.discovery().discover(admitted, auditId);
        inputs.put("diagramGroups", String.join(", ", release.diagramGroups()));
        inputs.put("package", release.fingerprint());
        List<NodePlan.PlanError> releaseErrors = d.planner().checkRelease(release, auditId);
        List<NodePlan> plans = new ArrayList<>();
        for (NodeId node : admitted.targetNodes()) {
            NodePlan plan = d.planner().plan(admitted, release, node, auditId);
            plan = plan.with(outsideChain(plan), releaseErrors);
            d.planner().rewriteSummary(plan, admitted, auditId);
            plans.add(plan);
        }
        String state = state(release, plans);
        boolean executable = plans.stream().allMatch(NodePlan::executable);
        Optional<String> code = Optional.empty();
        Optional<java.time.Instant> expiresAt = Optional.empty();
        String instruction;
        if (executable) {
            WriteChallengeRegistry.Issued issued = d.challenges().issue(
                WriteChallengeRegistry.DEPLOY_RELEASE, admitted.target(),
                inputFingerprint(admitted.target(), admitted.tag(), admitted.owner()),
                state, d.ttl());
            Map<String, String> audited = new LinkedHashMap<>(inputs);
            audited.put(AuditRecord.ISSUED_CONFIRMATION_CODE, issued.code());
            try {
                audit(auditId, admitted, audited, AuditOutcome.CHALLENGE_ISSUED, "Preview of "
                    + admitted.tag() + " from " + admitted.source() + " into " + admitted
                        .target() + ", code valid until " + issued.expiresAt(), mcpClient);
            } catch (AuditFailed e) {
                d.challenges().discard(issued.code());
                throw e;
            }
            code = Optional.of(issued.code());
            expiresAt = Optional.of(issued.expiresAt());
            instruction = "Nothing was sent. To deploy " + admitted.tag() + " ("
                + String.join(", ", release.diagramGroups()) + ") from " + admitted.source()
                + " into " + admitted.target() + ", node by node, show this preview to the user"
                + " and, after their explicit approval, call deploy_release again with the same"
                + " inputs and confirmationCode before " + issued.expiresAt() + ".";
            LOG.info("deploy_release into {}: CHALLENGE_ISSUED", admitted.target());
        } else {
            long errors = plans.stream().mapToLong(plan -> plan.errors().size()).sum();
            String reason = "The preview is not executable: " + errors + " error(s) on "
                + String.join(", ", plans.stream().filter(plan -> !plan.executable())
                    .map(plan -> plan.node().value()).toList());
            audit(auditId, admitted, inputs, AuditOutcome.REFUSED, reason, mcpClient);
            instruction = "Nothing was sent. " + reason + "; there is no confirmation code."
                + " Show the errors to the user, resolve them, then call deploy_release again"
                + " for a new preview.";
            LOG.info("deploy_release into {}: preview not executable", admitted.target());
        }
        return new DeploymentPreview(auditId, admitted.target(), admitted.source(),
            admitted.tag(), admitted.owner(), admitted.mode(),
            List.copyOf(release.diagramGroups()), release.olderThanHead(), plans,
            List.of(NOTE_SYSTEM_DIAGRAMS), state, code, expiresAt, instruction);
    }

    /**
     * {@code OUTSIDE_CHAIN} for every changed or layout-only artifact whose current state on the
     * node is not what the last deployment of this server wrote (research D-8).
     */
    private List<Warning> outsideChain(NodePlan plan) {
        Map<String, DeploymentLedger.Entry> entries = d.ledger().entries(plan.node());
        List<Warning> warnings = new ArrayList<>();
        for (PlannedArtifact artifact : plan.artifacts()) {
            if (artifact.artifactClass() != ArtifactClass.CHANGED
                && artifact.artifactClass() != ArtifactClass.LAYOUT_ONLY) {
                continue;
            }
            String key = NodePlan.key(artifact);
            DeploymentLedger.Entry entry = entries.get(key);
            String state = plan.artifactStates().get(key);
            if (entry == null || !entry.fingerprint().equals(state)) {
                warnings.add(new Warning(WarningKind.OUTSIDE_CHAIN, artifact.name(),
                    "changed on the target outside the chain" + (entries.isEmpty()
                        ? " (no earlier deployment by this server)" : entry == null
                            ? " (not deployed by this server before)" : " (since "
                                + entry.tag() + ", " + entry.at() + ")")));
            }
        }
        return warnings;
    }

    /** The fingerprint of the inputs a code is bound to: tool, target, tag and owner. */
    static String inputFingerprint(GroupId target, String tag, String owner) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : List.of(WriteChallengeRegistry.DEPLOY_RELEASE, target.value(),
                tag, owner)) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> inputs(DeployGuard.Admitted admitted) {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("tag", admitted.tag());
        inputs.put("source", admitted.source().value());
        inputs.put("owner", admitted.owner());
        inputs.put("mode", admitted.mode().name());
        return inputs;
    }

    private void audit(UUID auditId, DeployGuard.Admitted admitted, Map<String, String> inputs,
        AuditOutcome outcome, String reason, Optional<String> mcpClient) {
        audit(auditId, admitted, inputs, AuditRecord.Step.PREVIEW, outcome, reason, mcpClient);
    }

    private void audit(UUID auditId, DeployGuard.Admitted admitted, Map<String, String> inputs,
        AuditRecord.Step step, AuditOutcome outcome, String reason,
        Optional<String> mcpClient) {
        Map<String, String> bounded = new LinkedHashMap<>();
        inputs.forEach((key, value) -> bounded.put(key, bounded(value)));
        try {
            d.audit().append(new AuditRecord(auditId, d.clock().instant(), d.profile(),
                admitted.target().value(), Optional.of(admitted.target().value()),
                DeployGuard.CAPABILITY, step, bounded, Optional.empty(),
                outcome, Optional.of(bounded(reason)), mcpClient));
        } catch (RuntimeException e) {
            LOG.error("Audit record {} could not be written ({})", auditId,
                e.getClass().getSimpleName());
            throw new AuditFailed(new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The audit record could not be written; nothing was sent and no code was"
                    + " issued",
                "The audit directory is not writable, full, or not owned by this user",
                "Fix the audit directory (auditDirectory in the configuration), then retry")));
        }
    }

    /** An audit record could not be written: reported as {@code error}, never audited. */
    private static final class AuditFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final ToolErrorException error;

        AuditFailed(ToolErrorException error) {
            super(null, null, false, false);
            this.error = error;
        }
    }

    private static String bounded(String value) {
        return value.length() <= MAX_AUDITED_INPUT ? value
            : value.substring(0, MAX_AUDITED_INPUT);
    }
}
