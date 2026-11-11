package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the suggestions for a name that was not found (feature 019, T006); the rule is shared by the
 * inventory and the local knowledge.
 */
class SimilarNamesTest {

    @Test
    void containedNamesAndNamesWithinTheEditDistanceAreSuggestedClosestFirst() {
        List<String> candidates = List.of("workflow-0101", "Workflow-0102", "unrelated", "flow",
            "workflow-0101-copy", "wf");

        assertThat(SimilarNames.of("workflow-0101", candidates, 10))
            .containsExactly("Workflow-0102", "workflow-0101-copy", "flow");
    }

    @Test
    void theNameItselfIsNeverSuggestedButOtherCaseIs() {
        assertThat(SimilarNames.of("Acme", List.of("Acme", "acme", "ACME"), 5))
            .containsExactly("ACME", "acme");
    }

    @Test
    void shortCandidatesAreSuggestedOnlyWithinTheEditDistance() {
        assertThat(SimilarNames.of("abcdefghij", List.of("ab", "abc"), 5)).containsExactly("abc");
        assertThat(SimilarNames.MIN_CONTAINED).isEqualTo(3);
    }

    @Test
    void theNumberOfSuggestionsIsBoundedAndDuplicatesCollapse() {
        assertThat(SimilarNames.of("node", List.of("node1", "node2", "node1", "node3"), 2))
            .containsExactly("node1", "node2");
        assertThat(SimilarNames.of("x", List.of(), 5)).isEmpty();
        assertThat(SimilarNames.of("", List.of("a", "abc"), 5)).containsExactly("a");
    }

    @Test
    void theLevenshteinDistanceCountsEdits() {
        assertThat(SimilarNames.levenshtein("kitten", "sitting")).isEqualTo(3);
        assertThat(SimilarNames.levenshtein("", "abc")).isEqualTo(3);
        assertThat(SimilarNames.levenshtein("same", "same")).isZero();
    }
}
