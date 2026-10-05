package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.v81.LogRequestPlan.Window;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import tools.jackson.databind.JsonNode;

/**
 * The 8.1 {@link LogPort}: {@code POST /ibis/rest/log/<logName>?format=json} with a
 * {@code <logRequest>} body ({@link LogRequestBuilder}, research R-9) through the server's
 * authenticated client and credential guard; rows are mapped by {@link LogEntryMapper}.
 */
public final class V81LogAdapter implements LogPort {

    private final NodeId server;
    private final LogEndpoint endpoint;

    public V81LogAdapter(NodeId server, InubitHttpClient client) {
        this.server = Objects.requireNonNull(server, "server");
        this.endpoint = new LogEndpoint(server, client);
    }

    /** Server-independent: the error carries no server id (review D8). */
    @Override
    public void validate(LogQuery query) {
        LogRequestBuilder.validate(query);
    }

    @Override
    public Page<LogEntry> query(LogQuery query) {
        LogRequestPlan plan = LogRequestBuilder.plan(query); // INVALID_INPUT: no server id
        LogFilterTable table = LogFilterTable.of(query.logType());
        Window<JsonNode> window = endpoint.fetch(query.logType().logName(), plan,
            table.timeField());
        List<LogEntry> entries = new ArrayList<>();
        boolean cut = false;
        for (JsonNode row : window.rows()) {
            Bounded<LogEntry> entry = LogEntryMapper.map(server, table, row);
            entries.add(entry.value());
            cut |= entry.cut();
        }
        int next = query.offset() + entries.size();
        return new Page<>(entries, query.offset(), query.limit(),
            OptionalLong.of(window.total()), false, cut,
            next < window.total() ? OptionalInt.of(next) : OptionalInt.empty());
    }

    @Override
    public String toString() {
        return "V81LogAdapter[" + server + "]";
    }
}
