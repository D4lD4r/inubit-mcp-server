package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeAccess;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeSummary;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState.Timed;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The {@code list_nodes} view of the nodes (FR-004, research D-9): the configuration summaries of
 * startup with a version block from {@link NodeVersions} as of the call. It only reads what the
 * version gate, {@code get_health} and the client probe recorded; it never contacts a node, runs
 * StartCLI, detects or probes.
 *
 * <p>A capability is usable if its tools run on the node as the {@link VersionGate} would admit
 * them now: {@code SUPPORTED}, or {@code UNVERIFIED} for reads, no pin contradiction for writes,
 * and not refused by the known StartCLI client (FR-011). A read capability whose StartCLI part
 * the client refuses ({@code inventory}: modules and history) stays usable for its REST part
 * and is additionally listed as {@code "<name> (StartCLI parts)"} in {@code notUsable}; a write
 * is admitted for all its channels at once, so it is not usable at all.
 *
 * <p>Feature 011 (research D-13): the block names the node's access, {@code cli_only} for a node
 * pinned to a CLI-only version (6.1), whose capabilities are judged on its matrix entry and run
 * only if {@code SUPPORTED}.
 */
public final class NodeOverview {

    private final List<NodeSummary> nodes;
    private final NodeVersions versions;
    private final CapabilityMatrix matrix;
    private final Function<NodeId, Set<Capability>> allowed;

    /**
     * @param nodes   the configuration summaries, in config order; a node with
     *                {@link NodeSummary#cliAvailable()} has a StartCLI client
     * @param allowed the capabilities each node's configuration allows (write, development and
     *                deployment settings, StartCLI installation; FR-003)
     */
    public NodeOverview(List<NodeSummary> nodes, NodeVersions versions, CapabilityMatrix matrix,
        Function<NodeId, Set<Capability>> allowed) {
        this.nodes = List.copyOf(nodes);
        this.versions = Objects.requireNonNull(versions, "versions");
        this.matrix = Objects.requireNonNull(matrix, "matrix");
        this.allowed = Objects.requireNonNull(allowed, "allowed");
    }

    /** The nodes with their version block as of now. */
    public List<NodeSummary> nodes() {
        return nodes.stream().map(node -> node.withVersion(version(node))).toList();
    }

    private NodeSummary.Version version(NodeSummary node) {
        NodeId id = node.id();
        NodeVersionState state = versions.state(id);
        Optional<InubitVersion> version = state.effectiveVersion();
        // feature 011 (FR-002): a node pinned to a CLI-only version is judged on its pin, as the
        // gate does, also while an observation contradicts the pin
        Optional<InubitVersion> pin = NodeVersionState.initial(state.setting()).effectiveVersion();
        boolean cliOnly = matrix.isCliOnly(pin);
        Optional<InubitVersion> judged = cliOnly ? pin : version;
        Set<Capability> configured = allowed.apply(id);
        List<String> usable = new ArrayList<>();
        List<NodeSummary.NotUsable> notUsable = new ArrayList<>();
        boolean allSupported = true;
        for (Capability capability : Capability.values()) {
            if (!configured.contains(capability)) {
                continue;
            }
            SupportState support = support(state, judged, capability);
            allSupported &= support == SupportState.SUPPORTED;
            Optional<String> refusal = refusal(id, state, judged, capability, support, cliOnly);
            if (refusal.isPresent()) {
                notUsable.add(new NodeSummary.NotUsable(name(capability), refusal.get()));
                continue;
            }
            // the entry's channels: on 6.1 monitoring and process diagnosis run over StartCLI
            Optional<String> clientRefusal =
                matrix.channels(judged, capability).contains(Channel.CLI)
                    ? clientRefusal(id, state, judged) : Optional.empty();
            if (clientRefusal.isEmpty()) {
                usable.add(name(capability));
            } else if (capability.kind() == Capability.Kind.READ
                && capability.channels().size() > 1) {
                // a read is admitted per part: its REST part still runs
                usable.add(name(capability));
                notUsable.add(new NodeSummary.NotUsable(name(capability) + " (StartCLI parts)",
                    clientRefusal.get()));
            } else {
                notUsable.add(new NodeSummary.NotUsable(name(capability), clientRefusal.get()));
            }
        }
        boolean unknown = version.isEmpty() && state.setting() instanceof VersionSetting.Auto;
        String status = unknown ? "unknown"
            : usable.isEmpty() ? "unsupported"
            : allSupported && notUsable.isEmpty() ? "supported"
            : "partially_supported";
        List<String> warnings = new ArrayList<>();
        if (state.pinContradiction()) {
            warnings.add(contradiction(id, state) + "; writes on " + id + " are refused until"
                + " get_health reads a matching version or versionLine is corrected and the"
                + " server restarted");
        }
        // FR-011, US1-4: what the client means for the StartCLI-backed tools, as in get_health
        state.client().map(Timed::value).flatMap(client -> HealthService.clientWarning(id,
            client, version, client.compare(version))).ifPresent(warnings::add);
        NodeAccess access = cliOnly ? NodeAccess.CLI_ONLY : NodeAccess.REST_AND_CLI;
        return new NodeSummary.Version(state.setting().text(),
            access.name().toLowerCase(Locale.ROOT), server(state, version),
            client(node, state, version), status,
            new NodeSummary.Capabilities(usable, notUsable), Optional.of(warnings));
    }

    /** As {@link VersionGate}: a line without version (e.g. {@code V9_X}) is looked up by line. */
    private SupportState support(NodeVersionState state, Optional<InubitVersion> version,
        Capability capability) {
        if (version.isEmpty() && state.setting() instanceof VersionSetting.Line line) {
            return matrix.stateOfLine(line.major(), line.minor(), capability);
        }
        return matrix.state(version, capability);
    }

