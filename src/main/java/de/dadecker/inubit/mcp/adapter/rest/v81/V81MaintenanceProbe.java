package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The 8.1 {@link MaintenanceProbe}: one unauthenticated {@code GET /ibis/rest/healthcheck} with a
 * timeout of at most {@link #MAX_TIMEOUT}; {@code maintenancemode} {@code 1}/{@code true} means
 * maintenance (research R-5, R-10). Its own client has no probe, so a 503 of the healthcheck never
 * triggers another probe.
 */
public final class V81MaintenanceProbe implements MaintenanceProbe, AutoCloseable {

    public static final String HEALTHCHECK_PATH = "/ibis/rest/healthcheck";
    public static final Duration MAX_TIMEOUT = Duration.ofSeconds(2);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InubitHttpClient client;

    public V81MaintenanceProbe(EffectiveNodeConfig server, Optional<Secret> trustStorePassword,
        SecretScrubber scrubber) {
        Duration timeout = server.timeout().compareTo(MAX_TIMEOUT) < 0
            ? server.timeout() : MAX_TIMEOUT;
        this.client = new InubitHttpClient(server.withTimeout(timeout), Optional.empty(),
            trustStorePassword, MaintenanceProbe.NONE, scrubber);
    }

    @Override
    public Optional<Boolean> maintenance() {
        try {
            RestResponse response = client.get(HEALTHCHECK_PATH, Map.of(), 503);
            JsonNode flag = JSON.readTree(response.body()).get("maintenancemode");
            if (flag == null || flag.isNull()) {
                return Optional.empty();
            }
            if (flag.isBoolean()) {
                return Optional.of(flag.booleanValue());
            }
            if (flag.isNumber()) {
                return Optional.of(flag.asInt() != 0);
            }
            String text = flag.asString().strip();
            return switch (text) {
                case "1", "true" -> Optional.of(true);
                case "0", "false" -> Optional.of(false);
                default -> Optional.empty();
            };
        } catch (RuntimeException e) {
            // ToolErrorException, malformed JSON, ...: the state is unknown
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
