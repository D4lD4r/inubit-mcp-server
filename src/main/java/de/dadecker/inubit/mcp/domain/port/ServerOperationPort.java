package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Operates one INUBIT server for {@code operate_server} (feature 022, US4, research D-22): the
 * maintenance mode, the trace and its level over StartCLI, the readiness over
 * REST, the midnight task and the deletion of the processes in state ERROR over StartCLI, with the
 * set of those processes read over REST (research D-14). Failures are thrown as
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException} with the server id.
 *
 * <p>The states before and after a change come from here and from {@link MonitoringPort}, the
 * source {@code get_health} uses: the readiness from {@link MonitoringPort#ready()}, whether the
 * trace is on from {@link MonitoringPort#systemInfo()} ({@code tracingEnabled}), and the
 * maintenance mode from {@link #maintenanceState()} or {@link MonitoringPort#healthcheck()}.
 *
 * <p>Every change takes the {@link WritePermit} of the call's admission for this port's node and
 * is handed off under the node's {@link WriteLock}; after a version change since the admission it
 * is refused with {@code UNSUPPORTED_VERSION} and nothing is sent. A permit for another node is an
 * {@link IllegalArgumentException}; nothing is sent. No answer of these commands is recorded yet
 * (live check T054): an answer of another form is {@code UNEXPECTED_RESPONSE}, the change was
 * sent but is not confirmed.
 */
public interface ServerOperationPort {

    /** The most rows of processes in ERROR whose ids {@link #errorProcesses} lists (D-14). */
    int MAX_ERROR_PROCESS_IDS = 10_000;

    /** Whether maintenance mode is on, read with StartCLI {@code maintenance -s}. */
    boolean maintenanceState();

    /**
     * Switches maintenance mode ({@code maintenance on|off [-eq true|false]}).
     *
     * @param executeQueued {@code -eq}, only with {@code on}
     * @return the state INUBIT answers with
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code INVALID_INPUT} for
     *     {@code executeQueued} with {@code off}, before anything is sent
     */
    boolean switchMaintenance(boolean on, Optional<Boolean> executeQueued, WritePermit permit);

    /**
     * Switches the readiness ({@code POST /ready/on|off}). Its answer is not read; the state after
     * is {@link MonitoringPort#ready()}.
     */
    void switchReadiness(boolean ready, WritePermit permit);

    /**
     * Switches the trace ({@code trace on|off}).
     *
     * @return the state INUBIT answers with
     */
    boolean switchTrace(boolean on, WritePermit permit);

    /**
     * The trace level; always empty, without contacting the server: INUBIT has no known form that
     * reads it (research D-22; the live check T054 looks for one).
     */
    Optional<TraceLevel> traceLevel();

    /**
     * Sets the trace level ({@code trace -l <level>}).
     *
     * @return the level INUBIT answers with
     */
    TraceLevel setTraceLevel(TraceLevel level, WritePermit permit);

    /**
     * Runs the midnight task now ({@code triggerMidnightTask}).
     *
     * @return INUBIT's answer
     */
    String triggerMidnightTask(WritePermit permit);

    /**
     * The processes in state ERROR, read from the Queue Manager page by page
     * ({@code POST /log/queueLog}); more than {@link #MAX_ERROR_PROCESS_IDS} rows give the count
     * only (research D-14).
     *
     * @param now the reference time of the rows' time in state
     */
    ErrorProcesses errorProcesses(Instant now);

    /**
     * Deletes the processes in state ERROR ({@code processdelete error}).
     *
     * @return the number of processes INUBIT reports as deleted
     */
    long deleteErrorProcesses(WritePermit permit);

    /**
     * The processes in state ERROR (research D-14).
     *
     * @param count     the Queue Manager rows in state ERROR INUBIT reports (a process with
     *                  sub-workflows may have several)
     * @param ids       the distinct process ids of those rows, sorted; empty for the count only
     * @param workflows the distinct workflow names of those rows, sorted; empty for the count only
     */
    record ErrorProcesses(long count, List<String> ids, List<String> workflows) {

        public ErrorProcesses {
            ids = List.copyOf(ids);
            workflows = List.copyOf(workflows);
            if (count < 0 || count > MAX_ERROR_PROCESS_IDS
                && !(ids.isEmpty() && workflows.isEmpty())) {
                throw new IllegalArgumentException("count " + count + " with " + ids.size()
                    + " ids and " + workflows.size() + " workflows");
            }
        }

        /** More rows than {@link #MAX_ERROR_PROCESS_IDS}: the count only. */
        public static ErrorProcesses countOnly(long count) {
            return new ErrorProcesses(count, List.of(), List.of());
        }

        /** Whether only the count is known: more rows than {@link #MAX_ERROR_PROCESS_IDS}. */
        public boolean countOnly() {
            return count > MAX_ERROR_PROCESS_IDS;
        }
    }
}
