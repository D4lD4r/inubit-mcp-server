package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The result of {@code tag_artifacts} once a tag command may have been sent (feature 004,
 * research D-16, D-26, data-model.md → TagOutcome): the tag, the diagram groups, how many
 * workflows and modules carry it on their current version, or, on failure, what failed. A tag
 * is never removed again.
 *
 * @param failure   {@code IMPORT_FAILED} (a tag command failed) or {@code VERIFY_MISMATCH} (a
 *                  current version of the requested groups does not carry the tag)
 * @param workflows the number of technical workflows whose current version carries the tag
 * @param modules   the number of modules whose current version carries the tag
 * @param reports   workspace-relative report files
 */
public record TagOutcome(UUID auditId, WriteOutcome.Outcome outcome,
    Optional<WriteOutcome.Failure> failure, String tag, List<String> diagramGroups,
    int workflows, int modules, List<String> reports, List<String> warnings) {

    public TagOutcome {
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(outcome, "outcome");
        failure = failure == null ? Optional.empty() : failure;
        Objects.requireNonNull(tag, "tag");
        diagramGroups = List.copyOf(diagramGroups);
        reports = List.copyOf(reports);
        warnings = List.copyOf(warnings);
        if ((outcome == WriteOutcome.Outcome.FAILED) != failure.isPresent()) {
            throw new IllegalArgumentException("A failed outcome, and only it, has a failure");
        }
    }
}
