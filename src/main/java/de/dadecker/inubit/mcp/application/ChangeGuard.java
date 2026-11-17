package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The admission of a change of feature 022 (research D-12, FR-014–FR-016), before anything is
 * read from or sent to INUBIT. In this order:
 *
 * <ol>
 *   <li>the node must be one configured server: a group id is {@code INVALID_INPUT}, an unknown
 *       id {@code TARGET_UNKNOWN};
 *   <li>production without {@code write.productionOptIn}, or with {@code write.confirmation:
 *       CLIENT}, is {@code PRODUCTION_PROTECTED};
 *   <li>{@code write.enabled: false} is {@code WRITE_DISABLED};
 *   <li>the group's {@code administration.<area>} opt-in of the action's area is required
 *       ({@code NOT_CONFIGURED}); not for {@code OPERATION}, which needs none;
 *   <li>{@link VersionGate#admitCall} with the entries of this call ({@code UNSUPPORTED_VERSION}
 *       naming the variant);
 *   <li>a call that sends a StartCLI entry needs a usable StartCLI client and credentials
 *       ({@code CLI_UNAVAILABLE}, {@code AUTH_FAILED}).
 * </ol>
 *
 * <p>Steps 1–3 are those of {@link WriteGuard}. Every refusal is audited as {@code REFUSED}
 * through {@link ChangeAudit}, with the action and a presented confirmation code as inputs.
 */
public final class ChangeGuard {

    private static final Logger LOG = LoggerFactory.getLogger(ChangeGuard.class);

    private final TargetResolver targets;
    private final Function<NodeId, WritePolicy> policies;
    private final BiPredicate<NodeId, ActionSpec.Area> administration;
    private final GatewayFactory gateways;
    private final VersionGate gate;
    private final WriteGuard writes;
    private final ChangeAudit audit;

    /**
     * @param policies       the write settings of each configured server ({@code null}: no write
     *                       access)
     * @param administration whether the group of a server has opted in to the changes of an area
     *                       ({@code administration.<area>})
     */
    public ChangeGuard(TargetResolver targets, Function<NodeId, WritePolicy> policies,
        BiPredicate<NodeId, ActionSpec.Area> administration, GatewayFactory gateways,
        VersionGate gate, ChangeAudit audit) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.administration = Objects.requireNonNull(administration, "administration");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.writes = new WriteGuard(targets, policies, gateways, gate);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /**
     * The policy of the server {@code node} on which {@code tool} may run the call of
     * {@code spec} with {@code input}, with the version gate's warnings and permit.
     *
     * @throws ToolErrorException {@code INVALID_INPUT}, {@code TARGET_UNKNOWN},
     *     {@code PRODUCTION_PROTECTED}, {@code WRITE_DISABLED}, {@code NOT_CONFIGURED},
     *     {@code UNSUPPORTED_VERSION}, {@code CLI_UNAVAILABLE} or {@code AUTH_FAILED}, audited
     *     as {@code REFUSED}; {@code INTERNAL} if that audit record could not be written
     */
    public WritePolicy admit(String tool, String node, ActionSpec spec,
        Map<String, Object> input) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(input, "input");
        try {
            WritePolicy policy = writes.admitWrite(node, tool);
            NodeId id = policy.node();
            if (spec.area() != ActionSpec.Area.OPERATION
                && !administration.test(id, spec.area())) {
                throw notConfigured(tool, id, spec.area());
            }
            Set<CoverageKey> entries = spec.entries(input);
            VersionGate.Admission admission = gate.admitCall(tool, id, spec, entries);
            if (entries.stream().anyMatch(entry -> entry.channel() == Channel.CLI)) {
                gateways.processControl(id).checkAvailable();
            }
            return policy.withWarnings(admission.warnings())
                .withPermit(admission.permit(id, spec.capability()));
        } catch (ToolErrorException e) {
            throw refused(tool, String.valueOf(node), spec, input, e.error());
        }
    }

    private ToolErrorException notConfigured(String tool, NodeId id, ActionSpec.Area area) {
        Terminology terms = targets.terms();
        String group = terms.render("{group} ") + id.group();
        String key = "administration." + key(area);
        return new ToolErrorException(ToolError.of(ErrorCode.NOT_CONFIGURED,
            tool + " is not enabled for " + group + ": " + key + " is not set; nothing was"
                + " changed",
            terms.render("Changes of this area are off unless the {group} opts in"),
            "Set " + key + ": true on " + group + " in the configuration file and restart the"
                + " MCP client").withNode(id));
    }

    /** The configuration key of {@code area}, e.g. {@code runtimeData}. */
    private static String key(ActionSpec.Area area) {
        StringBuilder key = new StringBuilder();
        for (String word : area.name().toLowerCase(Locale.ROOT).split("_")) {
            key.append(key.isEmpty() ? word : Character.toUpperCase(word.charAt(0))
                + word.substring(1));
        }
        return key.toString();
    }

    /**
     * Audits {@code error} as {@code REFUSED}: in the step {@code EXECUTE} if the call carried a
     * confirmation code or needs none ({@link ChangeChallenges#needsCode}), else {@code PREVIEW}.
     */
    private ToolErrorException refused(String tool, String node, ActionSpec spec,
        Map<String, Object> input, ToolError error) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        Object code = input.get(AuditRecord.CONFIRMATION_CODE);
        if (code != null) {
            inputs.put(AuditRecord.CONFIRMATION_CODE, code);
        }
        Optional<WritePolicy> known = known(node);
        boolean execute = code != null
            || known.map(policy -> !ChangeChallenges.needsCode(policy, spec.area())).orElse(false);
        ChangeAudit.Call call = audit.call(tool, node, spec.action(), inputs, Optional.empty());
        call.account(known.flatMap(WritePolicy::account));
        ToolErrorException refusal = call.refused(execute ? AuditRecord.Step.EXECUTE
            : AuditRecord.Step.PREVIEW, error);
        if (refusal.error() == error) {
            LOG.info("{} {}: REFUSED ({}, audit id {})", tool, spec.action(), error.code(),
                call.auditId());
        }
        return refusal;
    }

    /** The policy of a configured server, if {@code node} is one. */
    private Optional<WritePolicy> known(String node) {
        try {
            return Optional.ofNullable(policies.apply(NodeId.parse(node)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
