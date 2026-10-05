package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Builds a {@link ModuleUsage} (T126): reads the nodes of every technical workflow, at most
 * {@code concurrency} at a time (one virtual thread each; the server's credential guard still
 * applies to every call), all within {@code budget}.
 *
 * <ul>
 *   <li>Account-lockout protection (follow-up N1): the credentials may have become invalid since
 *       they were confirmed (e.g. a password changed mid-session), and parallel reads would then
 *       all be rejected logins. So workflows are read <em>one at a time</em> until the first
 *       read succeeds; only then are the others read in parallel. After the first
 *       {@code AUTH_FAILED} no further read is started (the rest stay unread).
 *   <li>A workflow that cannot be read (any {@link ToolErrorException}, also {@code NOT_FOUND}:
 *       a workflow that was listed but cannot be read might use the module) makes the index
 *       incomplete; the failure names the first such workflow in list order and the count.
 *   <li>Workflows not read within the budget are interrupted and make the index incomplete
 *       ({@code TIMEOUT}); reads that completed before the budget still count (N4).
 *   <li>If the caller is interrupted (its fan-out deadline), the reads are stopped and the
 *       caller gets {@code TIMEOUT}; its interrupt flag stays set.
 * </ul>
 */
final class ModuleUsageIndexer {

    private ModuleUsageIndexer() {
    }

    static ModuleUsage build(NodeId server, List<String> workflows,
        Function<String, List<ModuleRef>> nodesOf, int concurrency, Duration budget) {
        int total = workflows.size();
        Semaphore permits = new Semaphore(concurrency);
        AtomicReference<ToolError> rejected = new AtomicReference<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        long deadline = System.nanoTime() + budget.toNanos();
        List<Future<List<ModuleRef>>> reads = new ArrayList<>(
            Collections.nCopies(total, (Future<List<ModuleRef>>) null));
        try {
            Function<String, Callable<List<ModuleRef>>> read = workflow -> () -> {
                permits.acquire();
                try {
                    if (rejected.get() != null) {
                        return null; // not started: a login was rejected
                    }
                    try {
                        return nodesOf.apply(workflow);
                    } catch (ToolErrorException e) {
                        if (e.error().code() == ErrorCode.AUTH_FAILED) {
                            rejected.compareAndSet(null, e.error());
                        }
                        throw e;
                    }
                } finally {
                    permits.release();
                }
            };
            // one at a time until the first success (N1)
            int next = 0;
            boolean succeeded = false;
            boolean timedOut = false;
            while (next < total && !succeeded && rejected.get() == null && !timedOut) {
                Future<List<ModuleRef>> probe = executor.submit(read.apply(workflows.get(next)));
                reads.set(next, probe);
                next++;
                try {
                    succeeded = probe.get(remaining(deadline), TimeUnit.NANOSECONDS) != null;
                } catch (TimeoutException e) {
                    timedOut = true;
                } catch (ExecutionException e) {
                    // recorded below; try the next workflow alone
                } catch (InterruptedException e) {
                    throw cancelled(server);
                }
            }
            if (succeeded) {
                for (int i = next; i < total; i++) {
                    reads.set(i, executor.submit(read.apply(workflows.get(i))));
                }
            }
            return collect(server, workflows, reads, deadline, timedOut, rejected, budget);
        } finally {
            // never close(): it would wait for reads that ignore interrupts
            executor.shutdownNow();
        }
    }

    private static ModuleUsage collect(NodeId server, List<String> workflows,
        List<Future<List<ModuleRef>>> reads, long deadline, boolean alreadyTimedOut,
        AtomicReference<ToolError> rejected, Duration budget) {
        Map<String, Map<String, Set<String>>> uses = new HashMap<>();
        boolean timedOut = alreadyTimedOut;
        int read = 0;
        List<Integer> unread = new ArrayList<>();
        Map<Integer, ToolError> errors = new HashMap<>();
        for (int i = 0; i < workflows.size(); i++) {
            Future<List<ModuleRef>> future = reads.get(i);
            if (future == null) {
                unread.add(i); // never started
                continue;
            }
            try {
                // after the budget only reads that are already done count (N4)
                List<ModuleRef> nodes = timedOut
                    ? (future.isDone() ? future.get() : null)
                    : future.get(remaining(deadline), TimeUnit.NANOSECONDS);
                if (nodes == null) {
                    unread.add(i);
                    continue;
                }
                for (ModuleRef node : nodes) {
                    uses.computeIfAbsent(node.name(), name -> new HashMap<>())
                        .computeIfAbsent(workflows.get(i), name -> new HashSet<>())
                        .add(node.type());
                }
                read++;
            } catch (TimeoutException e) {
                timedOut = true;
                unread.add(i);
            } catch (ExecutionException e) {
                unread.add(i);
                errors.put(i, e.getCause() instanceof ToolErrorException tool
                    ? tool.error()
                    : ToolError.of(ErrorCode.INTERNAL, "Reading the workflow failed ("
                            + e.getCause().getClass().getSimpleName() + ")",
                        "An unexpected error in the MCP server", "Retry with refresh=true"));
            } catch (CancellationException e) {
                unread.add(i);
            } catch (InterruptedException e) {
                throw cancelled(server);
            }
        }
        if (unread.isEmpty()) {
            return new ModuleUsage(uses, read, workflows.size(), Optional.empty());
        }
        // the reason of the first unread workflow in list order: its own error, else the
        // rejected login that stopped it, else the budget
        int first = unread.get(0);
        ToolError reason = errors.getOrDefault(first, Optional.ofNullable(rejected.get())
            .orElseGet(() -> timeout(server, budget)));
        return new ModuleUsage(uses, read, workflows.size(), Optional.of(summary(server, reason,
            workflows.get(first), unread.size(), workflows.size())));
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private static ToolErrorException cancelled(NodeId server) {
        Thread.currentThread().interrupt();
        return new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
            "The module usage index of " + server + " was cancelled",
            "The overall deadline of the request expired",
            "Retry with refresh=true").withNode(server));
    }

    private static ToolError timeout(NodeId server, Duration budget) {
        return ToolError.of(ErrorCode.TIMEOUT, "not read within "
                + Durations.human(budget), "The INUBIT server answers slowly", "Retry with refresh=true")
            .withNode(server);
    }

    private static ToolError summary(NodeId server, ToolError first, String workflow,
        int unread, int total) {
        return ToolError.of(first.code(), "The module usage index of " + server
                + " is incomplete: " + unread + " of " + total + " technical workflows could not"
                + " be read (first: workflow " + Names.quote(workflow) + ": " + first.message()
                + ")",
            first.likelyCause(),
            first.code() == ErrorCode.TIMEOUT
                ? "Retry with refresh=true, or raise cliExportTimeout (the index budget)"
                : "Retry with refresh=true; if it persists: " + first.nextStep())
            .withNode(server);
    }
}
