package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.Fetched;
import de.dadecker.inubit.mcp.config.ParameterStore;
import de.dadecker.inubit.mcp.config.ParameterType;
import de.dadecker.inubit.mcp.config.StoreLocation;
import de.dadecker.inubit.mcp.infra.Secret;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory {@link ParameterStore} (feature 010); thread-safe. Values and failures are
 * scripted per location and name, whole-call failures and delays per location, delays also per
 * name. A call sleeps the longest delay that applies to it (real time, interruptible), then
 * answers from the script as it is at that moment; names without a script are {@code NOT_FOUND}.
 * The {@code timeout} is recorded but never enforced, so a long delay stands for a store call that
 * never returns; bounding the call is the caller's job ({@code NodeSecrets} does it). A caller
 * interrupted during the delay gets {@code CallFailed(UNREACHABLE, "interrupted")} with its
 * interrupt flag set again.
 */
public final class FakeParameterStore implements ParameterStore {

    /** One call to {@link #get}. */
    public record Request(StoreLocation location, List<String> names, Duration timeout) {
    }

    private record Key(StoreLocation location, String name) {
    }

    private final Map<Key, Fetched.Result> results = new ConcurrentHashMap<>();
    private final Map<Key, Duration> nameDelays = new ConcurrentHashMap<>();
    private final Map<StoreLocation, Duration> locationDelays = new ConcurrentHashMap<>();
    private final Map<StoreLocation, Fetched.Failure> callFailures = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    /** Stores {@code value} as a {@code SecureString}, replacing any earlier script for it. */
    public FakeParameterStore put(StoreLocation location, String name, String value) {
        return put(location, name, value, ParameterType.SECURE_STRING);
    }

    /** Stores {@code value} with {@code type}, replacing any earlier script for the name. */
    public FakeParameterStore put(StoreLocation location, String name, String value,
        ParameterType type) {
        results.put(new Key(location, name), new Fetched.Value(Secret.of(value), type));
        return this;
    }

    /** Reading {@code name} fails with {@code kind}, replacing any earlier script for it. */
    public FakeParameterStore fail(StoreLocation location, String name, FailureKind kind,
        String logDetail) {
        results.put(new Key(location, name), new Fetched.Failure(kind, logDetail));
        return this;
    }

    /** Every call to {@code location} fails as a whole until {@link #clearCallFailure}. */
    public FakeParameterStore failCall(StoreLocation location, FailureKind kind,
        String logDetail) {
        callFailures.put(location, new Fetched.Failure(kind, logDetail));
        return this;
    }

    public FakeParameterStore clearCallFailure(StoreLocation location) {
        callFailures.remove(location);
        return this;
    }

    /** Every call to {@code location} takes at least {@code delay}. */
    public FakeParameterStore delay(StoreLocation location, Duration delay) {
        locationDelays.put(location, delay);
        return this;
    }

    /** Every call to {@code location} that asks for {@code name} takes at least {@code delay}. */
    public FakeParameterStore delay(StoreLocation location, String name, Duration delay) {
        nameDelays.put(new Key(location, name), delay);
        return this;
    }

    /** The number of calls so far, including calls still waiting out their delay. */
    public int calls() {
        return requests.size();
    }

    /** The calls so far in the order they started. */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public Fetched get(StoreLocation location, List<String> names, Duration timeout) {
        requests.add(new Request(location, List.copyOf(names), timeout));
        try {
            Thread.sleep(delayOf(location, names));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Fetched.CallFailed(
                new Fetched.Failure(FailureKind.UNREACHABLE, "interrupted"));
        }
        Fetched.Failure callFailure = callFailures.get(location);
        if (callFailure != null) {
            return new Fetched.CallFailed(callFailure);
        }
        Map<String, Fetched.Result> answer = new HashMap<>();
        for (String name : names) {
            answer.put(name, results.getOrDefault(new Key(location, name),
                new Fetched.Failure(FailureKind.NOT_FOUND, "no such parameter: " + name)));
        }
        return new Fetched.PerName(answer);
    }

    private Duration delayOf(StoreLocation location, List<String> names) {
        Duration longest = locationDelays.getOrDefault(location, Duration.ZERO);
        for (String name : names) {
            Duration delay = nameDelays.getOrDefault(new Key(location, name), Duration.ZERO);
            if (delay.compareTo(longest) > 0) {
                longest = delay;
            }
        }
        return longest;
    }
}
