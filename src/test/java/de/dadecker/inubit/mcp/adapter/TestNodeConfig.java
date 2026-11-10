package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.config.CliChoice.HomeSource;
import de.dadecker.inubit.mcp.config.CliClient;
import de.dadecker.inubit.mcp.config.ConfirmationMode;
import de.dadecker.inubit.mcp.config.CredentialNames;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.config.SecretPlan;
import de.dadecker.inubit.mcp.config.SecretSourceConfig;
import de.dadecker.inubit.mcp.config.StoreKind;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Builder for {@link EffectiveNodeConfig} values in adapter tests. */
public final class TestNodeConfig {

    private NodeId id = NodeId.parse("dev/node1");
    private URI baseUrl = URI.create("https://inubit.example.test:8443");
    private boolean allowInsecureHttp;
    private Optional<URI> cliUrl = Optional.empty();
    private Optional<Path> trustStore = Optional.empty();
    private boolean disableHostnameVerification;
    private Optional<String> pin = Optional.empty();
    private Optional<Path> cliHome = Optional.empty();
    private Optional<Path> cliJavaHome = Optional.empty();
    private Duration timeout = Duration.ofSeconds(5);
    private Duration cliTimeout = Duration.ofSeconds(30);
    private Duration cliExportTimeout = Duration.ofSeconds(120);
    private String inventoryOwner = "OWNERS";
    private VersionSetting versionLine = VersionSetting.V8_1;
    private boolean production;
    private EffectiveNodeConfig.Write write =
        new EffectiveNodeConfig.Write(false, false, ConfirmationMode.SERVER);

    /** {@code credentials.envPrefix: INUBIT}: the variable names of feature 001. */
    private String credentialPrefix = "INUBIT";
    private EffectiveNodeConfig.Development development = EffectiveNodeConfig.NO_DEVELOPMENT;
    private boolean archivesProductionOptIn;
    private final Map<MajorMinor, CliClient> cliClients = new LinkedHashMap<>();
    private Optional<Path> defaultCliHome = Optional.empty();
    private Optional<Path> defaultCliJavaHome = Optional.empty();

    private TestNodeConfig() {
    }

    /** The message names of an environment node with credential prefix {@code prefix}. */
    public static SecretLabels storeLabels(String prefix, NodeId node) {
        return new SecretLabels.Store(new CredentialNames(prefix, node), StoreKind.KEYCHAIN);
    }

    public static TestNodeConfig node() {
        return new TestNodeConfig();
    }

    public TestNodeConfig id(String value) {
        this.id = NodeId.parse(value);
        return this;
    }

    public TestNodeConfig baseUrl(String value) {
        this.baseUrl = URI.create(value);
        return this;
    }

    public TestNodeConfig allowInsecureHttp(boolean value) {
        this.allowInsecureHttp = value;
        return this;
    }

    public TestNodeConfig cliUrl(String value) {
        this.cliUrl = Optional.of(URI.create(value));
        return this;
    }

    public TestNodeConfig trustStore(Path value) {
        this.trustStore = Optional.of(value);
        return this;
    }

    public TestNodeConfig disableHostnameVerification(String pinnedSha256) {
        this.disableHostnameVerification = true;
        this.pin = Optional.of(pinnedSha256);
        return this;
    }

    /** {@code disableHostnameVerification} without a pin (refused by the validator). */
    public TestNodeConfig disableHostnameVerificationWithoutPin() {
        this.disableHostnameVerification = true;
        this.pin = Optional.empty();
        return this;
    }

    public TestNodeConfig pin(String pinnedSha256) {
        this.pin = Optional.of(pinnedSha256);
        return this;
    }

    public TestNodeConfig cliHome(Path value) {
        this.cliHome = Optional.of(value);
        return this;
    }

    public TestNodeConfig cliJavaHome(Path value) {
        this.cliJavaHome = Optional.of(value);
        return this;
    }

    /** Feature 017: a {@code defaults.cliClients} entry. */
    public TestNodeConfig cliClient(String key, Path home, Optional<Path> javaHome) {
        this.cliClients.put(MajorMinor.parse(key).orElseThrow(), new CliClient(home, javaHome));
        return this;
    }

    /** Feature 017: {@code defaults.cliHome} (below a node's {@code cli.home}). */
    public TestNodeConfig defaultCliHome(Path value) {
        this.defaultCliHome = Optional.of(value);
        return this;
    }

