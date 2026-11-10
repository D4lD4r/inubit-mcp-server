package de.dadecker.inubit.mcp.domain.model.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T004: a system diagram is identified by name and diagram group; a workflow may be unresolved. */
class DiagramRefTest {

    @Test
    void twoRefsAreEqualOnlyIfNameAndDiagramGroupAreEqual() {
        DiagramRef ref = new DiagramRef("SD_Partner", "Group A");

        assertThat(ref).isEqualTo(new DiagramRef("SD_Partner", "Group A"))
            .hasSameHashCodeAs(new DiagramRef("SD_Partner", "Group A"));
        assertThat(ref).isNotEqualTo(new DiagramRef("SD_Partner", "Group B"));
        assertThat(ref).isNotEqualTo(new DiagramRef("SD_Other", "Group A"));
    }

    @Test
    void aDiagramRefNeedsANameAndADiagramGroup() {
        assertThatNullPointerException().isThrownBy(() -> new DiagramRef(null, "Group A"))
            .withMessage("name");
        assertThatNullPointerException().isThrownBy(() -> new DiagramRef("SD_Partner", null))
            .withMessage("diagramGroup");
        assertThatIllegalArgumentException().isThrownBy(() -> new DiagramRef(" ", "Group A"))
            .withMessageContaining("name");
        assertThatIllegalArgumentException().isThrownBy(() -> new DiagramRef("SD_Partner", ""))
            .withMessageContaining("diagramGroup");
    }

    @Test
    void aWorkflowRefNamesTheResolvedWorkflow() {
        assertThat(new WorkflowRef(Optional.of("TWF_Receive")).name()).contains("TWF_Receive");
    }

    @Test
    void aWorkflowRefWhoseIdDoesNotResolveIsEmpty() {
        assertThat(new WorkflowRef(Optional.empty()).name()).isEmpty();
        assertThat(new WorkflowRef(null).name()).isEmpty();
    }

    @Test
    void aResolvedWorkflowNameMustNotBeBlank() {
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkflowRef(Optional.of(" ")))
            .withMessageContaining("name");
    }
}
