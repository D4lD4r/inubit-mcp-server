package de.dadecker.inubit.mcp.adapter.cli.v61;

import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.QueryRules;

/**
 * The 6.1 input checks of the diagnosis queries, without a server (feature 012 on the
 * {@link QueryRules} of feature 011): what {@link V61ProcessQueryAdapter#validate} checks. The
 * errors carry no server id; {@code DiagnosisService} adds it to the refusal it keeps as the
 * node's entry.
 *
 * <p>A log query is not checked: log diagnosis is not available on 6.1, and the version gate
 * refuses it with its next step.
 */
public final class V61QueryRules implements QueryRules {

    public static final V61QueryRules INSTANCE = new V61QueryRules();

    private V61QueryRules() {
    }

    @Override
    public void validate(LogQuery query) {
        // refused by the version gate (log diagnosis is not available on 6.1)
    }

    /**
     * {@code INVALID_INPUT} for {@code offset + limit} above {@link CliSessionCommand#MAX_ROWS},
     * {@code UNSUPPORTED_VERSION} for {@code tag} (the listing has no tag column).
     */
    @Override
    public void validate(ProcessQuery query) {
        if ((long) query.offset() + query.limit() > CliSessionCommand.MAX_ROWS) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "offset + limit must be at most " + CliSessionCommand.MAX_ROWS + ", got "
                    + query.offset() + " + " + query.limit(),
                "Paging deeper than " + CliSessionCommand.MAX_ROWS + " entries is not supported",
                "Narrow the query with states, workflow or since/until instead of paging"
                    + " further"));
        }
        if (query.tag().isPresent()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
                "find_processes: INUBIT 6.1 does not support the input tag; nothing was sent",
                "The process listing of StartCLI 6.1 has no tag column",
                "Omit tag for this node, or filter by workflow"));
        }
    }
}
