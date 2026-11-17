package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Target;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
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
 * <p>Steps 1–3 are those of {@link WriteGuard}. Every refusal is audited as {@code REFUSED}.
 */
public final class ChangeGuard {

    private static final Logger LOG = LoggerFactory.getLogger(ChangeGuard.class);
    private static final int MAX_AUDITED_INPUT = 500;

    private final TargetResolver targets;
    private final Function<NodeId, WritePolicy> policies;
    private final BiPredicate<NodeId, ActionSpec.Area> administration;
    private final GatewayFactory gateways;
    private final VersionGate gate;
    private final WriteGuard writes;
    private final AuditPort audit;
    private final String profile;
    private final Clock clock;
    private final Supplier<UUID> ids;

    /**
     * @param policies       the write settings of each configured server ({@code null}: no write
     *                       access)
     * @param administration whether the group of a server has opted in to the changes of an area
     *                       ({@code administration.<area>})
     */
    public ChangeGuard(TargetResolver targets, Function<NodeId, WritePolicy> policies,
        BiPredicate<NodeId, ActionSpec.Area> administration, GatewayFactory gateways,
        VersionGate gate, AuditPort audit, String profile, Clock clock, Supplier<UUID> ids) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.administration = Objects.requireNonNull(administration, "administration");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.writes = new WriteGuard(targets, policies, gateways, gate);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
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

    /** Audits {@code error} as {@code REFUSED}. */
    private ToolErrorException refused(String tool, String node, ActionSpec spec,
        Map<String, Object> input, ToolError error) {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("action", spec.action());
        Object code = input.get(AuditRecord.CONFIRMATION_CODE);
        if (code != null) {
            inputs.put(AuditRecord.CONFIRMATION_CODE, bounded(code.toString()));
        }
        try {
            audit.append(new AuditRecord(ids.get(), clock.instant(), profile, bounded(node),
                group(node), tool, code != null ? AuditRecord.Step.EXECUTE
                    : AuditRecord.Step.PREVIEW, inputs, account(node), AuditOutcome.REFUSED,
                Optional.of(error.code() + ": " + error.message()), Optional.empty()));
        } catch (RuntimeException e) {
            LOG.error("Audit record of a refusal ({}) could not be written ({})", error.code(),
                e.getClass().getSimpleName());
            return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The audit record could not be written; the call was refused anyway ("
                    + error.code() + ": " + error.message() + ") and nothing was changed",
                "The audit directory is not writable, full, or not owned by this user",
                "Fix the audit directory (auditDirectory in the configuration), then retry"));
        }
        LOG.info("{} {} on {}: REFUSED ({})", tool, spec.action(), bounded(node), error.code());
        return new ToolErrorException(error);
    }

    /** The account of a configured server, if known. */
    private Optional<String> account(String node) {
        try {
            return Optional.ofNullable(policies.apply(NodeId.parse(node)))
                .flatMap(WritePolicy::account);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> group(String node) {
        try {
            return Optional.of(switch (Target.parse(node)) {
                case Target.Group group -> group.id().value();
                case Target.Node server -> server.id().group().value();
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
