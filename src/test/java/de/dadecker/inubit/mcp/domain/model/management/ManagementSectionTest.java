package de.dadecker.inubit.mcp.domain.model.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.management.ManagementSection.Status;
import de.dadecker.inubit.mcp.domain.model.management.Unreadable.Kind;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T004: the state rules of a management section (data-model.md → ManagementSection). */
class ManagementSectionTest {

    private static final Optional<String> OWNER = Optional.of("OWNERS");
    private static final Optional<Instant> AT = Optional.of(Instant.parse("2026-11-28T08:00:00Z"));
    private static final DiagramRef SD = new DiagramRef("SD-HTTP", "GRP-CONNECT");
    private static final ManagingElement ELEMENT = new ManagingElement(SD, "Central-Backend",
        Optional.of("HTTP Connector"), List.of(), List.of(), Optional.empty());
    private static final Optional<Unreadable> NOT_READ = Optional.of(new Unreadable(
        Kind.SYSTEM_DIAGRAM, Optional.of("SD-FTP"), Optional.empty(), Optional.empty(),
        ErrorCode.TIMEOUT, "read timed out"));
    private static final Optional<Unreadable> DIAGRAM_LIST = Optional.of(new Unreadable(
        Kind.DIAGRAM_LIST, Optional.empty(), Optional.empty(), Optional.empty(),
        ErrorCode.UNREACHABLE, "connection refused"));
    private static final Optional<Reason> REASON = Optional.of(new Reason(ErrorCode.TIMEOUT,
        "read timed out", "check the system diagram with get_inventory_item, then retry with "
            + "refresh: true"));
    private static final Optional<Reason> NOT_CONFIGURED = Optional.of(new Reason(
        ErrorCode.NOT_CONFIGURED, "no owner is configured", "configure the owner"));

    @Test
    void aCompleteManagedSection() {
        ManagementSection section = new ManagementSection(Status.MANAGED, true, OWNER, AT, 3, 0,
            Optional.empty(), List.of(ELEMENT), List.of(), Optional.empty(), false);

        assertThat(section.status()).isEqualTo(Status.MANAGED);
        assertThat(section.complete()).isTrue();
        assertThat(section.owner()).isEqualTo(OWNER);
        assertThat(section.collectedAt()).isEqualTo(AT);
        assertThat(section.systemDiagramsChecked()).isEqualTo(3);
        assertThat(section.systemDiagramsNotRead()).isZero();
        assertThat(section.elements()).containsExactly(ELEMENT);
        assertThat(section.warnings()).isEmpty();
        assertThat(section.reason()).isEmpty();
        assertThat(section.truncated()).isFalse();
    }

    @Test
    void anIncompleteManagedSectionNamesTheFirstItemNotRead() {
        ManagementSection section = new ManagementSection(Status.MANAGED, false, OWNER, AT, 2, 1,
            NOT_READ, List.of(ELEMENT),
            List.of("incomplete: further management cannot be ruled out"), Optional.empty(),
            false);

        assertThat(section.firstNotRead()).isEqualTo(NOT_READ);
    }

    @Test
    void aNotManagedSectionIsComplete() {
        ManagementSection section = new ManagementSection(Status.NOT_MANAGED, true, OWNER, AT, 3,
            0, Optional.empty(), List.of(), List.of(), Optional.empty(), false);

        assertThat(section.elements()).isEmpty();
        assertThat(section.reason()).isEmpty();
    }

    @Test
    void anUnknownSectionWithoutIndexHasAReasonButNoFirstNotRead() {
        ManagementSection section = new ManagementSection(Status.UNKNOWN, false,
            Optional.empty(), Optional.empty(), 0, 0, Optional.empty(), List.of(), List.of(),
            NOT_CONFIGURED, false);

        assertThat(section.reason()).isEqualTo(NOT_CONFIGURED);
        assertThat(section.firstNotRead()).isEmpty();
    }

    @Test
    void anUnknownSectionFromAnIncompleteIndexHasAReasonAndTheFirstItemNotRead() {
        ManagementSection fromDiagram = new ManagementSection(Status.UNKNOWN, false, OWNER, AT, 2,
            1, NOT_READ, List.of(), List.of(), REASON, false);
        ManagementSection fromDiagramList = new ManagementSection(Status.UNKNOWN, false, OWNER,
            AT, 0, 0, DIAGRAM_LIST, List.of(), List.of(), REASON, false);

        assertThat(fromDiagram.firstNotRead()).isEqualTo(NOT_READ);
        assertThat(fromDiagramList.firstNotRead()).isEqualTo(DIAGRAM_LIST);
        assertThat(fromDiagramList.systemDiagramsChecked()).isZero();
    }

