package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.Objects;
import java.util.function.Function;

/**
 * The configuration checks of the development tools of feature 004, before anything is read from
 * INUBIT (research D-1, FR-001, FR-004, FR-029, Constitution I). In this order:
 *
 * <ol>
 *   <li>The input must be one configured node: a group id is {@code INVALID_INPUT}, an unknown
 *       id {@code TARGET_UNKNOWN}.
 *   <li>A node that is not a development stage ({@code development.enabled} false, the default,
 *       or no policy at all: fail closed) is {@code NOT_DEVELOPMENT}.
 *   <li>A development node of a production group is {@code PRODUCTION_PROTECTED} (defence in
 *       depth: the configuration validation rejects it at startup).
 *   <li>{@code run_e2e_test} needs {@code e2eTests} other than {@code FORBIDDEN}
 *       ({@code E2E_FORBIDDEN}); it does not need StartCLI.
 *   <li>The other tools need a usable StartCLI installation and credentials, checked without
 *       launching anything ({@code CLI_UNAVAILABLE}, {@code AUTH_FAILED}).
 * </ol>
 *
 * <p>The existing {@link WriteGuard} of restart and kill stays unchanged: its settings and texts
 * belong to those tools.
 */
public final class DevelopmentGuard {

    /** The tools of feature 004; the name is the tool name and the audit capability. */
    public enum Capability {
        IMPORT_ARTIFACTS("import_artifacts"),
        RESTORE_BACKUP("restore_backup"),
        SET_ACTIVE("set_active"),
        TAG_ARTIFACTS("tag_artifacts"),
        RUN_E2E_TEST("run_e2e_test");

        private final String toolName;

        Capability(String toolName) {
            this.toolName = toolName;
        }

        public String toolName() {
            return toolName;
        }

        /** Every tool except the SOAP test runs StartCLI. */
        boolean needsCli() {
            return this != RUN_E2E_TEST;
        }
    }

    /**
     * Checks without launching anything that StartCLI can be used for a node.
     *
     * @see de.dadecker.inubit.mcp.domain.port.ProcessControlPort#checkAvailable()
     */
    @FunctionalInterface
    public interface CliCheck {

        /** @throws ToolErrorException {@code CLI_UNAVAILABLE} or {@code AUTH_FAILED} */
        void check(NodeId node);
    }

    private final TargetResolver targets;
    private final Function<NodeId, DevelopmentPolicy> policies;
    private final CliCheck cli;

    /**
     * @param policies the development settings of each configured node ({@code null}: not a
     *                 development node)
     * @param cli      the StartCLI availability check of a node
     */
    public DevelopmentGuard(TargetResolver targets, Function<NodeId, DevelopmentPolicy> policies,
        CliCheck cli) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.cli = Objects.requireNonNull(cli, "cli");
    }

    /**
     * The policy of the node a development tool may run on.
     *
     * @throws ToolErrorException {@code INVALID_INPUT}, {@code TARGET_UNKNOWN},
     *     {@code NOT_DEVELOPMENT}, {@code PRODUCTION_PROTECTED}, {@code E2E_FORBIDDEN},
     *     {@code CLI_UNAVAILABLE} or {@code AUTH_FAILED}
     */
    public DevelopmentPolicy admit(String node, Capability capability) {
        Objects.requireNonNull(capability, "capability");
        NodeId id = targets.resolveSingleServer(node);
        Terminology terms = targets.terms();
        DevelopmentPolicy policy = policies.apply(id);
        if (policy == null || !policy.enabled()) {
            throw refused(ErrorCode.NOT_DEVELOPMENT, id,
                id + terms.render(" is not a development {node}; ") + capability.toolName()
                    + " is refused there and nothing was sent",
                terms.render("development.enabled is false for this {node} (the default)"),
                terms.render("Use a development {node} (list_nodes), or, if this {node} is a"
                    + " development stage, set development.enabled: true for its {group} or"
                    + " {node} in the configuration file and restart the MCP client (see"
                    + " docs/setup.md, development settings)"));
        }
        if (policy.production()) {
            throw refused(ErrorCode.PRODUCTION_PROTECTED, id,
                id + terms.render(" belongs to the production {group} ") + id.group() + "; "
                    + capability.toolName() + " is refused there",
                terms.render("The {group} is classified production: true; development settings"
                    + " are not allowed on production (the configuration check rejects them)"),
                terms.render("Remove development.enabled and e2eTests from the production"
                    + " {group} and restart the MCP client"));
        }
        if (capability == Capability.RUN_E2E_TEST && policy.e2eTests() == E2ePolicy.FORBIDDEN) {
            throw refused(ErrorCode.E2E_FORBIDDEN, id,
                terms.render("End-to-end tests are not allowed on ") + id
                    + "; nothing was sent",
                terms.render("e2eTests is FORBIDDEN for this {node} (the default)"),
                terms.render("Set e2eTests: CONFIRM or FREE and e2e.soap.baseUrl for this"
                    + " development {group} or {node} and restart the MCP client"));
        }
        if (capability.needsCli()) {
            cli.check(id);
        }
        return policy;
    }

    private static ToolErrorException refused(ErrorCode code, NodeId node, String message,
        String likelyCause, String nextStep) {
        return new ToolErrorException(ToolError.of(code, message, likelyCause, nextStep)
            .withNode(node));
    }
}
