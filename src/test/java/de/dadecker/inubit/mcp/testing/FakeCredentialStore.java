package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.config.CredentialStore;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.StoreKind;
import de.dadecker.inubit.mcp.infra.Secret;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory {@link CredentialStore} (feature 011); thread-safe. Values and failures are scripted
 * per account, delays per account. A read sleeps its delay (real time, interruptible), then answers
 * from the script as it is at that moment; accounts without a script are {@code NotFound}. The
 * {@code timeout} is recorded but never enforced, so a long delay stands for an access dialog
 * nobody answers; bounding the read is the caller's job. A caller interrupted during the delay gets
 * {@code Failed(TIMEOUT, "interrupted")} with its interrupt flag set again.
 */
public final class FakeCredentialStore implements CredentialStore {

    /** One call to {@link #read}. */
    public record Read(String account, Duration timeout) {
    }

    private final StoreKind kind;
    private final Map<String, Lookup> lookups = new ConcurrentHashMap<>();
    private final Map<String, Duration> delays = new ConcurrentHashMap<>();
    private final List<Read> reads = new CopyOnWriteArrayList<>();

    /** A double of the macOS Keychain. */
    public FakeCredentialStore() {
        this(StoreKind.KEYCHAIN);
    }

    public FakeCredentialStore(StoreKind kind) {
        this.kind = kind;
    }

    /** Stores {@code value} for {@code account}, replacing any earlier script for it. */
    public FakeCredentialStore put(String account, String value) {
        lookups.put(account, new Lookup.Found(Secret.of(value)));
        return this;
    }

    /** Reading {@code account} fails with {@code kind}, replacing any earlier script for it. */
    public FakeCredentialStore fail(String account, FailureKind failure, String detail) {
        lookups.put(account, new Lookup.Failed(failure, detail));
        return this;
    }

    /** Removes the script of {@code account}; it is {@code NotFound} again. */
    public FakeCredentialStore remove(String account) {
        lookups.remove(account);
        return this;
    }

    /** Every read of {@code account} takes at least {@code delay}. */
    public FakeCredentialStore delay(String account, Duration delay) {
        delays.put(account, delay);
        return this;
    }

    /** The reads so far in the order they started, including reads still waiting out a delay. */
    public List<Read> reads() {
        return List.copyOf(reads);
    }

    @Override
    public StoreKind kind() {
        return kind;
    }

    @Override
    public Lookup read(String account, Duration timeout) {
        reads.add(new Read(account, timeout));
        try {
            Thread.sleep(delays.getOrDefault(account, Duration.ZERO));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Lookup.Failed(FailureKind.TIMEOUT, "interrupted");
        }
        return lookups.getOrDefault(account, new Lookup.NotFound());
    }
}
