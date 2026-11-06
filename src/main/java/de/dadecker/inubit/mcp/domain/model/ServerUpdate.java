package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of the last successful update check (feature 016, data-model.md → ServerUpdate),
 * shown as {@code get_health.serverUpdate}.
 *
 * @param currentVersion  the running version
 * @param latestVersion   the version of the latest release
 * @param updateAvailable whether {@code latestVersion} is higher than {@code currentVersion}
 * @param releaseUrl      the release page of {@code latestVersion}
 * @param checkedAt       when the check ran
 * @param notice          the manual-update notice; present exactly when {@code updateAvailable}
 */
public record ServerUpdate(String currentVersion, String latestVersion, boolean updateAvailable,
    String releaseUrl, Instant checkedAt, Optional<String> notice) {

    /** The release pages of this project; the tag is {@code v<version>}. */
    public static final String RELEASES = "https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v";

    public ServerUpdate {
        Objects.requireNonNull(currentVersion, "currentVersion");
        Objects.requireNonNull(latestVersion, "latestVersion");
        Objects.requireNonNull(releaseUrl, "releaseUrl");
        Objects.requireNonNull(checkedAt, "checkedAt");
        Objects.requireNonNull(notice, "notice");
        if (notice.isPresent() != updateAvailable) {
            throw new IllegalArgumentException("A notice belongs exactly to an available update");
        }
    }

    /** The status for the running {@code current} version and the {@code latest} release. */
    public static ServerUpdate of(SemVer current, SemVer latest, Instant checkedAt) {
        boolean available = latest.compareTo(current) > 0;
        String url = RELEASES + latest;
        Optional<String> notice = available
            ? Optional.of("inubit-mcp-server " + latest + " is available (running " + current
                + "): " + url + ". Update manually: download the JAR and its .sha256 file, check"
                + " the checksum, replace the JAR the MCP registration runs, restart the client.")
            : Optional.empty();
        return new ServerUpdate(current.toString(), latest.toString(), available, url, checkedAt,
            notice);
    }
}
