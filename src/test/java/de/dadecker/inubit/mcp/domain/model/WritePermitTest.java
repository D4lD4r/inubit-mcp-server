package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Arrays;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Feature 017 (T040, FR-018, research D-24): the write purpose a call was admitted for, bound to
 * the node and the version it was judged on.
 */
class WritePermitTest {

    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final InubitVersion V9_0_7 = new InubitVersion(9, 0, OptionalInt.of(7),
        "9.0.7");

    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"DEVELOPMENT_WRITE", "DEPLOYMENT",
        "E2E_TEST", "PROCESS_CONTROL"})
    void acceptsEveryWriteCapability(Capability capability) {
        WritePermit permit = new WritePermit(NODE, capability, V9_0_7);

        assertThat(permit.node()).isEqualTo(NODE);
        assertThat(permit.capability()).isEqualTo(capability);
        assertThat(permit.admitted()).isEqualTo(V9_0_7);
    }

    @Test
    void theWriteCapabilitiesAreExactlyTheFourOfKindWrite() {
        assertThat(Arrays.stream(Capability.values())
            .filter(capability -> capability.kind() == Capability.Kind.WRITE))
            .containsExactlyInAnyOrder(Capability.DEVELOPMENT_WRITE, Capability.DEPLOYMENT,
                Capability.E2E_TEST, Capability.PROCESS_CONTROL);
    }

    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"MONITORING", "PROCESS_DIAGNOSIS",
        "LOG_DIAGNOSIS", "INVENTORY", "WORKSPACE_EXPORT"})
    void refusesAReadCapability(Capability capability) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new WritePermit(NODE, capability, V9_0_7))
            .withMessageContaining(capability.name());
    }

    @Test
    void admittedLineIsTheMajorMinorOfTheAdmittedVersion() {
        assertThat(new WritePermit(NODE, Capability.DEPLOYMENT, V9_0_7).admittedLine())
            .isEqualTo(new MajorMinor(9, 0));
        assertThat(new WritePermit(NODE, Capability.DEPLOYMENT,
            new InubitVersion(8, 1, OptionalInt.empty(), "8.1")).admittedLine())
            .isEqualTo(new MajorMinor(8, 1));
    }

    @Test
    void needsEveryComponent() {
        assertThatNullPointerException()
            .isThrownBy(() -> new WritePermit(null, Capability.DEPLOYMENT, V9_0_7));
        assertThatNullPointerException()
            .isThrownBy(() -> new WritePermit(NODE, null, V9_0_7));
        assertThatNullPointerException()
            .isThrownBy(() -> new WritePermit(NODE, Capability.DEPLOYMENT, null));
    }

    @Test
    void requireForAcceptsItsNodeOnly() {
        WritePermit permit = new WritePermit(NODE, Capability.DEPLOYMENT, V9_0_7);

        assertThat(permit.requireFor(NODE)).isSameAs(permit);
        assertThatIllegalArgumentException()
            .isThrownBy(() -> permit.requireFor(NodeId.parse("test/node2")))
            .withMessage("The write permit WritePermit[test/node1, DEPLOYMENT, INUBIT 9.0.7] is"
                + " not for test/node2");
    }

    @Test
    void textNamesNodeCapabilityAndVersionOnly() {
        assertThat(new WritePermit(NODE, Capability.E2E_TEST, V9_0_7).toString())
            .isEqualTo("WritePermit[test/node1, E2E_TEST, INUBIT 9.0.7]");
    }
}
