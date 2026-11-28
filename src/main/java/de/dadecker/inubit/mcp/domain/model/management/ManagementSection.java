package de.dadecker.inubit.mcp.domain.model.management;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a system diagram manages one module on one server (data-model.md → ManagementSection,
 * contracts/management-section.md §1).
 *
 * <p>State rules: {@code elements} is non-empty exactly for {@link Status#MANAGED};
 * {@code reason} is present exactly for {@link Status#UNKNOWN}; {@link Status#NOT_MANAGED} is
 * always {@code complete} and so has no {@code firstNotRead}.
 *
 * @param status                the answer
 * @param complete              the index's {@link ManagementIndex#complete()} when an index was
 *                              had, else false
 * @param owner                 the owner whose system diagrams were checked; empty only for
 *                              {@code NOT_CONFIGURED}
 * @param collectedAt           when the index was collected; present exactly when an index was had
 * @param systemDiagramsChecked the system diagrams read completely
 * @param systemDiagramsNotRead the index's {@link ManagementIndex#systemDiagramsNotRead()}
 * @param firstNotRead          the index's first unreadable item; present exactly when an index
 *                              was had and it is not complete
 * @param elements              the managing elements, at most {@value #MAX_ELEMENTS}
 * @param warnings              warnings of the check and of the version gate
 * @param reason                why the answer is unknown
 * @param truncated             true if {@code elements} or a list inside was cut
 */
public record ManagementSection(Status status, boolean complete, Optional<String> owner,
    Optional<Instant> collectedAt, int systemDiagramsChecked, int systemDiagramsNotRead,
    Optional<Unreadable> firstNotRead, List<ManagingElement> elements, List<String> warnings,
    Optional<Reason> reason, boolean truncated) {

    /** The most managing elements of one section; the matcher cuts and sets {@code truncated}. */
    public static final int MAX_ELEMENTS = 10;

    /** The answer for the module. */
    public enum Status {
        MANAGED,
        NOT_MANAGED,
        UNKNOWN
    }

    public ManagementSection {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(collectedAt, "collectedAt");
        Objects.requireNonNull(firstNotRead, "firstNotRead");
        elements = List.copyOf(elements);
        warnings = List.copyOf(warnings);
        Objects.requireNonNull(reason, "reason");
        if (systemDiagramsChecked < 0) {
            throw new IllegalArgumentException("systemDiagramsChecked must not be negative");
        }
        if (systemDiagramsNotRead < 0) {
            throw new IllegalArgumentException("systemDiagramsNotRead must not be negative");
        }
        if (elements.isEmpty() == (status == Status.MANAGED)) {
            throw new IllegalArgumentException(
                "elements are non-empty exactly when MANAGED, here " + status);
        }
        if (elements.size() > MAX_ELEMENTS) {
            throw new IllegalArgumentException("more than " + MAX_ELEMENTS + " elements");
        }
        if (reason.isPresent() != (status == Status.UNKNOWN)) {
            throw new IllegalArgumentException(
                "a reason is present exactly when UNKNOWN, here " + status);
        }
        if (complete && collectedAt.isEmpty()) {
            throw new IllegalArgumentException("complete needs an index (collectedAt)");
        }
        if (complete && systemDiagramsNotRead > 0) {
            throw new IllegalArgumentException("complete has no system diagrams not read");
        }
        if (status == Status.NOT_MANAGED && !complete) {
            throw new IllegalArgumentException("NOT_MANAGED is always complete");
        }
        if (status == Status.UNKNOWN && complete) {
            throw new IllegalArgumentException("UNKNOWN is never complete");
        }
        if (status == Status.MANAGED && collectedAt.isEmpty()) {
            throw new IllegalArgumentException("MANAGED needs an index (collectedAt)");
        }
        if (owner.isEmpty()
            && reason.map(r -> r.code() != ErrorCode.NOT_CONFIGURED).orElse(true)) {
            throw new IllegalArgumentException("owner is empty only for NOT_CONFIGURED");
        }
        if (firstNotRead.isPresent() != (collectedAt.isPresent() && !complete)) {
            throw new IllegalArgumentException(
                "firstNotRead is present exactly when an index was had and it is not complete");
        }
    }
}
