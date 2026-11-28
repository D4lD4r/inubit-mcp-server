package de.dadecker.inubit.mcp.domain.model.management;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The modules the external systems of one system diagram manage (data-model.md →
 * SystemDiagramManagement, contracts/management-section.md §2, FR-010, research D-8).
 *
 * @param elements              one entry per external system element in INUBIT's order, at most
 *                              {@value #MAX_ELEMENTS}; empty exactly when {@code unavailable}
 * @param moduleListUnavailable why the owner's module list is not at hand; present exactly when
 *                              no cached module list exists and not {@code unavailable}; then no
 *                              linked name has a {@code found} mark
 * @param unavailable           why there is no usable index for this diagram: the version gate
 *                              refused, the budget was reached ({@code TIMEOUT}), the index's
 *                              {@code DIAGRAM_LIST} item, the diagram is an unreadable
 *                              {@code SYSTEM_DIAGRAM} item, or the diagram is missing from a
 *                              cached index ({@code NOT_FOUND})
 * @param collectedAt           when the index was collected; present exactly when an index was
 *                              had (also with a failed diagram list)
 * @param warnings              the version gate's warnings, if any
 * @param truncated             true if {@code elements} or a list inside was cut
 */
public record SystemDiagramManagement(List<ElementEntry> elements,
    Optional<Reason> moduleListUnavailable, Optional<Reason> unavailable,
    Optional<Instant> collectedAt, List<String> warnings, boolean truncated) {

    /** The most entries of one system diagram; the caller cuts and sets {@code truncated}. */
    public static final int MAX_ELEMENTS = 50;

    public SystemDiagramManagement {
        elements = List.copyOf(elements);
        Objects.requireNonNull(moduleListUnavailable, "moduleListUnavailable");
        Objects.requireNonNull(unavailable, "unavailable");
        Objects.requireNonNull(collectedAt, "collectedAt");
        warnings = List.copyOf(warnings);
        if (elements.size() > MAX_ELEMENTS) {
            throw new IllegalArgumentException("more than " + MAX_ELEMENTS + " elements");
        }
        if (elements.isEmpty() != unavailable.isPresent()) {
            throw new IllegalArgumentException("elements are empty exactly when unavailable");
        }
        if (unavailable.isPresent() && moduleListUnavailable.isPresent()) {
            throw new IllegalArgumentException(
                "an unavailable detail has no moduleListUnavailable");
        }
        if (unavailable.isEmpty() && collectedAt.isEmpty()) {
            throw new IllegalArgumentException("an available detail comes from an index "
                + "(collectedAt)");
        }
        boolean marksExpected = moduleListUnavailable.isEmpty();
        boolean marksMatch = elements.stream()
            .flatMap(entry -> entry.linked().orElse(List.of()).stream())
            .allMatch(module -> module.found().isPresent() == marksExpected);
        if (!marksMatch) {
            throw new IllegalArgumentException(
                "linked names have a found mark exactly when the module list is available");
        }
    }
}
