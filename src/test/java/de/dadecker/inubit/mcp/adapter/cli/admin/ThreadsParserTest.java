package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.block;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T018 (feature 022, US1): the answer of {@code threads showNumberOfWorkflowThreads}. The case
 * {@code admin-settings-threads-reconstructed} is SYNTHETIC until T014 records the real one.
 */
class ThreadsParserTest {

    @Test
    void theReconstructedAnswerGivesTheNumberOfWorkflowThreads() {
        assertThat(ThreadsParser.parse(block("admin-settings-threads-reconstructed")))
            .isEqualTo(16L);
    }

    @Test
    void surroundingSpacesAreIgnored() {
        assertThat(ThreadsParser.parse(List.of("  Number of workflow threads: 0  ")))
            .isZero();
    }

    @Test
    void anUnknownAnswerIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> ThreadsParser.parse(List.of()));
        assertUnknown(() -> ThreadsParser.parse(List.of("16")));
        assertUnknown(() -> ThreadsParser.parse(List.of("Unknown command: threads")));
        assertUnknown(() -> ThreadsParser.parse(List.of("Number of workflow threads: -1")));
        assertUnknown(() -> ThreadsParser.parse(List.of("Number of workflow threads: many")));
        assertUnknown(() -> ThreadsParser.parse(
            List.of("Number of workflow threads: 99999999999999999999")));
        assertUnknown(() -> ThreadsParser.parse(
            List.of("Number of workflow threads: 16", "Number of workflow threads: 8")));
    }

    @Test
    void theExcerptShowsTheUnknownAnswer() {
        assertThat(assertUnknown(() -> ThreadsParser.parse(List.of("1-NOK: Access denied")))
            .excerpt()).contains("1-NOK: Access denied");
    }
}
