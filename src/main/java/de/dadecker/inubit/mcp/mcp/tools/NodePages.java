package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import java.util.List;
import java.util.Optional;

/** The result shape {@code {results: [{node, page?, error?}]}} of the US2 tools. */
record NodePages(List<Entry> results) {

    record Entry(NodeId node, Optional<Page<?>> page, Optional<ToolError> error) {
    }

    static <T> NodePages of(List<NodeResult<Page<T>>> results) {
        return new NodePages(results.stream()
            .map(result -> new Entry(result.node(), result.payload().map(page -> page),
                result.error()))
            .toList());
    }
}
