package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ArtifactClass;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
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
import de.dadecker.inubit.mcp.domain.port.AuditPort;
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
        Supplier<UUID> ids) {
        public Dependencies {
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

    /**
     * The preview of {@code request} (a call without confirmation code); see the class
     * description.
     *
     * @throws ToolErrorException the refusals of {@link DeployGuard}, {@code DEPLOY_LOCKED},
     *     {@code PRECONDITION_FAILED}, {@code NOT_FOUND}, {@code SOURCE_INCONSISTENT} and the
     *     export failures of a node, each audited
     */
    public DeploymentPreview preview(DeployGuard.Request request) {
        if (request.confirmationCode().isPresent()) {
            throw new IllegalArgumentException("A preview has no confirmation code");
        }
        DeployGuard.Admitted admitted = d.guard().admit(request);
        UUID auditId = d.ids().get();
        Map<String, String> inputs = inputs(admitted);
        try (DeployLock lock = DeployLock.acquire(d.deployments(), admitted.target(), d.root())) {
            return locked(admitted, auditId, inputs, request.mcpClient());
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
        StringBuilder state = new StringBuilder("release=").append(release.fingerprint())
            .append(";groups=").append(String.join(",", release.diagramGroups()));
        plans.forEach(plan -> state.append(';').append(plan.node()).append('=')
            .append(plan.targetFingerprint()));
        boolean executable = plans.stream().allMatch(NodePlan::executable);
        Optional<String> code = Optional.empty();
        Optional<java.time.Instant> expiresAt = Optional.empty();
        String instruction;
        if (executable) {
            WriteChallengeRegistry.Issued issued = d.challenges().issue(
                WriteChallengeRegistry.DEPLOY_RELEASE, admitted.target(),
                inputFingerprint(admitted.target(), admitted.tag(), admitted.owner()),
                state.toString(), d.ttl());
            Map<String, String> audited = new LinkedHashMap<>(inputs);
            audited.put(AuditRecord.ISSUED_CONFIRMATION_CODE, issued.code());
            try {
                audit(auditId, admitted, audited, AuditOutcome.CHALLENGE_ISSUED, "Preview of "
                    + admitted.tag() + " from " + admitted.source() + " into " + admitted
                        .target() + ", code valid until " + issued.expiresAt(), mcpClient);
            } catch (ToolErrorException e) {
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
            List.of(NOTE_SYSTEM_DIAGRAMS), state.toString(), code, expiresAt, instruction);
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
        Map<String, String> bounded = new LinkedHashMap<>();
        inputs.forEach((key, value) -> bounded.put(key, bounded(value)));
        try {
            d.audit().append(new AuditRecord(auditId, d.clock().instant(), d.profile(),
                admitted.target().value(), Optional.of(admitted.target().value()),
                DeployGuard.CAPABILITY, AuditRecord.Step.PREVIEW, bounded, Optional.empty(),
                outcome, Optional.of(bounded(reason)), mcpClient));
        } catch (RuntimeException e) {
            LOG.error("Audit record {} could not be written ({})", auditId,
                e.getClass().getSimpleName());
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The audit record could not be written; nothing was sent and no code was"
                    + " issued",
                "The audit directory is not writable, full, or not owned by this user",
                "Fix the audit directory (auditDirectory in the configuration), then retry"));
        }
    }

    private static String bounded(String value) {
        return value.length() <= MAX_AUDITED_INPUT ? value
            : value.substring(0, MAX_AUDITED_INPUT);
    }
}
