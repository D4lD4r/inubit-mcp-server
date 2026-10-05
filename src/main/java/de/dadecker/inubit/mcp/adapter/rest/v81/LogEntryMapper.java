package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Severity;
import de.dadecker.inubit.mcp.infra.ResultJson;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Maps a log row to a {@link LogEntry} within the size bounds of data-model.md → LogEntry.
 *
 * <ul>
 *   <li>{@code timestamp} = the log type's time field; {@code workflow} = {@code workflowName},
 *       {@code module} = {@code moduleName}, {@code processId} = {@code workflowId},
 *       {@code message} = {@code message}; severity from the success/status field per the
 *       severity table ({@code OTHER} for unknown values and log types without such a field),
 *       its raw value in {@code rawSeverity}.
 *   <li>{@code fields}: the other columns in INUBIT's order, without {@code index} and empty
 *       values; epoch-millisecond columns as ISO-8601.
 *   <li>Bounds: {@code message} cut to 2,000 chars, every other text to 200, both with the
 *       truncation marker. If the entry is still above {@code MAX_ITEM_CHARS} serialized
 *       (JSON escapes expand control characters, quotes and backslashes), {@code fields}
 *       entries are dropped from the end, then {@code message} is cut further, then the short
 *       texts are halved; an entry is never rejected for its size.
 * </ul>
 */
final class LogEntryMapper {

    private static final Set<String> NEVER_FIELDS = Set.of("index");

    private LogEntryMapper() {
    }

    static Bounded<LogEntry> map(NodeId server, LogFilterTable table, JsonNode row) {
        Cuts cuts = new Cuts();
        Set<String> consumed = new HashSet<>(NEVER_FIELDS);
        consumed.addAll(List.of(LogFilterTable.WORKFLOW_NAME, LogFilterTable.MODULE_NAME,
            LogFilterTable.WORKFLOW_ID, LogFilterTable.MESSAGE));
        table.timeField().ifPresent(consumed::add);
        table.severityField().ifPresent(consumed::add);

        Optional<Instant> timestamp = table.timeField()
            .flatMap(field -> RowValues.instant(row, field));
        Optional<String> rawSeverity = table.severityField()
            .flatMap(field -> RowValues.text(row, field))
            .map(value -> cuts.cut(value, ItemBounds.MAX_FIELD_CHARS));
        Severity severity = rawSeverity.map(raw -> severity(table, raw)).orElse(Severity.OTHER);
        Optional<String> workflow = shortText(row, LogFilterTable.WORKFLOW_NAME, cuts);
        Optional<String> module = shortText(row, LogFilterTable.MODULE_NAME, cuts);
        Optional<String> processId = shortText(row, LogFilterTable.WORKFLOW_ID, cuts);
        Optional<String> message = RowValues.text(row, LogFilterTable.MESSAGE)
            .map(value -> cuts.cut(value, ItemBounds.MAX_MESSAGE_CHARS));

        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> column : row.properties()) {
            String name = column.getKey();
            if (consumed.contains(name)) {
                continue;
            }
            Optional<String> value = table.timeColumns().contains(name)
                ? RowValues.instant(row, name).map(Instant::toString)
                    .or(() -> RowValues.render(column.getValue()))
                : RowValues.render(column.getValue());
            value.ifPresent(text -> fields.put(cuts.cut(name, ItemBounds.MAX_FIELD_CHARS),
                cuts.cut(text, ItemBounds.MAX_FIELD_CHARS)));
        }

        LogEntry entry = new LogEntry(server, table.logType(), timestamp, severity, rawSeverity,
            workflow, module, processId, message, fields);
        while (ResultJson.size(entry) > ItemBounds.MAX_ITEM_CHARS) {
            cuts.cut = true;
            entry = shrink(entry);
        }
        return new Bounded<>(entry, cuts.cut);
    }

    /**
     * One shrinking step (review D1): drops the last field; without fields cuts the message by
     * the overflow; with the message down to the marker halves (finally drops) the longest of
     * {@code workflow}, {@code module}, {@code processId} and {@code rawSeverity}; finally drops
     * the message. Every step makes the entry smaller, so the loop ends.
     */
    private static LogEntry shrink(LogEntry entry) {
        if (!entry.fields().isEmpty()) {
            List<String> names = new ArrayList<>(entry.fields().keySet());
            Map<String, String> fields = new LinkedHashMap<>(entry.fields());
            fields.remove(names.get(names.size() - 1));
            return with(entry, entry.message(), fields);
        }
        String message = entry.message().orElse("");
        if (message.length() > ItemBounds.TRUNCATION_MARKER.length()) {
            int overflow = ResultJson.size(entry) - ItemBounds.MAX_ITEM_CHARS;
            int target = Math.max(message.length() - overflow - 1, 0);
            return with(entry, Optional.of(target <= ItemBounds.TRUNCATION_MARKER.length()
                ? ItemBounds.TRUNCATION_MARKER : RowValues.cut(message, target)),
                entry.fields());
        }
        Map<String, Optional<String>> texts = new LinkedHashMap<>();
        texts.put("workflow", entry.workflow());
        texts.put("module", entry.module());
        texts.put("processId", entry.processId());
        texts.put("rawSeverity", entry.rawSeverity());
        Optional<String> longest = texts.entrySet().stream()
            .filter(text -> text.getValue().isPresent())
            .max((a, b) -> Integer.compare(ResultJson.size(a.getValue().get()),
                ResultJson.size(b.getValue().get())))
            .map(Map.Entry::getKey);
        if (longest.isEmpty()) {
            if (entry.message().isPresent()) {
                return with(entry, Optional.empty(), entry.fields());
            }
            throw new IllegalStateException("a log entry without text exceeds "
                + ItemBounds.MAX_ITEM_CHARS + " chars");
        }
        String value = texts.get(longest.get()).get();
        String halved = RowValues.halve(value);
        texts.put(longest.get(), halved.equals(value) ? Optional.empty() : Optional.of(halved));
        return new LogEntry(entry.node(), entry.logType(), entry.timestamp(), entry.severity(),
            texts.get("rawSeverity"), texts.get("workflow"), texts.get("module"),
            texts.get("processId"), entry.message(), entry.fields());
    }

    private static LogEntry with(LogEntry entry, Optional<String> message,
        Map<String, String> fields) {
        return new LogEntry(entry.node(), entry.logType(), entry.timestamp(), entry.severity(),
            entry.rawSeverity(), entry.workflow(), entry.module(), entry.processId(), message,
            fields);
    }

    private static Severity severity(LogFilterTable table, String raw) {
        return table.severityValues().entrySet().stream()
            .filter(entry -> entry.getValue().contains(raw))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(Severity.OTHER);
    }

    private static Optional<String> shortText(JsonNode row, String field, Cuts cuts) {
        return RowValues.text(row, field).map(value -> cuts.cut(value,
            ItemBounds.MAX_FIELD_CHARS));
    }

    /** Remembers whether any text was cut. */
    private static final class Cuts {
        boolean cut;

        String cut(String text, int max) {
            String result = RowValues.cut(text, max);
            if (!result.equals(text)) {
                cut = true;
            }
            return result;
        }
    }
}
