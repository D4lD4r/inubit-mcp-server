package de.dadecker.inubit.mcp.domain.model.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.management.Unreadable.Kind;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T004: an unreadable item names what it is about according to its kind. */
class UnreadableTest {

    private static final DiagramRef SD = new DiagramRef("SD-HTTP", "GRP-CONNECT");

    @Test
    void aFailedDiagramListHasNoNameAndNoSystemDiagram() {
        Unreadable item = new Unreadable(Kind.DIAGRAM_LIST, Optional.empty(), Optional.empty(),
            Optional.empty(), ErrorCode.UNREACHABLE, "connection refused");

        assertThat(item.name()).isEmpty();
        assertThat(item.systemDiagram()).isEmpty();

        assertThatIllegalArgumentException().isThrownBy(() -> new Unreadable(Kind.DIAGRAM_LIST,
            Optional.of("SD-HTTP"), Optional.empty(), Optional.empty(), ErrorCode.UNREACHABLE,
            "failed"))
            .withMessageContaining("name");
        assertThatIllegalArgumentException().isThrownBy(() -> new Unreadable(Kind.DIAGRAM_LIST,
            Optional.empty(), Optional.of(SD), Optional.empty(), ErrorCode.UNREACHABLE, "failed"))
            .withMessageContaining("systemDiagram");
    }

    @Test
    void anUnreadableSystemDiagramHasANameButNoSystemDiagram() {
        Unreadable item = new Unreadable(Kind.SYSTEM_DIAGRAM, Optional.of("SD-HTTP"),
            Optional.empty(), Optional.empty(), ErrorCode.TIMEOUT, "read timed out");

        assertThat(item.name()).contains("SD-HTTP");

        assertThatIllegalArgumentException().isThrownBy(() -> new Unreadable(
            Kind.SYSTEM_DIAGRAM, Optional.empty(), Optional.empty(), Optional.empty(),
            ErrorCode.TIMEOUT, "failed"))
            .withMessageContaining("name");
        assertThatIllegalArgumentException().isThrownBy(() -> new Unreadable(
            Kind.SYSTEM_DIAGRAM, Optional.of("SD-HTTP"), Optional.of(SD), Optional.empty(),
            ErrorCode.TIMEOUT, "failed")).withMessageContaining("systemDiagram");
        assertThatIllegalArgumentException().isThrownBy(() -> new Unreadable(
            Kind.SYSTEM_DIAGRAM, Optional.of("SD-HTTP"), Optional.empty(), Optional.of("201"),
            ErrorCode.TIMEOUT, "failed")).withMessageContaining("nodeId");
    }

    @Test
    void anUnreadableElementHasANameItsSystemDiagramAndItsNodeId() {
        Unreadable item = new Unreadable(Kind.ELEMENT, Optional.of("Central-Backend"),
            Optional.of(SD), Optional.of("201"), ErrorCode.UNEXPECTED_RESPONSE, "no node");

        assertThat(item.name()).contains("Central-Backend");
        assertThat(item.systemDiagram()).contains(SD);
        assertThat(item.nodeId()).contains("201");

        assertThatIllegalArgumentException().isThrownBy(() -> new Unreadable(Kind.ELEMENT,
            Optional.empty(), Optional.of(SD), Optional.of("201"), ErrorCode.UNEXPECTED_RESPONSE,
            "failed")).withMessageContaining("name");
        assertThatIllegalArgumentException().isThrownBy(() -> new Unreadable(Kind.ELEMENT,
            Optional.of("Central-Backend"), Optional.empty(), Optional.of("201"),
            ErrorCode.UNEXPECTED_RESPONSE, "failed")).withMessageContaining("systemDiagram");
        assertThatIllegalArgumentException().isThrownBy(() -> new Unreadable(Kind.ELEMENT,
            Optional.of("Central-Backend"), Optional.of(SD), Optional.empty(),
            ErrorCode.UNEXPECTED_RESPONSE, "failed")).withMessageContaining("nodeId");
    }

    @Test
    void theNodeIdHasOneToTenDigits() {
        assertThat(element("1").nodeId()).contains("1");
        assertThat(element("1234567890").nodeId()).contains("1234567890");

        for (String bad : new String[] {"", "12345678901", "20a", " 201", "-1"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> element(bad))
                .withMessageContaining("nodeId");
        }
    }

    private static Unreadable element(String nodeId) {
        return new Unreadable(Kind.ELEMENT, Optional.of("Central-Backend"), Optional.of(SD),
            Optional.of(nodeId), ErrorCode.UNEXPECTED_RESPONSE, "no node");
    }

    @Test
    void theMessageIsBounded() {
        String bound = "m".repeat(ItemBounds.MAX_MESSAGE_CHARS);
        assertThat(new Unreadable(Kind.DIAGRAM_LIST, Optional.empty(), Optional.empty(),
            Optional.empty(), ErrorCode.INTERNAL, bound).message()).hasSize(ItemBounds.MAX_MESSAGE_CHARS);

        assertThatIllegalArgumentException().isThrownBy(() -> new Unreadable(Kind.DIAGRAM_LIST,
            Optional.empty(), Optional.empty(), Optional.empty(), ErrorCode.INTERNAL, bound + "m"))
            .withMessageContaining("message");
    }

    @Test
    void everyFieldIsRequired() {
        Optional<String> none = Optional.empty();
        Optional<DiagramRef> noSd = Optional.empty();
        assertThatNullPointerException().isThrownBy(() -> new Unreadable(null, none, noSd, none,
            ErrorCode.INTERNAL, "failed")).withMessage("kind");
        assertThatNullPointerException().isThrownBy(() -> new Unreadable(Kind.DIAGRAM_LIST,
            null, noSd, none, ErrorCode.INTERNAL, "failed")).withMessage("name");
        assertThatNullPointerException().isThrownBy(() -> new Unreadable(Kind.DIAGRAM_LIST,
            none, null, none, ErrorCode.INTERNAL, "failed")).withMessage("systemDiagram");
        assertThatNullPointerException().isThrownBy(() -> new Unreadable(Kind.DIAGRAM_LIST,
            none, noSd, null, ErrorCode.INTERNAL, "failed")).withMessage("nodeId");
        assertThatNullPointerException().isThrownBy(() -> new Unreadable(Kind.DIAGRAM_LIST,
            none, noSd, none, null, "failed")).withMessage("code");
        assertThatNullPointerException().isThrownBy(() -> new Unreadable(Kind.DIAGRAM_LIST,
            none, noSd, none, ErrorCode.INTERNAL, null)).withMessage("message");
    }
}