    @Test
    void elementsArePresentExactlyWhenManaged() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.MANAGED, true, OWNER, AT, 3, 0, Optional.empty(), List.of(), List.of(),
            Optional.empty(), false)).withMessageContaining("elements");
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.NOT_MANAGED, true, OWNER, AT, 3, 0, Optional.empty(), List.of(ELEMENT),
            List.of(), Optional.empty(), false)).withMessageContaining("elements");
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.UNKNOWN, false, OWNER, AT, 2, 1, NOT_READ, List.of(ELEMENT), List.of(), REASON,
            false)).withMessageContaining("elements");
    }

    @Test
    void aReasonIsPresentExactlyWhenUnknown() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.UNKNOWN, false, OWNER, AT, 2, 1, NOT_READ, List.of(), List.of(),
            Optional.empty(), false)).withMessageContaining("reason");
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.MANAGED, true, OWNER, AT, 3, 0, Optional.empty(), List.of(ELEMENT), List.of(),
            REASON, false)).withMessageContaining("reason");
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.NOT_MANAGED, true, OWNER, AT, 3, 0, Optional.empty(), List.of(), List.of(),
            REASON, false)).withMessageContaining("reason");
    }

    @Test
    void notManagedIsNeverIncompleteAndHasNoFirstNotRead() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.NOT_MANAGED, false, OWNER, AT, 2, 1, NOT_READ, List.of(), List.of(),
            Optional.empty(), false)).withMessageContaining("complete");
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.NOT_MANAGED, true, OWNER, AT, 3, 0, NOT_READ, List.of(), List.of(),
            Optional.empty(), false)).withMessageContaining("firstNotRead");
    }

    @Test
    void firstNotReadIsPresentExactlyWhenAnIndexWasHadAndItIsIncomplete() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.MANAGED, false, OWNER, AT, 2, 1, Optional.empty(), List.of(ELEMENT),
            List.of(), Optional.empty(), false)).withMessageContaining("firstNotRead");
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.UNKNOWN, false, OWNER, Optional.empty(), 0, 0, NOT_READ, List.of(), List.of(),
            REASON, false)).withMessageContaining("firstNotRead");
    }

    @Test
    void completeNeedsAnIndex() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.NOT_MANAGED, true, OWNER, Optional.empty(), 0, 0, Optional.empty(), List.of(),
            List.of(), Optional.empty(), false)).withMessageContaining("complete");
    }

    @Test
    void unknownIsNeverComplete() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.UNKNOWN, true, OWNER, AT, 3, 0, Optional.empty(), List.of(), List.of(), REASON,
            false)).withMessageContaining("UNKNOWN is never complete");
    }

    @Test
    void managedNeedsAnIndex() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.MANAGED, false, OWNER, Optional.empty(), 0, 0, Optional.empty(),
            List.of(ELEMENT), List.of(), Optional.empty(), false))
            .withMessageContaining("MANAGED needs an index");
    }

    @Test
    void completeHasNoSystemDiagramsNotRead() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.MANAGED, true, OWNER, AT, 2, 1, Optional.empty(), List.of(ELEMENT), List.of(),
            Optional.empty(), false)).withMessageContaining("system diagrams not read");
    }

    @Test
    void theOwnerIsEmptyOnlyForNotConfigured() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.UNKNOWN, false, Optional.empty(), Optional.empty(), 0, 0, Optional.empty(),
            List.of(), List.of(), REASON, false)).withMessageContaining("owner");
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.NOT_MANAGED, true, Optional.empty(), AT, 3, 0, Optional.empty(), List.of(),
            List.of(), Optional.empty(), false)).withMessageContaining("owner");
    }

    @Test
    void atMostTenElements() {
        List<ManagingElement> ten = Collections.nCopies(ManagementSection.MAX_ELEMENTS, ELEMENT);
        assertThat(new ManagementSection(Status.MANAGED, true, OWNER, AT, 3, 0, Optional.empty(),
            ten, List.of(), Optional.empty(), true).elements()).hasSize(10);

        List<ManagingElement> eleven = Collections.nCopies(ManagementSection.MAX_ELEMENTS + 1,
            ELEMENT);
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.MANAGED, true, OWNER, AT, 3, 0, Optional.empty(), eleven, List.of(),
            Optional.empty(), true)).withMessageContaining("elements");
    }

    @Test
    void theCountsAreNotNegative() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.NOT_MANAGED, true, OWNER, AT, -1, 0, Optional.empty(), List.of(), List.of(),
            Optional.empty(), false)).withMessageContaining("systemDiagramsChecked");
        assertThatIllegalArgumentException().isThrownBy(() -> new ManagementSection(
            Status.NOT_MANAGED, true, OWNER, AT, 0, -1, Optional.empty(), List.of(), List.of(),
            Optional.empty(), false)).withMessageContaining("systemDiagramsNotRead");
    }

    @Test
    void everyFieldIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new ManagementSection(null, true,
            OWNER, AT, 3, 0, Optional.empty(), List.of(), List.of(), Optional.empty(), false))
            .withMessage("status");
        assertThatNullPointerException().isThrownBy(() -> new ManagementSection(
            Status.NOT_MANAGED, true, null, AT, 3, 0, Optional.empty(), List.of(), List.of(),
            Optional.empty(), false)).withMessage("owner");
        assertThatNullPointerException().isThrownBy(() -> new ManagementSection(
            Status.NOT_MANAGED, true, OWNER, null, 3, 0, Optional.empty(), List.of(), List.of(),
            Optional.empty(), false)).withMessage("collectedAt");
        assertThatNullPointerException().isThrownBy(() -> new ManagementSection(
            Status.NOT_MANAGED, true, OWNER, AT, 3, 0, null, List.of(), List.of(),
            Optional.empty(), false)).withMessage("firstNotRead");
        assertThatNullPointerException().isThrownBy(() -> new ManagementSection(
            Status.NOT_MANAGED, true, OWNER, AT, 3, 0, Optional.empty(), List.of(), List.of(),
            null, false)).withMessage("reason");
    }
}
