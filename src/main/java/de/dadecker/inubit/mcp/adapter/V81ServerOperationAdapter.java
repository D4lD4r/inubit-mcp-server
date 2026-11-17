package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.cli.admin.CliOperationRunner;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.ServerOperationPort;
import de.dadecker.inubit.mcp.domain.port.WriteLock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The 8.1 {@link ServerOperationPort} (feature 022, US4, research D-14, D-22); 9.0 uses it too.
 *
 * <ul>
 *   <li>the StartCLI forms run on the node's {@link CliOperationRunner}, the changes inside the
 *       write hand-off of their permit;
 *   <li>the readiness is switched with {@code POST /ready/on|off} on the node's client, handed
 *       off under the same write lock as the StartCLI writes; its answer is not read;
 *   <li>the processes in ERROR are the {@code find_processes} query of state ERROR on the node's
 *       {@link ProcessQueryPort} ({@code POST /log/queueLog}), 1,000 rows per request, up to
 *       {@link #MAX_ERROR_PROCESS_IDS} rows; with more, the total INUBIT reports on the first page
 *       is the count only;
 *   <li>the trace level and the debug state are unknown: INUBIT has no known form that reads them
 *       (research D-22, live check T054).
 * </ul>
 */
final class V81ServerOperationAdapter implements ServerOperationPort {

    /** The rows of one Queue Manager request for the set of processes in ERROR. */
    private static final int PAGE = 1_000;

    private final EffectiveNodeConfig server;
    private final CliOperationRunner operations;
    private final InubitHttpClient client;
    private final WriteLock writeLock;
    private final ProcessQueryPort processes;

    /**
     * @param writeLock the lock the node's StartCLI writes are handed off under
     * @param processes the node's Queue Manager reads
     */
    V81ServerOperationAdapter(EffectiveNodeConfig server, CliOperationRunner operations,
        InubitHttpClient client, WriteLock writeLock, ProcessQueryPort processes) {
        this.server = Objects.requireNonNull(server, "server");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.client = Objects.requireNonNull(client, "client");
        this.writeLock = Objects.requireNonNull(writeLock, "writeLock");
        this.processes = Objects.requireNonNull(processes, "processes");
    }

    @Override
    public boolean maintenanceState() {
        return operations.maintenanceState();
    }

    @Override
    public boolean switchMaintenance(boolean on, Optional<Boolean> executeQueued,
        WritePermit permit) {
        return operations.switchMaintenance(on, executeQueued.orElse(null), permit);
    }

    @Override
    public void switchReadiness(boolean ready, WritePermit permit) {
        client.post(RestEndpoint.READY_SWITCH, List.of(ready ? "on" : "off"), permit, writeLock);
    }

    @Override
    public boolean switchTrace(boolean on, WritePermit permit) {
        return operations.switchTrace(on, permit);
    }

    @Override
    public Optional<TraceLevel> traceLevel() {
        return Optional.empty();
    }

    @Override
    public TraceLevel setTraceLevel(TraceLevel level, WritePermit permit) {
        return operations.setTraceLevel(level, permit);
    }

    @Override
    public Optional<Boolean> debugState() {
        return Optional.empty();
    }

    @Override
    public boolean switchDebug(boolean on, WritePermit permit) {
        return operations.switchDebug(on, permit);
    }

    @Override
    public String triggerMidnightTask(WritePermit permit) {
        return operations.triggerMidnightTask(permit);
    }

    @Override
    public ErrorProcesses errorProcesses(Instant now) {
        SortedSet<String> ids = new TreeSet<>();
        SortedSet<String> workflows = new TreeSet<>();
        int offset = 0;
        while (true) {
            Page<ProcessInstance> page = processes.find(errorQuery(offset, now));
            long total = page.total().orElseThrow(); // the 8.1 queue log always reports it
            if (total > MAX_ERROR_PROCESS_IDS) {
                return ErrorProcesses.countOnly(total);
            }
            for (ProcessInstance row : page.items()) {
                ids.add(row.processId());
                row.workflow().ifPresent(workflows::add);
            }
            if (page.nextOffset().isEmpty() || page.items().isEmpty()) {
                return new ErrorProcesses(total, List.copyOf(ids), List.copyOf(workflows));
            }
            offset = page.nextOffset().getAsInt();
        }
    }

    /** One page of the rows in state ERROR; the window never passes the 10,000 rows. */
    private ProcessQuery errorQuery(int offset, Instant now) {
        return new ProcessQuery(Set.of(ProcessState.ERROR), false, server.hangingThreshold(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), offset,
            Math.min(PAGE, MAX_ERROR_PROCESS_IDS - offset), now, Optional.empty());
    }

    @Override
    public long deleteErrorProcesses(WritePermit permit) {
        return operations.deleteErrorProcesses(permit);
    }

    @Override
    public String toString() {
        return "V81ServerOperationAdapter[" + server.id() + "]";
    }
}
