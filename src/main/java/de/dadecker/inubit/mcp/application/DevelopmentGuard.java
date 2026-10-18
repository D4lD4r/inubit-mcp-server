package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.DeployMode;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The configuration checks of the development tools of feature 004, before anything is read from
 * INUBIT (research D-1, FR-001, FR-004, FR-029, Constitution I). In this order:
 *
 * <ol>
 *   <li>The input must be one configured node: a group id is {@code INVALID_INPUT}, an unknown
 *       id {@code TARGET_UNKNOWN}.
 *   <li>A node that is not a development stage ({@code development.enabled} false, the default,
 *       or no policy at all: fail closed) is {@code NOT_DEVELOPMENT} — except for
 *       {@code run_e2e_test} on a non-production node of a group that receives deployments
 *       (feature 005, T027), which its {@code e2eTests} governs.
 *   <li>A development node of a production group is {@code PRODUCTION_PROTECTED} (defence in
 *       depth: the configuration validation rejects it at startup).
 *   <li>{@code run_e2e_test} needs {@code e2eTests} other than {@code FORBIDDEN}
 *       ({@code E2E_FORBIDDEN}); it does not need StartCLI.
 *   <li>Feature 009 (research D-5): the {@link VersionGate} admits the operation's capability
 *       for writes on the node's INUBIT version and StartCLI client
 *       ({@code UNSUPPORTED_VERSION}); its warnings travel in
 *       {@link DevelopmentPolicy#warnings()}.
 *   <li>The other tools need a usable StartCLI installation and credentials, checked without
 *       launching anything ({@code CLI_UNAVAILABLE}, {@code AUTH_FAILED}).
 * </ol>
 *
 * <p>The existing {@link WriteGuard} of restart and kill stays unchanged: its settings and texts
 * belong to those tools.
 */
public final class DevelopmentGuard {

    /** The tools of feature 004; the name is the tool name and the audit capability. */
    public enum Operation {
        IMPORT_ARTIFACTS("import_artifacts"),
        RESTORE_BACKUP("restore_backup"),
        SET_ACTIVE("set_active"),
        TAG_ARTIFACTS("tag_artifacts"),
        RUN_E2E_TEST("run_e2e_test");

        private final String toolName;

        Operation(String toolName) {
            this.toolName = toolName;
        }

        public String toolName() {
            return toolName;
        }

        /** The capability of the spec's tool classification (feature 009). */
        Capability capability() {
            return this == RUN_E2E_TEST ? Capability.E2E_TEST : Capability.DEVELOPMENT_WRITE;
        }

        /** The SOAP test sends SOAP and reads REST; every other tool runs StartCLI and REST. */
        Set<Channel> channels() {
            return this == RUN_E2E_TEST ? Set.of(Channel.SOAP, Channel.REST)
                : Set.of(Channel.CLI, Channel.REST);
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
    private final VersionGate gate;
    private final Function<GroupId, Optional<DeployMode>> deployModes;
    private final Duration deployTtl;

    /** A guard without a stage chain (feature 004). */
    public DevelopmentGuard(TargetResolver targets, Function<NodeId, DevelopmentPolicy> policies,
        CliCheck cli, VersionGate gate) {
        this(targets, policies, cli, gate, group -> Optional.empty(), Duration.ofMinutes(30));
    }

    /**
     * @param policies    the development settings of each configured node ({@code null}: not a
     *                    development node)
     * @param cli         the StartCLI availability check of a node
     * @param gate        feature 009: the version gate
     * @param deployModes feature 005: the {@code deploy.mode} of a group that receives
     *                    deployments (empty: it receives none)
     * @param deployTtl   {@code defaults.deployConfirmationTtl}, the validity of the code of a
     *                    deployment restore
     */
    public DevelopmentGuard(TargetResolver targets, Function<NodeId, DevelopmentPolicy> policies,
        CliCheck cli, VersionGate gate, Function<GroupId, Optional<DeployMode>> deployModes,
        Duration deployTtl) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.cli = Objects.requireNonNull(cli, "cli");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.deployModes = Objects.requireNonNull(deployModes, "deployModes");
        this.deployTtl = Objects.requireNonNull(deployTtl, "deployTtl");
    }

    /**
     * Feature 005 (T026): admits {@code restore_backup} of a deployment backup on a node of a
     * group that receives deployments in mode {@code EXECUTE} — whether or not the node is a
     * development stage, always with a preview and a server code valid
     * {@code deployConfirmationTtl}. The caller has found a {@code DEPLOYMENT} backup of that
     * node; a package-only group never has one and is refused.
     *
     * @throws ToolErrorException {@code INVALID_INPUT}, {@code TARGET_UNKNOWN},
     *     {@code NOT_DEVELOPMENT}, {@code CLI_UNAVAILABLE} or {@code AUTH_FAILED}
     */
    public DevelopmentPolicy admitDeploymentRestore(String node) {
        NodeId id = targets.resolveSingleServer(node);
        Terminology terms = targets.terms();
        if (deployModes.apply(id.group()).filter(DeployMode.EXECUTE::equals).isEmpty()) {
            throw refused(ErrorCode.NOT_DEVELOPMENT, id,
                id + terms.render(" neither is a development {node} nor receives deployments;"
                    + " restore_backup is refused there and nothing was sent"),
                terms.render("Its {group} has no deploy setting with mode EXECUTE"),
                terms.render("Restore deployment backups on the {node} they were taken on"));
        }
        cli.check(id);
        DevelopmentPolicy policy = policies.apply(id);
        return new DevelopmentPolicy(id, policy != null && policy.production(), false,
            WritePolicy.Confirmation.SERVER, deployTtl, E2ePolicy.FORBIDDEN, Optional.empty(),
            false);
    }

    /**
     * {@code policy} as the restore of a deployment backup needs it (final review n1): always
     * a server code, valid {@code deployConfirmationTtl}, also on a development node.
     */
    public DevelopmentPolicy forDeploymentRestore(DevelopmentPolicy policy) {
        return new DevelopmentPolicy(policy.node(), policy.production(), policy.enabled(),
            WritePolicy.Confirmation.SERVER, deployTtl, policy.e2eTests(), policy.soapBaseUrl(),
            policy.repositoryWrite(), policy.warnings());
    }

    /**
     * Feature 008 (research D-6): {@code policy} as a write of repository files needs it —
     * always a server code; everything else, the code's validity included, stays as configured.
     */
    public DevelopmentPolicy forRepositoryWrite(DevelopmentPolicy policy) {
        return new DevelopmentPolicy(policy.node(), policy.production(), policy.enabled(),
            WritePolicy.Confirmation.SERVER, policy.confirmationTtl(), policy.e2eTests(),
            policy.soapBaseUrl(), policy.repositoryWrite(), policy.warnings());
    }

    /**
     * The policy of the node a development tool may run on, with the version gate's warnings.
     *
     * @throws ToolErrorException {@code INVALID_INPUT}, {@code TARGET_UNKNOWN},
     *     {@code NOT_DEVELOPMENT}, {@code PRODUCTION_PROTECTED}, {@code E2E_FORBIDDEN},
     *     {@code UNSUPPORTED_VERSION}, {@code CLI_UNAVAILABLE} or {@code AUTH_FAILED}
     */
    public DevelopmentPolicy admit(String node, Operation operation) {
        Objects.requireNonNull(operation, "operation");
        NodeId id = targets.resolveSingleServer(node);
        Terminology terms = targets.terms();
        DevelopmentPolicy policy = policies.apply(id);
        if (policy != null && policy.production() && !policy.enabled()) {
            throw refused(ErrorCode.NOT_DEVELOPMENT, id,
                id + terms.render(" belongs to the production {group} ") + id.group() + "; "
                    + operation.toolName() + " is refused there and nothing was sent",
                terms.render("The {group} is classified production: true, and production {groups}"
                    + " are never development stages"),
                terms.render("Use a development {node} (list_nodes) and deploy to production"
                    + " through the stage chain"));
        }
        // feature 005 (T027): end-to-end tests also on the nodes of a group that receives
        // deployments, as its e2eTests allows; nothing else of feature 004
        boolean target = operation == Operation.RUN_E2E_TEST && policy != null
            && deployModes.apply(id.group()).isPresent();
        if ((policy == null || !policy.enabled()) && !target) {
            throw refused(ErrorCode.NOT_DEVELOPMENT, id,
                id + terms.render(" is not a development {node}; ") + operation.toolName()
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
                    + operation.toolName() + " is refused there",
                terms.render("The {group} is classified production: true; development settings"
                    + " are not allowed on production (the configuration check rejects them)"),
                terms.render("Remove development.enabled and e2eTests from the production"
                    + " {group} and restart the MCP client"));
        }
        if (operation == Operation.RUN_E2E_TEST && policy.e2eTests() == E2ePolicy.FORBIDDEN) {
            throw refused(ErrorCode.E2E_FORBIDDEN, id,
                terms.render("End-to-end tests are not allowed on ") + id
                    + "; nothing was sent",
                terms.render("e2eTests is FORBIDDEN for this {node} (the default)"),
                terms.render("Set e2eTests: CONFIRM or FREE and e2e.soap.baseUrl for this"
                    + " development {group} or {node} and restart the MCP client"));
        }
        VersionGate.Admission admission = gate.admit(operation.toolName(), id,
            operation.capability(), Capability.Kind.WRITE, operation.channels());
        if (operation.channels().contains(Channel.CLI)) {
            cli.check(id);
        }
        return new DevelopmentPolicy(policy.node(), policy.production(), policy.enabled(),
            policy.confirmation(), policy.confirmationTtl(), policy.e2eTests(),
            policy.soapBaseUrl(), policy.repositoryWrite(), admission.warnings());
    }

    private static ToolErrorException refused(ErrorCode code, NodeId node, String message,
        String likelyCause, String nextStep) {
        return new ToolErrorException(ToolError.of(code, message, likelyCause, nextStep)
            .withNode(node));
    }
}
