package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UnavailablePartTest {

    @ParameterizedTest
    @ValueSource(strings = {"status", "ready", "version", "systemInfo", "load", "client",
        "threads", "blockingQueue", "processCounts"})
    void everyHealthPartIsAccepted(String part) {
        assertThat(new UnavailablePart(part, "TIMEOUT: too slow").part()).isEqualTo(part);
    }

    @Test
    void the61PartsAreNamedAsInTheOutput() {
        assertThat(UnavailablePart.THREADS).isEqualTo("threads");
        assertThat(UnavailablePart.BLOCKING_QUEUE).isEqualTo("blockingQueue");
        assertThat(UnavailablePart.PROCESS_COUNTS).isEqualTo("processCounts");
        assertThat(UnavailablePart.PARTS).contains(UnavailablePart.THREADS,
            UnavailablePart.BLOCKING_QUEUE, UnavailablePart.PROCESS_COUNTS);
    }

    @Test
    void theReasonCodeForPartsMissingOn61() {
        assertThat(UnavailablePart.NOT_AVAILABLE_ON_6_1).isEqualTo("NOT_AVAILABLE_ON_6_1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"counts", "thread", "blocking_queue", ""})
    void anUnknownPartIsRejected(String part) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new UnavailablePart(part, "TIMEOUT: too slow"))
            .withMessageContaining("unknown health part");
    }

    @Test
    void aPartWithAReasonOnlyHasNoCauseOrStep() {
        UnavailablePart part = new UnavailablePart(UnavailablePart.THREADS,
            UnavailablePart.NOT_AVAILABLE_ON_6_1 + ": INUBIT 6.1 reports no thread figures");

        assertThat(part.likelyCause()).isEmpty();
        assertThat(part.nextStep()).isEmpty();
    }
}
