package de.dadecker.inubit.mcp.testing;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.Fetched;
import de.dadecker.inubit.mcp.config.ParameterType;
import de.dadecker.inubit.mcp.config.StoreLocation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class FakeParameterStoreTest {

    private static final StoreLocation EU = new StoreLocation("eu-central-1", Optional.empty());
    private static final StoreLocation US =
        new StoreLocation("us-east-1", Optional.of("test-profile"));
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final FakeParameterStore store = new FakeParameterStore();

    @Test
    void answersScriptedValuesWithTheirTypeAndUnknownNamesAsNotFound() {
        store.put(EU, "/test/user", "test-user-1")
            .put(EU, "/test/password", "test-password-1", ParameterType.STRING);

        Fetched.PerName fetched = perName(store.get(EU,
            List.of("/test/user", "/test/password", "/test/missing"), TIMEOUT));

        assertThat(fetched.results()).containsOnlyKeys("/test/user", "/test/password",
            "/test/missing");
        Fetched.Value user = (Fetched.Value) fetched.results().get("/test/user");
        assertThat(user.value().reveal()).isEqualTo("test-user-1");
        assertThat(user.type()).isEqualTo(ParameterType.SECURE_STRING);
        assertThat(((Fetched.Value) fetched.results().get("/test/password")).type())
            .isEqualTo(ParameterType.STRING);
        assertThat(((Fetched.Failure) fetched.results().get("/test/missing")).kind())
            .isEqualTo(FailureKind.NOT_FOUND);
    }

    @Test
    void keepsLocationsApart() {
        store.put(EU, "/test/password", "test-password-eu")
            .put(US, "/test/password", "test-password-us");

        assertThat(value(store.get(US, List.of("/test/password"), TIMEOUT), "/test/password"))
            .isEqualTo("test-password-us");
        assertThat(value(store.get(EU, List.of("/test/password"), TIMEOUT), "/test/password"))
            .isEqualTo("test-password-eu");
    }

    @Test
    void scriptsPerNameFailures() {
        store.put(EU, "/test/user", "test-user-1")
            .fail(EU, "/test/password", FailureKind.ACCESS_DENIED, "test detail");

        Fetched.PerName fetched =
            perName(store.get(EU, List.of("/test/user", "/test/password"), TIMEOUT));

        assertThat(fetched.results().get("/test/user")).isInstanceOf(Fetched.Value.class);
        assertThat(fetched.results().get("/test/password"))
            .isEqualTo(new Fetched.Failure(FailureKind.ACCESS_DENIED, "test detail"));
    }

    @Test
    void scriptsWholeCallFailuresPerLocationUntilCleared() {
        store.put(EU, "/test/user", "test-user-1").put(US, "/test/user", "test-user-2")
            .failCall(EU, FailureKind.NO_CREDENTIALS, "test detail");

        assertThat(store.get(EU, List.of("/test/user"), TIMEOUT)).isEqualTo(
            new Fetched.CallFailed(new Fetched.Failure(FailureKind.NO_CREDENTIALS, "test detail")));
        assertThat(value(store.get(US, List.of("/test/user"), TIMEOUT), "/test/user"))
            .isEqualTo("test-user-2");

        store.clearCallFailure(EU);

        assertThat(value(store.get(EU, List.of("/test/user"), TIMEOUT), "/test/user"))
            .isEqualTo("test-user-1");
    }

    @Test
    void aNewValueReplacesTheOldOneForLaterCalls() {
        store.put(EU, "/test/password", "test-password-1");
        store.get(EU, List.of("/test/password"), TIMEOUT);

        store.put(EU, "/test/password", "test-password-2");

        assertThat(value(store.get(EU, List.of("/test/password"), TIMEOUT), "/test/password"))
            .isEqualTo("test-password-2");
    }

    @Test
    void countsCallsAndRecordsTheRequestedNames() {
        store.get(EU, List.of("/test/user", "/test/password"), TIMEOUT);
        store.get(US, List.of(), Duration.ofSeconds(3));

        assertThat(store.calls()).isEqualTo(2);
        assertThat(store.requests()).containsExactly(
            new FakeParameterStore.Request(EU, List.of("/test/user", "/test/password"), TIMEOUT),
            new FakeParameterStore.Request(US, List.of(), Duration.ofSeconds(3)));
    }

    @Test
    void delaysCallsPerLocationAndPerNameWithTheLongestDelayWinning() {
        store.delay(EU, Duration.ofMillis(50)).delay(EU, "/test/slow", Duration.ofMillis(300));

        assertThat(elapsed(() -> store.get(US, List.of("/test/slow"), TIMEOUT)))
            .isLessThan(Duration.ofMillis(200));
        assertThat(elapsed(() -> store.get(EU, List.of("/test/fast"), TIMEOUT)))
            .isGreaterThanOrEqualTo(Duration.ofMillis(50)).isLessThan(Duration.ofMillis(250));
        assertThat(elapsed(() -> store.get(EU, List.of("/test/fast", "/test/slow"), TIMEOUT)))
            .isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void recordsACallBeforeItsDelayEnds() throws Exception {
        store.delay(EU, Duration.ofSeconds(5));
        Thread caller = Thread.ofVirtual().start(() -> store.get(EU, List.of("/test/a"), TIMEOUT));
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (store.calls() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(store.calls()).isEqualTo(1);
            assertThat(caller.isAlive()).isTrue();
        } finally {
            caller.interrupt();
            caller.join();
        }
    }

    @Test
    void concurrentCallersRunInParallel() throws Exception {
        store.put(EU, "/test/password", "test-password-1").delay(EU, Duration.ofMillis(300));
        int callers = 8;
        List<Future<Fetched>> results = new ArrayList<>();
        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                results.add(executor.submit(
                    () -> store.get(EU, List.of("/test/password"), TIMEOUT)));
            }
            for (Future<Fetched> result : results) {
                assertThat(value(result.get(), "/test/password")).isEqualTo("test-password-1");
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(store.calls()).isEqualTo(callers);
        assertThat(elapsed).isLessThan(Duration.ofMillis(300L * callers / 2));
    }

    private static Fetched.PerName perName(Fetched fetched) {
        assertThat(fetched).isInstanceOf(Fetched.PerName.class);
        return (Fetched.PerName) fetched;
    }

    private static String value(Fetched fetched, String name) {
        return ((Fetched.Value) perName(fetched).results().get(name)).value().reveal();
    }

    private static Duration elapsed(Runnable call) {
        long start = System.nanoTime();
        call.run();
        return Duration.ofNanos(System.nanoTime() - start);
    }
}
