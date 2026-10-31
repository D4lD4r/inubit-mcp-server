package de.dadecker.inubit.mcp.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What the server knows about the INUBIT version of one node (research D-4, data-model.md →
 * NodeVersionState). Immutable; every {@code with…} returns the next state.
 *
 * <p>A purpose is a {@link Capability.Kind}: {@code READ} or {@code WRITE}.
 *
 * @param setting           the configured {@code versionLine}
 * @param detected          the last successful detection (gate or gateway slot)
 * @param detectionFailure  the last failed detection, until a detection succeeds or an
 *                          observation supersedes it
 * @param observed          the last version read by {@code get_health}
 * @param stale             a response indicated another version (D-10); cleared by a detection or
 *                          an observation
 * @param pinContradiction  the last observation's major.minor differs from the pin (FR-012)
 * @param client            the StartCLI client probe result, kept for the process lifetime
 * @param clientFailure     the last failed or timed-out probe while no result is known
 */
public record NodeVersionState(
    VersionSetting setting,
    Optional<Timed<InubitVersion>> detected,
    Optional<Timed<DetectionFailure>> detectionFailure,
    Optional<Timed<InubitVersion>> observed,
    boolean stale,
    boolean pinContradiction,
    Optional<Timed<ClientVersion>> client,
    Optional<Timed<ErrorCode>> clientFailure) {

    /** How long a failed detection or client probe is not repeated (FR-002, FR-010). */
    public static final Duration RETRY_AFTER = Duration.ofSeconds(60);

    /** A value with the time it was recorded. */
    public record Timed<T>(T value, Instant at) {
        public Timed {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * A failed detection: its error code, and whether the detection request was answered with
     * HTTP 404 ({@code endpointMissing}, as by a typical INUBIT 6.1 server; feature 011 FR-003).
     */
    public record DetectionFailure(ErrorCode code, boolean endpointMissing) {
        public DetectionFailure {
            Objects.requireNonNull(code, "code");
        }
    }

    public NodeVersionState {
        Objects.requireNonNull(setting, "setting");
        Objects.requireNonNull(detected, "detected");
        Objects.requireNonNull(detectionFailure, "detectionFailure");
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(clientFailure, "clientFailure");
    }

    /** Nothing detected, observed or probed yet. */
    public static NodeVersionState initial(VersionSetting setting) {
        return new NodeVersionState(setting, Optional.empty(), Optional.empty(), Optional.empty(),
            false, false, Optional.empty(), Optional.empty());
    }

    /** A successful detection: clears {@code stale} and the last failure. */
    public NodeVersionState withDetected(InubitVersion version, Instant at) {
        return new NodeVersionState(setting, Optional.of(new Timed<>(version, at)),
            Optional.empty(), observed, false, pinContradiction, client, clientFailure);
    }

    /** A failed detection; the last detected version stays for reads. */
    public NodeVersionState withDetectionFailure(DetectionFailure failure, Instant at) {
        return new NodeVersionState(setting, detected, Optional.of(new Timed<>(failure, at)),
            observed, stale, pinContradiction, client, clientFailure);
    }

    /**
     * A {@code get_health} observation: clears {@code stale}, supersedes a failed detection and
     * sets or clears the pin contradiction.
     */
    public NodeVersionState withObserved(InubitVersion version, Instant at) {
        return new NodeVersionState(setting, detected, Optional.empty(),
            Optional.of(new Timed<>(version, at)), false, contradicts(setting, version), client,
            clientFailure);
    }

    /** A response indicated that the node may run another version (D-10). */
    public NodeVersionState withStale() {
        return new NodeVersionState(setting, detected, detectionFailure, observed, true,
            pinContradiction, client, clientFailure);
    }

    /** A successful client probe (a version without range is a success with an empty range). */
    public NodeVersionState withClient(ClientVersion version, Instant at) {
        return new NodeVersionState(setting, detected, detectionFailure, observed, stale,
            pinContradiction, Optional.of(new Timed<>(version, at)), Optional.empty());
    }

    /** A failed or timed-out client probe; ignored once a result is known. */
    public NodeVersionState withClientFailure(ErrorCode code, Instant at) {
        if (client.isPresent()) {
            return this;
        }
        return new NodeVersionState(setting, detected, detectionFailure, observed, stale,
            pinContradiction, client, Optional.of(new Timed<>(code, at)));
    }

    /**
     * The version reads use. On {@code AUTO} the more recent of detection and observation (an
     * observation wins a tie). Otherwise the observation, or without one the pin: a concrete
     * version, {@code V8_1} as 8.1 without patch, and nothing for a major-only line such as
     * {@code V9_X}, which gives no major.minor and counts as unknown (data-model.md →
     * CapabilityMatrix). Detections are ignored for a non-{@code AUTO} setting (FR-001).
     */
    public Optional<InubitVersion> effectiveVersion() {
        Optional<InubitVersion> observedVersion = observed.map(Timed::value);
        return switch (setting) {
            case VersionSetting.Auto auto -> latest().map(Timed::value);
            case VersionSetting.Pinned pinned -> observedVersion.or(() -> Optional.of(
                pinned.version()));
            case VersionSetting.Line line -> observedVersion.or(() -> lineVersion(line));
        };
    }

    private static Optional<InubitVersion> lineVersion(VersionSetting.Line line) {
        OptionalInt minor = line.minor();
        return minor.isPresent()
            ? Optional.of(new InubitVersion(line.major(), minor.getAsInt(), OptionalInt.empty(),
                line.major() + "." + minor.getAsInt()))
            : Optional.empty();
    }

    /**
     * Whether the gate has to detect before a call with {@code purpose}: only on {@code AUTO},
     * when no version is known, or for a write when the version is stale or older than
     * {@code bound}; never within {@link #RETRY_AFTER} after a failed detection.
     */
    public boolean needsDetection(Capability.Kind purpose, Instant now, Duration bound) {
        if (!(setting instanceof VersionSetting.Auto) || inBackOff(detectionFailure, now)) {
            return false;
        }
        Optional<Timed<InubitVersion>> latest = latest();
        return latest.isEmpty()
            || purpose == Capability.Kind.WRITE && outdated(latest.get(), now, bound);
    }

    /**
     * The version a write may rely on. On {@code AUTO} a stale version or one older than
     * {@code bound} counts as unknown: the gate re-detects first, so such a version is left only
     * when that re-detection failed or was suppressed by the back-off. Otherwise
     * {@link #effectiveVersion()}; the pin contradiction is judged separately.
     */
    public Optional<InubitVersion> writableVersion(Instant now, Duration bound) {
        if (!(setting instanceof VersionSetting.Auto)) {
            return effectiveVersion();
        }
        return latest().filter(version -> !outdated(version, now, bound)).map(Timed::value);
    }

    /** Whether the client should be probed: no result yet and no failure within 60 s. */
    public boolean needsClientProbe(Instant now) {
        return client.isEmpty() && !inBackOff(clientFailure, now);
    }

    /**
     * The more recent of {@code detected} and {@code observed} (an observation wins a tie),
     * whatever the setting.
     */
    public Optional<Timed<InubitVersion>> latest() {
        if (detected.isEmpty()) {
            return observed;
        }
        if (observed.isEmpty()) {
            return detected;
        }
        return observed.get().at().isBefore(detected.get().at()) ? detected : observed;
    }

    private boolean outdated(Timed<InubitVersion> version, Instant now, Duration bound) {
        return stale || now.isAfter(version.at().plus(bound));
    }

    private static boolean inBackOff(Optional<? extends Timed<?>> failure, Instant now) {
        return failure.isPresent() && now.isBefore(failure.get().at().plus(RETRY_AFTER));
    }

    /**
     * Whether {@code observedVersion} contradicts {@code setting} (FR-012): a different major, or
     * a different minor for a line with minor or a concrete pin; never for {@code AUTO}.
     */
    public static boolean contradicts(VersionSetting setting, InubitVersion observedVersion) {
        return switch (setting) {
            case VersionSetting.Auto auto -> false;
            case VersionSetting.Line line -> observedVersion.major() != line.major()
                || line.minor().isPresent() && observedVersion.minor() != line.minor().getAsInt();
            case VersionSetting.Pinned pinned -> !pinned.version().sameMinor(observedVersion);
        };
    }
}