    /**
     * Why the gate refuses {@code capability} for its version alone; empty if it admits it. On a
     * CLI-only pin a contradiction leaves only monitoring ({@code get_health}), and a refusal
     * names the configured line without "yet", as the gate's message before its next step.
     */
    private static Optional<String> refusal(NodeId node, NodeVersionState state,
        Optional<InubitVersion> version, Capability capability, SupportState support,
        boolean cliOnly) {
        if (state.pinContradiction() && (capability.kind() == Capability.Kind.WRITE
            || cliOnly && capability != Capability.MONITORING)) {
            return Optional.of(contradiction(node, state));
        }
        if (support == SupportState.SUPPORTED
            || support == SupportState.UNVERIFIED && capability.kind() == Capability.Kind.READ
            && !cliOnly) {
            return Optional.empty();
        }
        return Optional.of(VersionGate.name(state, version)
            .map(name -> cliOnly
                ? name + (state.setting() instanceof VersionSetting.Line line
                    ? " (versionLine " + line.text() + ")" : "") + " on " + node
                    + " does not support " + VersionGate.label(capability)
                : name + " on " + node + " does not support "
                    + VersionGate.label(capability) + " yet")
            .orElseGet(() -> VersionGate.unknown(node, state)
                + "; call get_health to detect it"));
    }

    /** Why the known StartCLI client refuses StartCLI calls; empty if it does not (FR-011). */
    private static Optional<String> clientRefusal(NodeId node, NodeVersionState state,
        Optional<InubitVersion> version) {
        if (state.client().isEmpty()) {
            return Optional.empty();
        }
        ClientVersion client = state.client().get().value();
        ClientVersion.Compatibility compatibility =
            version.isEmpty() && state.setting() instanceof VersionSetting.Line line
                ? client.compatibleWith(line.major(), line.minor())
                : client.compatibleWith(version);
        if (compatibility != ClientVersion.Compatibility.INCOMPATIBLE) {
            return Optional.empty();
        }
        return Optional.of("StartCLI client " + client.version().raw() + " of " + node
            + " supports INUBIT " + client.supportedFrom().orElseThrow().raw() + " to "
            + client.supportedTo().orElseThrow().raw() + ", which excludes "
            + VersionGate.name(state, version).orElseThrow());
    }

    private static NodeSummary.Server server(NodeVersionState state,
        Optional<InubitVersion> version) {
        if (version.isPresent()) {
            if (state.setting() instanceof VersionSetting.Auto) {
                Timed<InubitVersion> latest = state.latest().orElseThrow();
                boolean observed = state.observed().equals(Optional.of(latest));
                return NodeSummary.Server.known(version.get().raw(),
                    observed ? "observed" : "detected", Optional.of(at(latest)));
            }
            return state.observed().map(observed -> NodeSummary.Server.known(
                    observed.value().raw(), "observed", Optional.of(at(observed))))
                .orElseGet(() -> NodeSummary.Server.known(version.get().raw(), "pinned",
                    Optional.empty()));
        }
        if (state.setting() instanceof VersionSetting.Line line) {
            return NodeSummary.Server.known(line.major() + ".x", "pinned", Optional.empty());
        }
        return state.detectionFailure()
            .map(failure -> NodeSummary.Server.unknown(failure.value().code().name(),
                at(failure)))
            .orElse(NodeSummary.Server.NOT_YET_DETECTED);
    }

    private static NodeSummary.Client client(NodeSummary node, NodeVersionState state,
        Optional<InubitVersion> version) {
        if (!node.cliAvailable()) {
            return NodeSummary.Client.state("not_available", Optional.empty(), Optional.empty());
        }
        if (state.client().isPresent()) {
            ClientVersion client = state.client().get().value();
            return NodeSummary.Client.of(client, client.compare(version));
        }
        return state.clientFailure()
            .map(failure -> NodeSummary.Client.state(failure.value() == ErrorCode.TIMEOUT
                    ? "not_yet_detected" : "unknown", Optional.of(failure.value().name()),
                Optional.of(at(failure))))
            .orElseGet(() -> NodeSummary.Client.state("not_yet_detected", Optional.empty(),
                Optional.empty()));
    }

    /** The time of {@code value} to the second, as in the gate's texts. */
    private static Instant at(Timed<?> value) {
        return value.at().truncatedTo(ChronoUnit.SECONDS);
    }

    /** As the gate's refusal: "versionLine V8_1 of qa/node1 contradicts INUBIT 9.0.7 …". */
    private static String contradiction(NodeId node, NodeVersionState state) {
        Timed<InubitVersion> observed = state.observed().orElseThrow();
        return "versionLine " + state.setting().text() + " of " + node + " contradicts INUBIT "
            + observed.value().raw() + " observed by get_health at "
            + VersionGate.time(observed);
    }

    /** The capability's name in {@code list_nodes} (contracts/mcp-tools-delta.md). */
    private static String name(Capability capability) {
        return switch (capability) {
            case MONITORING -> "monitoring";
            case PROCESS_DIAGNOSIS -> "process_diagnosis";
            case LOG_DIAGNOSIS -> "log_diagnosis";
            case INVENTORY -> "inventory";
            case WORKSPACE_EXPORT -> "workspace_export";
            case DEVELOPMENT_WRITE -> "development_writes";
            case DEPLOYMENT -> "deployment";
            case E2E_TEST -> "e2e_test";
            case PROCESS_CONTROL -> "process_control";
        };
    }
}
