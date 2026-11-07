package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.WriteLock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Test doubles for US4: a gateway factory whose servers have a scripted state read
 * ({@link ProcessQueryPort#findByProcessId}) and a recording {@link ProcessControlPort}.
 */
final class FakeProcessControl implements GatewayFactory {

    /** Every call of the fakes, in order, e.g. {@code read 4711}, {@code restart 4711}. */
    final List<String> calls = new CopyOnWriteArrayList<>();
    /** The write permits of restart/kill, in order (feature 017, research D-24). */
    final List<WritePermit> permits = new CopyOnWriteArrayList<>();
    /** Answers of the successive state reads; the last one repeats. */
    final Deque<Supplier<ProcessRows>> reads = new ArrayDeque<>();
    /** Thrown by {@link ProcessControlPort#checkAvailable()} if set. */
    volatile ToolErrorException unavailable;
    /** Thrown by {@link ProcessControlPort#checkAvailable()} if set (an unexpected failure). */
    volatile RuntimeException unavailableUnexpectedly;
    /** The answer of restart/kill. */
    volatile Supplier<String> action = () -> "Process restarted.";
    /** Runs inside restart/kill before it answers (e.g. to inspect the audit log). */
    volatile Runnable duringAction = () -> { };
    /**
     * If set, restart/kill are handed off under this lock like the StartCLI adapter does
     * (feature 017, research D-24); a refused hand-off records the permit but no call.
     */
    volatile WriteLock lock;

    static ProcessInstance row(NodeId server, String processId, ProcessState state,
        String rawState, Instant since, String workflow, String module) {
        return new ProcessInstance(server, processId, Optional.of(processId), state, rawState,
            since, Duration.ZERO, false, Optional.of(workflow), Optional.of(module),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());
    }

    static ProcessRows rows(ProcessInstance... rows) {
        return new ProcessRows(List.of(rows), rows.length);
    }

    FakeProcessControl reading(ProcessRows... answers) {
        for (ProcessRows answer : answers) {
            reads.add(() -> answer);
        }
        return this;
    }

    FakeProcessControl readingFailure(ToolErrorException failure) {
        reads.add(() -> {
            throw failure;
        });
        return this;
    }

    private ProcessRows nextRead() {
        Supplier<ProcessRows> next = reads.size() > 1 ? reads.poll() : reads.peek();
        if (next == null) {
            throw new AssertionError("no state read scripted");
        }
        return next.get();
    }

    @Override
    public ProcessQueryPort processes(NodeId server) {
        return new ProcessQueryPort() {
            @Override
            public void validate(ProcessQuery query) {
                throw new AssertionError("not used by US4");
            }

            @Override
            public Page<ProcessInstance> find(ProcessQuery query) {
                throw new AssertionError("not used by US4");
            }

            @Override
            public ProcessRows findByProcessId(String processId, Instant now,
                Duration hangingThreshold) {
                calls.add("read " + server + " " + processId);
                return nextRead();
            }
        };
    }

    @Override
    public ProcessControlPort processControl(NodeId server) {
        return new ProcessControlPort() {
            @Override
            public void checkAvailable() {
                calls.add("check " + server);
                if (unavailable != null) {
                    throw unavailable;
                }
                if (unavailableUnexpectedly != null) {
                    throw unavailableUnexpectedly;
                }
            }

            @Override
            public String restart(String processId, WritePermit permit) {
                return act("restart " + server + " " + processId, permit.requireFor(server));
            }

            @Override
            public String kill(String processId, WritePermit permit) {
                return act("kill " + server + " " + processId, permit.requireFor(server));
            }
        };
    }

    private String act(String call, WritePermit permit) {
        permits.add(permit);
        WriteLock current = lock;
        if (current != null) {
            return current.handOff(permit, version -> act(call));
        }
        return act(call);
    }

    private String act(String call) {
        calls.add(call);
        duringAction.run();
        return action.get();
    }

    /** The calls that changed (or tried to change) state. */
    List<String> actions() {
        List<String> actions = new ArrayList<>();
        for (String call : calls) {
            if (call.startsWith("restart ") || call.startsWith("kill ")) {
                actions.add(call);
            }
        }
        return actions;
    }

    @Override
    public Gateway forServer(NodeId server) {
        throw new AssertionError("US4 takes the ports without version detection");
    }

    @Override
    public InubitVersion detect(NodeId server) {
        throw new AssertionError("not used before the version gate (T008)");
    }

    @Override
    public MonitoringPort monitoring(NodeId server) {
        throw new AssertionError("not used by US4");
    }

    @Override
    public LogPort logs(NodeId server) {
        throw new AssertionError("not used by US4");
    }

    @Override
    public InventoryPort inventory(NodeId server) {
        throw new AssertionError("not used by US4");
    }

    @Override
    public Optional<Gateway> knownGateway(NodeId server) {
        throw new AssertionError("not used by US4");
    }
}
