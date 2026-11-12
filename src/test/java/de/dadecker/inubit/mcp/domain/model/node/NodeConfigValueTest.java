package de.dadecker.inubit.mcp.domain.model.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import org.junit.jupiter.api.Test;

/** Feature 024 (T006, FR-031, research D-10): the answer for one configuration name. */
class NodeConfigValueTest {

    private static NodeConfigValue.Present present(String value) {
        return new NodeConfigValue.Present(NodeConfigName.CLUSTER_NAME, value, "String",
            "Cluster name", "The name of the cluster", "Cluster", true, false);
    }

    @Test
    void presentHoldsValueAndAttributes() {
        NodeConfigValue.Present value = present("acme-cluster");

        assertThat(value.name()).isEqualTo(NodeConfigName.CLUSTER_NAME);
        assertThat(value.value()).isEqualTo("acme-cluster");
        assertThat(value.type()).isEqualTo("String");
        assertThat(value.displayName()).isEqualTo("Cluster name");
        assertThat(value.description()).isEqualTo("The name of the cluster");
        assertThat(value.category()).isEqualTo("Cluster");
        assertThat(value.editable()).isTrue();
        assertThat(value.deprecated()).isFalse();
    }

    @Test
    void presentValueHasAtMost2000Characters() {
        assertThat(present("v".repeat(2_000)).value()).hasSize(NodeConfigValue.MAX_VALUE_CHARS);
        assertThatIllegalArgumentException().isThrownBy(() -> present("v".repeat(2_001)))
            .withMessageContaining("2000");
    }

    @Test
    void absentAndFailedNameTheirName() {
        NodeConfigValue absent = new NodeConfigValue.Absent(NodeConfigName.NO_OF_RETRIES);
        NodeConfigValue.Failed failed = new NodeConfigValue.Failed(NodeConfigName.RETRY_INTERVAL,
            ErrorCode.UNEXPECTED_RESPONSE, "The node did not answer");

        assertThat(absent.name()).isEqualTo(NodeConfigName.NO_OF_RETRIES);
        assertThat(failed.name()).isEqualTo(NodeConfigName.RETRY_INTERVAL);
        assertThat(failed.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(failed.message()).isEqualTo("The node did not answer");
    }
}
