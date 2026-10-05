package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeSummary;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The fully resolved settings of one server. Every value follows the chain server → stage →
 * {@code defaults} → built-in default (data-model.md → NodeConfig).
 *
 * @param credentialPrefix the profile's effective credential prefix (002 research D-6), so that
 *     every message about this node's credentials names its own variables
 *     ({@link #credentialVariables()})
 */
public record EffectiveNodeConfig(
    NodeId id,
    boolean production,
    URI baseUrl,
    boolean allowInsecureHttp,
    VersionLine versionLine,
    Write write,
    Tls tls,
    Cli cli,
    Inventory inventory,
    Duration timeout,
    Duration cliTimeout,
    Duration cliExportTimeout,
    Duration hangingThreshold,
    Duration confirmationTtl,
    String credentialPrefix) {

    /** Path of the StartCLI SOAP endpoint relative to the base URL (8.1). */
    public static final String DEFAULT_CLI_PATH = "/ibis/servlet/IBISSoapServlet";

    public EffectiveNodeConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (Urls.hasUserInfo(baseUrl)) {
            throw new IllegalArgumentException("baseUrl of " + id + " must not contain user info");
        }
        Objects.requireNonNull(versionLine, "versionLine");
        Objects.requireNonNull(write, "write");
        Objects.requireNonNull(tls, "tls");
        Objects.requireNonNull(cli, "cli");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(cliTimeout, "cliTimeout");
        Objects.requireNonNull(cliExportTimeout, "cliExportTimeout");
        Objects.requireNonNull(hangingThreshold, "hangingThreshold");
        Objects.requireNonNull(confirmationTtl, "confirmationTtl");
        Objects.requireNonNull(credentialPrefix, "credentialPrefix");
    }

    public record Write(boolean enabled, boolean productionOptIn, ConfirmationMode confirmation) {
        public Write {
            Objects.requireNonNull(confirmation, "confirmation");
        }
    }

    public record Tls(
        Optional<Path> trustStore,
        boolean disableHostnameVerification,
        Optional<String> pinnedCertificateSha256) {
        public Tls {
            Objects.requireNonNull(trustStore, "trustStore");
            Objects.requireNonNull(pinnedCertificateSha256, "pinnedCertificateSha256");
        }
    }

    /**
     * @param javaHome the configured JDK for StartCLI; when empty, the server's own
     *     {@code JAVA_HOME} applies (resolved where the process is launched)
     */
    public record Cli(URI url, Optional<Path> home, Optional<Path> javaHome) {
        public Cli {
            Objects.requireNonNull(url, "url");
            if (Urls.hasUserInfo(url)) {
                throw new IllegalArgumentException("cli.url must not contain user info");
            }
            Objects.requireNonNull(home, "home");
            Objects.requireNonNull(javaHome, "javaHome");
        }
    }

    /**
     * @param owner the effective {@code inventory.owner}; empty if neither the node, its group
     *     nor {@code defaults} sets one (there is no built-in owner, FR-014)
     */
    public record Inventory(Optional<String> owner, Duration cacheTtl) {
        public Inventory {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(cacheTtl, "cacheTtl");
        }
    }

    /** The same settings with another REST {@code timeout} (e.g. for short probes). */
    public EffectiveNodeConfig withTimeout(Duration newTimeout) {
        return new EffectiveNodeConfig(id, production, baseUrl, allowInsecureHttp, versionLine,
            write, tls, cli, inventory, newTimeout, cliTimeout, cliExportTimeout, hangingThreshold,
            confirmationTtl, credentialPrefix);
    }

    /** The credential environment variables of this node. */
    public CredentialVariables credentialVariables() {
        return new CredentialVariables(credentialPrefix, id);
    }

    /** Write access after the production lock: {@code enabled && (!production || optIn)}. */
    public boolean effectiveWriteEnabled() {
        return write.enabled() && (!production || write.productionOptIn());
    }

    /** True if a CLI home is configured (whether {@code startcli} exists is checked separately). */
    public boolean cliConfigured() {
        return cli.home().isPresent();
    }

    /** CLI home configured and {@code startcli} found (data-model.md → NodeSummary). */
    public boolean cliAvailable(Predicate<Path> exists, boolean windows) {
        return startCliScript(windows).filter(exists).isPresent();
    }

    /**
     * The {@code list_nodes} entry of this server: no URLs, usernames or credentials (FR-003).
     */
    public NodeSummary summary(Predicate<Path> exists, boolean windows) {
        return new NodeSummary(id, id.group(), id.name(), production, effectiveWriteEnabled(),
            write.confirmation().name(), versionLine.name(), cliAvailable(exists, windows));
    }

    /** StartCLI below the CLI home: {@code bin/startcli.sh}, {@code .bat} on Windows. */
    public Optional<Path> startCliScript(boolean windows) {
        return cli.home().map(home -> home.resolve("bin")
            .resolve(windows ? "startcli.bat" : "startcli.sh"));
    }

    /**
     * Resolves one server; see {@link ProfileConfig#effectiveNodes()} for the preconditions.
     *
     * @param credentialPrefix {@link ProfileConfig#credentialPrefix()}
     */
    static EffectiveNodeConfig resolve(Defaults defaults, GroupConfig group,
        NodeConfig node, String credentialPrefix) {
        NodeId id = NodeId.of(group.name(), node.name());
        URI baseUrl = Urls.withoutTrailingSlash(node.baseUrl().orElseThrow(
            () -> new IllegalArgumentException("baseUrl missing for " + id)));
        Write write = new Write(
            node.write().enabled().or(group.write()::enabled)
                .orElse(Defaults.BUILTIN_WRITE_ENABLED),
            node.write().productionOptIn().or(group.write()::productionOptIn)
                .orElse(Defaults.BUILTIN_PRODUCTION_OPT_IN),
            node.write().confirmation().or(group.write()::confirmation)
                .orElse(Defaults.BUILTIN_CONFIRMATION));
        Tls tls = new Tls(
            node.tls().trustStore().or(group.tls()::trustStore),
            node.tls().disableHostnameVerification().or(group.tls()::disableHostnameVerification)
                .orElse(false),
            node.tls().pinnedCertificateSha256().or(group.tls()::pinnedCertificateSha256));
        Cli cli = new Cli(
            node.cli().url().or(group.cli()::url)
                .orElseGet(() -> URI.create(baseUrl + DEFAULT_CLI_PATH)),
            node.cli().home().or(group.cli()::home).or(defaults::cliHome),
            node.cli().javaHome().or(group.cli()::javaHome).or(defaults::cliJavaHome));
        Inventory inventory = new Inventory(
            node.inventory().owner().or(group.inventory()::owner)
                .or(defaults.inventory()::owner),
            node.inventory().cacheTtl().or(group.inventory()::cacheTtl)
                .or(defaults.inventory()::cacheTtl).orElse(Defaults.BUILTIN_INVENTORY_CACHE_TTL));
        return new EffectiveNodeConfig(
            id,
            group.production(),
            baseUrl,
            node.allowInsecureHttp(),
            node.versionLine().or(group::versionLine).orElse(Defaults.BUILTIN_VERSION_LINE),
            write,
            tls,
            cli,
            inventory,
            node.timeout().or(group::timeout).or(defaults::timeout)
                .orElse(Defaults.BUILTIN_TIMEOUT),
            node.cliTimeout().or(group::cliTimeout).or(defaults::cliTimeout)
                .orElse(Defaults.BUILTIN_CLI_TIMEOUT),
            node.cliExportTimeout().or(group::cliExportTimeout).or(defaults::cliExportTimeout)
                .orElse(Defaults.BUILTIN_CLI_EXPORT_TIMEOUT),
            node.hangingThreshold().or(group::hangingThreshold).or(defaults::hangingThreshold)
                .orElse(Defaults.BUILTIN_HANGING_THRESHOLD),
            node.confirmationTtl().or(group::confirmationTtl).or(defaults::confirmationTtl)
                .orElse(Defaults.BUILTIN_CONFIRMATION_TTL),
            credentialPrefix);
    }
}
