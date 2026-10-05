package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.v81.LogRequestPlan.Window;
import de.dadecker.inubit.mcp.domain.model.LogType;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import tools.jackson.databind.JsonNode;

/**
 * The 8.1 {@link ProcessQueryPort} on the Queue Manager view
 * {@code POST /ibis/rest/log/queueLog?format=json} (research R-8, spike S-4).
 *
 * <ul>
 *   <li>{@code status EQUAL <raw>} per raw state value ({@link ProcessQuery#rawStates()}),
 *       combined by the paging rule of research R-9; no {@code states} and no
 *       {@code hangingOnly} → no status filter.
 *   <li>{@code hangingOnly}: only the non-final raw states and {@code startTime} before
 *       {@code now - threshold}; a query that matches nothing (e.g. {@code states=[ERROR]})
 *       sends no request.
 *   <li>{@code workflowName EQUAL}, {@code tag EQUAL}, {@code startTime BETWEEN}/{@code GREATER}/
 *       {@code LESSER} (epoch ms), sorted {@code startTime DESCENDING}.
 * </ul>
 */
public final class V81ProcessQueryAdapter implements ProcessQueryPort {

    private static final String START_TIME = "startTime";
    private static final String STATUS = "status";
    private static final LogType QUEUE_LOG = LogType.QUEUE_LOG;

    private final NodeId server;
    private final LogEndpoint endpoint;

    public V81ProcessQueryAdapter(NodeId server, InubitHttpClient client) {
        this.server = Objects.requireNonNull(server, "server");
        this.endpoint = new LogEndpoint(server, client);
    }

    @Override
    public Page<ProcessInstance> find(ProcessQuery query) {
        validate(query);
        if (query.matchesNothing()) {
            return new Page<>(List.of(), query.offset(), query.limit(), OptionalLong.of(0),
                false, false, OptionalInt.empty());
        }
        List<LogFilter> filters = new ArrayList<>();
        LogFilter.timeRange(START_TIME, query.since(), query.effectiveUntil())
            .ifPresent(filters::add);
        query.workflow().ifPresent(workflow -> filters.add(new LogFilter.Equal(
            LogFilterTable.WORKFLOW_NAME, workflow)));
        query.tag().ifPresent(tag -> filters.add(new LogFilter.Equal("tag", tag)));
        LogRequestPlan plan = LogRequestBuilder.plan(filters, STATUS,
            query.rawStates().orElse(List.of()), Optional.of(START_TIME), query.offset(),
            query.limit());
        Window<JsonNode> window = endpoint.fetch(QUEUE_LOG.logName(), plan,
            Optional.of(START_TIME));
        List<ProcessInstance> instances = new ArrayList<>();
        boolean cut = false;
        for (JsonNode row : window.rows()) {
            Bounded<ProcessInstance> instance = QueueLogRowParser.parse(server, row,
                query.now(), query.hangingThreshold());
            instances.add(instance.value());
            cut |= instance.cut();
        }
        int next = query.offset() + instances.size();
        return new Page<>(instances, query.offset(), query.limit(),
            OptionalLong.of(window.total()), false, cut,
            next < window.total() ? OptionalInt.of(next) : OptionalInt.empty());
    }

    /** Server-independent: the error carries no server id (review D8). */
    @Override
    public void validate(ProcessQuery query) {
        LogRequestBuilder.checkWindow(query.offset(), query.limit());
        query.workflow().ifPresent(value -> LogRequestBuilder.checkSendable("workflow", value));
        query.tag().ifPresent(value -> LogRequestBuilder.checkSendable("tag", value));
    }

    @Override
    public ProcessRows findByProcessId(String processId, Instant now,
        Duration hangingThreshold) {
        LogRequestBuilder.checkSendable("processId", processId);
        LogRequestPlan plan = LogRequestBuilder.plan(List.of(), LogFilterTable.WORKFLOW_ID,
            List.of(processId), Optional.of(START_TIME), 0, MAX_ROWS_PER_PROCESS);
        Window<JsonNode> window = endpoint.fetch(QUEUE_LOG.logName(), plan,
            Optional.of(START_TIME));
        List<ProcessInstance> rows = window.rows().stream()
            .map(row -> QueueLogRowParser.parse(server, row, now, hangingThreshold).value())
            .toList();
        return new ProcessRows(rows, Math.max(window.total(), rows.size()));
    }

    @Override
    public String toString() {
        return "V81ProcessQueryAdapter[" + server + "]";
    }
}
