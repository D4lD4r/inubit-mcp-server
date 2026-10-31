package de.dadecker.inubit.mcp.testing;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.config.CredentialStore.Lookup;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.StoreKind;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Feature 011 T002: the in-memory credential store double. */
class FakeCredentialStoreTest {

    private final FakeCredentialStore store = new FakeCredentialStore();

    @Test
    void answersScriptedValuesFailuresAndNotFound() {
        store.put("INUBIT_ACME_DEV_USERNAME", "jdoe")
            .fail("INUBIT_ACME_DEV_PASSWORD", FailureKind.ACCESS_DENIED, "denied");

        assertThat(store.read("INUBIT_ACME_DEV_USERNAME", Duration.ofSeconds(1)))
            .isInstanceOfSatisfying(Lookup.Found.class,
                found -> assertThat(found.value().reveal()).isEqualTo("jdoe"));
        assertThat(store.read("INUBIT_ACME_DEV_PASSWORD", Duration.ofSeconds(1)))
            .isInstanceOfSatisfying(Lookup.Failed.class,
                failed -> assertThat(failed.kind()).isEqualTo(FailureKind.ACCESS_DENIED));
        assertThat(store.read("INUBIT_ACME_DEV_NODE1_PASSWORD", Duration.ofSeconds(1)))
            .isInstanceOf(Lookup.NotFound.class);
    }

    @Test
    void recordsEveryReadInOrder() {
        store.read("A", Duration.ofSeconds(2));
        store.read("B", Duration.ofSeconds(3));

        assertThat(store.reads()).extracting(FakeCredentialStore.Read::account)
            .containsExactly("A", "B");
        assertThat(store.reads().get(1).timeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void aDelayedReadTakesAtLeastItsDelay() {
        store.put("A", "value-1").delay("A", Duration.ofMillis(50));

        long start = System.nanoTime();
        store.read("A", Duration.ofSeconds(1));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(
            Duration.ofMillis(50));
    }

    @Test
    void anInterruptedReadTimesOutAndKeepsTheFlag() {
        store.delay("A", Duration.ofSeconds(10));
        Thread.currentThread().interrupt();

        Lookup lookup = store.read("A", Duration.ofSeconds(1));

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.TIMEOUT));
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void hasAConfigurableKind() {
        assertThat(store.kind()).isEqualTo(StoreKind.KEYCHAIN);
        assertThat(new FakeCredentialStore(StoreKind.SECRET_SERVICE).kind())
            .isEqualTo(StoreKind.SECRET_SERVICE);
    }
}
