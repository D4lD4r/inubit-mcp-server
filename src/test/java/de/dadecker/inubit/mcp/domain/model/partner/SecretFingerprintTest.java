package de.dadecker.inubit.mcp.domain.model.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.infra.ResultJson;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.exc.InvalidDefinitionException;

/** T005: a fingerprint compares by its bytes but never reveals them. */
class SecretFingerprintTest {

    @Test
    void fingerprintsAreEqualOnlyIfTheirBytesAreEqual() {
        SecretFingerprint fingerprint = SecretFingerprint.of(new byte[] {1, 2, 3});

        assertThat(fingerprint).isEqualTo(SecretFingerprint.of(new byte[] {1, 2, 3}))
            .hasSameHashCodeAs(SecretFingerprint.of(new byte[] {1, 2, 3}));
        assertThat(fingerprint).isNotEqualTo(SecretFingerprint.of(new byte[] {1, 2, 4}));
        assertThat(fingerprint).isNotEqualTo(SecretFingerprint.of(new byte[] {1, 2}));
    }

    @Test
    void theFingerprintKeepsNoReferenceToTheInput() {
        byte[] input = {1, 2, 3};
        SecretFingerprint fingerprint = SecretFingerprint.of(input);

        Arrays.fill(input, (byte) 0);

        assertThat(fingerprint).isEqualTo(SecretFingerprint.of(new byte[] {1, 2, 3}));
    }

    @Test
    void aFingerprintNeedsBytes() {
        assertThatNullPointerException().isThrownBy(() -> SecretFingerprint.of(null))
            .withMessage("bytes");
        assertThatIllegalArgumentException().isThrownBy(() -> SecretFingerprint.of(new byte[0]))
            .withMessageContaining("bytes");
    }

    @Test
    void toStringIsRedacted() {
        assertThat(SecretFingerprint.of(new byte[] {1, 2, 3}))
            .hasToString("SecretFingerprint[redacted]");
    }

    @Test
    void itIsAFinalClassWithoutAccessor() {
        assertThat(SecretFingerprint.class.isRecord()).isFalse();
        assertThat(Modifier.isFinal(SecretFingerprint.class.getModifiers())).isTrue();
        assertThat(Arrays.stream(SecretFingerprint.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .filter(method -> !Modifier.isStatic(method.getModifiers()))
            .map(java.lang.reflect.Method::getName))
            .containsExactlyInAnyOrder("equals", "hashCode", "toString");
        assertThat(Arrays.stream(SecretFingerprint.class.getDeclaredFields())
            .allMatch(field -> Modifier.isPrivate(field.getModifiers()))).isTrue();
    }

    @Test
    void theResultMapperRefusesToSerializeIt() {
        assertThatThrownBy(() -> ResultJson.mapper()
            .writeValueAsString(SecretFingerprint.of(new byte[] {1, 2, 3})))
            .isInstanceOf(InvalidDefinitionException.class)
            .hasMessageContaining("SecretFingerprint");
    }
}
