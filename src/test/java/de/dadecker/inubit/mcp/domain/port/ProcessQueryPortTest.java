package de.dadecker.inubit.mcp.domain.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Feature 020 (T005, D-9): the refusing default of {@link ProcessQueryPort#queueEntries}. */
class ProcessQueryPortTest {

    /** A port that implements only what existed before feature 020; every call fails. */
    private static final class PreviousPort implements ProcessQueryPort {

        @Override
        public void validate(ProcessQuery query) {
            throw new AssertionError("validate called");
        }

        @Override
        public Page<ProcessInstance> find(ProcessQuery query) {
            throw new AssertionError("find called");
        }

        @Override
        public ProcessRows findByProcessId(String processId, Instant now,
            Duration hangingThreshold) {
            throw new AssertionError("findByProcessId called");
        }
    }

    @Test
    void theDefaultRefusesWithoutAnyCall() {
        String processId = TestSecrets.CREDENTIAL_PAYLOAD_VALUE;

        assertThatThrownBy(() -> new PreviousPort().queueEntries(processId))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
                assertThat(e.error().message()).isEqualTo("Reading Queue Manager entries is not"
                    + " available for this adapter line; nothing was sent");
                assertThat(e.error().likelyCause()).isNotBlank();
                assertThat(e.error().nextStep()).isNotBlank();
                assertThat(e.error().node()).isEmpty();
                assertThat(e.error().excerpt()).isEmpty();
                TestSecrets.assertNoLeak(e.getMessage());
                TestSecrets.assertNoLeak(e.error().likelyCause());
                TestSecrets.assertNoLeak(e.error().nextStep());
            });
    }
}
