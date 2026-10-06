package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import de.dadecker.inubit.mcp.domain.model.ArtifactRef.Kind;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T005: identity of an artifact is its name within group, owner and kind (spike §5). */
class ArtifactRefTest {

    private static final GroupId DEV = new GroupId("dev");

    @Test
    void factoriesSetKindAndTheKindSpecificField() {
        ArtifactRef workflow = ArtifactRef.workflow(DEV, "OWNERS", "GRP-01", "Workflow-0001");
        ArtifactRef module = ArtifactRef.module(DEV, "OWNERS", "XSLT Converter", "Module-0001");
        ArtifactRef file = ArtifactRef.repositoryFile(DEV, "OWNERS", "Root/OWNERS/xsd/msg.xsd");

        assertThat(workflow.kind()).isEqualTo(Kind.WORKFLOW);
        assertThat(workflow.diagramGroup()).contains("GRP-01");
        assertThat(workflow.pluginType()).isEmpty();
        assertThat(module.kind()).isEqualTo(Kind.MODULE);
        assertThat(module.pluginType()).contains("XSLT Converter");
        assertThat(module.diagramGroup()).isEmpty();
        assertThat(file.kind()).isEqualTo(Kind.REPOSITORY_FILE);
        assertThat(file.name()).isEqualTo("Root/OWNERS/xsd/msg.xsd");
    }

    @Test
    void identityIsGroupOwnerKindAndNameOnly() {
        ArtifactRef inOneDiagramGroup = ArtifactRef.workflow(DEV, "OWNERS", "GRP-01", "W-1");
        ArtifactRef movedToAnother = ArtifactRef.workflow(DEV, "OWNERS", "GRP-02", "W-1");
        ArtifactRef withoutDiagramGroup = new ArtifactRef(DEV, "OWNERS", Kind.WORKFLOW, "W-1",
            Optional.empty(), Optional.empty());

        assertThat(inOneDiagramGroup).isEqualTo(movedToAnother).isEqualTo(withoutDiagramGroup)
            .hasSameHashCodeAs(movedToAnother);
        assertThat(ArtifactRef.module(DEV, "OWNERS", "Assign", "M-1"))
            .isEqualTo(ArtifactRef.module(DEV, "OWNERS", "Demultiplexer", "M-1"));
    }

    @Test
    void anotherGroupOwnerKindOrNameIsAnotherArtifact() {
        ArtifactRef base = ArtifactRef.module(DEV, "OWNERS", "Assign", "M-1");

        assertThat(base).isNotEqualTo(ArtifactRef.module(new GroupId("test"), "OWNERS",
                "Assign", "M-1"))
            .isNotEqualTo(ArtifactRef.module(DEV, "jdoe", "Assign", "M-1"))
            .isNotEqualTo(ArtifactRef.module(DEV, "OWNERS", "Assign", "m-1"))
            .isNotEqualTo(ArtifactRef.workflow(DEV, "OWNERS", "GRP-01", "M-1"));
    }

    @Test
    void requiredFieldsAreChecked() {
        assertThatNullPointerException().isThrownBy(() -> ArtifactRef.module(null, "O", "T", "M"))
            .withMessageContaining("group");
        assertThatNullPointerException().isThrownBy(() -> new ArtifactRef(DEV, "O", null, "M",
            Optional.empty(), Optional.empty())).withMessageContaining("kind");
        assertThatIllegalArgumentException().isThrownBy(() -> ArtifactRef.module(DEV, " ", "T",
            "M")).withMessageContaining("owner");
        assertThatIllegalArgumentException().isThrownBy(() -> ArtifactRef.module(DEV, "O", "T",
            "")).withMessageContaining("name");
        assertThatIllegalArgumentException().isThrownBy(() -> ArtifactRef.module(DEV, "O", " ",
            "M")).withMessageContaining("pluginType");
    }

    @Test
    void absentOptionalsMayBeNull() {
        ArtifactRef ref = new ArtifactRef(DEV, "O", Kind.REPOSITORY_FILE, "f.xsd", null, null);

        assertThat(ref.diagramGroup()).isEmpty();
        assertThat(ref.pluginType()).isEmpty();
    }

    @Test
    void theKindSpecificFieldsBelongToTheirKind() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ArtifactRef(DEV, "O",
            Kind.MODULE, "M", Optional.of("GRP-01"), Optional.empty()))
            .withMessageContaining("diagramGroup");
        assertThatIllegalArgumentException().isThrownBy(() -> new ArtifactRef(DEV, "O",
            Kind.WORKFLOW, "W", Optional.empty(), Optional.of("Assign")))
            .withMessageContaining("pluginType");
    }
}
