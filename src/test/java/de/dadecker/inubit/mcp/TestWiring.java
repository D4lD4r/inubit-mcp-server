package de.dadecker.inubit.mcp;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpServerFactory;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Test-visible access to the package-private composition root {@link Wiring}, so that tests in
 * other packages (e.g. the live tests) run exactly the production wiring.
 */
public final class TestWiring implements AutoCloseable {

    private final Wiring wiring;

    private TestWiring(Wiring wiring) {
        this.wiring = wiring;
    }

    /** @param config a configuration that {@code ConfigValidator} reported no errors for */
    public static TestWiring of(ProfileConfig config, CredentialResolution credentials,
        SecretScrubber scrubber, Predicate<Path> exists, boolean windows) {
        return new TestWiring(new Wiring(config, credentials, scrubber, ClockProvider.system(),
            exists, windows));
    }

    /** As above, with a fake StartCLI ({@code launcher}) and its parent environment. */
    public static TestWiring of(ProfileConfig config, CredentialResolution credentials,
        SecretScrubber scrubber, Predicate<Path> exists, boolean windows,
        ProcessLauncher launcher, Map<String, String> environment) {
        return new TestWiring(new Wiring(config, credentials, scrubber, ClockProvider.system(),
            exists, windows, launcher, environment));
    }

    /** The MCP server the launcher starts, with {@link #toolHandlers()}. */
    public McpServerFactory serverFactory(String version) {
        return wiring.serverFactory(version, wiring.toolHandlers());
    }

    /** The handlers the server registers. */
    public List<ToolHandler> toolHandlers() {
        return wiring.toolHandlers();
    }

    /** The gateways of the wiring (e.g. for a live test's own read or cleanup). */
    public de.dadecker.inubit.mcp.domain.port.GatewayFactory gateways() {
        return wiring.gateways();
    }

    @Override
    public void close() {
        wiring.close();
    }
}
