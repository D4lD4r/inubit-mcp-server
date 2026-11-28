package de.dadecker.inubit.mcp.domain.model.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

/** T004: a difference holds two shown values, never a secret placeholder. */
class DifferenceTest {

    @Test
    void aDifferenceKeepsNameAndBothValues() {
        Difference difference = new Difference("ServerName", "https://central.example.test",
            "http://localhost:8000/dummy");

        assertThat(difference.name()).isEqualTo("ServerName");
        assertThat(difference.central()).isEqualTo("https://central.example.test");
        assertThat(difference.module()).isEqualTo("http://localhost:8000/dummy");
    }

    @Test
    void neitherValueContainsASecretPlaceholder() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Difference("Url",
            "https://user:${secret:ab12}@central.example.test", "https://central.example.test"))
            .withMessageContaining("central");
        assertThatIllegalArgumentException().isThrownBy(() -> new Difference("Url",
            "https://central.example.test", "${secret:ab12}"))
            .withMessageContaining("module");
    }

    @Test
    void everyFieldIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new Difference(null, "a", "b"))
            .withMessage("name");
        assertThatNullPointerException().isThrownBy(() -> new Difference("Url", null, "b"))
            .withMessage("central");
        assertThatNullPointerException().isThrownBy(() -> new Difference("Url", "a", null))
            .withMessage("module");
    }
}
