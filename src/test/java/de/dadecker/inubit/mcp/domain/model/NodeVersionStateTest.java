package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Feature 011 (T003, FR-002, research D-1): the effective version of a 6.1 setting. */
class NodeVersionStateTest {

    @Test
    void theSixOneLineIsSixOneWithoutPatch() {
        assertThat(NodeVersionState.initial(VersionSetting.V6_1).effectiveVersion())
            .contains(new InubitVersion(6, 1, OptionalInt.empty(), "6.1"));
    }

    @Test
    void aFourPartSixOnePinIsItsVersion() {
        VersionSetting pin = VersionSetting.parse("6.1.0.89-HF1").orElseThrow();

        assertThat(NodeVersionState.initial(pin).effectiveVersion())
            .contains(new InubitVersion(6, 1, OptionalInt.of(0), "6.1.0.89-HF1"));
    }
}