    /** Feature 017: {@code defaults.cliJavaHome} (below a node's {@code cli.javaHome}). */
    public TestNodeConfig defaultCliJavaHome(Path value) {
        this.defaultCliJavaHome = Optional.of(value);
        return this;
    }

    public TestNodeConfig timeout(Duration value) {
        this.timeout = value;
        return this;
    }

    public TestNodeConfig cliTimeout(Duration value) {
        this.cliTimeout = value;
        return this;
    }

    public TestNodeConfig cliExportTimeout(Duration value) {
        this.cliExportTimeout = value;
        return this;
    }

    public TestNodeConfig inventoryOwner(String value) {
        this.inventoryOwner = value;
        return this;
    }

    public TestNodeConfig versionLine(VersionSetting value) {
        this.versionLine = value;
        return this;
    }

    public TestNodeConfig production(boolean value) {
        this.production = value;
        return this;
    }

    public TestNodeConfig write(boolean enabled, boolean productionOptIn,
        ConfirmationMode confirmation) {
        this.write = new EffectiveNodeConfig.Write(enabled, productionOptIn, confirmation);
        return this;
    }

    /** The development settings of feature 004 (default: none). */
    public TestNodeConfig development(EffectiveNodeConfig.Development value) {
        this.development = value;
        return this;
    }

    /** The group's {@code archives.productionOptIn} of feature 015 (default: {@code false}). */
    public TestNodeConfig archivesProductionOptIn(boolean value) {
        this.archivesProductionOptIn = value;
        return this;
    }

    public TestNodeConfig credentialPrefix(String value) {
        this.credentialPrefix = value;
        return this;
    }

    /**
     * The node; with {@code cliClients} or a default CLI setting its StartCLI settings are layered
     * as {@code ConfigLoader} resolves them (feature 017, research D-3), else they are fixed node
     * settings.
     */
    public EffectiveNodeConfig build() {
        EffectiveNodeConfig fixed = buildFixed();
        if (cliClients.isEmpty() && defaultCliHome.isEmpty() && defaultCliJavaHome.isEmpty()) {
            return fixed;
        }
        EffectiveNodeConfig.CliSources sources = new EffectiveNodeConfig.CliSources(cliUrl,
            cliHome.map(home -> new EffectiveNodeConfig.Layered<>(home, HomeSource.NODE)),
            cliJavaHome.map(home -> new EffectiveNodeConfig.Layered<>(home, HomeSource.NODE)),
            cliClients, defaultCliHome, defaultCliJavaHome);
        EffectiveNodeConfig layered = new EffectiveNodeConfig(fixed.id(), fixed.production(),
            fixed.baseUrl(), fixed.allowInsecureHttp(), fixed.versionLine(), fixed.write(),
            fixed.tls(), fixed.cli(), fixed.inventory(), fixed.timeout(), fixed.cliTimeout(),
            fixed.cliExportTimeout(), fixed.hangingThreshold(), fixed.confirmationTtl(),
            fixed.credentialPrefix(), fixed.development(), fixed.secretPlan(),
            fixed.archivesProductionOptIn(), false, sources);
        return layered.withCli(layered.cliFor(
            NodeVersionState.initial(versionLine).effectiveVersion()).cli());
    }

    private EffectiveNodeConfig buildFixed() {
        return new EffectiveNodeConfig(
            id,
            production,
            baseUrl,
            allowInsecureHttp,
            versionLine,
            write,
            new EffectiveNodeConfig.Tls(trustStore, disableHostnameVerification, pin),
            new EffectiveNodeConfig.Cli(
                cliUrl.orElse(URI.create(baseUrl + EffectiveNodeConfig.DEFAULT_CLI_PATH)),
                cliHome, cliJavaHome),
            new EffectiveNodeConfig.Inventory(Optional.ofNullable(inventoryOwner),
                Duration.ofMinutes(10)),
            timeout,
            cliTimeout,
            cliExportTimeout,
            Duration.ofMinutes(60),
            Duration.ofMinutes(5),
            credentialPrefix,
            development,
            new SecretPlan(id, new SecretSourceConfig.SystemStore(Optional.empty()),
                SecretPlan.Origin.DEFAULT,
                Optional.of(new CredentialNames(credentialPrefix, id)), Optional.empty()),
            archivesProductionOptIn);
    }
}
