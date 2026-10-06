package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The result of a writing call once something may have been sent (feature 004, research D-10,
 * D-25, data-model.md → WriteOutcome): what was created and modified, the commit and the
 * backup, or, on failure, what failed, at which step, and the state of the rollback. Artifacts a
 * failed call created cannot be removed (nothing is ever deleted); they are listed.
 *
 * @param failure           {@code IMPORT_FAILED} or {@code VERIFY_MISMATCH}, the step and why
 * @param commit            the history entry of the verified state (on success)
 * @param backupRef         the audit id whose backup holds the state before the call
 * @param rollback          absent on success
 * @param reports           workspace-relative report files (differences, protocols)
 */
public record WriteOutcome(UUID auditId, Outcome outcome, Optional<Failure> failure,
    Optional<String> commit, Optional<String> backupRef, List<String> created,
    List<String> modified, List<String> notImported, Optional<Rollback> rollback,
    List<String> createdNotRemoved, List<String> reports, List<String> warnings) {

    /** The call's outcome. */
    public enum Outcome {
        EXECUTED,
        FAILED
    }

    /** What the rollback did. */
    public enum Rollback {
        NOT_NEEDED,
        SUCCEEDED,
        FAILED
    }

    /** What failed, at which step ({@code import}, {@code protocol}, {@code verify}). */
    public record Failure(ErrorCode code, String step, String message) {
        public Failure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(message, "message");
        }
    }

    public WriteOutcome {
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(outcome, "outcome");
        failure = failure == null ? Optional.empty() : failure;
        commit = commit == null ? Optional.empty() : commit;
        backupRef = backupRef == null ? Optional.empty() : backupRef;
        created = List.copyOf(created);
        modified = List.copyOf(modified);
        notImported = List.copyOf(notImported);
        rollback = rollback == null ? Optional.empty() : rollback;
        createdNotRemoved = List.copyOf(createdNotRemoved);
        reports = List.copyOf(reports);
        warnings = List.copyOf(warnings);
        if ((outcome == Outcome.FAILED) != failure.isPresent()) {
            throw new IllegalArgumentException("A failed outcome, and only it, has a failure");
        }
    }
}
