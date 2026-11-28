package de.dadecker.inubit.mcp.domain.model.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T004: the managed modules of a system diagram (data-model.md → SystemDiagramManagement). */
class SystemDiagramManagementTest {

    private static final Optional<Instant> AT = Optional.of(Instant.parse("2026-11-28T08:00:00Z"));
    private static final Reason ERROR = new Reason(ErrorCode.UNEXPECTED_RESPONSE, "no node",
        "check the system diagram with get_inventory_item, then retry with refresh: true");
    private static final Optional<Reason> NO_MODULE_LIST = Optional.of(new Reason(
        ErrorCode.NOT_FOUND, "module list not loaded",
        "a get_inventory_item or list_inventory call with kind: MODULE loads it"));
    private static final Optional<Reason> TIMEOUT = Optional.of(new Reason(ErrorCode.TIMEOUT,
        "budget reached", "the read goes on and fills the cache"));
    private static final Optional<Reason> NOT_IN_INDEX = Optional.of(new Reason(
        ErrorCode.NOT_FOUND, "the system diagram is not in the index collected at "
            + "2026-11-28T08:00:00Z", "call again with refresh: true"));

    private static ElementEntry readable(Optional<Boolean> found) {
        return new ElementEntry("Central-Backend", "201", Optional.of("HTTP Connector"),
            Optional.of(List.of()), Optional.of(List.of(new LinkedModule("Demo-HTTP-Out-A",
            found))), Optional.empty());
    }

    private static final ElementEntry FAILED = new ElementEntry("Second-Backend", "202",
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(ERROR));

    @Test
    void anAvailableDetailWithModuleListMarksEveryLinkedName() {
        SystemDiagramManagement detail = new SystemDiagramManagement(
            List.of(readable(Optional.of(true)), FAILED), Optional.empty(), Optional.empty(), AT,
            List.of(), false);

        assertThat(detail.elements()).hasSize(2);
        assertThat(detail.moduleListUnavailable()).isEmpty();
        assertThat(detail.unavailable()).isEmpty();
        assertThat(detail.collectedAt()).isEqualTo(AT);
        assertThat(detail.warnings()).isEmpty();
        assertThat(detail.truncated()).isFalse();
    }

    @Test
    void anAvailableDetailWithoutModuleListHasNoFoundMarks() {
        SystemDiagramManagement detail = new SystemDiagramManagement(
            List.of(readable(Optional.empty()), FAILED), NO_MODULE_LIST, Optional.empty(), AT,
            List.of(), false);

        assertThat(detail.moduleListUnavailable()).isEqualTo(NO_MODULE_LIST);
    }

    @Test
    void foundMarksArePresentExactlyWhenTheModuleListIsAvailable() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SystemDiagramManagement(
            List.of(readable(Optional.empty())), Optional.empty(), Optional.empty(), AT,
            List.of(), false)).withMessageContaining("found");
        assertThatIllegalArgumentException().isThrownBy(() -> new SystemDiagramManagement(
            List.of(readable(Optional.of(false))), NO_MODULE_LIST, Optional.empty(), AT,
            List.of(), false)).withMessageContaining("found");
    }

    @Test
    void anUnavailableDetailWithoutIndexHasNoElementsAndNoCollectedAt() {
        SystemDiagramManagement detail = new SystemDiagramManagement(List.of(), Optional.empty(),
            TIMEOUT, Optional.empty(), List.of(), false);

        assertThat(detail.elements()).isEmpty();
        assertThat(detail.unavailable()).isEqualTo(TIMEOUT);
        assertThat(detail.collectedAt()).isEmpty();
    }

    @Test
    void anUnavailableDetailFromAnIndexKeepsCollectedAt() {
        SystemDiagramManagement detail = new SystemDiagramManagement(List.of(), Optional.empty(),
            NOT_IN_INDEX, AT, List.of(), false);

        assertThat(detail.collectedAt()).isEqualTo(AT);
    }

    @Test
    void elementsAreEmptyExactlyWhenUnavailable() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SystemDiagramManagement(
            List.of(FAILED), Optional.empty(), NOT_IN_INDEX, AT, List.of(), false))
            .withMessageContaining("elements");
        assertThatIllegalArgumentException().isThrownBy(() -> new SystemDiagramManagement(
            List.of(), Optional.empty(), Optional.empty(), AT, List.of(), false))
            .withMessageContaining("elements");
    }

    @Test
    void anUnavailableDetailHasNoModuleListReason() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SystemDiagramManagement(
            List.of(), NO_MODULE_LIST, TIMEOUT, Optional.empty(), List.of(), false))
            .withMessageContaining("moduleListUnavailable");
    }

    @Test
    void anAvailableDetailComesFromAnIndex() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SystemDiagramManagement(
            List.of(FAILED), Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
            false)).withMessageContaining("collectedAt");
    }

    @Test
    void atMostFiftyElements() {
        List<ElementEntry> fifty = Collections.nCopies(SystemDiagramManagement.MAX_ELEMENTS,
            FAILED);
        assertThat(new SystemDiagramManagement(fifty, Optional.empty(), Optional.empty(), AT,
            List.of(), true).elements()).hasSize(50);

        List<ElementEntry> tooMany = Collections.nCopies(
            SystemDiagramManagement.MAX_ELEMENTS + 1, FAILED);
        assertThatIllegalArgumentException().isThrownBy(() -> new SystemDiagramManagement(
            tooMany, Optional.empty(), Optional.empty(), AT, List.of(), true))
            .withMessageContaining("elements");
    }

    @Test
    void theListsAreCopied() {
        List<String> warnings = new ArrayList<>(List.of("a gate warning"));
        List<ElementEntry> elements = new ArrayList<>(List.of(FAILED));
        SystemDiagramManagement detail = new SystemDiagramManagement(elements, Optional.empty(),
            Optional.empty(), AT, warnings, false);

        warnings.clear();
        elements.clear();

        assertThat(detail.warnings()).containsExactly("a gate warning");
        assertThat(detail.elements()).containsExactly(FAILED);
    }

    @Test
    void everyFieldIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new SystemDiagramManagement(
            List.of(FAILED), null, Optional.empty(), AT, List.of(), false))
            .withMessage("moduleListUnavailable");
        assertThatNullPointerException().isThrownBy(() -> new SystemDiagramManagement(
            List.of(FAILED), Optional.empty(), null, AT, List.of(), false))
            .withMessage("unavailable");
        assertThatNullPointerException().isThrownBy(() -> new SystemDiagramManagement(
            List.of(FAILED), Optional.empty(), Optional.empty(), null, List.of(), false))
            .withMessage("collectedAt");
    }
}
