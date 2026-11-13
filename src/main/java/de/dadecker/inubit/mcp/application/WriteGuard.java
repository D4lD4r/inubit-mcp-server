package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessAction;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The configuration checks of the write tools, before anything is read from INUBIT
 * (contracts/mcp-tools.md §7–8, FR-019, FR-024, Constitution I). In this order:
 *
 * <ol>
 *   <li>The input must be one configured server: a stage is {@code INVALID_INPUT} (Story 4 /
 *       AS 8), an unknown id {@code TARGET_UNKNOWN}.
 *   <li>A server of a production stage without {@code write.productionOptIn} is
 *       {@code PRODUCTION_PROTECTED}, whatever {@code write.enabled} says (FR-019, quickstart
 *       V13). This is checked before {@code write.enabled} so that the refusal names the
 *       production lock, which a plain {@code write.enabled: true} does not lift.
 *   <li>A production server with {@code write.confirmation: CLIENT} is
 *       {@code PRODUCTION_PROTECTED} as well (defense in depth; the configuration validation
 *       already rejects it at startup).
 *   <li>{@code write.enabled: false} (the default) is {@code WRITE_DISABLED} with a hint on how
 *       to enable it; a server without policy is treated the same (fail closed).
 *   <li>Feature 009 (research D-5): the {@link VersionGate} admits process control for writes
 *       on the server's INUBIT version and StartCLI client ({@code UNSUPPORTED_VERSION}), before
 *       anything else is sent; its warnings travel in {@link WritePolicy#warnings()}.
 *   <li>The server's process control port must be usable without launching anything
 *       ({@code CLI_UNAVAILABLE}, or {@code AUTH_FAILED} without credentials).
 * </ol>
 *
 * <p>Feature 024 (research D-15, D-18): the log-level tools pass the same checks with texts
 * naming the tool, and between {@code write.enabled} and the version gate their group's opt-in
 * ({@code NOT_CONFIGURED}); {@link #admitWithoutContact} stops after the opt-in.
 */
public final class WriteGuard {

    private final TargetResolver targets;
    private final Function<NodeId, WritePolicy> policies;
    private final GatewayFactory gateways;
    private final VersionGate gate;

    /**
     * @param policies the write settings of each configured server ({@code null}: no write
     *                 access)
     * @param gate     feature 009: the version gate
     */
    public WriteGuard(TargetResolver targets, Function<NodeId, WritePolicy> policies,
        GatewayFactory gateways, VersionGate gate) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /**
     * The policy of the server {@code action} may run on, with the version gate's warnings.
     *
     * @throws ToolErrorException {@code INVALID_INPUT}, {@code TARGET_UNKNOWN},
     *     {@code PRODUCTION_PROTECTED}, {@code WRITE_DISABLED}, {@code UNSUPPORTED_VERSION},
     *     {@code CLI_UNAVAILABLE} or {@code AUTH_FAILED}
     */
    public WritePolicy admit(String node, ProcessAction action) {
        Objects.requireNonNull(action, "action");
        NodeId id = targets.resolveSingleServer(node);
        WritePolicy policy = writeChecks(id, "restart and kill are",
            "restart_process and kill_process");
        // the state read is REST, the action StartCLI
        return contact(id, policy, action.capability(), Capability.PROCESS_CONTROL,
            Set.of(Channel.CLI, Channel.REST));
    }

    /**
     * Feature 024 (research D-18): the policy of the server {@code tool} may change: the checks
     * of {@link #admit(String, ProcessAction)} with texts naming {@code tool}, and after the
     * write checks the group's opt-in ({@code NOT_CONFIGURED} naming {@code optInKey}), before
     * the version gate and the client check (StartCLI, the channel of the log-level tools).
     *
     * @param optIn    whether the server's group has set {@code optInKey}
     * @param optInKey the opt-in setting, e.g. {@code unsupported.logLevelWrite}
     * @throws ToolErrorException as {@link #admit(String, ProcessAction)}, and
     *     {@code NOT_CONFIGURED}
     */
    public WritePolicy admit(String node, String tool, Capability capability,
        Set<Channel> channels, Predicate<NodeId> optIn, String optInKey) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(channels, "channels");
        NodeId id = targets.resolveSingleServer(node);
        return contact(id, configured(id, tool, optIn, optInKey), tool, capability, channels);
    }

    /**
     * Feature 024 (research D-15): the configuration checks of {@link #admit(String, String,
     * Capability, Set, Predicate, String)} up to the opt-in only, for a call that sends nothing
     * (forgetting a lease): no version gate and no client check, so neither the gate nor the
     * gateways are asked.
     *
     * @throws ToolErrorException {@code INVALID_INPUT}, {@code TARGET_UNKNOWN},
     *     {@code PRODUCTION_PROTECTED}, {@code WRITE_DISABLED} or {@code NOT_CONFIGURED}
     */
    public WritePolicy admitWithoutContact(String node, String tool, Predicate<NodeId> optIn,
        String optInKey) {
        return configured(targets.resolveSingleServer(node), tool, optIn, optInKey);
    }

    /** The write checks naming {@code tool}, then the opt-in. */
    private WritePolicy configured(NodeId id, String tool, Predicate<NodeId> optIn,
        String optInKey) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(optIn, "optIn");
        Objects.requireNonNull(optInKey, "optInKey");
        WritePolicy policy = writeChecks(id, tool + " is", tool);
        if (!optIn.test(id)) {
            Terminology terms = targets.terms();
            String group = terms.render("{group} ") + id.group();
            throw refused(ErrorCode.NOT_CONFIGURED, id,
                tool + " is not enabled for " + group + ": " + optInKey + " is not set",
                tool + terms.render(" uses an unsupported, undocumented INUBIT interface and is"
                    + " off unless the {group} opts in"),
                "Set " + optInKey + ": true on " + group + " and restart the MCP client"
                    + " (unsupported INUBIT functionality, see docs/setup.md \"Unsupported"
                    + " functions\")");
        }
        return policy;
    }

    /**
     * The production lock, {@code CLIENT} on production and {@code write.enabled}.
     *
     * @param refusedSubject what the production refusals say is refused, e.g. {@code
     *                       "restart and kill are"}
     * @param allowed        what {@code write.enabled} would allow, e.g. {@code
     *                       "restart_process and kill_process"}
     */
    private WritePolicy writeChecks(NodeId id, String refusedSubject, String allowed) {
        Terminology terms = targets.terms();
        WritePolicy policy = policies.apply(id);
        if (policy != null && policy.production() && !policy.productionOptIn()) {
            throw refused(ErrorCode.PRODUCTION_PROTECTED, id,
                id + terms.render(" belongs to the production {group} ") + id.group()
                    + "; " + refusedSubject + " refused there",
                terms.render("The {group} is classified production: true and"
                    + " write.productionOptIn is not set for this {node} (write.enabled alone"
                    + " never unlocks production)"),
                terms.render("Perform the action in the INUBIT Workbench; only if this {node}"
                    + " must allow it, set write.productionOptIn: true and write.enabled: true"
                    + " for it and restart the MCP client"));
        }
        if (policy != null && policy.production()
            && policy.confirmation() == WritePolicy.Confirmation.CLIENT) {
            // defense in depth (review W4): ConfigValidator already rejects this at startup
            throw refused(ErrorCode.PRODUCTION_PROTECTED, id,
                id + terms.render(" belongs to the production {group} ") + id.group()
                    + " and is configured with client-side confirmation; " + refusedSubject
                    + " refused there",
                terms.render("write.confirmation: CLIENT is not allowed on production {groups}"
                    + " (FR-022)"),
                terms.render("Remove write.confirmation: CLIENT for this {node} (confirmation by"
                    + " the MCP server is the default) and restart the MCP client"));
        }
        if (policy == null || !policy.enabled()) {
            throw refused(ErrorCode.WRITE_DISABLED, id,
                "Write access is disabled for " + id + "; nothing was changed",
                terms.render("The {node} is read-only: write.enabled is false (the default)"),
                "To allow " + allowed + terms.render(" on this {node}, set"
                    + " write.enabled: true for its {group} or {node} in the configuration file"
                    + " and restart the MCP client (see docs/setup.md, write settings)"));
        }
        return policy;
    }

    /** The version gate for {@code tool}, then the StartCLI client check. */
    private WritePolicy contact(NodeId id, WritePolicy policy, String tool,
        Capability capability, Set<Channel> channels) {
        VersionGate.Admission admission = gate.admit(tool, id, capability,
            Capability.Kind.WRITE, channels);
        gateways.processControl(id).checkAvailable();
        return policy.withWarnings(admission.warnings())
            .withPermit(admission.permit(id, capability));
    }

    private static ToolErrorException refused(ErrorCode code, NodeId server, String message,
        String likelyCause, String nextStep) {
        return new ToolErrorException(ToolError.of(code, message, likelyCause, nextStep)
            .withNode(server));
    }
}
