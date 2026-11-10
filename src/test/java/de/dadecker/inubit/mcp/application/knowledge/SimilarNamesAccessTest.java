package de.dadecker.inubit.mcp.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.SimilarNames;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The knowledge package suggests similar note names with the shared rule (feature 019, T006). */
class SimilarNamesAccessTest {

    @Test
    void theKnowledgePackageCanUseSimilarNames() {
        assertThat(SimilarNames.of("acme", List.of("partners/acme", "other"), 5))
            .containsExactly("partners/acme");
    }
}
