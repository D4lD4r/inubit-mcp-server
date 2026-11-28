package de.dadecker.inubit.mcp.domain.model.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Property;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T004: a managing element with its settings and either differences or why there are none. */
class ManagingElementTest {

    private static final DiagramRef SD = new DiagramRef("SD-HTTP", "GRP-CONNECT");
    private static final Difference SERVER = new Difference("ServerName",
        "https://central.example.test", "http://localhost:8000/dummy");
    private static final Reason UNREAD = new Reason(ErrorCode.NOT_FOUND,
        "the module is used by no technical workflow", "read the module with read_model_details");

    @Test
    void aManagingElementKeepsItsFields() {
        Property password = Property.masked("Password", "Password");

        ManagingElement element = new ManagingElement(SD, "Central-Backend",
            Optional.of("HTTP Connector"), List.of(password), List.of(SERVER), Optional.empty());

        assertThat(element.systemDiagram()).isEqualTo(SD);
        assertThat(element.element()).isEqualTo("Central-Backend");
        assertThat(element.connectorType()).contains("HTTP Connector");
        assertThat(element.settings()).containsExactly(password);
        assertThat(element.differing()).containsExactly(SERVER);
        assertThat(element.comparisonUnavailable()).isEmpty();
    }

    @Test
    void anUnavailableComparisonHasNoDifferences() {
        ManagingElement element = new ManagingElement(SD, "Central-Backend", Optional.empty(),
            List.of(), List.of(), Optional.of(UNREAD));

        assertThat(element.comparisonUnavailable()).contains(UNREAD);

        assertThatIllegalArgumentException().isThrownBy(() -> new ManagingElement(SD,
            "Central-Backend", Optional.empty(), List.of(), List.of(SERVER),
            Optional.of(UNREAD))).withMessageContaining("differing");
    }

    @Test
    void theListsAreCopied() {
        List<Difference> differing = new ArrayList<>(List.of(SERVER));
        ManagingElement element = new ManagingElement(SD, "Central-Backend", Optional.empty(),
            List.of(), differing, Optional.empty());

        differing.clear();

        assertThat(element.differing()).containsExactly(SERVER);
        assertThatThrownBy(() -> element.settings().add(Property.masked("a", "String")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void everyFieldIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new ManagingElement(null,
            "Central-Backend", Optional.empty(), List.of(), List.of(), Optional.empty()))
            .withMessage("systemDiagram");
        assertThatNullPointerException().isThrownBy(() -> new ManagingElement(SD, null,
            Optional.empty(), List.of(), List.of(), Optional.empty())).withMessage("element");
        assertThatNullPointerException().isThrownBy(() -> new ManagingElement(SD,
            "Central-Backend", null, List.of(), List.of(), Optional.empty()))
            .withMessage("connectorType");
        assertThatNullPointerException().isThrownBy(() -> new ManagingElement(SD,
            "Central-Backend", Optional.empty(), List.of(), List.of(), null))
            .withMessage("comparisonUnavailable");
    }
}
