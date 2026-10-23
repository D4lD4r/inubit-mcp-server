package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.DiagnosisService.NodePage;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import java.util.List;
import java.util.Optional;

/** The result shape {@code {results: [{node, page?, warnings?, error?}]}} of the US2 tools. */
record NodePages(List<Entry> results) {

    /**
     * @param warnings feature 009: the version warnings of the node; omitted if there are none
     */
    record Entry(NodeId node, Optional<Page<?>> page, Optional<List<String>> warnings,
        Optional<ToolError> error) {

        Entry {
            warnings = warnings.filter(list -> !list.isEmpty()).map(List::copyOf);
        }
    }

    static <T> NodePages of(List<NodeResult<NodePage<T>>> results) {
        return new NodePages(results.stream()
            .map(result -> new Entry(result.node(),
                result.payload().<Page<?>>map(NodePage::page),
                result.payload().map(NodePage::warnings), result.error()))
            .toList());
    }
}
