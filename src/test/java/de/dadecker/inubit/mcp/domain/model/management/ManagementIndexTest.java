package de.dadecker.inubit.mcp.domain.model.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.management.Unreadable.Kind;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T004: the index of one server and owner, its completeness and its links. */
class ManagementIndexTest {

    private static final Instant AT = Instant.parse("2026-11-28T08:00:00Z");
    private static final DiagramRef SD_A = new DiagramRef("SD-HTTP", "GRP-CONNECT");
    private static final DiagramRef SD_B = new DiagramRef("SD-FTP", "GRP-CONNECT");
    private static final DiagramRef SD_C = new DiagramRef("SD-MAIL", "GRP-CONNECT");

    private static ExternalSystem system(DiagramRef sd, String element, String nodeId,
        String... linked) {
        return new ExternalSystem(sd, element, nodeId, Optional.empty(), List.of(),
            List.of(linked));
    }

    private static Unreadable diagramList() {
        return new Unreadable(Kind.DIAGRAM_LIST, Optional.empty(), Optional.empty(),
            Optional.empty(), ErrorCode.UNREACHABLE, "connection refused");
    }

    private static Unreadable systemDiagram(DiagramRef sd) {
        return new Unreadable(Kind.SYSTEM_DIAGRAM, Optional.of(sd.name()), Optional.empty(),
            Optional.empty(), ErrorCode.TIMEOUT, "read timed out");
    }

    private static Unreadable element(DiagramRef sd, String name, String nodeId) {
        return new Unreadable(Kind.ELEMENT, Optional.of(name), Optional.of(sd),
            Optional.of(nodeId), ErrorCode.UNEXPECTED_RESPONSE, "no node");
    }

    private static ManagementIndex index(List<DiagramRef> diagrams,
        List<ExternalSystem> systems, List<Unreadable> unreadable,
        Map<DiagramRef, List<String>> elementNodeIds) {
        return new ManagementIndex("OWNERS", diagrams, systems, unreadable, elementNodeIds, AT);
    }

    @Test
    void anIndexWithoutUnreadableItemsIsComplete() {
        ManagementIndex index = index(List.of(SD_A),
            List.of(system(SD_A, "Central-Backend", "201")), List.of(),
            Map.of(SD_A, List.of("201")));

        assertThat(index.complete()).isTrue();
        assertThat(index.systemDiagramsNotRead()).isZero();
        assertThat(index.owner()).isEqualTo("OWNERS");
        assertThat(index.collectedAt()).isEqualTo(AT);
    }

    @Test
    void anIndexWithAnUnreadableItemIsIncomplete() {
        ManagementIndex index = index(List.of(SD_A, SD_B), List.of(), List.of(systemDiagram(SD_B)),
            Map.of());

        assertThat(index.complete()).isFalse();
    }

    @Test
    void linkingFindsTheExternalSystemsThatLinkTheExactName() {
        ExternalSystem first = system(SD_A, "Central-Backend", "201", "Demo-HTTP-Out-A",
            "Other");
        ExternalSystem second = system(SD_B, "Second-Backend", "202", "Demo-HTTP-Out-A");
        ExternalSystem third = system(SD_B, "Third-Backend", "203", "demo-http-out-a",
            "Demo-HTTP-Out-AB");
        ManagementIndex index = index(List.of(SD_A, SD_B), List.of(first, second, third),
            List.of(), Map.of(SD_A, List.of("201"), SD_B, List.of("202", "203")));

        assertThat(index.linking("Demo-HTTP-Out-A")).containsExactly(first, second);
        assertThat(index.linking("Missing")).isEmpty();
    }

    @Test
    void systemDiagramsNotReadCountsUnreadableDiagramsAndDiagramsWithAnUnreadableElement() {
        ManagementIndex index = index(List.of(SD_A, SD_B, SD_C), List.of(), List.of(
            systemDiagram(SD_A),
            element(SD_B, "Central-Backend", "201"),
            element(SD_B, "Second-Backend", "202"),
            element(SD_C, "Third-Backend", "301")),
            Map.of(SD_B, List.of("201", "202"), SD_C, List.of("301")));

        assertThat(index.systemDiagramsNotRead()).isEqualTo(3);
    }

