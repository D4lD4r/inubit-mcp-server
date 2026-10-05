package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The inventory cache of all servers (FR-016b, research R-11; data-model.md →
 * InventorySnapshot): diagram lists, module indexes and version histories take seconds to
 * collect (the module export about 10–15 s), so they are kept per {@link Key}.
 *
 * <ul>
 *   <li>Key {@code (server, kind, scope)}; {@code scope} is {@link #ALL} for lists or e.g. the
 *       diagram group of a version history.
 *   <li>An entry lives for the server's {@code inventory.cacheTtl} from the start of its load
 *       ({@code collectedAt}); the clock is injected.
 *   <li>Single flight: concurrent requests for a key share one load in flight; a
 *       {@code refresh} also joins a load in flight (its data is fresh) and otherwise replaces the
 *       entry.
 *   <li>A failed load is not cached: its failure reaches the caller and every waiter, and the
 *       next request loads again.
 *   <li>A waiter that is interrupted (fan-out deadline) gets {@code TIMEOUT}; the load itself
 *       continues in its own caller and is cached when it completes.
 * </ul>
 */
public final class InventoryCache {

    /** Scope of a whole list (diagrams or modules) of a server. */
    public static final String ALL = "all";

    /** What is cached: one list or one version history of one server. */
    public record Key(NodeId node, InventoryKind kind, String scope) {
        public Key {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(scope, "scope");
        }
    }

    /**
     * A cached value.
     *
     * @param collectedAt when its load started
     * @param expiresAt   {@code collectedAt + inventory.cacheTtl}
     */
    public record Snapshot<T>(T value, Instant collectedAt, Instant expiresAt) {
        public Snapshot {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(collectedAt, "collectedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    private final Clock clock;
    private final Function<NodeId, Duration> ttls;
    private final ConcurrentMap<Key, CompletableFuture<Snapshot<?>>> entries =
        new ConcurrentHashMap<>();

    /** @param ttls the {@code inventory.cacheTtl} of each server */
    public InventoryCache(Clock clock, Function<NodeId, Duration> ttls) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttls = Objects.requireNonNull(ttls, "ttls");
    }

    /**
     * The cached value of {@code key}, or the value {@code loader} returns (in the caller's
     * thread).
     *
     * @param refresh bypass a cached value (a load in flight is still shared)
     * @param loader  must return a non-null value; it is called by at most one caller at a time
     * @throws ToolErrorException the failure of the load, or {@code TIMEOUT} if the caller is
     *     interrupted while waiting for another caller's load
     */
    @SuppressWarnings("unchecked")
    public <T> Snapshot<T> get(Key key, boolean refresh, Supplier<T> loader) {
        while (true) {
            CompletableFuture<Snapshot<?>> existing = entries.get(key);
            CompletableFuture<Snapshot<?>> mine = new CompletableFuture<>();
            if (existing == null) {
                if (entries.putIfAbsent(key, mine) == null) {
                    return load(key, mine, loader);
                }
                continue;
            }
            if (!existing.isDone()) {
                return (Snapshot<T>) await(existing);
            }
            if (existing.isCompletedExceptionally()) {
                // a failed load between its removal and this lookup: never served
                entries.remove(key, existing);
                continue;
            }
            Snapshot<?> cached = existing.getNow(null);
            if (!refresh && clock.instant().isBefore(cached.expiresAt())) {
                return (Snapshot<T>) cached;
            }
            if (entries.replace(key, existing, mine)) {
                return load(key, mine, loader);
            }
        }
    }

    /**
     * Removes {@code snapshot} from the cache if it is still the entry of {@code key}, so that
     * the next request loads again (T126: an incomplete module usage index is served to the
     * callers that waited for it, but not kept). A newer entry is left alone.
     */
    public void discard(Key key, Snapshot<?> snapshot) {
        entries.computeIfPresent(key, (k, entry) -> entry.isDone()
            && !entry.isCompletedExceptionally() && entry.getNow(null) == snapshot ? null : entry);
    }

    private <T> Snapshot<T> load(Key key, CompletableFuture<Snapshot<?>> mine,
        Supplier<T> loader) {
        Instant started = clock.instant();
        try {
            Snapshot<T> snapshot = new Snapshot<>(loader.get(), started,
                started.plus(ttls.apply(key.node())));
            mine.complete(snapshot);
            return snapshot;
        } catch (RuntimeException | Error e) {
            entries.remove(key, mine);
            mine.completeExceptionally(e);
            throw e;
        }
    }

    private static Snapshot<?> await(CompletableFuture<Snapshot<?>> load) {
        try {
            return load.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
                "The request was cancelled while waiting for an inventory load in progress",
                "The overall deadline of the request expired; the load continues and is cached",
                "Retry in a moment"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("inventory load failed", cause);
        }
    }
}
