package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.SemVer;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The local cache of the last successful update check (feature 016, FR-018, FR-019). It carries the
 * result to the next start's handshake and remembers a version the operator does not want to be
 * reminded of. Implementations never throw: a cache that cannot be read is empty, a failed write
 * is logged and forgotten.
 */
public interface UpdateCache {

    /** A cache that stores nothing. */
    UpdateCache NONE = new UpdateCache() {
        @Override
        public Optional<CachedRelease> read() {
            return Optional.empty();
        }

        @Override
        public void write(SemVer latestVersion, Instant checkedAt) {
        }

        @Override
        public void dismiss(SemVer version) {
        }
    };

    Optional<CachedRelease> read();

    /** Stores the result of a successful check; keeps a dismissed version. */
    void write(SemVer latestVersion, Instant checkedAt);

    /** Remembers that the operator does not want to be reminded of {@code version} again. */
    void dismiss(SemVer version);

    /**
     * The cache content.
     *
     * @param latestVersion    the latest release of the last successful check
     * @param checkedAt        when that check ran
     * @param dismissedVersion the version the operator asked not to be reminded of
     */
    record CachedRelease(SemVer latestVersion, Instant checkedAt, Optional<SemVer> dismissedVersion) {

        public CachedRelease {
            Objects.requireNonNull(latestVersion, "latestVersion");
            Objects.requireNonNull(checkedAt, "checkedAt");
            Objects.requireNonNull(dismissedVersion, "dismissedVersion");
        }
    }
}