    @Test
    void aFailedDiagramListIsTheOnlyUnreadableItem() {
        ManagementIndex index = index(List.of(), List.of(), List.of(diagramList()), Map.of());

        assertThat(index.complete()).isFalse();
        assertThat(index.systemDiagramsNotRead()).isZero();

        assertThatIllegalArgumentException().isThrownBy(() -> index(List.of(SD_A), List.of(),
            List.of(diagramList(), systemDiagram(SD_A)), Map.of()))
            .withMessageContaining("DIAGRAM_LIST");
        assertThatIllegalArgumentException().isThrownBy(() -> index(List.of(), List.of(),
            List.of(diagramList(), diagramList()), Map.of())).withMessageContaining("DIAGRAM_LIST");
    }

    @Test
    void aFailedDiagramListHasNoSystemDiagrams() {
        assertThatIllegalArgumentException().isThrownBy(() -> index(List.of(SD_A), List.of(),
            List.of(diagramList()), Map.of())).withMessageContaining("system diagrams");
        assertThatIllegalArgumentException().isThrownBy(() -> index(List.of(),
            List.of(system(SD_A, "Central-Backend", "201")), List.of(diagramList()),
            Map.of(SD_A, List.of("201")))).withMessageContaining("system diagrams");
    }

    @Test
    void elementNodeIdsKeepTheElementListOrderOfReadableAndUnreadableElements() {
        ExternalSystem first = system(SD_A, "Central-Backend", "203", "Demo-HTTP-Out-A");
        ExternalSystem third = system(SD_A, "Third-Backend", "201", "Demo-HTTP-Out-B");
        Unreadable second = element(SD_A, "Second-Backend", "202");
        ManagementIndex index = index(List.of(SD_A), List.of(first, third), List.of(second),
            Map.of(SD_A, List.of("203", "202", "201")));

        assertThat(index.elementNodeIds()).containsExactly(
            Map.entry(SD_A, List.of("203", "202", "201")));
    }

    @Test
    void elementNodeIdsListExactlyTheElementsOfEachSystemDiagram() {
        List<ExternalSystem> systems = List.of(system(SD_A, "Central-Backend", "201"));
        List<Unreadable> unreadable = List.of(element(SD_B, "Second-Backend", "202"));

        assertThatIllegalArgumentException().isThrownBy(() -> index(List.of(SD_A, SD_B),
            systems, unreadable, Map.of(SD_A, List.of("201")))).withMessageContaining("exactly");
        assertThatIllegalArgumentException().isThrownBy(() -> index(List.of(SD_A, SD_B),
            systems, unreadable, Map.of(SD_A, List.of("201", "209"), SD_B, List.of("202"))))
            .withMessageContaining("exactly");
        assertThatIllegalArgumentException().isThrownBy(() -> index(List.of(SD_A, SD_B),
            systems, unreadable, Map.of(SD_A, List.of("202"), SD_B, List.of("201"))))
            .withMessageContaining("exactly");
        assertThatIllegalArgumentException().isThrownBy(() -> index(List.of(SD_A), List.of(),
            List.of(), Map.of(SD_A, List.of()))).withMessageContaining("empty");
    }

    @Test
    void aNodeIdOccursOncePerSystemDiagram() {
        assertThatIllegalArgumentException().isThrownBy(() -> index(List.of(SD_A),
            List.of(system(SD_A, "Central-Backend", "201")), List.of(),
            Map.of(SD_A, List.of("201", "201")))).withMessageContaining("twice");
        assertThatIllegalArgumentException().isThrownBy(() -> index(List.of(SD_A),
            List.of(system(SD_A, "Central-Backend", "201")),
            List.of(element(SD_A, "Second-Backend", "201")), Map.of(SD_A, List.of("201"))))
            .withMessageContaining("twice");
    }

    @Test
    void everyFieldIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new ManagementIndex(null, List.of(),
            List.of(), List.of(), Map.of(), AT)).withMessage("owner");
        assertThatNullPointerException().isThrownBy(() -> new ManagementIndex("OWNERS",
            List.of(), List.of(), List.of(), null, AT)).withMessage("elementNodeIds");
        assertThatNullPointerException().isThrownBy(() -> new ManagementIndex("OWNERS",
            List.of(), List.of(), List.of(), Map.of(), null)).withMessage("collectedAt");
    }
}
