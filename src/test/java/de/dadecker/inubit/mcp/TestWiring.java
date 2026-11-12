package de.dadecker.inubit.mcp;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.config.ConfigLocation;
import de.dadecker.inubit.mcp.config.CredentialStore;
import de.dadecker.inubit.mcp.config.ParameterStore;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpServerFactory;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test-visible access to the package-private composition root {@link Wiring}, so that tests in
 * other packages (e.g. the live tests) run exactly the production wiring.
 *
 * <p>Feature 023 (research D-2): every server built by a factory other than {@code withHome} gets
 * a fresh temporary home, deleted by {@link #close()} or, if the server is never closed, at JVM
 * exit; no test writes below the real {@code ~/.inubit-mcp}.
 */
public final class TestWiring implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TestWiring.class);

    /**
     * The configuration location of the factories without one (feature 015, research R-9): a
     * default directory below {@code java.io.tmpdir} that is never created, with the configuration
     * file in it.
     */
    public static final ConfigLocation TEST_CONFIG_LOCATION;

    static {
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "inubit-mcp-test-config");
        TEST_CONFIG_LOCATION = new ConfigLocation(directory, directory.resolve("config.yaml"));
    }

    /** The temporary homes created here and not deleted yet. */
    private static final Set<Path> OWNED_HOMES = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
            () -> OWNED_HOMES.forEach(TestWiring::deleteHome), "inubit-mcp-test-home-cleanup"));
    }

    private final Wiring wiring;
    private final Path userHome;
    private final boolean ownsHome;

    private TestWiring(Wiring wiring, Path userHome, boolean ownsHome) {
        this.wiring = wiring;
        this.userHome = userHome;
        this.ownsHome = ownsHome;
    }

    /** Builds the wiring on a fresh temporary home that this class deletes. */
    private static TestWiring withTemporaryHome(Function<Path, Wiring> wiring) {
        Path home;
        try {
            home = Files.createTempDirectory("inubit-mcp-home");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        OWNED_HOMES.add(home);
        return new TestWiring(wiring.apply(home), home, true);
    }

    /** Deletes a temporary home once; a failure is logged and otherwise ignored. */
    private static void deleteHome(Path home) {
        if (!OWNED_HOMES.remove(home)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(home)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("Could not delete the temporary home {}: {}", home, e.toString());
        }
    }

    /** @param config a configuration that {@code ConfigValidator} reported no errors for */
    public static TestWiring of(ProfileConfig config, CredentialStore credentials,
        SecretScrubber scrubber, Predicate<Path> exists, boolean windows) {
        return withTemporaryHome(home -> new Wiring(config, credentials, scrubber,
            ClockProvider.system(), exists, windows, TEST_CONFIG_LOCATION, home));
    }

    /** As above, with a fake StartCLI ({@code launcher}) and its parent environment. */
    public static TestWiring of(ProfileConfig config, CredentialStore credentials,
        SecretScrubber scrubber, Predicate<Path> exists, boolean windows,
        ProcessLauncher launcher, Map<String, String> environment) {
        return withTemporaryHome(home -> new Wiring(config, credentials, scrubber,
            ClockProvider.system(), exists, windows, launcher, environment, TEST_CONFIG_LOCATION,
            home));
    }

    /** As above, with the Parameter Store of the AWS nodes. */
    public static TestWiring of(ProfileConfig config, CredentialStore credentials,
        SecretScrubber scrubber, Predicate<Path> exists, boolean windows,
        ProcessLauncher launcher, Map<String, String> environment, ParameterStore store) {
        return withTemporaryHome(home -> new Wiring(config, credentials, scrubber,
            ClockProvider.system(), exists, windows, launcher, environment, () -> store,
            TEST_CONFIG_LOCATION, home));
    }

    /**
     * As above, with a supplier of the Parameter Store, called only if a node reads it (feature
     * 011, research R-14: the live tests pass the real AWS client this way, as the server does).
     */
    public static TestWiring withParameterStoreSupplier(ProfileConfig config,
        CredentialStore credentials, SecretScrubber scrubber, Predicate<Path> exists,
        boolean windows, ProcessLauncher launcher, Map<String, String> environment,
        Supplier<ParameterStore> parameterStore) {
        return withTemporaryHome(home -> new Wiring(config, credentials, scrubber,
            ClockProvider.system(), exists, windows, launcher, environment, parameterStore,
            TEST_CONFIG_LOCATION, home));
    }

    /**
     * As {@link #withParameterStoreSupplier}, with a home the caller owns (feature 023, research
     * D-2): the state survives a new wiring on the same home, and this class never deletes it.
     */
    public static TestWiring withHome(ProfileConfig config, CredentialStore credentials,
        SecretScrubber scrubber, Predicate<Path> exists, boolean windows,
        ProcessLauncher launcher, Map<String, String> environment,
        Supplier<ParameterStore> parameterStore, Path userHome) {
        return new TestWiring(new Wiring(config, credentials, scrubber, ClockProvider.system(),
            exists, windows, launcher, environment, parameterStore, TEST_CONFIG_LOCATION,
            userHome), userHome, false);
    }

    /**
     * As {@link #of(ProfileConfig, CredentialStore, SecretScrubber, Predicate, boolean,
     * ProcessLauncher, Map)}, with the configuration location the server would have been started
     * with (feature 015, research R-9).
     */
    public static TestWiring withConfigLocation(ProfileConfig config, CredentialStore credentials,
        SecretScrubber scrubber, Predicate<Path> exists, boolean windows,
        ProcessLauncher launcher, Map<String, String> environment, ConfigLocation location) {
        return withTemporaryHome(home -> new Wiring(config, credentials, scrubber,
            ClockProvider.system(), exists, windows, launcher, environment, location, home));
    }

    /** As the launcher variant, with a capability matrix other than the production one. */
    public static TestWiring of(ProfileConfig config, CredentialStore credentials,
        SecretScrubber scrubber, Predicate<Path> exists, boolean windows,
        ProcessLauncher launcher, Map<String, String> environment, CapabilityMatrix matrix) {
        return withTemporaryHome(home -> new Wiring(config, credentials, scrubber,
            ClockProvider.system(), exists, windows, launcher, environment, () -> {
                throw new IllegalStateException("no Parameter Store in this test");
            }, TEST_CONFIG_LOCATION, home, matrix));
    }

    /** The MCP server the launcher starts, with {@link #toolHandlers()}. */
    public McpServerFactory serverFactory(String version) {
        return wiring.serverFactory(version, wiring.toolHandlers());
    }

    /** The handlers the server registers. */
    public List<ToolHandler> toolHandlers() {
        return wiring.toolHandlers();
    }

    /** Feature 022 (coverage check 6): the registered tool actions. */
    public de.dadecker.inubit.mcp.application.ActionCatalog actionCatalog() {
        return wiring.actionCatalog();
    }

    /** The configuration location of the wiring. */
    ConfigLocation configLocation() {
        return wiring.configLocation();
    }

    /** The gateways of the wiring (e.g. for a live test's own read or cleanup). */
    public de.dadecker.inubit.mcp.domain.port.GatewayFactory gateways() {
        return wiring.gateways();
    }

    /** The home the wiring keeps its per-profile state below ({@code <home>/.inubit-mcp}). */
    public Path userHome() {
        return userHome;
    }

    /** Closes the wiring, then deletes its temporary home (not a home given to withHome). */
    @Override
    public void close() {
        try {
            wiring.close();
        } finally {
            if (ownsHome) {
                deleteHome(userHome);
            }
        }
    }
}
