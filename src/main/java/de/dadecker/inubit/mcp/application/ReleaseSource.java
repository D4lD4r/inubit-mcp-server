package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.SemVer;
import java.util.Objects;

/**
 * Where the latest published release of this server comes from (feature 016, research R-1). One
 * call is one request; the application layer sees only the version.
 */
@FunctionalInterface
public interface ReleaseSource {

    /**
     * The latest published, non-draft, non-pre-release release.
     *
     * @throws ReleaseCheckException on any failure (network, HTTP status, unreadable answer)
     */
    LatestRelease latest() throws ReleaseCheckException;

    /** The answer of a successful lookup. */
    record LatestRelease(SemVer version) {

        public LatestRelease {
            Objects.requireNonNull(version, "version");
        }
    }

    /** A failed lookup; the message is a short reason without response content. */
    final class ReleaseCheckException extends Exception {

        private static final long serialVersionUID = 1L;

        public ReleaseCheckException(String reason) {
            super(reason, null, false, false);
        }
    }
}
