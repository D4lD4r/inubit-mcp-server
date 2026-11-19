package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The result of a confirmed change (feature 022, data-model.md → ChangeResult, research D-14).
 * INUBIT's answer and the states are masked by {@link SecretTextMasker#mask}; the answer is then
 * cut to {@value #MAX_ANSWER_CHARS} characters.
 *
 * @param target        as in the {@link ChangePreview}
 * @param inubitAnswer  INUBIT's answer, ending with {@link ItemBounds#TRUNCATION_MARKER} when cut
 * @param before        the state read right before sending
 * @param after         the state read again after the change; empty if the re-read failed
 * @param reReadFailed  why the re-read failed; present exactly when {@code after} is empty
 * @param affectedCount the number INUBIT reports, for bulk actions
 * @param backup        what was stored before the change, if anything
 * @param auditId       the id of the change's audit records
 */
public record ChangeResult(NodeId node, String tool, String action, String target,
    ChangeOutcome outcome, String before, String inubitAnswer, Optional<String> after,
    Optional<String> reReadFailed, OptionalInt affectedCount, Optional<Backup> backup,
    RestartNeeded restartNeeded, UUID auditId) {

    /** The largest length of {@code inubitAnswer}, the truncation marker included. */
    public static final int MAX_ANSWER_CHARS = ItemBounds.MAX_MESSAGE_CHARS;

    /** Whether INUBIT must be restarted for the change to take effect. */
    public enum RestartNeeded {
        TRUE,
        FALSE,
        UNKNOWN
    }

    /** A stored backup: its manifest's path and id. */
    public record Backup(String path, String manifestId) {

        public Backup {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(manifestId, "manifestId");
        }
    }

    public ChangeResult {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(outcome, "outcome");
        before = SecretTextMasker.mask(before);
        inubitAnswer = cut(SecretTextMasker.mask(inubitAnswer));
        after = after == null ? Optional.empty() : after.map(SecretTextMasker::mask);
        reReadFailed = reReadFailed == null ? Optional.empty()
            : reReadFailed.map(SecretTextMasker::mask);
        if (after.isPresent() == reReadFailed.isPresent()) {
            throw new IllegalArgumentException(
                "a change result has either an after state or a re-read failure");
        }
        if (reReadFailed.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("a re-read failure needs a reason");
        }
        affectedCount = affectedCount == null ? OptionalInt.empty() : affectedCount;
        backup = backup == null ? Optional.empty() : backup;
        Objects.requireNonNull(restartNeeded, "restartNeeded");
        Objects.requireNonNull(auditId, "auditId");
    }

    /** {@code text} cut to {@link #MAX_ANSWER_CHARS} with the marker; a pair is never split. */
    private static String cut(String text) {
        if (text.length() <= MAX_ANSWER_CHARS) {
            return text;
        }
        int end = MAX_ANSWER_CHARS - ItemBounds.TRUNCATION_MARKER.length();
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + ItemBounds.TRUNCATION_MARKER;
    }
}
