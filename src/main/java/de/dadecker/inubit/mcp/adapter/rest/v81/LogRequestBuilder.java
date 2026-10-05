package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.v81.LogRequestPlan.LogRequest;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.LogType;
import de.dadecker.inubit.mcp.domain.model.Severity;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Builds the XML {@code <logRequest>} bodies of {@code POST /ibis/rest/log/<logName>} (research
 * R-9, spike S-5) and validates log queries against the {@link LogFilterTable}.
 *
 * <ul>
 *   <li>{@code noOfItems} is always sent (without it INUBIT returns every match).
 *   <li>Every value is XML-escaped; {@code LIKE} patterns are otherwise passed unchanged
 *       ({@code %} and {@code _} stay wildcards).
 *   <li>Sorting: the time field {@code DESCENDING}, if the log type has one.
 *   <li>{@code offset + limit > 10000} is {@code INVALID_INPUT} (bounds the merge).
 *   <li>Filter order: time range, workflow, process id, text, then the status/success value
 *       (one request per raw value, research R-9).
 * </ul>
 */
final class LogRequestBuilder {

    /** Upper bound of {@code offset + limit} (research R-9). */
    static final int MAX_WINDOW = 10_000;

    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
    private static final String INDENT = "    ";

    private LogRequestBuilder() {
    }

    /**
     * The plan of a query: one request, or one per raw value of {@code alternativeField} (e.g.
     * {@code status} = {@code Waiting}, {@code Retry}), combined by the paging rule.
     *
     * @param alternativeValues empty for a single request without that filter; one value is a
     *     single request with that filter
     * @throws ToolErrorException {@code INVALID_INPUT} for {@code offset + limit > 10000}
     */
    static LogRequestPlan plan(List<LogFilter> filters, String alternativeField,
        List<String> alternativeValues, Optional<String> sortField, int offset, int limit) {
        checkWindow(offset, limit);
        if (alternativeValues.size() <= 1) {
            List<LogFilter> all = new ArrayList<>(filters);
            alternativeValues.forEach(value -> all.add(new LogFilter.Equal(alternativeField,
                value)));
            return new LogRequestPlan(List.of(new LogRequest(offset, limit,
                xml(offset, limit, all, sortField))), false, offset, limit);
        }
        int window = offset + limit;
        List<LogRequest> requests = new ArrayList<>();
        for (String value : alternativeValues) {
            List<LogFilter> all = new ArrayList<>(filters);
            all.add(new LogFilter.Equal(alternativeField, value));
            requests.add(new LogRequest(0, window, xml(0, window, all, sortField)));
        }
        return new LogRequestPlan(requests, true, offset, limit);
    }

    /** The plan of a {@code query_logs} query; validates it first ({@link #validate}). */
    static LogRequestPlan plan(LogQuery query) {
        validate(query);
        LogFilterTable table = LogFilterTable.of(query.logType());
        List<LogFilter> filters = new ArrayList<>();
        table.timeField().flatMap(field -> LogFilter.timeRange(field, query.since(),
            query.until())).ifPresent(filters::add);
        query.workflow().ifPresent(workflow -> filters.add(new LogFilter.Equal(
            table.workflowField().orElseThrow(), workflow)));
        query.processId().ifPresent(processId -> filters.add(new LogFilter.Equal(
            DIGITS.matcher(processId).matches() ? LogFilterTable.WORKFLOW_ID
                : LogFilterTable.GLOBAL_PID, processId)));
        query.text().ifPresent(text -> filters.add(new LogFilter.Like(table.textField(),
            text)));
        List<String> rawValues = List.of(Severity.values()).stream()
            .filter(query.severities()::contains)
            .flatMap(severity -> table.severityValues().get(severity).stream())
            .toList();
        return plan(filters, table.severityField().orElse(""), rawValues, table.timeField(),
            query.offset(), query.limit());
    }

