package de.dadecker.inubit.mcp.domain.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a deployment would do on one target node (feature 005, FR-010–FR-013, research D-5,
 * data-model.md → NodePlan): every release artifact with its class, the resulting active flag of
 * each workflow, warnings, errors, the node's state fingerprint and the report files. A plan is
 * executable iff it has no error.
 *
 * @param targetFingerprint the node's state as the preview saw it (research D-6)
 * @param artifactStates    {@link #key artifact key} → fingerprint of the node's current
 *                          version of a release artifact it has (for the deployment ledger,
 *                          research D-8)
 * @param diffFile          workspace-relative difference file, placeholders only
 * @param summaryFile       workspace-relative summary file
 */
public record NodePlan(NodeId node, List<PlannedArtifact> artifacts, List<Warning> warnings,
    List<PlanError> errors, String targetFingerprint, Map<String, String> artifactStates,
    String diffFile, String summaryFile) {

    /** What a planned artifact is. */
    public enum Kind {
        WORKFLOW, MODULE, REPOSITORY_FILE
    }

    /** What a warning is about (names only; values are in the difference file). */
    public enum WarningKind {
        /** A changed property whose name or value looks like a host, URL, port or login. */
        STAGE_SPECIFIC_VALUE,
        /** A changed module that target workflows outside the release use as well. */
        SHARED_MODULE,
        /** Changed on the target since the last deployment of this server (or none known). */
        OUTSIDE_CHAIN,
        /** A tagged version older than head on the source (release level). */
        OLDER_THAN_HEAD,
        /** A referenced repository file outside {@code /Root/<owner>/}: never deployed. */
        OUTSIDE_OWNER_REPOSITORY
    }

    /**
     * One release artifact on this node.
     *
     * @param name   workflow or module name, or the repository path
     * @param group  the diagram group of a workflow, the plugin type of a module
     * @param active the resulting {@code IsActive} of a workflow that is not excluded
     * @param kept   true if {@code active} is the target's own flag (an existing workflow)
     */
    public record PlannedArtifact(Kind kind, String name, Optional<String> group,
        ArtifactClass artifactClass, Optional<Boolean> active, boolean kept) {
        public PlannedArtifact {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
            group = group == null ? Optional.empty() : group;
            Objects.requireNonNull(artifactClass, "artifactClass");
            active = active == null ? Optional.empty() : active;
        }
    }

    /** A warning about one artifact; {@code detail} names, never values. */
    public record Warning(WarningKind kind, String artifact, String detail) {
        public Warning {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** An error that makes the plan not executable. */
    public record PlanError(ErrorCode code, String artifact, String message) {
        public PlanError {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(message, "message");
        }
    }

    public NodePlan {
        Objects.requireNonNull(node, "node");
        artifacts = List.copyOf(artifacts);
        warnings = List.copyOf(warnings);
        errors = List.copyOf(errors);
        Objects.requireNonNull(targetFingerprint, "targetFingerprint");
        artifactStates = Map.copyOf(artifactStates);
        Objects.requireNonNull(diffFile, "diffFile");
        Objects.requireNonNull(summaryFile, "summaryFile");
    }

    /** True if the plan has no error. */
    public boolean executable() {
        return errors.isEmpty();
    }

    /** The number of artifacts per class (every class, zero included). */
    public Map<ArtifactClass, Integer> counts() {
        Map<ArtifactClass, Integer> counts = new EnumMap<>(ArtifactClass.class);
        for (ArtifactClass artifactClass : ArtifactClass.values()) {
            counts.put(artifactClass, 0);
        }
        artifacts.forEach(artifact -> counts.merge(artifact.artifactClass(), 1, Integer::sum));
        return counts;
    }

    /** This plan with more warnings and errors (release-level findings, ledger warnings). */
    public NodePlan with(List<Warning> moreWarnings, List<PlanError> moreErrors) {
        List<Warning> allWarnings = new java.util.ArrayList<>(warnings);
        allWarnings.addAll(moreWarnings);
        List<PlanError> allErrors = new java.util.ArrayList<>(errors);
        allErrors.addAll(moreErrors);
        return new NodePlan(node, artifacts, allWarnings, allErrors, targetFingerprint,
            artifactStates, diffFile, summaryFile);
    }

    /**
     * The key of an artifact in the ledger: {@code workflow:<diagram group>/<name>},
     * {@code module:<plugin type>/<name>} or {@code repository:<path>}.
     */
    public static String key(PlannedArtifact artifact) {
        return switch (artifact.kind()) {
            case WORKFLOW -> "workflow:" + artifact.group().orElse("") + "/" + artifact.name();
            case MODULE -> "module:" + artifact.group().orElse("") + "/" + artifact.name();
            case REPOSITORY_FILE -> "repository:" + artifact.name();
        };
    }
}
