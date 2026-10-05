package de.dadecker.inubit.mcp.infra;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.LogType;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * The JSON form of tool results (contracts/mcp-tools.md): record components in declaration order,
 * empty {@code Optional}s and {@code null}s omitted, ids and log types as strings (e.g.
 * {@code systemLog}), {@code java.time} values as ISO-8601. Shared by the MCP result mapping
 * and by the mappers that bound an item's serialized size ({@code ItemBounds.MAX_ITEM_CHARS}),
 * so that both measure the same text.
 */
public final class ResultJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
        .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .changeDefaultPropertyInclusion(inclusion ->
            inclusion.withValueInclusion(JsonInclude.Include.NON_ABSENT))
        .addModule(new SimpleModule("inubit-mcp-results")
            .addSerializer(NodeId.class, new ToStringSerializer(NodeId.class))
            .addSerializer(GroupId.class, new ToStringSerializer(GroupId.class))
            .addSerializer(LogType.class, new ToStringSerializer(LogType.class)))
        .build();

    private ResultJson() {
    }

    /** The shared, thread-safe mapper. */
    public static JsonMapper mapper() {
        return MAPPER;
    }

    /**
     * The length of {@code value} as compact JSON; scrubbing can only shorten it (secrets of at
     * least 4 chars become {@code ***}).
     */
    public static int size(Object value) {
        return MAPPER.writeValueAsString(value).length();
    }
}
