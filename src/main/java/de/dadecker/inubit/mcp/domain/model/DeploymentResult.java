package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The result of a confirmed deployment (feature 005, FR-014–FR-020, data-model.md →
 * DeploymentResult, contracts/mcp-tools-delta.md): the state of every target node — also of the
 * nodes that were not started — the backup of every node that was written, the workspace commit
 * if every node is deployed or unchanged, reports and warnings.
 *
 * @param commit the workspace history entry, if one was written
 */
public record DeploymentResult(UUID auditId, GroupId target, GroupId source, String tag,
    Outcome outcome, List<NodeOutcome> nodes, Optional<String> commit, List<String> reports,
    List<String> warnings) {

    /** The outcome of the whole call. */
    public enum Outcome {
        EXECUTED, FAILED, PACKAGED
    }

    /** Where one node stands after the call. */
    public enum State {
        /** Imported and verified. */
        DEPLOYED,
        /** Nothing to import (every artifact equal); only the tag. */
        UNCHANGED,
        /** A failure after the import started; the backup was re-imported and verified. */
        ROLLED_BACK,
        /** A failure, and the rollback failed as well; the backup is kept. */
        ROLLBACK_FAILED,
        /** Not written: an earlier node failed, or the node changed since the preview. */
        NOT_STARTED,
        /** Package-only target: a package was written instead of an import. */
        PACKAGED
    }

    /**
     * The outcome of one node.
     *
     * @param backupRef the backup of the node, if it was written to
     * @param imported  the artifacts sent to the node (workflows, modules, repository paths)
     * @param created   the artifacts the node did not have before (never removed)
     * @param tag       the tagging after a verified deployment
     * @param failure   what failed ({@code code}, {@code step}, {@code message})
     * @param packageDir the package of a package-only node
     */
    public record NodeOutcome(NodeId node, State state, Optional<String> backupRef,
        List<String> imported, List<String> created, Optional<WriteOutcome.TagResult> tag,
        Optional<WriteOutcome.Failure> failure, Optional<String> packageDir) {
        public NodeOutcome {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(state, "state");
            backupRef = backupRef == null ? Optional.empty() : backupRef;
            imported = List.copyOf(imported);
            created = List.copyOf(created);
            tag = tag == null ? Optional.empty() : tag;
            failure = failure == null ? Optional.empty() : failure;
            packageDir = packageDir == null ? Optional.empty() : packageDir;
        }

        /** A node that was not written. */
        public static NodeOutcome notStarted(NodeId node,
            Optional<WriteOutcome.Failure> failure) {
            return new NodeOutcome(node, State.NOT_STARTED, Optional.empty(), List.of(),
                List.of(), Optional.empty(), failure, Optional.empty());
        }
    }

    public DeploymentResult {
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(outcome, "outcome");
        nodes = List.copyOf(nodes);
        commit = commit == null ? Optional.empty() : commit;
        reports = List.copyOf(reports);
        warnings = List.copyOf(warnings);
    }
}
