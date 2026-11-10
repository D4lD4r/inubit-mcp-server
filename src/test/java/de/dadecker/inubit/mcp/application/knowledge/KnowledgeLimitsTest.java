package de.dadecker.inubit.mcp.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The bounds of the local knowledge as fixed by data-model.md (feature 019, T007). */
class KnowledgeLimitsTest {

    @Test
    void everyBoundHasItsDocumentedValue() {
        assertThat(KnowledgeLimits.NOTE_BYTES).isEqualTo(262_144);
        assertThat(KnowledgeLimits.TOTAL_BYTES).isEqualTo(104_857_600L);
        assertThat(KnowledgeLimits.READ_BYTES).isEqualTo(4_194_304);
        assertThat(KnowledgeLimits.HISTORY).isEqualTo(10);
        assertThat(KnowledgeLimits.LOCK_WAIT).isEqualTo(Duration.ofSeconds(10));
        assertThat(KnowledgeLimits.PAGE_CHARS).isEqualTo(20_000);
        assertThat(KnowledgeLimits.EXCERPT_CHARS).isEqualTo(300);
        assertThat(KnowledgeLimits.LINE_CHARS).isEqualTo(120);
        assertThat(KnowledgeLimits.LIST_CHARS).isEqualTo(4_000);
        assertThat(KnowledgeLimits.SEARCH_LIMIT_MAX).isEqualTo(50);
        assertThat(KnowledgeLimits.WALK_DEPTH).isEqualTo(16);
        assertThat(KnowledgeLimits.WALK_ENTRIES).isEqualTo(100_000);
        assertThat(KnowledgeLimits.SIZE_WALK_ENTRIES).isEqualTo(1_000_000);
        assertThat(KnowledgeLimits.START_PREPARE).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void theBoundsFitIntoEachOther() {
        assertThat(KnowledgeLimits.NOTE_BYTES).isLessThan(KnowledgeLimits.READ_BYTES);
        assertThat((long) KnowledgeLimits.READ_BYTES).isLessThan(KnowledgeLimits.TOTAL_BYTES);
        assertThat(KnowledgeLimits.PAGE_CHARS).isLessThan(KnowledgeLimits.READ_BYTES);
        assertThat(KnowledgeLimits.EXCERPT_CHARS).isLessThan(KnowledgeLimits.PAGE_CHARS)
            .isLessThan(KnowledgeLimits.LIST_CHARS);
        assertThat(KnowledgeLimits.LINE_CHARS).isLessThan(KnowledgeLimits.LIST_CHARS);
        assertThat(KnowledgeLimits.WALK_ENTRIES).isLessThan(KnowledgeLimits.SIZE_WALK_ENTRIES);
        assertThat(KnowledgeLimits.START_PREPARE).isLessThan(KnowledgeLimits.LOCK_WAIT);
    }
}
