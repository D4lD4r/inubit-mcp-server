package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import de.dadecker.inubit.mcp.domain.model.partner.SecretFingerprint;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * T009 (feature 018 FR-012b, research D-5): a keyed fingerprint of a stored secret value, with a key
 * that lives only in this process and is never reachable.
 */
class SecretFingerprinterTest {

    @Test
    void equalValuesGiveEqualFingerprints() {
        SecretFingerprinter fingerprinter = new SecretFingerprinter();

        assertThat(fingerprinter.fingerprint("AES:4f2a9c"))
            .isEqualTo(fingerprinter.fingerprint("AES:4f2a9c"))
            .hasSameHashCodeAs(fingerprinter.fingerprint("AES:4f2a9c"));
    }

    @Test
    void differentValuesGiveDifferentFingerprints() {
        SecretFingerprinter fingerprinter = new SecretFingerprinter();

        assertThat(fingerprinter.fingerprint("AES:4f2a9c"))
            .isNotEqualTo(fingerprinter.fingerprint("AES:4f2a9d"))
            .isNotEqualTo(fingerprinter.fingerprint(""));
        assertThat(fingerprinter.fingerprint("geheim-ä"))
            .isNotEqualTo(fingerprinter.fingerprint("geheim-a"));
    }

    @Test
    void twoProcessesGiveDifferentFingerprintsForTheSameValue() {
        SecretFingerprint first = new SecretFingerprinter().fingerprint("AES:4f2a9c");
        SecretFingerprint second = new SecretFingerprinter().fingerprint("AES:4f2a9c");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void theProcessKeepsOneFingerprinter() {
        assertThat(SecretFingerprinter.process()).isSameAs(SecretFingerprinter.process());
        assertThat(SecretFingerprinter.process().fingerprint("AES:4f2a9c"))
            .isEqualTo(SecretFingerprinter.process().fingerprint("AES:4f2a9c"));
    }

    @Test
    void aValueIsRequired() {
        assertThatNullPointerException()
            .isThrownBy(() -> new SecretFingerprinter().fingerprint(null))
            .withMessage("storedValue");
    }

    @Test
    void theKeyIsNeverReachable() {
        assertThat(Modifier.isFinal(SecretFingerprinter.class.getModifiers())).isTrue();
        assertThat(Arrays.stream(SecretFingerprinter.class.getDeclaredFields())
            .allMatch(field -> Modifier.isPrivate(field.getModifiers()))).isTrue();
        assertThat(Arrays.stream(SecretFingerprinter.class.getDeclaredMethods())
            .filter(method -> !Modifier.isPrivate(method.getModifiers()))
            .filter(method -> !method.isSynthetic())
            .map(Method::getName))
            .containsExactlyInAnyOrder("fingerprint", "process", "toString");
    }

    @Test
    void toStringIsRedacted() {
        assertThat(new SecretFingerprinter()).hasToString("SecretFingerprinter[redacted]");
    }
}
