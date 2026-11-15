package de.dadecker.inubit.mcp.application.nodetools;

import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigResult;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValue;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValues;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The use case behind {@code get_node_config} (feature 024, FR-030 … FR-032, SC-003, research
 * D-10, D-22; decision flow and texts per contracts/mcp-tools-delta.md). One call, nothing sent
 * before step 4:
 * <ol>
 *   <li>Every name is the {@link NodeConfigName#wireName()} of a listed name, exactly
 *       (case-sensitive), and none is given twice; else {@code INVALID_INPUT} listing the allowed
 *       names, a refused text never echoed. No name means all of them in list order.</li>
 *   <li>{@link TargetResolver#resolveSingleServer}: exactly one server.</li>
 *   <li>The group's opt-in {@code unsupported.nodeConfigRead}; without it {@code NOT_CONFIGURED}
 *       (no detection, no helper).</li>
 *   <li>{@link VersionGate#admitWithDetection} for {@code NODE_CONFIG_READ} over StartCLI's
 *       client: {@code UNSUPPORTED_VERSION} or the detection's error.</li>
 *   <li>The call deadline, now + the server's {@code cliTimeout}; within it the server's login
 *       credentials ({@link GatewayFactory#prepareCredentials}); still being read →
 *       {@code TIMEOUT} naming {@code cliTimeout}.</li>
 *   <li>One {@link de.dadecker.inubit.mcp.domain.port.NodeConfigPort#values} run with the names
 *       and the deadline; its errors pass through (one without a node names the server).</li>
 *   <li>The result and one INFO line with the server, the name count and the outcome; never a
 *       name or a value.</li>
 * </ol>
 * The port's values are the result as they are: the adapter already masks every value with
 * {@link NodeTextMasking} and cuts it to {@value NodeConfigValue#MAX_VALUE_CHARS} characters,
 * which {@link NodeConfigValue.Present} enforces. Masking again here would only risk a second
 * cut; the {@code ResultMapper} scrubs every string of the result once more.
 *
 * <p>Thread-safe; it keeps no state of its own.
 */
public final class NodeConfigService {

    /** The tool name, the start of the gate's refusals and of the INFO line. */
    static final String TOOL = "get_node_config";

    private static final Logger LOG = LoggerFactory.getLogger(NodeConfigService.class);
    private static final String UNKNOWN = "-";
    private static final String ALLOWED = Arrays.stream(NodeConfigName.values())
        .map(NodeConfigName::wireName).collect(Collectors.joining(", "));

    private final TargetResolver targets;
    private final Predicate<NodeId> optedIn;
    private final VersionGate gate;
    private final GatewayFactory gateways;
    private final Function<NodeId, Duration> cliTimeouts;
    private final LongSupplier nanoTime;

    /**
     * @param optedIn     whether the node's group sets {@code unsupported.nodeConfigRead: true}
     * @param gateways    the server's credentials and its node-configuration port
     * @param cliTimeouts the {@code cliTimeout} of a node, the call's budget
     * @param nanoTime    the clock of the call deadline ({@link System#nanoTime()})
     */
    public NodeConfigService(TargetResolver targets, Predicate<NodeId> optedIn, VersionGate gate,
        GatewayFactory gateways, Function<NodeId, Duration> cliTimeouts, LongSupplier nanoTime) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.optedIn = Objects.requireNonNull(optedIn, "optedIn");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.cliTimeouts = Objects.requireNonNull(cliTimeouts, "cliTimeouts");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Reads the configuration values as the class description says; logs one INFO line either
     * way.
     *
     * @throws ToolErrorException as described in the class description
     */
    public NodeConfigResult read(NodeConfigRequest request) {
        Objects.requireNonNull(request, "request");
        NodeId node = null;
        List<NodeConfigName> names = null;
        String outcome = ErrorCode.INTERNAL.name();
        try {
            names = names(request.names());
            node = targets.resolveSingleServer(request.target());
            if (!optedIn.test(node)) {
                throw notConfigured(node);
            }
            VersionGate.Admission admission = gate.admitWithDetection(TOOL, node,
                Capability.NODE_CONFIG_READ, Capability.Kind.READ, Set.of(Channel.CLI));
            NodeConfigValues values = read(node, admission, names);
            List<String> warnings = new ArrayList<>();
            warnings.add(NodeConfigResult.WARNING);
            warnings.addAll(admission.warnings());
            NodeConfigResult result = new NodeConfigResult(node, values.answeringNode(),
                values.values(), warnings);
            outcome = "OK";
            return result;
        } catch (ToolErrorException e) {
            if (node == null) {
                node = e.error().node().orElse(null);
            }
            outcome = e.error().code().name();
            throw e;
        } finally {
            LOG.info("{} on {}: {} name(s), outcome {}", TOOL,
                node == null ? UNKNOWN : node.toString(),
                names == null ? UNKNOWN : String.valueOf(names.size()), outcome);
        }
    }

    /** Steps 5 and 6: the call deadline, the server's login credentials, the port. */
    private NodeConfigValues read(NodeId node, VersionGate.Admission admission,
        List<NodeConfigName> names) {
        // the gate admits NODE_CONFIG_READ only on a known major.minor
        InubitVersion version = admission.version().orElseThrow(() -> new IllegalStateException(
            TOOL + " on " + node + " was admitted without a version"));
        Duration budget = cliTimeouts.apply(node);
        long deadlineNanos = nanoTime.getAsLong() + budget.toNanos();
        try {
            if (gateways.prepareCredentials(node, remaining(deadlineNanos))
                == CredentialState.PENDING) {
                throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT, TOOL + " on " + node
                        + " did not finish within cliTimeout (" + Durations.human(budget)
                        + "): the login credentials of " + node + " were not read in time",
                    "The first read of the login credentials from their store took longer than"
                        + " cliTimeout; it continues, so a later call finds them",
                    "Retry in a moment; raise cliTimeout of " + node + " if the credential store"
                        + " is just slow").withNode(node));
            }
            return gateways.nodeConfig(node).values(names, version, deadlineNanos);
        } catch (ToolErrorException e) {
            // an adapter error without a node (e.g. the PortSet's CLI_UNAVAILABLE) names the server
            throw e.error().node().isPresent() ? e
                : new ToolErrorException(e.error().withNode(node));
        }
    }

    /** What is left of the call deadline, never negative. */
    private Duration remaining(long deadlineNanos) {
        long left = deadlineNanos - nanoTime.getAsLong();
        return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
    }

    /** Step 1 (FR-030, SC-003): the listed names asked for, or all of them; nothing echoed. */
    private static List<NodeConfigName> names(List<String> given) {
        if (given.isEmpty()) {
            return List.of(NodeConfigName.values());
        }
        List<NodeConfigName> names = new ArrayList<>();
        for (int i = 0; i < given.size(); i++) {
            int index = i;
            NodeConfigName name = NodeConfigName.parse(given.get(i)).orElseThrow(() ->
                new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, "Configuration value"
                        + " names[" + index + "] is not readable; allowed: " + ALLOWED,
                    "get_node_config reads only a fixed list of configuration values that hold"
                        + " no secrets; the names are case-sensitive",
                    "Use names from the allowed list exactly as written, or leave names out for"
                        + " all of them")));
            if (names.contains(name)) {
                throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                    "Configuration value " + name.wireName() + " is named more than once in names",
                    "Each configuration value can be asked for once per call",
                    "Name each configuration value once"));
            }
            names.add(name);
        }
        return names;
    }

    private ToolErrorException notConfigured(NodeId node) {
        String group = targets.terms().render("{group}") + " " + node.group();
        return new ToolErrorException(ToolError.of(ErrorCode.NOT_CONFIGURED, TOOL
                + " is not enabled for " + group + ": unsupported.nodeConfigRead is not set",
            "Reading INUBIT configuration values uses an unsupported, undocumented INUBIT"
                + " interface and is off unless the " + targets.terms().render("{group}")
                + " opts in",
            "Set unsupported.nodeConfigRead: true on " + group + " and restart the MCP client"
                + " (unsupported INUBIT functionality, see docs/setup.md \"Unsupported"
                + " functions\")").withNode(node));
    }
}
