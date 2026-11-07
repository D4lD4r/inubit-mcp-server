package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Feature 017 (FR-018, research D-24, contracts "Every write tool"): the refusal's texts. */
class WriteRefusedExceptionTest {

    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final WritePermit PERMIT = new WritePermit(NODE, Capability.DEPLOYMENT,
        InubitVersion.parse("8.1.17").orElseThrow());

    @Test
    void aChangedVersionNamesBothVersions() {
        WriteRefusedException refusal = WriteRefusedException.of(PERMIT,
            InubitVersion.parse("9.0.7"));

        assertThat(refusal.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(refusal.error().message()).isEqualTo("INUBIT on test/node1 changed from"
            + " 8.1.17 to 9.0.7 during this call; the write was admitted for INUBIT 8.1; nothing"
            + " was sent");
        assertThat(refusal.error().likelyCause()).isEqualTo("A parallel call found that"
            + " test/node1 runs another INUBIT version");
        assertThat(refusal.error().nextStep()).isEqualTo("Call the tool again; it is judged on"
            + " the version INUBIT runs now");
        assertThat(refusal.error().node()).contains(NODE);
        assertThat(refusal.change()).isEqualTo("changed from 8.1.17 to 9.0.7 during this call");
        assertThat(refusal.permit()).isSameAs(PERMIT);
        assertThat(refusal.current()).map(InubitVersion::raw).contains("9.0.7");
    }

    @Test
    void aStaleOrUnknownVersionSaysItMayRunAnotherVersion() {
        WriteRefusedException refusal = WriteRefusedException.of(PERMIT, Optional.empty());

        assertThat(refusal.error().message()).isEqualTo("INUBIT on test/node1 may run another"
            + " version than 8.1.17 since this call was admitted; nothing was sent");
        assertThat(refusal.change()).isEqualTo("may run another version than 8.1.17 since this"
            + " call was admitted");
        assertThat(refusal.current()).isEmpty();
    }
}
