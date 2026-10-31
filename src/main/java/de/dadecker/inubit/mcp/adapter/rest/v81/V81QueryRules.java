package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.port.QueryRules;

/**
 * The 8.1 input checks of the diagnosis queries, without a server (feature 011): what
 * {@link V81LogAdapter#validate} and {@link V81ProcessQueryAdapter#validate} check. The errors
 * carry no server id (review D8).
 */
public final class V81QueryRules implements QueryRules {

    public static final V81QueryRules INSTANCE = new V81QueryRules();

    private V81QueryRules() {
    }

    @Override
    public void validate(LogQuery query) {
        LogRequestBuilder.validate(query);
    }

    @Override
    public void validate(ProcessQuery query) {
        LogRequestBuilder.checkWindow(query.offset(), query.limit());
        query.workflow().ifPresent(value -> LogRequestBuilder.checkSendable("workflow", value));
        query.tag().ifPresent(value -> LogRequestBuilder.checkSendable("tag", value));
    }
}
