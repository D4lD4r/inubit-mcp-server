package de.dadecker.inubit.mcp.domain.model.management;

import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One external system element in the detail of a system diagram (data-model.md → ElementEntry):
 * either readable, with settings and linked names, or an error.
 *
 * @param element       the element's name
 * @param nodeId        the element's node id
 * @param connectorType the element's {@code ResourceType}, when shown; empty with {@code error}
 * @param settings      the element's settings, possibly empty; an empty Optional exactly with
 *                      {@code error}
 * @param linked        the linked names, at most {@value #MAX_LINKED}, possibly empty; an empty
 *                      Optional exactly with {@code error}
 * @param error         why the element's detail could not be read
 */
public record ElementEntry(String element, String nodeId, Optional<String> connectorType,
    Optional<List<ModuleDetail.Property>> settings, Optional<List<LinkedModule>> linked,
    Optional<Reason> error) {

    /** The most linked names of one element; the caller cuts and sets {@code truncated}. */
    public static final int MAX_LINKED = 100;

    public ElementEntry {
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(connectorType, "connectorType");
        settings = Objects.requireNonNull(settings, "settings").map(List::copyOf);
        linked = Objects.requireNonNull(linked, "linked").map(List::copyOf);
        Objects.requireNonNull(error, "error");
        if (error.isPresent()) {
            if (connectorType.isPresent() || settings.isPresent() || linked.isPresent()) {
                throw new IllegalArgumentException(
                    "an entry with an error has no connectorType, settings or linked names");
            }
        } else {
            if (settings.isEmpty()) {
                throw new IllegalArgumentException("a readable entry has settings");
            }
            if (linked.isEmpty()) {
                throw new IllegalArgumentException("a readable entry has linked names");
            }
        }
        if (linked.filter(names -> names.size() > MAX_LINKED).isPresent()) {
            throw new IllegalArgumentException("more than " + MAX_LINKED + " linked names");
        }
    }
}
