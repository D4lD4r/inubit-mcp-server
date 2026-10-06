package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T005 (research D-7, FR-024): placeholders name the property path and nothing else. */
class SecretPlaceholderTest {

    @Test
    void rendersThePropertyPath() {
        assertThat(new SecretPlaceholder("Mime.Sign.Password").render())
            .isEqualTo("${secret:Mime.Sign.Password}");
        assertThat(new SecretPlaceholder("xslt.sourceVariables/ISCurrentTime").render())
            .isEqualTo("${secret:xslt.sourceVariables/ISCurrentTime}");
        assertThat(new SecretPlaceholder("Mime.Sign.Password")).hasToString(
            "${secret:Mime.Sign.Password}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Password", "Mime.Decrypt.Keystore", "xslt.sourceVariables/var.a b",
        "Assignments/var.fixturePassword", "Module-0008(4)@@@DeMuxInput"})
    void parsingARenderedPlaceholderYieldsItsPath(String path) {
        SecretPlaceholder placeholder = new SecretPlaceholder(path);

        assertThat(SecretPlaceholder.parse(placeholder.render())).contains(placeholder);
        assertThat(SecretPlaceholder.isPlaceholder(placeholder.render())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "secret", "${secret:}", "${secret:a", "x${secret:a}",
        "${secret:a} ", "${secret:a}}", "${SECRET:a}", "AES-U1lOVEgtQUVTLTAwMDAwMDAx"})
    void otherTextIsNoPlaceholder(String text) {
        assertThat(SecretPlaceholder.parse(text)).isEmpty();
        assertThat(SecretPlaceholder.isPlaceholder(text)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " a", "a ", "a}b", "a{b", "a\nb", "a\tb"})
    void propertyPathsThatCannotBeRenderedUnambiguouslyAreRejected(String path) {
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretPlaceholder(path))
            .withMessageContaining("property path");
    }

    @Test
    void theInvalidPathIsNotEchoed() {
        // a mistaken caller could pass a value; the message must not repeat it
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretPlaceholder("se{cret"))
            .withMessageNotContaining("se{cret");
        assertThatNullPointerException().isThrownBy(() -> new SecretPlaceholder(null));
        assertThat(SecretPlaceholder.parse(null)).isEmpty();
    }
}
