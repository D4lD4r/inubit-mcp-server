package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.DeployMode;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.StageChain;
import de.dadecker.inubit.mcp.domain.model.Target;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first gate of {@code deploy_release} (feature 005, US2, FR-007, FR-008, research D-3):
 * everything that can be decided without contacting INUBIT, then the version check, whose only
 * requests are the version detections (and StartCLI client probes) of the gate.
 *
 * <ul>
 *   <li>The target is the id of one group ({@code INVALID_INPUT} for a node id or a malformed
 *       id, {@code TARGET_UNKNOWN} for an unknown group) that receives deployments; a group
 *       without {@code deploy} is {@code CHAIN_VIOLATION}, naming the groups that do receive
 *       deployments and their sources. The source is always the target's {@code deploy.from}
 *       and never an input.
 *   <li>The tag is not blank, has no wildcard ({@code *}, {@code ?}) and matches the StartCLI
 *       value rule; an owner, if given, matches it too; without one the target's
 *       {@code inventory.owner} applies ({@code NOT_CONFIGURED} if none). A confirmation code
 *       has the shape the server issues (22 URL-safe characters).
 *   <li>Feature 009 (FR-014a, research D-11): every source and target node is judged by the
 *       {@link VersionGate} as a deployment write over StartCLI and REST (detection, matrix,
 *       pin contradiction, client check); the deployment is refused as a whole
 *       ({@code UNSUPPORTED_VERSION}, one message naming every node with its version and the
 *       cause of each refused node) if the gate refuses a node, a node's version is unknown, or
 *       the nodes differ in major.minor. The admission warnings travel in {@link Admitted}.
 *   <li>Every refusal is audited ({@code REFUSED}, capability {@code deploy_release}, step
 *       {@code PREVIEW} or, with a code, {@code EXECUTE}) before it is thrown; nothing is
 *       launched.
 * </ul>
 */
public final class DeployGuard {

    /** The tool this guard admits. */
    public static final String CAPABILITY = "deploy_release";
    /** Same rule as {@code CliCommand.VALUE} (research R-11). */
    static final Pattern VALUE = Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{22}$");
    private static final int MAX_AUDITED_INPUT = 200;
    private static final Logger LOG = LoggerFactory.getLogger(DeployGuard.class);
    /** Sources export, targets import and tag through StartCLI; both read through REST. */
    private static final Set<Channel> CHANNELS = Set.of(Channel.CLI, Channel.REST);

    /**
     * A {@code deploy_release} call as received.
     *
     * @param target the group id to deploy into
     * @param owner  the owner of the release, default the target's {@code inventory.owner}
     */
    public record Request(String target, String tag, Optional<String> owner,
        Optional<String> confirmationCode, Optional<String> mcpClient) {
        public Request {
            owner = owner == null ? Optional.empty() : owner;
            confirmationCode = confirmationCode == null ? Optional.empty() : confirmationCode;
            mcpClient = mcpClient == null ? Optional.empty() : mcpClient;
        }
    }

    /**
     * An admitted call: the target, its link of the chain, the nodes of both groups.
     *
     * @param warnings the version gate's admission warnings of the nodes (feature 009)
     */
    public record Admitted(GroupId target, GroupId source, DeployMode mode,
        List<StageChain.Exclusion> exclude, List<NodeId> targetNodes, List<NodeId> sourceNodes,
        String owner, String tag, List<String> warnings) {
        public Admitted {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(mode, "mode");
            exclude = List.copyOf(exclude);
            targetNodes = List.copyOf(targetNodes);
            sourceNodes = List.copyOf(sourceNodes);
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(tag, "tag");
            warnings = List.copyOf(warnings);
        }
    }

    private final StageChain chain;
    private final TargetResolver nodes;
    private final Function<GroupId, Optional<String>> owners;
    private final AuditPort audit;
    private final VersionGate gate;
    private final String profile;
    private final Clock clock;
    private final Supplier<UUID> ids;

