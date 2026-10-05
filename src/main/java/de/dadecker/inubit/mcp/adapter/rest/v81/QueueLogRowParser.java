package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.ResultJson;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Maps a {@code queueLog} row to a {@link ProcessInstance} (research R-8, spike S-4;
 * data-model.md → ProcessInstance): {@code processId} = {@code workflowId},
 * {@code globalProcessId} = {@code globalPId} (number or UUID), {@code rawState} =
 * {@code status.content} with the state table of {@link ProcessState}, {@code since} =
 * {@code startTime} (epoch ms), {@code workflow} = {@code workflowName}, {@code module} =
 * {@code moduleName}, plus {@code moduleType}, {@code tag}, {@code node}, {@code owner} and
 * {@code priority}. Blank values are absent.
 *
 * <p>Size bound: every text is cut to 200 chars with the truncation marker. Because JSON escapes
 * expand some characters (a control character becomes {@code \u0001}, 6 chars), the serialized
 * row is then measured, and while it exceeds {@code MAX_ITEM_CHARS} the longest optional text is
 * halved (finally dropped), then {@code rawState}, then {@code processId}. A row is never
 * rejected for its size (review D1).
 */
final class QueueLogRowParser {

    private static final List<String> OPTIONAL = List.of("globalProcessId", "workflow",
        "module", "moduleType", "tag", "node", "owner", "priority");
    private static final List<String> REQUIRED = List.of("rawState", "processId");

    private QueueLogRowParser() {
    }

    /**
     * @param now       the reference time of {@code timeInState} and {@code hanging}
     * @param threshold the hanging threshold
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} for a row without
     *     {@code workflowId} or {@code startTime}
     */
    static Bounded<ProcessInstance> parse(NodeId server, JsonNode row, Instant now,
        Duration threshold) {
        String processId = RowValues.text(row, LogFilterTable.WORKFLOW_ID)
            .orElseThrow(() -> unexpected(server, LogFilterTable.WORKFLOW_ID));
        Instant since = RowValues.instant(row, "startTime")
            .orElseThrow(() -> unexpected(server, "startTime"));
        String rawState = RowValues.text(row, "status").orElse("");
        ProcessState state = ProcessState.fromRaw(rawState);

        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("processId", processId);
        texts.put("rawState", rawState);
        put(texts, "globalProcessId", row, LogFilterTable.GLOBAL_PID);
        put(texts, "workflow", row, LogFilterTable.WORKFLOW_NAME);
        put(texts, "module", row, LogFilterTable.MODULE_NAME);
        put(texts, "moduleType", row, "moduleType");
        put(texts, "tag", row, "tag");
        put(texts, "node", row, "node");
        put(texts, "owner", row, "owner");
        put(texts, "priority", row, "priority");
        boolean cut = false;
        for (Map.Entry<String, String> text : texts.entrySet()) {
            String bounded = RowValues.cut(text.getValue(), ItemBounds.MAX_FIELD_CHARS);
            cut |= !bounded.equals(text.getValue());
            text.setValue(bounded);
        }

        ProcessInstance instance = build(server, texts, state, since, now, threshold);
        while (ResultJson.size(instance) > ItemBounds.MAX_ITEM_CHARS) {
            cut = true;
            shrink(texts);
            instance = build(server, texts, state, since, now, threshold);
        }
        return new Bounded<>(instance, cut);
    }

    /** Halves (or drops) the longest optional text, else the longest required one. */
    private static void shrink(Map<String, String> texts) {
        Optional<String> optional = longest(texts, OPTIONAL);
        if (optional.isPresent()) {
            String value = texts.get(optional.get());
            String halved = RowValues.halve(value);
            if (halved.equals(value)) {
                texts.remove(optional.get());
            } else {
                texts.put(optional.get(), halved);
            }
            return;
        }
        String key = longest(texts, REQUIRED).orElseThrow(() -> new IllegalStateException(
            "a queueLog row without text exceeds " + ItemBounds.MAX_ITEM_CHARS + " chars"));
        String value = texts.get(key);
        String halved = RowValues.halve(value);
        texts.put(key, halved.equals(value) ? "" : halved);
    }

    private static Optional<String> longest(Map<String, String> texts, List<String> keys) {
        return keys.stream()
            .filter(key -> texts.containsKey(key) && !texts.get(key).isEmpty())
            .max((a, b) -> Integer.compare(ResultJson.size(texts.get(a)),
                ResultJson.size(texts.get(b))));
    }

    private static ProcessInstance build(NodeId server, Map<String, String> texts,
        ProcessState state, Instant since, Instant now, Duration threshold) {
        return new ProcessInstance(server,
            texts.get("processId"),
            text(texts, "globalProcessId"),
            state,
            texts.get("rawState"),
            since,
            ProcessInstance.timeInState(since, now),
            ProcessInstance.hanging(state, since, now, threshold),
            text(texts, "workflow"),
            text(texts, "module"),
            text(texts, "moduleType"),
            text(texts, "tag"),
            text(texts, "node"),
            text(texts, "owner"),
            text(texts, "priority"));
    }

    private static void put(Map<String, String> texts, String key, JsonNode row,
        String field) {
        RowValues.text(row, field).ifPresent(value -> texts.put(key, value));
    }

    private static Optional<String> text(Map<String, String> texts, String key) {
        return Optional.ofNullable(texts.get(key));
    }

    private static ToolErrorException unexpected(NodeId server, String field) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
            "A queueLog row of " + server + " has no " + field,
            "This INUBIT version lists the Queue Manager entries in another format",
            "Check the INUBIT version with get_health").withNode(server));
    }
}
