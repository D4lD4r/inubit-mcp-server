package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.MediaRange;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.ResourceKind;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.domain.model.CliSettingsSnapshot;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ServerSettingsPort;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The 8.1 {@link ServerSettingsPort} (feature 022, US1); 9.0 uses it too.
 *
 * <ul>
 *   <li>{@code GET /ibis/rest/license} with credentials ({@link LicenseXmlParser});
 *   <li>{@code GET /ibis/rest/configurations} with credentials ({@link ConfigurationJsonParser},
 *       bounded by the limits of the call);
 *   <li>the StartCLI parts are passed to {@code cliSettings} (in production
 *       {@code CliSettingsRunner::read}), so this REST package does not reach the StartCLI
 *       adapter.
 * </ul>
 *
 * <p>Both endpoints need the INUBIT role "System Administrator" (vendor documentation), so their
 * HTTP 403 is {@code FORBIDDEN} naming that role (spec edge case: the refusal names the missing
 * role). Every other status keeps the mapping of {@link InubitHttpClient}, e.g. 503 during
 * maintenance {@code MAINTENANCE_MODE}; a 404 is a missing endpoint ({@code UNEXPECTED_RESPONSE}).
 */
public final class V81SettingsAdapter implements ServerSettingsPort {

    private static final String ROLE = "System Administrator";

    private final NodeId server;
    private final InubitHttpClient client;
    private final Function<Set<String>, CliSettingsSnapshot> cliSettings;

    public V81SettingsAdapter(NodeId server, InubitHttpClient client,
        Function<Set<String>, CliSettingsSnapshot> cliSettings) {
        this.server = Objects.requireNonNull(server, "server");
        this.client = Objects.requireNonNull(client, "client");
        this.cliSettings = Objects.requireNonNull(cliSettings, "cliSettings");
    }

    @Override
    public LicenseFacts license() {
        return LicenseXmlParser.parse(server, get(RestEndpoint.LICENSE, MediaRange.XML).body());
    }

    @Override
    public ConfigurationView configuration(Optional<String> section, int maxValues,
        int maxChars) {
        return ConfigurationJsonParser.parse(server,
            get(RestEndpoint.CONFIGURATIONS, MediaRange.JSON).body(), section, maxValues,
            maxChars);
    }

    @Override
    public CliSettingsSnapshot cliSettings(Set<String> parts) {
        return cliSettings.apply(parts);
    }

    private RestResponse get(RestEndpoint endpoint, MediaRange range) {
        try {
            return client.get(endpoint, List.of(), Map.of(), range, ResourceKind.ENDPOINT);
        } catch (ToolErrorException e) {
            if (e.error().code() == ErrorCode.FORBIDDEN) {
                throw new ToolErrorException(ToolError.of(ErrorCode.FORBIDDEN,
                    "The INUBIT account lacks the role " + ROLE + " for GET " + endpoint.path()
                        + " (HTTP 403)",
                    "Reading the license and the configuration over REST needs the INUBIT role "
                        + ROLE,
                    "Give the account the role " + ROLE + ", or use another account")
                    .withNode(server));
            }
            throw e.error().node().isPresent() ? e
                : new ToolErrorException(e.error().withNode(server));
        }
    }

    @Override
    public String toString() {
        return "V81SettingsAdapter[" + server + "]";
    }
}
