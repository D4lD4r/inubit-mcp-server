package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.CoverageState;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState.DetectionFailure;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState.Timed;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.ClientSelectionPort;
import de.dadecker.inubit.mcp.domain.port.ClientVersionPort;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides before a tool call whether the INUBIT version and the StartCLI client of a node provide
 * the call's capability (research D-5, FR-005, FR-008 to FR-011). Steps:
 * <ol>
 *   <li>detect the version of an {@code AUTO} node through {@link GatewayFactory#detect} when
 *       {@link NodeVersions#needsDetection} says so (never for pinned nodes, never for
 *       {@code MONITORING}, whose {@code get_health} read is the detection itself);</li>
 *   <li>refuse a write when a pin is contradicted by {@code get_health}; for a pin of a
 *       {@linkplain CapabilityMatrix#isCliOnly CLI-only} version every capability except
 *       {@code MONITORING} (feature 012, research D-11);</li>
 *   <li>look up the {@link CapabilityMatrix}: {@code SUPPORTED} admits, {@code UNVERIFIED} admits
 *       a read with a warning, anything else refuses ({@code MONITORING} is never refused and gets
 *       a warning instead); on a CLI-only version anything but {@code SUPPORTED} refuses,
 *       {@code MONITORING} and reads included (feature 012, research D-3);</li>
 *   <li>only when {@link Channel#CLI} is among the call's channels (on a CLI-only version the
 *       channels of its matrix entry): probe the StartCLI client the node uses at the gate's
 *       version ({@link ClientSelectionPort}, feature 017) if it exists, when no result of that
 *       client is known and no failed probe of it
 *       lies within the last 60 s ({@link NodeVersions#needsClientProbe}; FR-002), bounded by the
 *       node's {@code cliTimeout}, a run of its own before the tool's StartCLI call; then check
 *       the client against the server's major.minor (FR-011): an excluded server refuses, a patch
 *       mismatch or a range that cannot be evaluated (also a failed or timed-out probe) gives a
 *       warning; the results of other clients of the node are not judged (FR-017). An
 *       explicit servlet {@code cli.url} on major 9 or later adds its warning (research
 *       D-19).</li>
 * </ol>
 * A refusal is a {@link ToolErrorException} with {@link ErrorCode#UNSUPPORTED_VERSION}, logged
 * (FR-015). Its texts are built here from the node id, versions and error codes only, never from
 * the detection's error (FR-016; {@code V81VersionDetector} suggests {@code versionLine: V8_1}
 * unconditionally, which FR-005 forbids); they name the node level with the profile's
 * terminology (feature 002, FR-007). A refusal on a version whose matrix entry carries a note for
 * the capability uses it, e.g. "… is not yet verified for INUBIT 9.0" (feature 020).
 *
 * <p>The purpose of a call is a {@link Capability.Kind} ({@code READ} or {@code WRITE}).
 *
 * <p>Feature 022 (research D-2 "Gate per call", D-12 step 5): {@link #admitCall} and
 * {@link #admitParts} run the capability admission of an {@link ActionSpec}, then admit only the
 * coverage entries that are {@code reachable} for the node's major.minor in the
 * {@link InterfaceCoverage} the gate is given.
 *
 * <p>Thread-safe; it keeps no state of its own. Concurrent admissions on the same node may both
 * detect or both probe the client (the check and the detection or probe are not atomic);
 * {@link NodeVersions} keeps the latest outcome, so no state is lost.
 */
public final class VersionGate {

    private static final Logger LOG = LoggerFactory.getLogger(VersionGate.class);
    /** The end of every refusal message. */
    private static final String NOTHING_SENT = "; nothing was sent";
    /** Feature 022: the likely cause of a refused coverage entry. */
    private static final String COVERAGE_CAUSE = "This server sends an entry of the INUBIT"
        + " interfaces only on a version for which it is verified (docs/interface-coverage.md)";

    /**
     * An admitted call (data-model.md → Admission): the warnings for the call's result, and the
     * version notes of the node's version (feature 012, FR-010: what the version lacks next to the
     * admitted capability, e.g. "logs of INUBIT 6.1 cannot be read with query_logs; read them in
     * the Workbench"), which the caller adds to its result.
     *
     * @param version feature 017 (FR-018, research D-24): the version the call was judged on;
     *     present for every admitted write of a known version, the version of its
     *     {@link WritePermit}
     */
    public record Admission(List<String> warnings, List<String> notes,
        Optional<InubitVersion> version) {
        public Admission {
            warnings = List.copyOf(warnings);
            notes = List.copyOf(notes);
            Objects.requireNonNull(version, "version");
        }

        /** An admission without a known version. */
        public Admission(List<String> warnings, List<String> notes) {
            this(warnings, notes, Optional.empty());
        }

        /**
         * The permit of this admitted write on {@code node} for {@code capability}.
         *
         * @throws IllegalStateException without a version: a write is admitted only on a known
         *     version, so this is a programming error (fail closed)
         */
        public WritePermit permit(NodeId node, Capability capability) {
            return new WritePermit(node, capability, version.orElseThrow(() ->
                new IllegalStateException("A write on " + node + " was admitted without a"
                    + " version")));
        }
    }

    /**
     * The outcome of {@link #evaluate} for one node: the version the call was judged on (empty if
     * unknown, also for a major-only line such as {@code V9_X}), that version as text, and either
     * the admission's warnings or the refusal {@code admit} would throw with its cause.
     *
     * @param versionName e.g. "INUBIT 8.1.17", "INUBIT 9.x (versionLine V9_X)" or "INUBIT version
     *                    unknown"
     * @param cause       the refusal's message without its closing "; nothing was sent";
     *                    present exactly when {@code refusal} is
     */
    public record Evaluation(Optional<InubitVersion> version, String versionName,
        List<String> warnings, Optional<ToolError> refusal, Optional<String> cause) {
        public Evaluation {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(versionName, "versionName");
            warnings = List.copyOf(warnings);
            Objects.requireNonNull(refusal, "refusal");
            Objects.requireNonNull(cause, "cause");
            if (refusal.isPresent() != cause.isPresent()) {
                throw new IllegalArgumentException("A refusal needs its cause and vice versa");
            }
        }
    }

    /**
     * The outcome of {@link #admitParts}: the capability admission, the admitted parts, and every
     * other requested part with its reason, in the order of the part names.
     */
    public record PartAdmission(Admission admission, Set<Part> admitted,
        List<UnavailablePart> unavailable) {
        public PartAdmission {
            Objects.requireNonNull(admission, "admission");
            admitted = Set.copyOf(admitted);
            unavailable = List.copyOf(unavailable);
        }
    }

    /** The capability admission of an action and the version line its entries are judged on. */
    private record ActionAdmission(Admission admission, String versionName, MajorMinor line) {
    }

    /** A refusal of this gate: the error and the cause its message was built from. */
    private static final class Refusal extends ToolErrorException {

        private static final long serialVersionUID = 1L;

        private final String cause;

        Refusal(ToolError error, String cause) {
            super(error);
            this.cause = cause;
        }
    }

    private final NodeVersions versions;
    private final GatewayFactory gateways;
    private final CapabilityMatrix matrix;
    private final ClientVersionPort clients;
    private final ClientSelectionPort selection;
    private final Function<NodeId, Duration> cliTimeouts;
    private final Terminology terms;
    private final boolean cliClientsConfigured;
    private final InterfaceCoverage coverage;

    /**
     * A gate of a profile without {@code defaults.cliClients}, with an empty coverage list
     * ({@link #admitCall} and {@link #admitParts} refuse every entry).
     */
    public VersionGate(NodeVersions versions, GatewayFactory gateways, CapabilityMatrix matrix,
        ClientVersionPort clients, ClientSelectionPort selection,
        Function<NodeId, Duration> cliTimeouts, Terminology terms) {
        this(versions, gateways, matrix, clients, selection, cliTimeouts, terms, false);
    }

    /** As below, with an empty coverage list. */
    public VersionGate(NodeVersions versions, GatewayFactory gateways, CapabilityMatrix matrix,
        ClientVersionPort clients, ClientSelectionPort selection,
        Function<NodeId, Duration> cliTimeouts, Terminology terms,
        boolean cliClientsConfigured) {
        this(versions, gateways, matrix, clients, selection, cliTimeouts, terms,
            cliClientsConfigured, InterfaceCoverage.empty());
    }

    /**
     * @param selection   the StartCLI client of a node at the gate's version; only an existing
     *     client is probed (feature 017, research D-7)
     * @param cliTimeouts the {@code cliTimeout} of each node, the bound of its client probe
     * @param terms       the profile's terminology of the messages
     * @param cliClientsConfigured whether the profile sets {@code defaults.cliClients}: the
     *     refusal of a client names the entry of the version then (feature 017, research D-8)
     * @param coverage    the coverage list of {@link #admitCall} and {@link #admitParts}
     *     (feature 022)
     */
    public VersionGate(NodeVersions versions, GatewayFactory gateways, CapabilityMatrix matrix,
        ClientVersionPort clients, ClientSelectionPort selection,
        Function<NodeId, Duration> cliTimeouts, Terminology terms,
        boolean cliClientsConfigured, InterfaceCoverage coverage) {
        this.cliClientsConfigured = cliClientsConfigured;
        this.coverage = Objects.requireNonNull(coverage, "coverage");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.matrix = Objects.requireNonNull(matrix, "matrix");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.cliTimeouts = Objects.requireNonNull(cliTimeouts, "cliTimeouts");
        this.terms = Objects.requireNonNull(terms, "terms");
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
        return admission(capability, evaluate(node, capability, purpose, channels,
            Optional.empty()));
    }

    private Admission admission(Capability capability, Evaluation evaluation) {
        if (evaluation.refusal().isPresent()) {
            throw new ToolErrorException(evaluation.refusal().get());
        }
        return new Admission(evaluation.warnings(), notes(capability, evaluation.version()),
            evaluation.version());
    }

    /**
     * {@link #admit(NodeId, Capability, Capability.Kind, Set)} that returns a refusal instead of
     * throwing it (research D-11: {@code deploy_release} judges every node before it refuses);
     * the steps, texts and logging are the same.
     *
     * @throws ToolErrorException as {@code admit}, except this gate's own
     *     {@code UNSUPPORTED_VERSION} refusals
     */
    public Evaluation evaluate(NodeId node, Capability capability, Capability.Kind purpose,
        Set<Channel> channels) {
        return evaluate(node, capability, purpose, channels, Optional.empty());
    }

    /**
     * @param versionNeededBy the tool of {@link #admitWithDetection}; empty for today's
     *     behaviour, which judges an undetectable version as unknown
     */
    private Evaluation evaluate(NodeId node, Capability capability, Capability.Kind purpose,
        Set<Channel> channels, Optional<String> versionNeededBy) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(channels, "channels");
        if (capability != Capability.MONITORING && versions.needsDetection(node, purpose)) {
            detect(node, versionNeededBy.isPresent());
        }
        NodeVersionState state = versions.state(node);
        Optional<InubitVersion> version = purpose == Capability.Kind.WRITE
            ? versions.writableVersion(node) : state.effectiveVersion();
        if (versionNeededBy.isPresent() && version.isEmpty()
            && state.setting() instanceof VersionSetting.Auto) {
            // no detection in this call: the back-off of a recorded failure suppressed it
            Optional<Timed<DetectionFailure>> failure = state.detectionFailure();
            if (failure.isPresent()) {
                throw undetectable(versionNeededBy.get(), node, failure.get());
            }
        }
        String versionName = name(state, version).orElse("INUBIT version unknown");
        try {
            return new Evaluation(version, versionName,
                judge(node, capability, purpose, channels, state, version),
                Optional.empty(), Optional.empty());
        } catch (Refusal e) {
            return new Evaluation(version, versionName, List.of(), Optional.of(e.error()),
                Optional.of(e.cause));
        }
    }

    /**
     * How one {@code get_health} call reaches the node, decided before any contact (FR-001,
     * research D-11): {@link Channel#CLI} for a node pinned to a CLI-only version whose
     * {@code MONITORING} is {@code SUPPORTED}, {@link Channel#REST} for every other node, whose
     * call then goes on with {@link #admitMonitoring} after its reads. Decided on the pin, not on
     * an observation, so a contradicted 6.1 pin still gets no REST request (FR-002). Never detects
     * and never probes the client: {@code get_health} probes it in parallel with its own calls.
     *
     * @throws ToolErrorException {@code UNSUPPORTED_VERSION} if the node is pinned to a CLI-only
     *     version whose {@code MONITORING} is not {@code SUPPORTED}
     */
    public Channel monitoringAccess(NodeId node) {
        Objects.requireNonNull(node, "node");
        NodeVersionState state = versions.state(node);
        Optional<InubitVersion> pin = pinnedVersion(state);
        if (!matrix.isCliOnly(pin)) {
            return Channel.REST;
        }
        if (matrix.state(pin, Capability.MONITORING) != SupportState.SUPPORTED) {
            throw refuseUnsupported(node, Capability.MONITORING, Capability.Kind.READ, state, pin,
                name(state, pin));
        }
        return Channel.CLI;
    }

    /**
     * The channels through which {@code capability} reaches {@code node}, decided on the pin
     * before any contact like {@link #monitoringAccess}: the matrix entry's channels of the
     * pinned version (StartCLI for process diagnosis on 6.1), else the capability's own. Callers
     * size their deadlines by it (feature 012, research D-10). Never detects, never probes and
     * never refuses; the admission still decides.
     */
    public Set<Channel> channels(NodeId node, Capability capability) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(capability, "capability");
        return matrix.channels(pinnedVersion(versions.state(node)), capability);
    }

    /**
     * The {@code MONITORING} admission of one {@code get_health} call over REST: never detects
     * and never refuses, except on a CLI-only version (research D-3). It is judged on the version
     * this call's own {@code /system/info} read returned; without one (the read failed or gave no
     * parsable version) on the node's known state, so a failed {@code AUTO} detection gives the
     * "unknown (&lt;code&gt; at &lt;time&gt;)" warning.
     *
     * @param read the version the call read, if any
     */
    public Admission admitMonitoring(NodeId node, Optional<InubitVersion> read) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(read, "read");
        NodeVersionState state = versions.state(node);
        Optional<InubitVersion> version = read.or(state::effectiveVersion);
        return new Admission(judge(node, Capability.MONITORING, Capability.Kind.READ,
            Set.of(Channel.REST), state, version), List.of(), version);
    }

    /** @return the warnings of the admission */
    private List<String> judge(NodeId node, Capability capability, Capability.Kind purpose,
        Set<Channel> channels, NodeVersionState state, Optional<InubitVersion> version) {
        Optional<String> name = name(state, version);
        List<String> warnings = new ArrayList<>();
        boolean cliOnly = matrix.isCliOnly(version);
        // the pinned version itself: version is the observed one while the pin is contradicted
        boolean cliOnlyPin = matrix.isCliOnly(pinnedVersion(state));

        // a contradicted pin of a CLI-only version leaves only get_health (research D-11)
        if (state.pinContradiction() && (purpose == Capability.Kind.WRITE
            || cliOnlyPin && capability != Capability.MONITORING)) {
            throw refuseContradiction(node, capability, state);
        }
        SupportState support = support(state, version, capability);
        if (cliOnly && support != SupportState.SUPPORTED) {
            // no warning path on a CLI-only version: it has no 8.1 adapters to fall back to
            throw refuseUnsupported(node, capability, purpose, state, version, name);
        }
        if (support == SupportState.UNVERIFIED && purpose == Capability.Kind.READ
            || support == SupportState.UNSUPPORTED && capability == Capability.MONITORING) {
            warnings.add(name.map(n -> n + " on " + node + " is not "
                    + (support == SupportState.UNVERIFIED ? "verified" : "supported") + " for "
                    + capability.label() + "; results may be incomplete")
                .orElseGet(() -> unknown(node, state) + "; results are unverified"));
        } else if (support != SupportState.SUPPORTED) {
            throw refuseUnsupported(node, capability, purpose, state, version, name);
        }
        if ((cliOnly ? matrix.channels(version, capability) : channels).contains(Channel.CLI)) {
            // feature 017 (FR-017): only the client chosen for this version counts
            Optional<ClientKey> key = selection.current(node, version);
            if (key.isPresent()) {
                boolean probe = selection.available(node, version)
                    && versions.needsClientProbe(node, key.get());
                if (probe) {
                    probeClient(node, key.get(), version);
                }
                checkClient(node, capability, probe ? versions.state(node) : state, key.get(),
                    version, name, warnings);
            }
            selection.warning(node, version).ifPresent(warnings::add);
        }
        return warnings;
    }

    /**
     * {@link #admit(NodeId, Capability, Capability.Kind, Set)} for one tool: the message of a
     * refusal starts with the tool name, e.g. {@code "import_artifacts: INUBIT 9.0.7 on …"}
     * (contracts/mcp-tools-delta.md).
     */
    public Admission admit(String tool, NodeId node, Capability capability,
        Capability.Kind purpose, Set<Channel> channels) {
        Objects.requireNonNull(tool, "tool");
        return named(tool, () -> admit(node, capability, purpose, channels));
    }

    /**
     * {@link #admit(String, NodeId, Capability, Capability.Kind, Set)} for a tool that needs the
     * version of the node (feature 020, US3-4, research D-6): an undetectable version fails the
     * call with the detection's error instead of being judged as unknown. A detection of this
     * call that fails has been recorded in {@link NodeVersions} (which starts the back-off) and
     * is rethrown with its own code and message. Within the back-off of a recorded failure, with
     * no known version, the call fails with the recorded code and does not detect again (no
     * extra login attempt), e.g. "The INUBIT version of dev/node1 could not be detected
     * (UNREACHABLE at 2026-11-10T08:15:30Z); read_queue_entry needs it".
     *
     * @throws ToolErrorException as {@code admit}, and the detection's error as described
     */
    public Admission admitWithDetection(String tool, NodeId node, Capability capability,
        Capability.Kind purpose, Set<Channel> channels) {
        Objects.requireNonNull(tool, "tool");
        return named(tool, () -> admission(capability,
            evaluate(node, capability, purpose, channels, Optional.of(tool))));
    }

    /** Starts the message of this gate's refusal with {@code tool}. */
    private static <T> T named(String tool, Supplier<T> admission) {
        try {
            return admission.get();
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
     * Admits one call of {@code spec} that sends {@code callEntries} (feature 022, research D-2
     * "Gate per call", D-12 step 5): the capability admission of
     * {@link #admit(String, NodeId, Capability, Capability.Kind, Set)} on the channels of the
     * entries, a {@code CHANGE} as a write, then every entry must be {@code reachable} for the
     * node's major.minor. A refusal names the action and the first entry that is not, e.g.
     * "get_model_details: INUBIT 9.0 on test/node1: module via REST GET /model/nodeByName/…
     * is not documented for this version (INUBIT 9.0); nothing was sent"; a sibling variant whose
     * entries are reachable is still admitted.
     *
     * @param callEntries the entries of this call ({@link ActionSpec#entries}), a non-empty
     *     subset of {@link ActionSpec#callEntries()}
     * @throws ToolErrorException {@code UNSUPPORTED_VERSION} if the capability admission refuses,
     *     the node's major.minor is not known, or an entry is not reachable; otherwise as
     *     {@code admit}
     */
    public Admission admitCall(String tool, NodeId node, ActionSpec spec,
        Set<CoverageKey> callEntries) {
        Objects.requireNonNull(tool, "tool");
        if (callEntries.isEmpty() || !spec.callEntries().containsAll(callEntries)) {
            throw new IllegalArgumentException("The entries " + callEntries + " are not a variant"
                + " of " + spec.tool() + "/" + spec.action());
        }
        return named(tool, () -> {
            ActionAdmission judged = admitAction(node, spec, channelsOf(callEntries));
            coverage.refusal(callEntries, judged.line()).ifPresent(gap -> {
                throw refuse(node, spec.capability(),
                    judged.versionName() + " on " + node + ": " + spec.action() + " via "
                        + gap.key().channel() + " " + gap.key().entry() + " is "
                        + why(gap.state(), judged.line()),
                    COVERAGE_CAUSE, coverageStep());
            });
            return judged.admission();
        });
    }

    /**
     * Admits the requested parts of a multi-part read of {@code spec} (feature 022, research D-2
     * "Gate per call", FR-013): the capability admission as in {@link #admitCall} on the channels
     * of the parts, then each part is admitted when its entries are {@code reachable} for the
     * node's major.minor and, for a StartCLI part, the node has a StartCLI client at that version.
     * Every other part is reported as an {@link UnavailablePart} with {@code UNSUPPORTED_VERSION}
     * ("not yet verified", "not documented for this version" or the exclusion reason) or
     * {@code CLI_UNAVAILABLE}. When the call has REST and StartCLI parts and only the StartCLI
     * channel adds a refusal (for example the node's StartCLI client does not support its
     * version), the StartCLI parts are reported with that refusal and the REST parts are admitted
     * (T021, FR-013).
     *
     * @param parts a non-empty subset of {@link ActionSpec#parts()}
     * @throws ToolErrorException as {@link #admitCall} for the capability admission and an
     *     unknown major.minor; when no part is admitted, {@code CLI_UNAVAILABLE} if every part
     *     lacks only the client, else {@code UNSUPPORTED_VERSION}, naming every part's reason
     */
    public PartAdmission admitParts(String tool, NodeId node, ActionSpec spec, Set<Part> parts) {
        Objects.requireNonNull(tool, "tool");
        if (parts.isEmpty() || !spec.parts().containsAll(parts)) {
            throw new IllegalArgumentException("The parts " + parts + " are not parts of "
                + spec.tool() + "/" + spec.action());
        }
        return named(tool, () -> {
            Set<Channel> channels =
                parts.stream().map(Part::channel).collect(Collectors.toUnmodifiableSet());
            ActionAdmission judged;
            // T021 (T010 review): a refusal that only the StartCLI channel adds, for example a
            // client whose range excludes the version, refuses only the StartCLI parts (FR-013)
            Optional<ToolError> cliRefusal = Optional.empty();
            try {
                judged = admitAction(node, spec, channels);
            } catch (ToolErrorException e) {
                if (e.error().code() != ErrorCode.UNSUPPORTED_VERSION
                    || !channels.equals(Set.of(Channel.REST, Channel.CLI))) {
                    throw e;
                }
                judged = admitAction(node, spec, Set.of(Channel.REST));
                cliRefusal = Optional.of(e.error());
            }
            Optional<InubitVersion> version = judged.admission().version();
            boolean client = selection.current(node, version).isPresent()
                && selection.available(node, version);
            Set<Part> admitted = new HashSet<>();
            Map<Part, ToolError> refused = new LinkedHashMap<>();
            for (Part part : parts.stream().sorted(Comparator.comparing(Part::name)).toList()) {
                Optional<InterfaceCoverage.Unreachable> gap =
                    coverage.refusal(part.entries(), judged.line());
                if (gap.isPresent()) {
                    refused.put(part, ToolError.of(ErrorCode.UNSUPPORTED_VERSION, part.name() + " via "
                        + gap.get().key().channel() + " " + gap.get().key().entry() + " is "
                        + why(gap.get().state(), judged.line()), COVERAGE_CAUSE,
                        coverageStep()));
                } else if (part.channel() == Channel.CLI && cliRefusal.isPresent()) {
                    // the REST parts are still sent, so without "nothing was sent"
                    ToolError error = cliRefusal.get();
                    refused.put(part, ToolError.of(ErrorCode.UNSUPPORTED_VERSION, part.name()
                        + ": " + error.message().replace(NOTHING_SENT, ""),
                        error.likelyCause(), error.nextStep()));
                } else if (part.channel() == Channel.CLI && !client) {
                    refused.put(part, ToolError.of(ErrorCode.CLI_UNAVAILABLE, part.name()
                        + " is read through StartCLI, and no StartCLI client is installed for "
                        + node,
                        terms.render("No StartCLI client is configured or installed for the INUBIT"
                            + " version of the {node}"),
                        "Install a StartCLI client for " + node + " (cli.home or"
                            + " defaults.cliClients) to read " + part.name()));
                } else {
                    admitted.add(part);
                }
            }
            if (admitted.isEmpty()) {
                throw noPart(tool, node, spec.capability(), judged.versionName(),
                    List.copyOf(refused.values()));
            }
            return new PartAdmission(judged.admission(), admitted, refused.entrySet().stream()
                .map(entry -> UnavailablePart.of(entry.getKey().name(), entry.getValue()))
                .toList());
        });
    }

    private String coverageStep() {
        return terms.render("Use another variant of the call if there is one, or another {node}"
            + " whose INUBIT version offers it (docs/interface-coverage.md lists the entries per"
            + " version)");
    }

    /**
     * The capability admission of an action on {@code channels}, a {@code CHANGE} as a write, and
     * the major.minor its entries are judged on.
     *
     * @throws ToolErrorException {@code UNSUPPORTED_VERSION} if the node has no known
     *     major.minor, else as {@link #evaluate}
     */
    private ActionAdmission admitAction(NodeId node, ActionSpec spec, Set<Channel> channels) {
        Capability.Kind purpose = spec.kind() == ActionSpec.Kind.CHANGE
            ? Capability.Kind.WRITE : Capability.Kind.READ;
        Evaluation evaluation = evaluate(node, spec.capability(), purpose, channels,
            Optional.empty());
        Admission admission = admission(spec.capability(), evaluation);
        if (evaluation.version().isEmpty()) {
            NodeVersionState state = versions.state(node);
            throw refuse(node, spec.capability(), name(state, Optional.empty())
                    .map(line -> line + " on " + node + " names no major.minor; "
                        + spec.action() + " is offered per INUBIT major.minor")
                    .orElseGet(() -> spec.action() + " refused: "
                        + unknown(node, state)),
                "This server lists the entries it sends per INUBIT major.minor",
                terms.render("Let get_health detect the version of the {node}, or set versionLine"
                    + " of ") + node + " to a line with minor (such as V9_0) and restart the"
                    + " server");
        }
        return new ActionAdmission(admission, evaluation.versionName(),
            MajorMinor.of(evaluation.version().get()));
    }

    /** The channels of {@code entries}. */
    private static Set<Channel> channelsOf(Set<CoverageKey> entries) {
        return entries.stream().map(CoverageKey::channel)
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Why an entry that is not {@code reachable} on {@code line} is refused: "not yet verified
     * for INUBIT 9.0", "not documented for this version (INUBIT 9.0)", "excluded for INUBIT 9.0:
     * &lt;reason&gt;", or "not listed for INUBIT 6.1" without a state.
     */
    private static String why(Optional<CoverageState> state, MajorMinor line) {
        String version = "INUBIT " + line.text();
        return switch (state.orElse(null)) {
            case null -> "not listed for " + version;
            case CoverageState.Planned planned -> "not yet verified for " + version;
            case CoverageState.Undocumented undocumented ->
                "not documented for this version (" + version + ")";
            case CoverageState.Excluded excluded ->
                "excluded for " + version + ": " + excluded.reason();
            case CoverageState.Covered covered -> "not sent by this server on " + version + " ("
                + covered.tool() + " reads it through another entry)";
            case CoverageState.Reachable reachable ->
                throw new IllegalStateException("A reachable entry is no refusal");
        };
    }

    /**
     * The refusal of {@link #admitParts} when no part is admitted: {@code CLI_UNAVAILABLE} if
     * every part lacks only the client, else {@code UNSUPPORTED_VERSION}; the message names every
     * part's reason, cause and step are those of the first part.
     */
    private ToolErrorException noPart(String tool, NodeId node, Capability capability,
        String versionName, List<ToolError> errors) {
        String cause = versionName + " on " + node + ": no requested part can be read: "
            + errors.stream().map(ToolError::message).collect(Collectors.joining("; "));
        ToolError first = errors.get(0);
        if (errors.stream().allMatch(error -> error.code() == ErrorCode.CLI_UNAVAILABLE)) {
            String message = tool + ": " + cause + NOTHING_SENT;
            LOG.info("{} on {}: REFUSED ({}): {}", capability, node, ErrorCode.CLI_UNAVAILABLE,
                message);
            return new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE, message,
                first.likelyCause(), first.nextStep()).withNode(node));
        }
        return refuse(node, capability, cause, first.likelyCause(), first.nextStep());
    }

    /**
     * Detects now. A failure other than {@code TLS_ERROR} has been reported to
     * {@link NodeVersions} (which starts the back-off) and is judged as an unknown version; a
     * cancelled call is passed through, as it was not reported.
     *
     * @param passFailures pass every failure through (feature 020, {@link #admitWithDetection})
     */
    private void detect(NodeId node, boolean passFailures) {
        try {
            gateways.detect(node);
        } catch (ToolErrorException e) {
            if (passFailures || e.error().code() == ErrorCode.TLS_ERROR
                || Thread.currentThread().isInterrupted()) {
                throw e;
            }
        }
    }

    /** The recorded failure of a detection the back-off suppressed in this call (US3-4). */
    private static ToolErrorException undetectable(String tool, NodeId node,
        Timed<DetectionFailure> failure) {
        ErrorCode code = failure.value().code();
        return new ToolErrorException(ToolError.of(code,
            "The INUBIT version of " + node + " could not be detected (" + code + " at "
                + time(failure) + "); " + tool + " needs it",
            "The last version detection of " + node + " failed less than a minute ago and is"
                + " not repeated before then",
            "Fix the cause and retry in a minute, or set versionLine of " + node)
            .withNode(node));
    }

    /**
     * Probes the client now and records the outcome in {@link NodeVersions}; a failure starts the
     * 60 s back-off and is judged as a range that cannot be evaluated. A cancelled probe is passed
     * through, as it was not recorded.
     */
    private void probeClient(NodeId node, ClientKey key, Optional<InubitVersion> version) {
        try {
            versions.clientProbed(node, clients.probe(node, version, cliTimeouts.apply(node)));
        } catch (ToolErrorException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw e;
            }
            versions.clientProbeFailed(node, key, e.error().code());
        }
    }

    /**
     * The version the node's setting pins, whatever was observed; empty for {@code AUTO} and a
     * major-only line.
     */
    private static Optional<InubitVersion> pinnedVersion(NodeVersionState state) {
        return NodeVersionState.initial(state.setting()).effectiveVersion();
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
        ClientKey key, Optional<InubitVersion> version, Optional<String> name,
        List<String> warnings) {
        if (state.clientFor(key).isEmpty()) {
            state.clientFailureFor(key).ifPresent(failure -> warnings.add(
                "The StartCLI client probe of "
                + node + " failed (" + timed(failure) + "); the INUBIT range of the client"
                + " cannot be checked, StartCLI results may be unreliable"));
            return;
        }
        ClientVersion client = state.clientFor(key).get().value();
        ClientVersion.Compatibility compatibility =
            version.isEmpty() && state.setting() instanceof VersionSetting.Line line
                ? client.compatibleWith(line.major(), line.minor())
                : client.compatibleWith(version);
        switch (compatibility) {
            case INCOMPATIBLE -> throw refuse(node, capability,
                "StartCLI client " + client.version().raw() + " of " + node + " supports INUBIT "
                    + client.supportedFrom().orElseThrow().raw() + " to "
                    + client.supportedTo().orElseThrow().raw() + ", which excludes "
                    + name.orElseThrow() + "; " + capability.label()
                    + " via StartCLI refused",
                terms.render("The local StartCLI client does not support the INUBIT version of"
                    + " the {node}"),
                "Install a StartCLI client on the host of this server that supports INUBIT "
                    + majorMinor(state, version)
                    + version.filter(known -> !wholeMajorLine(state)
                            && (cliClientsConfigured || known.major() >= 9))
                        .map(known -> ", or add defaults.cliClients.\""
                            + MajorMinor.of(known).text() + "\"")
                        .orElse(""));
            case NOT_EVALUABLE -> warnings.add(client.supportedFrom().isEmpty()
                ? "StartCLI client " + client.version().raw() + " of " + node
                    + " declares no supported INUBIT range; StartCLI results may be unreliable"
                : "The StartCLI client range of " + node + " cannot be checked: "
                    + unknown(node, state) + "; StartCLI results may be unreliable");
            case COMPATIBLE -> {
                if (client.compare(version) == ClientVersion.Comparison.MISMATCH) {
                    warnings.add(mismatchWarning(node, client, version.orElseThrow()));
                }
            }
        }
    }

    /**
     * The warning about a client whose major.minor.patch differs from the server's (FR-011), in
     * tool results as in {@code get_health} and {@code list_nodes}.
     */
    static String mismatchWarning(NodeId node, ClientVersion client, InubitVersion server) {
        return "StartCLI client " + client.version().raw() + " on " + node
            + " differs from INUBIT " + server.raw() + " (mismatch); StartCLI-backed tools still"
            + " run, but their results may be unreliable";
    }

    /** On a pin of a CLI-only version the message carries the next step (feature 012, FR-006). */
    private Refusal refuseContradiction(NodeId node, Capability capability,
        NodeVersionState state) {
        Timed<InubitVersion> observed = state.observed().orElseThrow();
        return refuse(node, capability,
            "versionLine " + state.setting().text() + " of " + node + " contradicts INUBIT "
                + observed.value().raw() + " observed by get_health at " + time(observed) + "; "
                + capability.label() + " refused"
                + (matrix.isCliOnly(pinnedVersion(state))
                    ? "; check the version with get_health and correct versionLine" : ""),
            terms.render("The configured version does not match the version the {node} runs"
                + " (for example after an upgrade)"),
            "Correct versionLine of " + node + " (get_health observed INUBIT "
                + observed.value().raw() + ") and restart the server");
    }

    /**
     * On a CLI-only version the message names the configured line and ends with the next step of
     * the capability (feature 012, FR-007), e.g. "INUBIT 6.1 (versionLine V6_1) on old/node1 does
     * not support log diagnosis; read the logs of INUBIT 6.1 in the Workbench".
     *
     * @param version the version {@code name} was built from
     */
    private Refusal refuseUnsupported(NodeId node, Capability capability,
        Capability.Kind purpose, NodeVersionState state, Optional<InubitVersion> version,
        Optional<String> name) {
        if (name.isEmpty()) {
            return refuse(node, capability,
                capitalized(capability.label()) + " refused: " + unknown(node, state),
                capitalized(capability.label()) + " needs a known INUBIT version that supports"
                    + " it, and the version could not be detected",
                "Fix the cause of the failed version detection and retry in a minute, or set"
                    + " versionLine of " + node + terms.render(" to the version the {node}"
                    + " actually runs and restart the server"));
        }
        String likelyCause = purpose == Capability.Kind.WRITE
            ? terms.render("The {node} runs a version for which no verified write access exists")
            : "This server declares " + capability.label() + " unsupported for that version";
        String otherNode = terms.render("Use another {node} whose INUBIT version supports ")
            + capability.label()
            + terms.render(" (list_nodes shows the usable capabilities per {node}), or wait"
            + " for a version of this server that supports that INUBIT version");
        Optional<String> note = matrix.note(version, capability);
        if (note.isPresent()) {
            // feature 020 (FR-019 fallback): e.g. "… is not yet verified for INUBIT 9.0"
            return refuse(node, capability,
                name.get() + " on " + node + ": " + capability.label() + " is " + note.get()
                    + " for INUBIT " + majorMinor(state, version),
                likelyCause, otherNode);
        }
        if (matrix.isCliOnly(version)) {
            String release = majorMinor(state, version);
            String step = cliOnlyStep(capability, release);
            return refuse(node, capability,
                name.get() + (state.setting() instanceof VersionSetting.Line line
                    ? " (versionLine " + line.text() + ")" : "")
                    + " on " + node + " does not support " + capability.label()
                    + (capability == Capability.INVENTORY
                        ? "; modules of INUBIT " + release + " cannot be looked up" : "")
                    + supportedLines(capability) + "; " + step,
                likelyCause, capitalized(step));
        }
        return refuse(node, capability,
            name.get() + " on " + node + " does not support " + capability.label() + " yet"
                + supportedLines(capability),
            likelyCause, otherNode);
    }

    /**
     * Feature 020 (US3-3): a refusal of reading Queue Manager entries names the supported lines,
     * e.g. "; supported: 8.1, 9.0"; so does one of downloading a server log (feature 021, research
     * D-6) and one of a node tool (feature 024, research D-4); empty for every other capability.
     */
    private String supportedLines(Capability capability) {
        return switch (capability) {
            case QUEUE_ENTRY_READ, TRACE_LOG_READ, WEBSERVICE_LOG_READ, CLUSTER_NODES_READ,
                IO_LOG_READ, NODE_CONFIG_READ, LOG_LEVEL_WRITE ->
                "; supported: " + String.join(", ", matrix.lines(capability));
            default -> "";
        };
    }

    /**
     * The next step of a refusal on a CLI-only version (contracts/mcp-tools-delta.md, "Refusals on
     * a 6.1 node").
     *
     * @param release the version's major.minor, e.g. "6.1"
     */
    private String cliOnlyStep(Capability capability, String release) {
        return switch (capability) {
            case LOG_DIAGNOSIS -> "read the logs of INUBIT " + release + " in the Workbench";
            case WORKSPACE_EXPORT -> "use the Workbench export";
            case MONITORING, PROCESS_DIAGNOSIS, INVENTORY, PARTNER_MANAGEMENT, SERVER_SETTINGS,
                USER_DIRECTORY ->
                terms.render("use the Workbench, or another {node} with a different INUBIT"
                    + " version");
            case DEVELOPMENT_WRITE, DEPLOYMENT, E2E_TEST, PROCESS_CONTROL -> "use the Workbench";
            case QUEUE_ENTRY_READ -> "use the Queue Manager in the Workbench";
            case TRACE_LOG_READ, WEBSERVICE_LOG_READ ->
                "download the log in the Workbench (Administration, Server Log)";
            case CLUSTER_NODES_READ, NODE_CONFIG_READ -> "use the Workbench's Cluster Manager";
            case IO_LOG_READ -> "use the Workbench's input/output logging view";
            case LOG_LEVEL_WRITE -> "use StartCLI trace -nl/-r by hand";
        };
    }

    /**
     * The version notes of an admission (feature 012, FR-010): process diagnosis on a CLI-only
     * version that does not support log diagnosis notes where its logs can be read.
     */
    private List<String> notes(Capability capability, Optional<InubitVersion> version) {
        if (capability != Capability.PROCESS_DIAGNOSIS || !matrix.isCliOnly(version)
            || matrix.state(version, Capability.LOG_DIAGNOSIS) == SupportState.SUPPORTED) {
            return List.of();
        }
        InubitVersion known = version.orElseThrow();
        // the find_processes warning of contracts/mcp-tools-delta.md; T033 returns it verbatim
        return List.of("logs of INUBIT " + known.major() + "." + known.minor()
            + " cannot be read with query_logs; read them in the Workbench");
    }

    /** @param cause the message without its closing "; nothing was sent" */
    private static Refusal refuse(NodeId node, Capability capability, String cause,
        String likelyCause, String nextStep) {
        String message = cause + NOTHING_SENT;
        LOG.info("{} on {}: REFUSED ({}): {}", capability, node, ErrorCode.UNSUPPORTED_VERSION,
            message);
        return new Refusal(ToolError.of(ErrorCode.UNSUPPORTED_VERSION, message, likelyCause,
            nextStep).withNode(node), cause);
    }

    /** "INUBIT 9.0.7", "INUBIT 9.x (versionLine V9_X)", or empty for an unknown version. */
    static Optional<String> name(NodeVersionState state,
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
    /** A line without minor never chooses a {@code cliClients} entry (research D-3). */
    private static boolean wholeMajorLine(NodeVersionState state) {
        return state.setting() instanceof VersionSetting.Line line && line.minor().isEmpty();
    }

    static String majorMinor(NodeVersionState state, Optional<InubitVersion> version) {
        if (version.isPresent()) {
            return version.get().major() + "." + version.get().minor();
        }
        VersionSetting.Line line = (VersionSetting.Line) state.setting();
        return line.major() + ".x";
    }

    /**
     * "the INUBIT version of dev/node1 is unknown (AUTH_FAILED at 2026-10-18T08:00:00Z)"; after a
     * detection answered with HTTP 404 followed by "; if dev/node1 runs INUBIT 6.1, pin V6_1"
     * (feature 012 FR-003).
     */
    static String unknown(NodeId node, NodeVersionState state) {
        Optional<Timed<DetectionFailure>> failure = state.detectionFailure();
        String hint = failure.filter(f -> f.value().endpointMissing()).isPresent()
            ? "; if " + node + " runs INUBIT 6.1, pin V6_1" : "";
        return "the INUBIT version of " + node + " is unknown ("
            + failure.map(f -> f.value().code() + " at " + time(f)).orElse("not yet detected")
            + ")" + hint;
    }

    private static String timed(Timed<ErrorCode> failure) {
        return failure.value() + " at " + time(failure);
    }

    static String time(Timed<?> value) {
        return value.at().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String capitalized(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
