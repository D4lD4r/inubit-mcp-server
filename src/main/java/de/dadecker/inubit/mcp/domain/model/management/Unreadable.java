package de.dadecker.inubit.mcp.domain.model.management;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import java.util.Objects;
import java.util.Optional;

/**
 * An item of the index that could not be read (data-model.md → Unreadable, research D-13).
 *
 * @param kind          what could not be read
 * @param name          the system diagram or element name; empty exactly for
 *                      {@link Kind#DIAGRAM_LIST}
 * @param systemDiagram the element's system diagram; present exactly for {@link Kind#ELEMENT}
 * @param nodeId        the element's node id, 1 to 10 digits; present exactly for
 *                      {@link Kind#ELEMENT}
 * @param code          the error code
 * @param message       at most {@value ItemBounds#MAX_MESSAGE_CHARS} characters, no excerpt of an
 *                      INUBIT answer
 */
public record Unreadable(Kind kind, Optional<String> name, Optional<DiagramRef> systemDiagram,
    Optional<String> nodeId, ErrorCode code, String message) {

    /** What could not be read. */
    public enum Kind {
        /** The owner's diagram list. */
        DIAGRAM_LIST,
        /** The element list of one system diagram. */
        SYSTEM_DIAGRAM,
        /** The detail of one external system element. */
        ELEMENT
    }

    public Unreadable {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(systemDiagram, "systemDiagram");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(code, "code");
        Reason.requireMessage(message, "message");
        if (name.isPresent() == (kind == Kind.DIAGRAM_LIST)) {
            throw new IllegalArgumentException("a name is present exactly when the kind is not "
                + "DIAGRAM_LIST, here " + kind);
        }
        if (systemDiagram.isPresent() != (kind == Kind.ELEMENT)) {
            throw new IllegalArgumentException("a systemDiagram is present exactly when the kind "
                + "is ELEMENT, here " + kind);
        }
        if (nodeId.isPresent() != (kind == Kind.ELEMENT)) {
            throw new IllegalArgumentException("a nodeId is present exactly when the kind is "
                + "ELEMENT, here " + kind);
        }
        if (nodeId.isPresent() && !ExternalSystem.NODE_ID.matcher(nodeId.get()).matches()) {
            throw new IllegalArgumentException("nodeId must have 1 to 10 digits");
        }
    }
}
