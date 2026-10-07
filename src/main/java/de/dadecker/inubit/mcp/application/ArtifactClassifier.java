package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ArtifactClass;
import de.dadecker.inubit.mcp.domain.port.ReleaseArchivePort;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;

/**
 * The class of one release artifact on one target node (feature 005, FR-010, research D-5),
 * from the rendered files (placeholders compared as placeholders): {@code NEW} if the target
 * has none, {@code UNCHANGED} if every file has the same reviewed content
 * ({@link ReleaseArchivePort#equivalent}: check-in comments, UIDs and a workflow's active flag
 * do not count), {@code LAYOUT_ONLY} for a workflow that differs only in layout, otherwise
 * {@code CHANGED}. Exclusions and {@code ONLY_ON_TARGET} are decided by the planner.
 */
final class ArtifactClassifier {

    private final ReleaseArchivePort releases;

    ArtifactClassifier(ReleaseArchivePort releases) {
        this.releases = Objects.requireNonNull(releases, "releases");
    }

    /** A workflow file; {@code target} {@code null} if the node has none at this path. */
    ArtifactClass workflow(String path, byte[] release, byte[] target) {
        if (target == null) {
            return ArtifactClass.NEW;
        }
        if (releases.equivalent(path, release, target)) {
            return ArtifactClass.UNCHANGED;
        }
        return releases.layoutOnly(path, release, target) ? ArtifactClass.LAYOUT_ONLY
            : ArtifactClass.CHANGED;
    }

    /**
     * A module: its files (index entry, configuration, embedded documents) by workspace path;
     * {@code target} empty if the node has no module of this name and plugin type.
     */
    ArtifactClass module(SortedMap<String, byte[]> release, SortedMap<String, byte[]> target) {
        if (target.isEmpty()) {
            return ArtifactClass.NEW;
        }
        if (!release.keySet().equals(target.keySet())) {
            return ArtifactClass.CHANGED;
        }
        for (Map.Entry<String, byte[]> file : release.entrySet()) {
            if (!releases.equivalent(file.getKey(), file.getValue(),
                target.get(file.getKey()))) {
                return ArtifactClass.CHANGED;
            }
        }
        return ArtifactClass.UNCHANGED;
    }

    /** A repository file: its content byte for byte; {@code target} {@code null} if missing. */
    ArtifactClass repository(byte[] release, byte[] target) {
        if (target == null) {
            return ArtifactClass.NEW;
        }
        return Arrays.equals(release, target) ? ArtifactClass.UNCHANGED : ArtifactClass.CHANGED;
    }
}
