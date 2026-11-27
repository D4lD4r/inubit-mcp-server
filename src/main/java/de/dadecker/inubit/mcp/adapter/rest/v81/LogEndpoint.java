package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.MediaRange;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.ResourceKind;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.adapter.rest.v81.LogRequestPlan.LogRequest;
import de.dadecker.inubit.mcp.adapter.rest.v81.LogRequestPlan.Window;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a {@link LogRequestPlan} against {@code POST /ibis/rest/log/<logName>?format=json}
 * (research R-9) on the server's authenticated REST client, so every request passes the
 * server's credential guard. INUBIT (8.1 and 9.0) runs one log query of a user at a time and
 * answers an overlapping one with HTTP 500 {@code MonitoringLockedByOtherClient} (feature 017
 * live check, F-4): the requests of a merged plan run one after the other, every request waits
 * for the log queries of other calls on the same client
 * ({@link InubitHttpClient#oneLogQueryAtATime}), and a refusal because another client of the
 * user holds the lock is sent again after the waits of {@link #LOCKED_RETRIES}. The answer
 * {@code {<logName>: {total, success, count, row[]}}} becomes a {@link Window} of raw rows.
 */
final class LogEndpoint {

    static final String CONTENT_TYPE = "application/xml";

    /** The waits before each repeat of a query refused because monitoring is locked. */
    static final List<Duration> LOCKED_RETRIES = List.of(Duration.ofMillis(250),
        Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NodeId server;
    private final InubitHttpClient client;
    private final List<Duration> lockedRetries;

    LogEndpoint(NodeId server, InubitHttpClient client) {
        this(server, client, LOCKED_RETRIES);
    }

    /** With other waits before the repeats of a locked query (tests). */
    LogEndpoint(NodeId server, InubitHttpClient client, List<Duration> lockedRetries) {
        this.server = Objects.requireNonNull(server, "server");
        this.client = Objects.requireNonNull(client, "client");
        this.lockedRetries = List.copyOf(lockedRetries);
    }

    /**
     * The combined rows of the plan.
     *
     * @param timeField orders merged rows (newest first); rows without a time come last
     */
    Window<JsonNode> fetch(String logName, LogRequestPlan plan, Optional<String> timeField) {
        List<Window<JsonNode>> responses = plan.requests().size() == 1
            ? List.of(send(logName, plan.requests().get(0)))
            : sendAll(logName, plan.requests());
        Comparator<JsonNode> newestFirst = Comparator.comparing(
            (JsonNode row) -> timeField.flatMap(field -> RowValues.instant(row, field))
                .orElse(Instant.MIN)).reversed();
        return plan.combine(responses, newestFirst);
    }

    private List<Window<JsonNode>> sendAll(String logName, List<LogRequest> requests) {
        List<Window<JsonNode>> responses = new ArrayList<>();
        for (LogRequest request : requests) {
            responses.add(send(logName, request));
        }
        return responses;
    }

    private Window<JsonNode> send(String logName, LogRequest request) {
        RestResponse response;
        try {
            response = client.oneLogQueryAtATime(() -> sendRetryingWhileLocked(logName, request));
        } catch (ToolErrorException e) {
            throw e.error().node().isPresent() ? e
                : new ToolErrorException(e.error().withNode(server));
        }
        return parse(logName, response);
    }

    /**
     * Sends the request; a refusal because monitoring is locked by another client of the same
     * user (e.g. the Workbench; this server's own calls wait in the client's queue) is sent again
     * after each wait of {@link #lockedRetries}, then reported as such. Other errors are thrown
     * at once.
     */
    private RestResponse sendRetryingWhileLocked(String logName, LogRequest request) {
        for (int attempt = 0; ; attempt++) {
            try {
                return client.post(RestEndpoint.LOG, List.of(logName), Map.of("format", "json"),
                    request.body(), CONTENT_TYPE, MediaRange.JSON, ResourceKind.ENDPOINT);
            } catch (ToolErrorException e) {
                if (!monitoringLocked(e.error())) {
                    throw e;
                }
                if (attempt == lockedRetries.size()) {
                    throw new ToolErrorException(locked(logName, e.error()));
                }
                pause(lockedRetries.get(attempt), logName);
            }
        }
    }

    /** The HTTP 500 of INUBIT's monitoring lock, by its code or its message (9.0 JSON). */
    private static boolean monitoringLocked(ToolError error) {
        return error.code() == ErrorCode.UNEXPECTED_RESPONSE
            && error.excerpt().map(text -> text.toLowerCase(Locale.ROOT))
                .filter(text -> text.contains("monitoringlockedbyotherclient")
                    || text.contains("monitoring is locked by other client"))
                .isPresent();
    }

    private void pause(Duration wait, String logName) {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
                "POST " + RestEndpoint.LOG.path(logName) + " was cancelled while it waited for"
                    + " INUBIT's monitoring lock",
                "The overall deadline of the request expired; another client of the same INUBIT"
                    + " user holds the monitoring lock",
                "Retry later, or raise the timeout in the configuration").withNode(server));
        }
    }

    private ToolError locked(String logName, ToolError refused) {
        return ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
            "INUBIT refused POST " + RestEndpoint.LOG.path(logName) + " with HTTP 500: monitoring"
                + " is locked by other client (" + (1 + lockedRetries.size()) + " attempts)",
            "Another client of the same INUBIT user (e.g. a Workbench or another MCP server) runs a"
                + " log query; INUBIT runs one log query of a user at a time",
            "Retry later, or give this server its own INUBIT user")
            .withNode(server).withExcerpt(refused.excerpt().orElseThrow());
    }

    private Window<JsonNode> parse(String logName, RestResponse response) {
        JsonNode root;
        try {
            root = JSON.readTree(response.body());
        } catch (JacksonException e) {
            throw unexpected(logName, "is not JSON");
        }
        JsonNode log = root == null ? null : root.get(logName);
        if (log == null || !log.isObject()) {
            throw unexpected(logName, "has no '" + logName + "' object");
        }
        JsonNode total = log.get("total");
        if (total == null || !total.isIntegralNumber()) {
            throw unexpected(logName, "has no total");
        }
        JsonNode success = log.get("success");
        if (success != null && success.isBoolean() && !success.asBoolean()) {
            throw unexpected(logName, "reports success=false");
        }
        JsonNode rows = log.get("row");
        List<JsonNode> list = new ArrayList<>();
        if (rows != null && rows.isArray()) {
            rows.forEach(row -> {
                if (row.isObject()) {
                    list.add(row);
                }
            });
        } else if (rows != null && rows.isObject()) {
            list.add(rows); // a single row
        }
        return new Window<>(list, total.asLong());
    }

    private ToolErrorException unexpected(String logName, String problem) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
            "The INUBIT answer to POST " + RestEndpoint.LOG.path(logName) + " " + problem,
            "The baseUrl does not point to the INUBIT REST API, a proxy answers instead, or this"
                + " INUBIT version answers in another format",
            "Check the INUBIT server with get_health and the baseUrl in the configuration")
            .withNode(server));
    }
}
