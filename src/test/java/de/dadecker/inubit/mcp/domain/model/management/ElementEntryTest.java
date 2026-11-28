package de.dadecker.inubit.mcp.domain.model.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Property;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T004: an element entry is either readable (settings and linked names) or an error. */
class ElementEntryTest {

    private static final Property SERVER = Property.shown("ServerName", "String",
        "https://central.example.test", false);
    private static final LinkedModule LINKED = new LinkedModule("Demo-HTTP-Out-A",
        Optional.of(true));
    private static final Reason ERROR = new Reason(ErrorCode.UNEXPECTED_RESPONSE, "no node",
        "check the system diagram with get_inventory_item, then retry with refresh: true");

    @Test
    void aReadableEntryHasSettingsAndLinkedNames() {
        ElementEntry entry = new ElementEntry("Central-Backend", "201",
            Optional.of("HTTP Connector"), Optional.of(List.of(SERVER)),
            Optional.of(List.of(LINKED)), Optional.empty());

        assertThat(entry.element()).isEqualTo("Central-Backend");
        assertThat(entry.nodeId()).isEqualTo("201");
        assertThat(entry.connectorType()).contains("HTTP Connector");
        assertThat(entry.settings()).contains(List.of(SERVER));
        assertThat(entry.linked()).contains(List.of(LINKED));
        assertThat(entry.error()).isEmpty();
    }

    @Test
    void aReadableEntryMayHaveEmptyLists() {
        ElementEntry entry = new ElementEntry("Central-Backend", "201", Optional.empty(),
            Optional.of(List.of()), Optional.of(List.of()), Optional.empty());

        assertThat(entry.settings()).contains(List.of());
        assertThat(entry.linked()).contains(List.of());
    }

    @Test
    void aReadableEntryNeedsSettingsAndLinkedNames() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ElementEntry("Central-Backend",
            "201", Optional.empty(), Optional.empty(), Optional.of(List.of()), Optional.empty()))
            .withMessageContaining("settings");
        assertThatIllegalArgumentException().isThrownBy(() -> new ElementEntry("Central-Backend",
            "201", Optional.empty(), Optional.of(List.of()), Optional.empty(), Optional.empty()))
            .withMessageContaining("linked");
    }

    @Test
    void anErrorEntryHasNoConnectorTypeSettingsOrLinkedNames() {
        ElementEntry entry = new ElementEntry("Second-Backend", "202", Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(ERROR));

        assertThat(entry.error()).contains(ERROR);

        assertThatIllegalArgumentException().isThrownBy(() -> new ElementEntry("Second-Backend",
            "202", Optional.of("HTTP Connector"), Optional.empty(), Optional.empty(),
            Optional.of(ERROR))).withMessageContaining("error");
        assertThatIllegalArgumentException().isThrownBy(() -> new ElementEntry("Second-Backend",
            "202", Optional.empty(), Optional.of(List.of()), Optional.empty(),
            Optional.of(ERROR))).withMessageContaining("error");
        assertThatIllegalArgumentException().isThrownBy(() -> new ElementEntry("Second-Backend",
            "202", Optional.empty(), Optional.empty(), Optional.of(List.of()),
            Optional.of(ERROR))).withMessageContaining("error");
    }

    @Test
    void atMostOneHundredLinkedNames() {
        List<LinkedModule> hundred = Collections.nCopies(ElementEntry.MAX_LINKED, LINKED);
        assertThat(new ElementEntry("Central-Backend", "201", Optional.empty(),
            Optional.of(List.of()), Optional.of(hundred), Optional.empty()).linked().orElseThrow())
            .hasSize(100);

        List<LinkedModule> tooMany = Collections.nCopies(ElementEntry.MAX_LINKED + 1, LINKED);
        assertThatIllegalArgumentException().isThrownBy(() -> new ElementEntry("Central-Backend",
            "201", Optional.empty(), Optional.of(List.of()), Optional.of(tooMany),
            Optional.empty())).withMessageContaining("linked");
    }

    @Test
    void theListsAreCopied() {
        List<LinkedModule> linked = new ArrayList<>(List.of(LINKED));
        ElementEntry entry = new ElementEntry("Central-Backend", "201", Optional.empty(),
            Optional.of(new ArrayList<>(List.of(SERVER))), Optional.of(linked), Optional.empty());

        linked.clear();

        assertThat(entry.linked().orElseThrow()).containsExactly(LINKED);
        assertThatThrownBy(() -> entry.settings().orElseThrow().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void everyFieldIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new ElementEntry(null, "201",
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(ERROR)))
            .withMessage("element");
        assertThatNullPointerException().isThrownBy(() -> new ElementEntry("Central-Backend",
            null, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(ERROR)))
            .withMessage("nodeId");
        assertThatNullPointerException().isThrownBy(() -> new ElementEntry("Central-Backend",
            "201", null, Optional.empty(), Optional.empty(), Optional.of(ERROR)))
            .withMessage("connectorType");
        assertThatNullPointerException().isThrownBy(() -> new ElementEntry("Central-Backend",
            "201", Optional.empty(), null, Optional.empty(), Optional.of(ERROR)))
            .withMessage("settings");
        assertThatNullPointerException().isThrownBy(() -> new ElementEntry("Central-Backend",
            "201", Optional.empty(), Optional.empty(), null, Optional.of(ERROR)))
            .withMessage("linked");
        assertThatNullPointerException().isThrownBy(() -> new ElementEntry("Central-Backend",
            "201", Optional.empty(), Optional.empty(), Optional.empty(), null))
            .withMessage("error");
    }
}
