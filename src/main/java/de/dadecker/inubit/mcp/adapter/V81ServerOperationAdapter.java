package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.cli.admin.CliOperationRunner;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The 8.1 {@link ServerOperationPort} (feature 022, US4, research D-14, D-22); 9.0 uses it too.
 *
 * <ul>
 *   <li>the StartCLI forms run on the node's {@link CliOperationRunner}, the changes inside the
 *       write hand-off of their permit;
 *   <li>the readiness is switched with {@code POST /ready/on|off} on the node's client, handed
 *       off under the same write lock as the StartCLI writes; INUBIT 8.1.17 answers a successful
 *       {@code off} with HTTP 503 and the message {@code No Service.} (T054), which is the only
 *       non-2xx answer taken as done;
 *   <li>the processes in ERROR are the {@code find_processes} query of state ERROR on the node's
 *       {@link ProcessQueryPort} ({@code POST /log/queueLog}), 1,000 rows per request, up to
 *       {@link #MAX_ERROR_PROCESS_IDS} rows; with more, the total INUBIT reports on the first page
 *       is the count only.
 * </ul>
 */
final class V81ServerOperationAdapter implements ServerOperationPort {

    /** The rows of one Queue Manager request for the set of processes in ERROR. */
    private static final int PAGE = 1_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The message of a done {@code POST /ready/off} on 8.1.17 (T054). */
    private static final String NO_SERVICE = "No Service.";

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
    public String switchMaintenance(boolean on, Optional<Boolean> executeQueued,
        WritePermit permit) {
        return operations.switchMaintenance(on, executeQueued.orElse(null), permit);
    }

    @Override
    public void switchReadiness(boolean ready, WritePermit permit) {
        if (ready) {
            client.post(RestEndpoint.READY_SWITCH, List.of("on"), permit, writeLock);
            return;
        }
        RestResponse response = client.post(RestEndpoint.READY_SWITCH, List.of("off"), permit,
            writeLock, 503);
        if (response.status() == 503 && !switchedOff(response)) {
            throw new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
                "INUBIT answered POST " + RestEndpoint.READY_SWITCH.path("off")
                    + " with HTTP 503 but not with the answer of a done switch",
                "INUBIT is not serving, or another server answered for it",
                "Read the readiness with get_health; it may not have been switched")
                .withNode(server.id()));
        }
    }

    /**
     * Whether a 503 answer of {@code POST /ready/off} is the one recorded for a done switch
     * (T054; only the message {@code No Service.} was recorded, not the whole body): the plain
     * text {@code No Service.}, or a JSON object whose {@code message} is {@code No Service.}, or
     * whose {@code status} is {@code NOT READY} without a message. Any other message, such as
     * {@code No Service - Maintenance mode active.}, or any other body is not.
     */
    private static boolean switchedOff(RestResponse response) {
        if (response.bodyText().strip().equals(NO_SERVICE)) {
            return true;
        }
        JsonNode root;
        try {
            root = JSON.readTree(response.body());
        } catch (JacksonException e) {
            return false;
        }
        if (root == null || !root.isObject()) {
            return false;
        }
        JsonNode message = root.get("message");
        if (message != null) {
            return message.isString() && message.asString().strip().equals(NO_SERVICE);
        }
        JsonNode status = root.get("status");
        return status != null && status.isString() && status.asString().equals("NOT READY");
    }

    @Override
    public void switchTrace(boolean on, WritePermit permit) {
        operations.switchTrace(on, permit);
    }

    @Override
    public TraceLevel setTraceLevel(TraceLevel level, WritePermit permit) {
        return operations.setTraceLevel(level, permit);
    }

    @Override
    public void triggerMidnightTask(WritePermit permit) {
        operations.triggerMidnightTask(permit);
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
    public String deleteErrorProcesses(WritePermit permit) {
        return operations.deleteErrorProcesses(permit);
    }

    @Override
    public String toString() {
        return "V81ServerOperationAdapter[" + server.id() + "]";
    }
}
