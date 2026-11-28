package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls several servers in parallel, one virtual thread per server, each with its own deadline
 * measured from the start of the fan-out (research R-15, SC-002).
 *
 * <p>Results come back in input (config) order. A server that misses its deadline yields
 * {@code TIMEOUT} and its call is interrupted; failures never hide the other servers' results.
 * The fan-out returns after at most the largest deadline, even if a call ignores interrupts.
 *
 * <p>Feature 025 (FR-030 (b)): a thrown {@link ToolErrorException} keeps its
 * {@link ToolErrorException#warnings() warnings} in the server's failure; with a {@link Sink}, a
 * server that misses its deadline gets the warnings its call registered there.
 */
public final class FanOut {

    private static final Logger LOG = LoggerFactory.getLogger(FanOut.class);

    /** One server's call; a {@link ToolErrorException} becomes that server's error. */
    @FunctionalInterface
    public interface ServerCall<T> {
        T call(NodeId server) throws Exception;
    }

    /**
     * Where the calls of one fan-out register the warnings of their server as soon as they are
     * known (e.g. right after the version gate admitted it), so that a deadline {@code TIMEOUT}
     * still carries them.
     */
    public static final class Sink {

        private final Map<NodeId, List<String>> warnings = new ConcurrentHashMap<>();

        public void register(NodeId server, List<String> serverWarnings) {
            warnings.put(server, List.copyOf(serverWarnings));
        }

        private List<String> of(NodeId server) {
            return warnings.getOrDefault(server, List.of());
        }
    }

    public <T> List<NodeResult<T>> run(List<NodeId> servers, Duration deadline,
        ServerCall<T> call) {
        return run(servers, server -> deadline, call);
    }

    public <T> List<NodeResult<T>> run(List<NodeId> servers,
        Function<NodeId, Duration> deadlineOf, ServerCall<T> call) {
        return run(servers, deadlineOf, call, new Sink());
    }

    /**
     * As {@link #run(List, Function, ServerCall)}; a deadline {@code TIMEOUT} carries the warnings
     * the server's call registered in {@code sink}, none if it registered none.
     */
    public <T> List<NodeResult<T>> run(List<NodeId> servers,
        Function<NodeId, Duration> deadlineOf, ServerCall<T> call, Sink sink) {
        if (servers.isEmpty()) {
            return List.of();
        }
        List<Duration> deadlines = servers.stream().map(deadlineOf).toList();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        long start = System.nanoTime();
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (NodeId server : servers) {
                futures.add(executor.submit(() -> call.call(server)));
            }
            List<NodeResult<T>> results = new ArrayList<>();
            for (int i = 0; i < servers.size(); i++) {
                long remaining = deadlines.get(i).toNanos() - (System.nanoTime() - start);
                results.add(await(servers.get(i), futures.get(i), remaining, deadlines.get(i),
                    sink));
            }
            return List.copyOf(results);
        } finally {
            // never close(): it would wait for calls that ignore interrupts
            executor.shutdownNow();
        }
    }

    private static <T> NodeResult<T> await(NodeId server, Future<T> future, long remainingNanos,
        Duration deadline, Sink sink) {
        if (Thread.currentThread().isInterrupted() && !future.isDone()) {
            return NodeResult.failure(server, interrupted(future, server));
        }
        try {
            T payload = future.get(Math.max(remainingNanos, 0), TimeUnit.NANOSECONDS);
            return payload == null
                ? NodeResult.failure(server, internal(server, "returned no result"))
                : NodeResult.success(server, payload);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new NodeResult<>(server, Optional.empty(),
                Optional.of(timeout(server, deadline)), sink.of(server));
        } catch (ExecutionException e) {
            List<String> warnings = e.getCause() instanceof ToolErrorException toolError
                ? toolError.warnings() : List.of();
            return new NodeResult<>(server, Optional.empty(),
                Optional.of(failure(server, e.getCause())), warnings);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NodeResult.failure(server, interrupted(future, server));
        }
    }

    private static ToolError failure(NodeId server, Throwable cause) {
        if (cause instanceof ToolErrorException toolError) {
            ToolError error = toolError.error();
            return error.node().isPresent() ? error : error.withNode(server);
        }
        // logged with the stack trace (the log encoder scrubs secrets); the tool result only
        // names the exception class, because the message may contain unscrubbed response text
        LOG.warn("Call to {} failed unexpectedly", server, cause);
        return internal(server, "failed unexpectedly (" + cause.getClass().getSimpleName() + ")");
    }

    private static ToolError interrupted(Future<?> future, NodeId server) {
        future.cancel(true);
        return internal(server, "was interrupted before it completed");
    }

    private static ToolError timeout(NodeId server, Duration deadline) {
        return ToolError.of(ErrorCode.TIMEOUT,
            "No response from " + server + " within " + Durations.human(deadline),
            "The INUBIT server or the network is slow, overloaded or hanging.",
            "Retry later, check it with get_health, or raise its timeout in the"
                + " configuration.")
            .withNode(server);
    }

    private static ToolError internal(NodeId server, String what) {
        return ToolError.of(ErrorCode.INTERNAL, "The call to " + server + " " + what,
            "An unexpected error inside the MCP server.",
            "Retry; if it persists, check the MCP server's log on stderr for the details.")
            .withNode(server);
    }

}
