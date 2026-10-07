package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.config.ConfigException;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ConfigValidator;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.LoadedConfig;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.config.ValidationReport;
import de.dadecker.inubit.mcp.config.WorkspaceDirectory;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;

/**
 * The live node of the opt-in live tests (Constitution III; 002 research D-13):
 * {@code INUBIT_LIVE_NODE} (e.g. {@code test/node1}) from the real configuration, located like
 * the server locates it without arguments (002 research D-9: {@code INUBIT_MCP_CONFIG}, then
 * {@code INUBIT_MCP_PROFILE}, then {@code ~/.config/inubit-mcp/config.yaml}), with the
 * credentials from the profile's environment variables. Skips without the variable; the variable
 * of feature 001, {@code INUBIT_LIVE_SERVER}, is refused with a hint; a node whose group is
 * {@code production: true} is refused before anything is contacted.
 */
record LiveTarget(NodeId node, LoadedConfig loaded, CredentialResolution credentials,
    SecretScrubber scrubber, boolean windows) {

    static final String NODE_VARIABLE = "INUBIT_LIVE_NODE";
    /** The variable of feature 001; refused (002 research D-13). */
    static final String OLD_SERVER_VARIABLE = "INUBIT_LIVE_SERVER";
    /** The node of the development live test (feature 004). */
    static final String DEVELOPMENT_NODE_VARIABLE = "INUBIT_LIVE_DEV_NODE";

    static LiveTarget resolve() {
        return resolve(System.getenv(), Path.of(System.getProperty("user.home")),
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
    }

    /**
     * @param environment the process environment (live target, configuration location,
     *     credentials)
     * @param home        the user's home directory
     * @param windows     whether the tests run on Windows
     */
    static LiveTarget resolve(Map<String, String> environment, Path home, boolean windows) {
        return resolve(environment, home, windows, NODE_VARIABLE);
    }

    private static LiveTarget resolve(Map<String, String> environment, Path home,
        boolean windows, String nodeVariable) {
        if (isSet(environment.get(OLD_SERVER_VARIABLE))) {
            fail(OLD_SERVER_VARIABLE + " is no longer supported; use " + NODE_VARIABLE
                + "=<group>/<node> instead, and select the profile with "
                + ConfigLoader.PROFILE_ENV + "=<profile> (docs/live-tests.md)");
        }
        String target = environment.get(nodeVariable);
        Assumptions.assumeTrue(isSet(target), nodeVariable
            + " is not set (e.g. test/node1); the live test is skipped");
        NodeId nodeId;
        try {
            nodeId = NodeId.parse(target.strip());
        } catch (IllegalArgumentException e) {
            throw new AssertionError(nodeVariable + " must name one configured node as"
                + " <group>/<node>: " + e.getMessage(), e);
        }

        // the same location order, profile checks and validation as the server (Launcher)
        ConfigLoader loader = new ConfigLoader(environment, home, windows);
        LoadedConfig loaded;
        try {
            loaded = loader.load(null, null);
        } catch (ConfigException e) {
            throw new AssertionError(e.getMessage(), e);
        }
        List<LoadedConfig> otherProfiles = loader.otherProfiles(loaded.source());
        Set<String> otherVariables = new HashSet<>();
        otherProfiles.forEach(other -> otherVariables.addAll(
            other.config().credentialVariableNames()));
        SecretScrubber scrubber = new SecretScrubber();
        CredentialResolution credentials = new CredentialResolver(environment, scrubber,
            loaded.config().terminology().effectiveOrDefault(),
            loaded.config().credentialPrefix())
            .resolve(loaded.config().nodeIds(), otherVariables);
        // the workspace is not prepared: live tests must not create the person's real
        // workspace (feature 003, review M11); a test that needs one uses a temporary directory
        ValidationReport report = new ConfigValidator(Files::exists, environment, windows,
            Path.of(System.getProperty("java.io.tmpdir")), source -> otherProfiles,
            workspace -> new WorkspaceDirectory.Usable(false)).validate(loaded, credentials);
        assertThat(report.errors()).as("configuration errors in " + loaded.source()).isEmpty();
        EffectiveNodeConfig node = loaded.config().effectiveNodes().stream()
            .filter(candidate -> candidate.id().equals(nodeId))
            .findFirst()
            .orElseThrow(() -> new AssertionError(nodeId + " is not configured in "
                + loaded.source()));
        if (node.production()) {
            fail("Refusing to run live tests against " + nodeId
                + ": its group is production: true (Constitution III)");
        }
        return new LiveTarget(nodeId, loaded, credentials, scrubber, windows);
    }

    /**
     * The node of the opt-in development live test (feature 004, research D-23):
     * {@code INUBIT_LIVE_DEV_NODE}, resolved like {@link #resolve}, and refused unless it is a
     * development stage ({@code development.enabled}); a production group is refused anyway.
     */
    static LiveTarget resolveDevelopment() {
        return resolveDevelopment(System.getenv(), Path.of(System.getProperty("user.home")),
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
    }

    /** As {@link #resolveDevelopment()} with the given environment, home and platform. */
    static LiveTarget resolveDevelopment(Map<String, String> environment, Path home,
        boolean windows) {
        LiveTarget live = resolve(environment, home, windows, DEVELOPMENT_NODE_VARIABLE);
        boolean development = live.loaded().config().effectiveNodes().stream()
            .filter(node -> node.id().equals(live.node()))
            .anyMatch(node -> node.development().enabled());
        if (!development) {
            fail("Refusing to run the development live test: " + live.node() + " is not a"
                + " development node (development.enabled is not true for it)");
        }
        return live;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /** The production wiring for the real configuration. */
    TestWiring wiring() {
        return TestWiring.of(loaded.config(), credentials, scrubber, Files::exists, windows);
    }

    /**
     * The production wiring with {@code workspace} instead of the configured one (feature 003):
     * a live test never writes into the person's real workspace.
     */
    TestWiring wiring(Path workspace) {
        ProfileConfig config = loaded.config();
        return TestWiring.of(new ProfileConfig(config.profile(), config.terminology(),
            config.credentials(), config.groups(), config.defaults(), config.auditDirectory(),
            config.logLevel(), config.resultLimits(), workspace, config.owners()), credentials, scrubber,
            Files::exists, windows);
    }
}
