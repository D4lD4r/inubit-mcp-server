package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A part of a health report that could not be obtained, with the reason (FR-008).
 *
 * @param part        one of {@link #PARTS}
 * @param reason      starts with a code, e.g. {@code METRICS_NOT_LICENSED: …} or
 *                    {@code TIMEOUT: …}
 * @param likelyCause the likely cause, copied from the {@link ToolError} (Phase 3 review m1)
 * @param nextStep    the suggested next step, copied from the {@link ToolError}
 */
public record UnavailablePart(String part, String reason, Optional<String> likelyCause,
    Optional<String> nextStep) {

    public static final String STATUS = "status";
    public static final String READY = "ready";
    public static final String VERSION = "version";
    public static final String SYSTEM_INFO = "systemInfo";
    public static final String LOAD = "load";
    public static final Set<String> PARTS = Set.of(STATUS, READY, VERSION, SYSTEM_INFO, LOAD);

    /** Reason code of a {@code /metrics} failure that indicates a missing license (R-10). */
    public static final String METRICS_NOT_LICENSED = "METRICS_NOT_LICENSED";

    public UnavailablePart {
        if (!PARTS.contains(part)) {
            throw new IllegalArgumentException("unknown health part " + Names.quote(part));
        }
        Objects.requireNonNull(reason, "reason");
        likelyCause = likelyCause == null ? Optional.empty() : likelyCause;
        nextStep = nextStep == null ? Optional.empty() : nextStep;
    }

    /** A part with a reason only. */
    public UnavailablePart(String part, String reason) {
        this(part, reason, Optional.empty(), Optional.empty());
    }

    /** The part with {@code CODE: message} of {@code error} as reason, plus cause and step. */
    public static UnavailablePart of(String part, ToolError error) {
        return new UnavailablePart(part, error.code() + ": " + error.message(),
            Optional.of(error.likelyCause()), Optional.of(error.nextStep()));
    }
}
