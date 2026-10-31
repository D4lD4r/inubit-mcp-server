package de.dadecker.inubit.mcp.adapter.os;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.config.CredentialStore.Lookup;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.StoreKind;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Feature 011 T006 (research R-4, quickstart Q6): decoding and outcome mapping of the Windows
 * Credential Manager adapter through the {@link CredApi} seam; the JNA call itself is covered by the
 * Windows live check only.
 */
class WindowsCredentialStoreTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final List<String> targets = new ArrayList<>();

    private WindowsCredentialStore store(CredApi.Result result) {
        return new WindowsCredentialStore(target -> {
            targets.add(target);
            return result;
        }, new StoreGate());
    }

    @Test
    void readsTheGenericCredentialOfTheTargetNameAsUtf16() {
        byte[] blob = "Pässwort-€1".getBytes(StandardCharsets.UTF_16LE);

        Lookup lookup = store(new CredApi.Result.Blob(blob))
            .read("INUBIT_ACME_DEV_PASSWORD", TIMEOUT);

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Found.class,
            found -> assertThat(found.value().reveal()).isEqualTo("Pässwort-€1"));
        assertThat(targets).containsExactly("inubit-mcp:INUBIT_ACME_DEV_PASSWORD");
        assertThat(blob).containsOnly((byte) 0);
    }

    @Test
    void anOddLengthIsAnInvalidValue() {
        Lookup lookup = store(new CredApi.Result.Blob(new byte[] {0x41, 0x00, 0x42}))
            .read("A", TIMEOUT);

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.INVALID_VALUE));
    }

    @Test
    void anUnpairedSurrogateIsAnInvalidValue() {
        Lookup lookup = store(new CredApi.Result.Blob(new byte[] {0x00, (byte) 0xD8, 0x41, 0x00}))
            .read("A", TIMEOUT);

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.INVALID_VALUE));
    }

    @Test
    void anEmptyBlobIsAnEmptyValue() {
        assertThat(store(new CredApi.Result.Blob(new byte[0])).read("A", TIMEOUT))
            .isInstanceOfSatisfying(Lookup.Found.class,
                found -> assertThat(found.value().reveal()).isEmpty());
    }

    @Test
    void errorNotFoundIsNotFound() {
        assertThat(store(new CredApi.Result.Error(1168)).read("A", TIMEOUT))
            .isInstanceOf(Lookup.NotFound.class);
    }

    @Test
    void noLogonSessionIsNoStore() {
        assertThat(store(new CredApi.Result.Error(1312)).read("A", TIMEOUT))
            .isInstanceOfSatisfying(Lookup.Failed.class,
                failed -> assertThat(failed.kind()).isEqualTo(FailureKind.STORE_NOT_AVAILABLE));
    }

    @Test
    void otherErrorsAreAccessDeniedWithTheNumber() {
        assertThat(store(new CredApi.Result.Error(5)).read("A", TIMEOUT))
            .isInstanceOfSatisfying(Lookup.Failed.class, failed -> {
                assertThat(failed.kind()).isEqualTo(FailureKind.ACCESS_DENIED);
                assertThat(failed.detail()).contains("error 5");
            });
    }

    @Test
    void aNativeLibraryThatCannotBeLoadedIsNoStore() {
        WindowsCredentialStore store = new WindowsCredentialStore(target -> {
            throw new UnsatisfiedLinkError("jnidispatch");
        }, new StoreGate());

        assertThat(store.read("A", TIMEOUT)).isInstanceOfSatisfying(Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.STORE_NOT_AVAILABLE));
    }

    @Test
    void aReadThatDoesNotReturnTimesOut() {
        WindowsCredentialStore store = new WindowsCredentialStore(target -> {
            try {
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new CredApi.Result.Error(1168);
        }, new StoreGate());

        assertThat(store.read("A", Duration.ofMillis(100))).isInstanceOfSatisfying(
            Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.TIMEOUT));
    }

    @Test
    void isTheCredentialManager() {
        assertThat(new WindowsCredentialStore(new StoreGate()).kind())
            .isEqualTo(StoreKind.CREDENTIAL_MANAGER);
    }
}