    /**
     * @param owners the {@code inventory.owner} of a group (its first node)
     * @param profile the profile every audit record carries
     */
    public DeployGuard(StageChain chain, TargetResolver nodes,
        Function<GroupId, Optional<String>> owners, AuditPort audit, VersionGate gate,
        String profile, Clock clock, Supplier<UUID> ids) {
        this.chain = Objects.requireNonNull(chain, "chain");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /**
     * Admits {@code request} or refuses it (audited).
     *
     * @throws ToolErrorException {@code INVALID_INPUT}, {@code TARGET_UNKNOWN},
     *     {@code CHAIN_VIOLATION}, {@code NOT_CONFIGURED}, {@code UNSUPPORTED_VERSION} and the
     *     errors the version gate passes through (e.g. {@code TLS_ERROR} of a detection);
     *     {@code INTERNAL} if the refusal could not be audited
     */
    public Admitted admit(Request request) {
        try {
            return check(request);
        } catch (ToolErrorException e) {
            throw refuse(request, e.error());
        }
    }

    private Admitted check(Request request) {
        GroupId target = target(request.target());
        String tag = request.tag();
        if (tag == null || tag.isBlank()) {
            throw invalid("The tag must not be empty", "A release is selected by one tag");
        }
        if (tag.contains("*") || tag.contains("?")) {
            throw invalid("The tag " + Names.quote(tag) + " contains a wildcard",
                "A release is selected by one exact tag name");
        }
        if (!VALUE.matcher(tag).matches()) {
            throw invalid("The tag " + Names.quote(tag) + " cannot be passed to StartCLI",
                "It must match " + VALUE.pattern());
        }
        request.confirmationCode().ifPresent(code -> {
            if (!CODE.matcher(code).matches()) {
                throw invalid("The confirmationCode is not a code this server issues",
                    "A code has 22 URL-safe characters");
            }
        });
        StageChain.ChainLink link = chain.link(target).orElseThrow(() -> new ToolErrorException(
            ToolError.of(ErrorCode.CHAIN_VIOLATION, target + " receives no deployments: it has"
                    + " no deploy record in the configuration",
                "Only groups with deploy.from receive releases, and only from that source",
                "Deploy into a group of the chain: " + chainTargets())));
        String owner = request.owner().orElseGet(() -> owners.apply(target).orElseThrow(() ->
            new ToolErrorException(ToolError.of(ErrorCode.NOT_CONFIGURED,
                "No owner is given and " + target + " has no inventory.owner",
                "The owner of the release defaults to the target's inventory.owner",
                "Pass owner, or set inventory.owner for " + target + " or in defaults"))));
        if (!VALUE.matcher(owner).matches()) {
            throw invalid("The owner " + Names.quote(owner) + " cannot be passed to StartCLI",
                "It must match " + VALUE.pattern());
        }
        List<NodeId> targetNodes = nodes.resolve(target.value());
        List<NodeId> sourceNodes = nodes.resolve(link.source().value());
        return new Admitted(target, link.source(), link.mode(), link.exclude(), targetNodes,
            sourceNodes, owner, tag, versions(link.source(), sourceNodes, target, targetNodes));
    }

    /**
     * FR-014a: judges every source and target node, then refuses the whole deployment if the
     * gate refused a node, a node's version is unknown ({@code V9_X} included) or the nodes
     * differ in major.minor.
     *
     * @return the admission warnings of every node
     */
    private List<String> versions(GroupId source, List<NodeId> sourceNodes, GroupId target,
        List<NodeId> targetNodes) {
        Map<NodeId, VersionGate.Evaluation> evaluations = new LinkedHashMap<>();
        for (List<NodeId> group : List.of(sourceNodes, targetNodes)) {
            group.forEach(node -> evaluations.put(node, gate.evaluate(node, Capability.DEPLOYMENT,
                Capability.Kind.WRITE, CHANNELS)));
        }
        List<String> refused = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        Set<String> minors = new LinkedHashSet<>();
        Set<String> nextSteps = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        evaluations.forEach((node, evaluation) -> {
            evaluation.refusal().ifPresentOrElse(error -> {
                refused.add(node.value());
                nextSteps.add(error.nextStep());
            }, () -> {
                if (evaluation.version().isEmpty()) {
                    unknown.add(node.value());
                }
            });
            evaluation.version().ifPresent(version -> minors.add(version.major() + "."
                + version.minor()));
            warnings.addAll(evaluation.warnings());
        });
        if (refused.isEmpty() && unknown.isEmpty() && minors.size() <= 1) {
            return warnings;
        }
        List<String> problems = new ArrayList<>();
        if (!refused.isEmpty()) {
            problems.add("the version check refused " + String.join(", ", refused));
        }
        if (!unknown.isEmpty()) {
            problems.add("the INUBIT version of " + String.join(", ", unknown) + " is unknown");
        }
        if (minors.size() > 1) {
            problems.add("the nodes run different INUBIT minor versions ("
                + String.join(", ", minors) + ")");
        }
        if (!unknown.isEmpty() || minors.size() > 1) {
            nextSteps.add("Deploy only between nodes of the same INUBIT minor version (e.g."
                + " 8.1.x to 8.1.y) whose versions are known");
        }
        throw new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION, CAPABILITY
                + ": deployment from " + source + " into " + target + " refused ("
                + String.join("; ", problems) + "). Sources: " + described(sourceNodes,
                    evaluations) + ". Targets: " + described(targetNodes, evaluations)
                + "; nothing was sent",
            "deploy_release deploys only between nodes whose INUBIT versions are known,"
                + " supported for deployments and of the same minor version",
            String.join("; ", nextSteps)));
    }

    /** {@code int/node1 INUBIT 9.0.7 (refused: <cause>), int/node2 INUBIT 8.1.17}. */
    private static String described(List<NodeId> group,
        Map<NodeId, VersionGate.Evaluation> evaluations) {
        return String.join(", ", group.stream().map(node -> {
            VersionGate.Evaluation evaluation = evaluations.get(node);
            return node + " " + evaluation.versionName() + evaluation.cause()
                .map(cause -> " (refused: " + cause + ")").orElse("");
        }).toList());
    }

    private GroupId target(String input) {
        Target parsed;
        try {
            parsed = Target.parse(input);
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid target " + Names.quote(input) + ": expected the id of one"
                + " group (e.g. 'int')", "deploy_release deploys into a whole group");
        }
        return switch (parsed) {
            case Target.Node node -> throw invalid(Names.quote(input) + " is a node id;"
                + " deploy_release takes the id of one group (" + node.id().group() + ")",
                "A group is deployed as a whole, its nodes one after another");
            case Target.Group group -> {
                nodes.resolve(group.id().value()); // TARGET_UNKNOWN
                yield group.id();
            }
        };
    }

    /** {@code int (from dev), prod (from int, package only)}. */
    private String chainTargets() {
        List<String> targets = new ArrayList<>();
        chain.targets().forEach((target, link) -> targets.add(target + " (from " + link.source()
            + (link.mode() == DeployMode.PACKAGE_ONLY ? ", package only" : "") + ")"));
        return String.join(", ", targets);
    }

    private ToolErrorException refuse(Request request, ToolError error) {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("tag", bounded(String.valueOf(request.tag())));
        request.owner().ifPresent(owner -> inputs.put("owner", bounded(owner)));
        request.confirmationCode().ifPresent(code -> inputs.put(AuditRecord.CONFIRMATION_CODE,
            code));
        String target = String.valueOf(request.target());
        Optional<String> group;
        try {
            group = Optional.of(switch (Target.parse(target)) {
                case Target.Group g -> g.id().value();
                case Target.Node n -> n.id().group().value();
            });
        } catch (IllegalArgumentException e) {
            group = Optional.empty();
        }
        try {
            audit.append(new AuditRecord(ids.get(), clock.instant(), profile, bounded(target),
                group, CAPABILITY, request.confirmationCode().isPresent()
                    ? AuditRecord.Step.EXECUTE : AuditRecord.Step.PREVIEW, inputs,
                Optional.empty(), AuditOutcome.REFUSED, Optional.of(bounded(error.code() + ": "
                    + error.message())), request.mcpClient()));
        } catch (RuntimeException e) {
            LOG.error("Audit record of a refusal ({}) could not be written ({})", error.code(),
                e.getClass().getSimpleName());
            return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The audit record could not be written; the call was refused anyway ("
                    + error.code() + ": " + error.message() + ") and nothing was sent",
                "The audit directory is not writable, full, or not owned by this user",
                "Fix the audit directory (auditDirectory in the configuration), then retry"));
        }
        LOG.info("{} into {}: REFUSED ({})", CAPABILITY, bounded(target), error.code());
        return new ToolErrorException(error);
    }

    private static ToolErrorException invalid(String message, String likelyCause) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message
            + "; nothing was sent", likelyCause, "Correct the input and call deploy_release"
                + " again"));
    }

    private static String bounded(String value) {
        return value.length() <= MAX_AUDITED_INPUT ? value
            : value.substring(0, MAX_AUDITED_INPUT);
    }
}
