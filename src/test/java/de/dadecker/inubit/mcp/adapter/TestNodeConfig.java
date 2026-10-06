package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.config.ConfirmationMode;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.VersionLine;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Builder for {@link EffectiveNodeConfig} values in adapter tests. */
public final class TestNodeConfig {

    private NodeId id = NodeId.parse("dev/node1");
    private URI baseUrl = URI.create("https://inubit.example.test:8443");
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
    private VersionLine versionLine = VersionLine.V8_1;
    private boolean production;
    private EffectiveNodeConfig.Write write =
        new EffectiveNodeConfig.Write(false, false, ConfirmationMode.SERVER);

    /** {@code credentials.envPrefix: INUBIT}: the variable names of feature 001. */
    private String credentialPrefix = "INUBIT";
    private EffectiveNodeConfig.Development development = EffectiveNodeConfig.NO_DEVELOPMENT;

    private TestNodeConfig() {
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

    public TestNodeConfig versionLine(VersionLine value) {
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

    public TestNodeConfig credentialPrefix(String value) {
        this.credentialPrefix = value;
        return this;
    }

    public EffectiveNodeConfig build() {
        return new EffectiveNodeConfig(
            id,
            production,
            baseUrl,
            false,
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
            development);
    }
}
