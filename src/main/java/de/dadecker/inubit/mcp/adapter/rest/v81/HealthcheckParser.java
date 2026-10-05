package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.domain.model.HealthStatus;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort.Healthcheck;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses the 8.1 {@code GET /ibis/rest/healthcheck} answer (research R-5, R-10):
 * {@code {"status": "OK", "maintenancemode": 0, "timestamp": "Thu Oct 01 15:58:45 CEST 2026"}}.
 * The timestamp is in Java {@code Date.toString()} format ({@value #TIMESTAMP_PATTERN}, English).
 * Any HTTP status is accepted (the server answered, so it is reachable); a body without the
 * expected JSON gives status {@code UNKNOWN} and a {@code problem}.
 */
final class HealthcheckParser {

    static final String TIMESTAMP_PATTERN = "EEE MMM dd HH:mm:ss zzz yyyy";
    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN, Locale.ENGLISH);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HealthcheckParser() {
    }

    static Healthcheck parse(RestResponse response) {
        int status = response.status();
        JsonNode root;
        try {
            root = JSON.readTree(response.body());
        } catch (JacksonException e) {
            root = null;
        }
        if (root == null || !root.isObject() || !root.has("status")) {
            return new Healthcheck(status, HealthStatus.UNKNOWN, Optional.empty(),
                Optional.empty(), Optional.of("the healthcheck answered HTTP " + status
                    + " without the health JSON (status, maintenancemode)"));
        }
        return new Healthcheck(status, status(root.get("status")),
            flag(root.get("maintenancemode")), timestamp(root.get("timestamp")),
            Optional.empty());
    }

    private static HealthStatus status(JsonNode node) {
        String text = node.isString() ? node.asString().strip().toUpperCase(Locale.ROOT) : "";
        return switch (text) {
            case "OK" -> HealthStatus.OK;
            case "ERROR" -> HealthStatus.ERROR;
            default -> HealthStatus.UNKNOWN;
        };
    }

    /** {@code 1}/{@code true} = on, {@code 0}/{@code false} = off, as number, boolean or text. */
    static Optional<Boolean> flag(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (node.isBoolean()) {
            return Optional.of(node.booleanValue());
        }
        if (node.isNumber()) {
            return Optional.of(node.asInt() != 0);
        }
        return switch (node.asString().strip().toLowerCase(Locale.ROOT)) {
            case "1", "true" -> Optional.of(true);
            case "0", "false" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    private static Optional<Instant> timestamp(JsonNode node) {
        if (node == null || !node.isString()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZonedDateTime.parse(node.asString().strip(), TIMESTAMP)
                .toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
