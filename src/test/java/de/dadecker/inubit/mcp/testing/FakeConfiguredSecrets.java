package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.domain.model.SecretSpan;
import de.dadecker.inubit.mcp.domain.port.ConfiguredSecrets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory {@link ConfiguredSecrets} (feature 019, research D-7); thread-safe. The state is
 * scripted: {@link #held}, {@link #nothingConfigured}, {@link #waiting} or {@link #failed}, and can
 * change later ({@link #hold}). {@link #occurrences} finds the plain spelling of the held values
 * only; the other spellings are the scrubber's job ({@code ProfileSecrets}).
 */
public final class FakeConfiguredSecrets implements ConfiguredSecrets {

    private volatile Readiness readiness;
    private volatile List<String> values;
    private final List<Duration> prepared = new CopyOnWriteArrayList<>();
    private volatile Error occurrencesFailure;

    private FakeConfiguredSecrets(Readiness readiness, List<String> values) {
        this.readiness = readiness;
        this.values = List.copyOf(values);
    }

    /** Every credential is read; {@code values} are configured. */
    public static FakeConfiguredSecrets held(String... values) {
        return new FakeConfiguredSecrets(new Readiness.Ready(), List.of(values));
    }

    /** Nothing is configured for any node. */
    public static FakeConfiguredSecrets nothingConfigured() {
        return new FakeConfiguredSecrets(new Readiness.Ready(), List.of());
    }

    /** The read of {@code node} waits (an access dialog nobody answers). */
    public static FakeConfiguredSecrets waiting(String node, String store) {
        return new FakeConfiguredSecrets(new Readiness.NotReady(
            List.of(new Pending(node, store, "waiting"))), List.of());
    }

    /** The read of {@code node} failed with {@code kind} (lower case, e.g. {@code unreachable}). */
    public static FakeConfiguredSecrets failed(String node, String store, String kind) {
        return new FakeConfiguredSecrets(new Readiness.NotReady(
            List.of(new Pending(node, store, kind))), List.of());
    }

    /** From now on every credential is read and {@code held} are configured. */
    public void hold(String... held) {
        values = List.of(held);
        readiness = new Readiness.Ready();
    }

    /** From now on {@link #occurrences} throws {@code error}. */
    public void failOccurrences(Error error) {
        occurrencesFailure = error;
    }

    /** The bounds of every {@link #prepare} call, in order. */
    public List<Duration> prepared() {
        return List.copyOf(prepared);
    }

    @Override
    public Readiness prepare(Duration bound) {
        prepared.add(bound);
        return readiness;
    }

    @Override
    public List<SecretSpan> occurrences(String text) {
        if (occurrencesFailure != null) {
            throw occurrencesFailure;
        }
        List<SecretSpan> spans = new ArrayList<>();
        for (String value : values) {
            for (int at = text.indexOf(value); at >= 0; at = text.indexOf(value, at + 1)) {
                spans.add(new SecretSpan(at, at + value.length()));
            }
        }
        return spans;
    }
}