    /**
     * Rejects filters the log type does not support, with {@code INVALID_INPUT} naming the
     * supported ones, and {@code offset + limit > 10000}.
     */
    static void validate(LogQuery query) {
        LogType type = query.logType();
        LogFilterTable table = LogFilterTable.of(type);
        if ((query.since().isPresent() || query.until().isPresent())
            && table.timeField().isEmpty()) {
            throw unsupported(table, "since/until", "it has no time field");
        }
        if (query.workflow().isPresent() && table.workflowField().isEmpty()) {
            throw unsupported(table, "workflow", "its rows have no workflow name");
        }
        if (query.processId().isPresent() && !table.processIds()) {
            throw unsupported(table, "processId", "its rows have no process id");
        }
        if (!query.severities().isEmpty()) {
            if (table.severityField().isEmpty()) {
                throw unsupported(table, "severity", "it has no success or status field to"
                    + " derive a severity from");
            }
            List<Severity> rejected = List.of(Severity.values()).stream()
                .filter(query.severities()::contains)
                .filter(severity -> !table.severityValues().containsKey(severity))
                .toList();
            if (!rejected.isEmpty()) {
                throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                    "severity " + rejected + " is not supported for " + type.logName(),
                    "INUBIT logs have no severity field; it is derived from the "
                        + table.severityField().get() + " field, which knows only "
                        + table.supportedSeverities(),
                    "Use severity " + table.supportedSeverities() + " for " + type.logName()
                        + ", or omit it"));
            }
        }
        query.workflow().ifPresent(value -> checkSendable("workflow", value));
        query.processId().ifPresent(value -> checkSendable("processId", value));
        query.text().ifPresent(value -> checkSendable("text", value));
        checkWindow(query.offset(), query.limit());
    }

    /** One {@code <logRequest>} document, formatted like the recorded requests. */
    static String xml(int startIndex, int noOfItems, List<LogFilter> filters,
        Optional<String> sortField) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<logRequest>\n");
        element(xml, 1, "startIndex", Integer.toString(startIndex));
        element(xml, 1, "noOfItems", Integer.toString(noOfItems));
        for (LogFilter filter : filters) {
            xml.append(INDENT).append("<filtering>\n");
            element(xml, 2, "field", filter.field());
            switch (filter) {
                case LogFilter.Equal equal -> comparison(xml, equal.value(), "EQUAL");
                case LogFilter.Like like -> comparison(xml, like.pattern(), "LIKE");
                case LogFilter.Greater greater ->
                    comparison(xml, Long.toString(greater.value()), "GREATER");
                case LogFilter.Lesser lesser ->
                    comparison(xml, Long.toString(lesser.value()), "LESSER");
                case LogFilter.Between between -> {
                    element(xml, 2, "comparison", "BETWEEN");
                    element(xml, 2, "min", Long.toString(between.min()));
                    element(xml, 2, "max", Long.toString(between.max()));
                }
            }
            xml.append(INDENT).append("</filtering>\n");
        }
        sortField.ifPresent(field -> {
            xml.append(INDENT).append("<sorting>\n");
            element(xml, 2, "field", field);
            element(xml, 2, "order", "DESCENDING");
            xml.append(INDENT).append("</sorting>\n");
        });
        return xml.append("</logRequest>\n").toString();
    }

    private static void comparison(StringBuilder xml, String value, String comparison) {
        element(xml, 2, "value", value);
        element(xml, 2, "comparison", comparison);
    }

    private static void element(StringBuilder xml, int depth, String name, String value) {
        xml.append(INDENT.repeat(depth)).append('<').append(name).append('>')
            .append(escape(value)).append("</").append(name).append(">\n");
    }

    /**
     * Escapes text content; a character that XML 1.0 does not allow is {@code INVALID_INPUT}
     * (never silently dropped, review D5).
     */
    static String escape(String value) {
        checkSendable("A filter value", value);
        StringBuilder escaped = new StringBuilder(value.length());
        value.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.appendCodePoint(c);
            }
        });
        return escaped.toString();
    }

    /**
     * {@code INVALID_INPUT} (without server id: it does not depend on the server) if
     * {@code value} contains a character that XML 1.0 does not allow, e.g. U+0001, U+FFFF or a
     * lone surrogate; such a value cannot be sent in a {@code <logRequest>}.
     */
    static void checkSendable(String name, String value) {
        value.codePoints().filter(c -> !xmlChar(c)).findFirst().ifPresent(c -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                name + " contains " + String.format("U+%04X", c) + ", a character that XML 1.0"
                    + " does not allow",
                "INUBIT's log queries are XML documents, which cannot carry control characters"
                    + " other than tab, line feed and carriage return",
                "Remove the character from " + name));
        });
    }

    private static boolean xmlChar(int c) {
        return c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF)
            || (c >= 0xE000 && c <= 0xFFFD) || (c >= 0x10000 && c <= 0x10FFFF);
    }

    /** {@code INVALID_INPUT} for {@code offset + limit > 10000} (research R-9). */
    static void checkWindow(int offset, int limit) {
        if ((long) offset + limit > MAX_WINDOW) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "offset + limit must be at most " + MAX_WINDOW + ", got " + offset + " + "
                    + limit,
                "Paging deeper than " + MAX_WINDOW + " entries is not supported (the merge of"
                    + " several requests is bounded)",
                "Narrow the query with since/until or other filters instead of paging further"));
        }
    }

    private static ToolErrorException unsupported(LogFilterTable table, String filter,
        String reason) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
            "The filter " + filter + " is not supported for " + table.logType().logName(),
            "INUBIT cannot filter " + table.logType().logName() + " by " + filter + ": "
                + reason,
            "Supported filters for " + table.logType().logName() + ": "
                + table.supportedFilters()));
    }
}
