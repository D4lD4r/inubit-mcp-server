package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import java.util.Objects;
import java.util.function.Function;

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
 *   <li>The server's process control port must be usable without launching anything
 *       ({@code CLI_UNAVAILABLE}, or {@code AUTH_FAILED} without credentials).
 * </ol>
 */
public final class WriteGuard {

    private final TargetResolver targets;
    private final Function<NodeId, WritePolicy> policies;
    private final GatewayFactory gateways;

    /**
     * @param policies the write settings of each configured server ({@code null}: no write
     *                 access)
     */
    public WriteGuard(TargetResolver targets, Function<NodeId, WritePolicy> policies,
        GatewayFactory gateways) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
    }

    /**
     * The policy of the server a write action may run on.
     *
     * @throws ToolErrorException {@code INVALID_INPUT}, {@code TARGET_UNKNOWN},
     *     {@code PRODUCTION_PROTECTED}, {@code WRITE_DISABLED}, {@code CLI_UNAVAILABLE} or
     *     {@code AUTH_FAILED}
     */
    public WritePolicy admit(String node) {
        NodeId id = targets.resolveSingleServer(node);
        Terminology terms = targets.terms();
        WritePolicy policy = policies.apply(id);
        if (policy != null && policy.production() && !policy.productionOptIn()) {
            throw refused(ErrorCode.PRODUCTION_PROTECTED, id,
                id + terms.render(" belongs to the production {group} ") + id.group()
                    + "; restart and kill are refused there",
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
                    + " and is configured with client-side confirmation; restart and kill are"
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
                terms.render("To allow restart_process and kill_process on this {node}, set"
                    + " write.enabled: true for its {group} or {node} in the configuration file"
                    + " and restart the MCP client (see docs/setup.md, write settings)"));
        }
        gateways.processControl(id).checkAvailable();
        return policy;
    }

    private static ToolErrorException refused(ErrorCode code, NodeId server, String message,
        String likelyCause, String nextStep) {
        return new ToolErrorException(ToolError.of(code, message, likelyCause, nextStep)
            .withNode(server));
    }
}
