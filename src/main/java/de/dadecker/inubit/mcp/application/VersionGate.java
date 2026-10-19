package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState.Timed;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ClientVersionPort;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides before a tool call whether the INUBIT version and the StartCLI client of a node provide
 * the call's capability (research D-5, FR-005, FR-008 to FR-011). Steps:
 * <ol>
 *   <li>detect the version of an {@code AUTO} node through {@link GatewayFactory#detect} when
 *       {@link NodeVersions#needsDetection} says so (never for pinned nodes, never for
 *       {@code MONITORING}, whose {@code get_health} read is the detection itself);</li>
 *   <li>refuse a write when a pin is contradicted by {@code get_health};</li>
 *   <li>look up the {@link CapabilityMatrix}: {@code SUPPORTED} admits, {@code UNVERIFIED} admits
 *       a read with a warning, anything else refuses ({@code MONITORING} is never refused and gets
 *       a warning instead);</li>
 *   <li>only when {@link Channel#CLI} is among the call's channels: probe the StartCLI client of
 *       a node with a StartCLI installation when no client result is known and no failed probe
 *       lies within the last 60 s ({@link NodeVersions#needsClientProbe}; FR-002), bounded by the
 *       node's {@code cliTimeout}, a run of its own before the tool's StartCLI call; then check
 *       the client against the server's major.minor (FR-011): an excluded server refuses, a patch
 *       mismatch or a range that cannot be evaluated (also a failed or timed-out probe) gives a
 *       warning.</li>
 * </ol>
 * A refusal is a {@link ToolErrorException} with {@link ErrorCode#UNSUPPORTED_VERSION}, logged
 * (FR-015). Its texts are built here from the node id, versions and error codes only, never from
 * the detection's error (FR-016; {@code V81VersionDetector} suggests {@code versionLine: V8_1}
 * unconditionally, which FR-005 forbids).
 *
 * <p>The purpose of a call is a {@link Capability.Kind} ({@code READ} or {@code WRITE}).
 *
 * <p>Thread-safe; it keeps no state of its own. Concurrent admissions on the same node may both
 * detect or both probe the client (the check and the detection or probe are not atomic);
 * {@link NodeVersions} keeps the latest outcome, so no state is lost.
 */
public final class VersionGate {

    private static final Logger LOG = LoggerFactory.getLogger(VersionGate.class);

    /** An admitted call (data-model.md → Admission): the warnings for the call's result. */
    public record Admission(List<String> warnings) {
        public Admission {
            warnings = List.copyOf(warnings);
        }
    }

    private final NodeVersions versions;
    private final GatewayFactory gateways;
    private final CapabilityMatrix matrix;
    private final ClientVersionPort clients;
    private final Predicate<NodeId> withClient;
    private final Function<NodeId, Duration> cliTimeouts;

    /**
     * @param withClient  whether a node has a StartCLI installation (only those are probed)
     * @param cliTimeouts the {@code cliTimeout} of each node, the bound of its client probe
     */
    public VersionGate(NodeVersions versions, GatewayFactory gateways, CapabilityMatrix matrix,
        ClientVersionPort clients, Predicate<NodeId> withClient,
        Function<NodeId, Duration> cliTimeouts) {
        this.versions = Objects.requireNonNull(versions, "versions");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.matrix = Objects.requireNonNull(matrix, "matrix");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.withClient = Objects.requireNonNull(withClient, "withClient");
        this.cliTimeouts = Objects.requireNonNull(cliTimeouts, "cliTimeouts");
    }

    /**
     * @param channels the channels the call is about to use; the client check runs only for
     *                 {@link Channel#CLI}
     * @throws ToolErrorException {@code UNSUPPORTED_VERSION} if the call is refused;
     *     {@code TLS_ERROR} of the detection, passed through (no request was possible, so it is
     *     no failed detection and no reason to refuse); the error of a detection or client probe
     *     cancelled by an interrupt, passed through and not recorded
     */
    public Admission admit(NodeId node, Capability capability, Capability.Kind purpose,
        Set<Channel> channels) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(channels, "channels");
        if (capability != Capability.MONITORING && versions.needsDetection(node, purpose)) {
            detect(node);
        }
        NodeVersionState state = versions.state(node);
        Optional<InubitVersion> version = purpose == Capability.Kind.WRITE
            ? versions.writableVersion(node) : state.effectiveVersion();
        return judge(node, capability, purpose, channels, state, version);
    }

    /**
     * The {@code MONITORING} admission of one {@code get_health} call: never detects and never
     * refuses. It is judged on the version this call's own {@code /system/info} read returned;
     * without one (the read failed or gave no parsable version) on the node's known state, so a
     * failed {@code AUTO} detection gives the "unknown (&lt;code&gt; at &lt;time&gt;)" warning.
     *
     * @param read the version the call read, if any
     */
    public Admission admitMonitoring(NodeId node, Optional<InubitVersion> read) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(read, "read");
        NodeVersionState state = versions.state(node);
        return judge(node, Capability.MONITORING, Capability.Kind.READ, Set.of(Channel.REST),
            state, read.or(state::effectiveVersion));
    }

    private Admission judge(NodeId node, Capability capability, Capability.Kind purpose,
        Set<Channel> channels, NodeVersionState state, Optional<InubitVersion> version) {
        Optional<String> name = name(state, version);
        List<String> warnings = new ArrayList<>();

        if (purpose == Capability.Kind.WRITE && state.pinContradiction()) {
            throw refuseContradiction(node, capability, state);
        }
        SupportState support = support(state, version, capability);
        if (support == SupportState.UNVERIFIED && purpose == Capability.Kind.READ
            || support == SupportState.UNSUPPORTED && capability == Capability.MONITORING) {
            warnings.add(name.map(n -> n + " on " + node + " is not "
                    + (support == SupportState.UNVERIFIED ? "verified" : "supported") + " for "
                    + label(capability) + "; results may be incomplete")
                .orElseGet(() -> unknown(node, state) + "; results are unverified"));
        } else if (support != SupportState.SUPPORTED) {
            throw refuseUnsupported(node, capability, purpose, state, name);
        }
        if (channels.contains(Channel.CLI)) {
            boolean probe = withClient.test(node) && versions.needsClientProbe(node);
            if (probe) {
                probeClient(node);
            }
            checkClient(node, capability, probe ? versions.state(node) : state, version, name,
                warnings);
        }
        return new Admission(warnings);
    }

    /**
     * {@link #admit(NodeId, Capability, Capability.Kind, Set)} for one tool: the message of a
     * refusal starts with the tool name, e.g. {@code "import_artifacts: INUBIT 9.0.7 on …"}
     * (contracts/mcp-tools-delta.md).
     */
    public Admission admit(String tool, NodeId node, Capability capability,
        Capability.Kind purpose, Set<Channel> channels) {
        Objects.requireNonNull(tool, "tool");
        try {
            return admit(node, capability, purpose, channels);
        } catch (ToolErrorException e) {
            ToolError error = e.error();
            if (error.code() != ErrorCode.UNSUPPORTED_VERSION) {
                throw e;
            }
            throw new ToolErrorException(new ToolError(error.code(), tool + ": "
                + error.message(), error.likelyCause(), error.nextStep(), error.node(),
                error.excerpt()));
        }
    }

    /**
     * Detects now. A failure other than {@code TLS_ERROR} has been reported to
     * {@link NodeVersions} (which starts the back-off) and is judged as an unknown version; a
     * cancelled call is passed through, as it was not reported.
     */
    private void detect(NodeId node) {
        try {
            gateways.detect(node);
        } catch (ToolErrorException e) {
            if (e.error().code() == ErrorCode.TLS_ERROR || Thread.currentThread().isInterrupted()) {
                throw e;
            }
        }
    }

    /**
     * Probes the client now and records the outcome in {@link NodeVersions}; a failure starts the
     * 60 s back-off and is judged as a range that cannot be evaluated. A cancelled probe is passed
     * through, as it was not recorded.
     */
    private void probeClient(NodeId node) {
        try {
            versions.clientProbed(node, clients.probe(node, cliTimeouts.apply(node)));
        } catch (ToolErrorException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw e;
            }
            versions.clientProbeFailed(node, e.error().code());
        }
    }

    private SupportState support(NodeVersionState state, Optional<InubitVersion> version,
        Capability capability) {
        if (version.isEmpty() && state.setting() instanceof VersionSetting.Line line) {
            return matrix.stateOfLine(line.major(), line.minor(), capability);
        }
        return matrix.state(version, capability);
    }

    /** FR-011: checks the known client result; without one, a failed probe gives a warning. */
    private void checkClient(NodeId node, Capability capability, NodeVersionState state,
        Optional<InubitVersion> version, Optional<String> name, List<String> warnings) {
        if (state.client().isEmpty()) {
            state.clientFailure().ifPresent(failure -> warnings.add("The StartCLI client probe of "
                + node + " failed (" + timed(failure) + "); the INUBIT range of the client"
                + " cannot be checked, StartCLI results may be unreliable"));
            return;
        }
        ClientVersion client = state.client().get().value();
        ClientVersion.Compatibility compatibility =
            version.isEmpty() && state.setting() instanceof VersionSetting.Line line
                ? client.compatibleWith(line.major(), line.minor())
                : client.compatibleWith(version);
        switch (compatibility) {
            case INCOMPATIBLE -> throw refuse(node, capability,
                "StartCLI client " + client.version().raw() + " of " + node + " supports INUBIT "
                    + client.supportedFrom().orElseThrow().raw() + " to "
                    + client.supportedTo().orElseThrow().raw() + ", which excludes "
                    + name.orElseThrow() + "; " + label(capability)
                    + " via StartCLI refused; nothing was sent",
                "The local StartCLI client does not support the INUBIT version of the node",
                "Install a StartCLI client on the host of this server that supports INUBIT "
                    + majorMinor(state, version));
            case NOT_EVALUABLE -> warnings.add(client.supportedFrom().isEmpty()
                ? "StartCLI client " + client.version().raw() + " of " + node
                    + " declares no supported INUBIT range; StartCLI results may be unreliable"
                : "The StartCLI client range of " + node + " cannot be checked: "
                    + unknown(node, state) + "; StartCLI results may be unreliable");
            case COMPATIBLE -> {
                if (client.compare(version) == ClientVersion.Comparison.MISMATCH) {
                    warnings.add("StartCLI client " + client.version().raw() + " and "
                        + name.orElseThrow() + " on " + node
                        + " differ; StartCLI results may be unreliable");
                }
            }
        }
    }

    private static ToolErrorException refuseContradiction(NodeId node, Capability capability,
        NodeVersionState state) {
        Timed<InubitVersion> observed = state.observed().orElseThrow();
        return refuse(node, capability,
            "versionLine " + state.setting().text() + " of " + node + " contradicts INUBIT "
                + observed.value().raw() + " observed by get_health at " + time(observed) + "; "
                + label(capability) + " refused; nothing was sent",
            "The configured version does not match the version the node runs (for example"
                + " after an upgrade)",
            "Correct versionLine of " + node + " (get_health observed INUBIT "
                + observed.value().raw() + ") and restart the server");
    }

    private static ToolErrorException refuseUnsupported(NodeId node, Capability capability,
        Capability.Kind purpose, NodeVersionState state, Optional<String> name) {
        if (name.isEmpty()) {
            return refuse(node, capability,
                capitalized(label(capability)) + " refused: " + unknown(node, state)
                    + "; nothing was sent",
                capitalized(label(capability)) + " needs a known INUBIT version that supports"
                    + " it, and the version could not be detected",
                "Fix the cause of the failed version detection and retry in a minute, or set"
                    + " versionLine of " + node + " to the version the node actually runs and"
                    + " restart the server");
        }
        return refuse(node, capability,
            name.get() + " on " + node + " does not support " + label(capability)
                + " yet; nothing was sent",
            purpose == Capability.Kind.WRITE
                ? "The node runs a version for which no verified write access exists"
                : "This server declares " + label(capability) + " unsupported for that version",
            "Use a node whose INUBIT version supports " + label(capability) + " (list_nodes"
                + " shows the usable capabilities per node), or wait for a version of this"
                + " server that supports that INUBIT version");
    }

    private static ToolErrorException refuse(NodeId node, Capability capability, String message,
        String likelyCause, String nextStep) {
        LOG.info("{} on {}: REFUSED ({}): {}", capability, node, ErrorCode.UNSUPPORTED_VERSION,
            message);
        return new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION, message,
            likelyCause, nextStep).withNode(node));
    }

    /** "INUBIT 9.0.7", "INUBIT 9.x (versionLine V9_X)", or empty for an unknown version. */
    private static Optional<String> name(NodeVersionState state,
        Optional<InubitVersion> version) {
        if (version.isPresent()) {
            return Optional.of("INUBIT " + version.get().raw());
        }
        if (state.setting() instanceof VersionSetting.Line line) {
            return Optional.of("INUBIT " + line.major() + ".x (versionLine " + line.text() + ")");
        }
        return Optional.empty();
    }

    /** "9.0", or "9.x" for a major-only line. */
    private static String majorMinor(NodeVersionState state, Optional<InubitVersion> version) {
        if (version.isPresent()) {
            return version.get().major() + "." + version.get().minor();
        }
        VersionSetting.Line line = (VersionSetting.Line) state.setting();
        return line.major() + ".x";
    }

    /** "the INUBIT version of dev/node1 is unknown (AUTH_FAILED at 2026-10-18T08:00:00Z)". */
    private static String unknown(NodeId node, NodeVersionState state) {
        return "the INUBIT version of " + node + " is unknown ("
            + state.detectionFailure().map(VersionGate::timed).orElse("not yet detected") + ")";
    }

    private static String timed(Timed<ErrorCode> failure) {
        return failure.value() + " at " + time(failure);
    }

    private static String time(Timed<?> value) {
        return value.at().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String capitalized(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** The capability as named in the spec's tool classification. */
    private static String label(Capability capability) {
        return switch (capability) {
            case MONITORING -> "monitoring";
            case DIAGNOSIS -> "diagnosis";
            case INVENTORY -> "inventory";
            case WORKSPACE_EXPORT -> "workspace export";
            case DEVELOPMENT_WRITE -> "development writes";
            case DEPLOYMENT -> "deployment";
            case E2E_TEST -> "end-to-end test";
            case PROCESS_CONTROL -> "process control";
        };
    }
}
