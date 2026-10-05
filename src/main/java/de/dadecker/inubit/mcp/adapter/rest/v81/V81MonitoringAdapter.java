package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The 8.1 {@link MonitoringPort} on the server's REST client (research R-10):
 *
 * <ul>
 *   <li>{@code GET /ibis/rest/healthcheck} and {@code GET /ibis/rest/ready} without credentials
 *       (public endpoints; a wrong password must not count as a failed login there). Every HTTP
 *       status of the healthcheck is an answer; {@code /ready} accepts 503 (= not ready).
 *   <li>{@code GET /ibis/rest/system/info} with credentials ({@link SystemInfoXmlParser}).
 *   <li>{@code GET /ibis/rest/metrics?format=json} with credentials ({@link MetricsJsonParser}).
 *       Any failure other than 401, 503, timeout or unreachable (e.g. 402/403/404/500, or a body
 *       without the JSON figures) is {@link Metrics.NotLicensed} with the HTTP status.
 * </ul>
 */
public final class V81MonitoringAdapter implements MonitoringPort {

    public static final String HEALTHCHECK_PATH = V81MaintenanceProbe.HEALTHCHECK_PATH;
    public static final String READY_PATH = "/ibis/rest/ready";
    public static final String SYSTEM_INFO_PATH = V81VersionDetector.SYSTEM_INFO_PATH;
    public static final String METRICS_PATH = "/ibis/rest/metrics";

    /** Every non-2xx status: the healthcheck counts any answer as reachable. */
    private static final int[] ANY_STATUS = nonSuccessStatusesExcept();
    /** 401 and 503 keep their mapping (AUTH_FAILED, MAINTENANCE_MODE/UNREACHABLE). */
    private static final int[] METRICS_ACCEPTED = nonSuccessStatusesExcept(401, 503);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NodeId server;
    private final InubitHttpClient client;

    /** Told about every {@link #systemInfo()} outcome, e.g. to complete a version detection. */
    public interface SystemInfoListener {

        SystemInfoListener NONE = new SystemInfoListener() {
            @Override
            public void succeeded(SystemInfo info) {
            }

            @Override
            public void failed(ToolError error) {
            }
        };

        void succeeded(SystemInfo info);

        /** Called on the calling thread, whose interrupt flag tells a cancelled call apart. */
        void failed(ToolError error);
    }

    private final SystemInfoListener listener;

    public V81MonitoringAdapter(NodeId server, InubitHttpClient client) {
        this(server, client, SystemInfoListener.NONE);
    }

    public V81MonitoringAdapter(NodeId server, InubitHttpClient client,
        SystemInfoListener listener) {
        this.server = Objects.requireNonNull(server, "server");
        this.client = Objects.requireNonNull(client, "client");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public boolean credentialsConfirmed() {
        return client.credentialGuard().confirmed();
    }

    @Override
    public Healthcheck healthcheck() {
        return HealthcheckParser.parse(withServer(() ->
            client.getWithoutCredentials(HEALTHCHECK_PATH, Map.of(), ANY_STATUS)));
    }

    @Override
    public Readiness ready() {
        RestResponse response = withServer(() ->
            client.getWithoutCredentials(READY_PATH, Map.of(), 503));
        return new Readiness(response.status() != 503, message(response));
    }

    @Override
    public SystemInfo systemInfo() {
        SystemInfo info;
        try {
            RestResponse response = withServer(() -> client.get(SYSTEM_INFO_PATH, Map.of()));
            info = SystemInfoXmlParser.parse(server, response.body());
        } catch (ToolErrorException e) {
            listener.failed(e.error());
            throw e;
        }
        listener.succeeded(info);
        return info;
    }

    @Override
    public Metrics metrics() {
        RestResponse response = withServer(() ->
            client.get(METRICS_PATH, Map.of("format", "json"), METRICS_ACCEPTED));
        if (response.status() < 200 || response.status() >= 300) {
            return new Metrics.NotLicensed("GET " + METRICS_PATH + " answered HTTP "
                + response.status());
        }
        return MetricsJsonParser.parse(response.body())
            .<Metrics>map(Metrics.Available::new)
            .orElseGet(() -> new Metrics.NotLicensed("GET " + METRICS_PATH + " answered HTTP "
                + response.status() + " without the JSON load figures"));
    }

    private static Optional<String> message(RestResponse response) {
        JsonNode root;
        try {
            root = JSON.readTree(response.body());
        } catch (JacksonException e) {
            return Optional.empty();
        }
        JsonNode message = root == null ? null : root.get("message");
        return message != null && message.isString() && !message.asString().isBlank()
            ? Optional.of(message.asString().strip()) : Optional.empty();
    }

    private RestResponse withServer(Supplier<RestResponse> call) {
        try {
            return call.get();
        } catch (ToolErrorException e) {
            throw e.error().node().isPresent() ? e
                : new ToolErrorException(e.error().withNode(server));
        }
    }

    private static int[] nonSuccessStatusesExcept(int... excluded) {
        return IntStream.range(100, 600)
            .filter(status -> status < 200 || status >= 300)
            .filter(status -> IntStream.of(excluded).noneMatch(e -> e == status))
            .toArray();
    }

    @Override
    public String toString() {
        return "V81MonitoringAdapter[" + server + "]";
    }
}
