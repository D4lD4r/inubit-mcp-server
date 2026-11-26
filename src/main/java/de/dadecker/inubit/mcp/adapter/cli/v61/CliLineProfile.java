package de.dadecker.inubit.mcp.adapter.cli.v61;

import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import java.util.Objects;

/**
 * What tells apart the INUBIT version lines that run on the 6.1 StartCLI adapters (feature 025,
 * spec FR-024): everything else of these adapters is the 6.1 behaviour.
 *
 * @param release                   the version line the texts name, e.g. "6.1" in "INUBIT 6.1
 *                                  has no readiness check"
 * @param unavailableReason         the reason code of the health parts the line does not offer
 *                                  (readiness, threads, blocking queue)
 * @param unameReportsServerVersion whether {@code uname} reports the server's version rather than
 *                                  the StartCLI client's
 */
public record CliLineProfile(String release, String unavailableReason,
    boolean unameReportsServerVersion) {

    /**
     * INUBIT 6.1 (feature 012). {@code uname} counts as the client's version (FR-006, research
     * D-6): decided by the recordings (T037); until they confirm the server version, the version
     * counts as the client's.
     */
    public static final CliLineProfile V61 =
        new CliLineProfile("6.1", UnavailablePart.NOT_AVAILABLE_ON_6_1, false);

    public CliLineProfile {
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(unavailableReason, "unavailableReason");
    }
}
