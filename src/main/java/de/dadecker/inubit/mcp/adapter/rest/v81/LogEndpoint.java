package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.adapter.rest.v81.LogRequestPlan.LogRequest;
import de.dadecker.inubit.mcp.adapter.rest.v81.LogRequestPlan.Window;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a {@link LogRequestPlan} against {@code POST /ibis/rest/log/<logName>?format=json}
 * (research R-9) on the server's authenticated REST client, so every request passes the
 * server's credential guard. The requests of a merged plan run in parallel (while the
 * credentials are unconfirmed the guard lets them pass one at a time). The answer
 * {@code {<logName>: {total, success, count, row[]}}} becomes a {@link Window} of raw rows.
 */
final class LogEndpoint {

    static final String PATH_PREFIX = "/ibis/rest/log/";
    static final String CONTENT_TYPE = "application/xml";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NodeId server;
    private final InubitHttpClient client;

    LogEndpoint(NodeId server, InubitHttpClient client) {
        this.server = Objects.requireNonNull(server, "server");
        this.client = Objects.requireNonNull(client, "client");
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
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Window<JsonNode>>> futures = new ArrayList<>();
            for (LogRequest request : requests) {
                futures.add(executor.submit(() -> send(logName, request)));
            }
            List<Window<JsonNode>> responses = new ArrayList<>();
            for (Future<Window<JsonNode>> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ToolErrorException toolError) {
                throw toolError;
            }
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
                "The log query on " + server + " was cancelled before INUBIT answered",
                "The overall deadline of the request expired",
                "Retry, narrow the query, or raise the timeout of " + server).withNode(server));
        } finally {
            // never close(): it would wait for calls that ignore interrupts
            executor.shutdownNow();
        }
    }

    private Window<JsonNode> send(String logName, LogRequest request) {
        RestResponse response;
        try {
            response = client.post(PATH_PREFIX + logName, Map.of("format", "json"),
                request.body(), CONTENT_TYPE);
        } catch (ToolErrorException e) {
            throw e.error().node().isPresent() ? e
                : new ToolErrorException(e.error().withNode(server));
        }
        return parse(logName, response);
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
            "The INUBIT answer to POST " + PATH_PREFIX + logName + " " + problem,
            "The baseUrl does not point to the INUBIT REST API, a proxy answers instead, or this"
                + " INUBIT version answers in another format",
            "Check the INUBIT server with get_health and the baseUrl in the configuration")
            .withNode(server));
    }
}
