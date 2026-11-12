package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.config.CliChoice.AddressKind;
import de.dadecker.inubit.mcp.config.CliChoice.HomeSource;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeSummary;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;

/**
 * The fully resolved settings of one server. Every value follows the chain server → stage →
 * {@code defaults} → built-in default (data-model.md → NodeConfig).
 *
 * @param credentialPrefix the profile's effective credential prefix (002 research D-6), so that
 *     every message about this node's credentials names its own variables
 *     ({@link #credentialNames()})
 * @param secretPlan where this node's credentials come from (feature 010, research R-6)
 * @param archivesProductionOptIn the group's {@code archives.productionOptIn} (feature 015,
 *     research R-15), built-in {@code false}; it matters only on a production group, where
 *     {@code export_archives} needs it, and is independent of {@link #write()}
 * @param queueEntryRead the group's {@code unsupported.queueEntryRead} (feature 020, research
 *     D-15), built-in {@code false}
 * @param serverLogRead the group's {@code unsupported.serverLogRead} (feature 021, research
 *     D-17), built-in {@code false}
 * @param cli the StartCLI settings in use: {@link #cliFor} of the pinned version (version-less
 *     for {@code AUTO} and {@code V9_X}), or the choice a launch selected ({@link #withCli})
 * @param cliSources the layers {@link #cliFor} chooses from (feature 017, research D-2)
 */
public record EffectiveNodeConfig(
    NodeId id,
    boolean production,
    URI baseUrl,
    boolean allowInsecureHttp,
    VersionSetting versionLine,
    Write write,
    Tls tls,
    Cli cli,
    Inventory inventory,
    Duration timeout,
    Duration cliTimeout,
    Duration cliExportTimeout,
    Duration hangingThreshold,
    Duration confirmationTtl,
    String credentialPrefix,
    Development development,
    SecretPlan secretPlan,
    boolean archivesProductionOptIn,
    boolean queueEntryRead,
    boolean serverLogRead,
    CliSources cliSources) {

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
        Objects.requireNonNull(development, "development");
        Objects.requireNonNull(secretPlan, "secretPlan");
        if (!secretPlan.node().equals(id)) {
            throw new IllegalArgumentException("The secret plan of " + secretPlan.node()
                + " does not belong to " + id);
        }
        Objects.requireNonNull(cliSources, "cliSources");
    }

    /**
     * A server built without {@link CliSources}: its StartCLI settings count as node settings
     * ({@link CliSources#of}), so every version chooses the same client and only an address that
     * is not the default one is explicit. It is not opted in to {@link #queueEntryRead} or
     * {@link #serverLogRead}.
     */
    public EffectiveNodeConfig(NodeId id, boolean production, URI baseUrl,
        boolean allowInsecureHttp, VersionSetting versionLine, Write write, Tls tls, Cli cli,
        Inventory inventory, Duration timeout, Duration cliTimeout, Duration cliExportTimeout,
        Duration hangingThreshold, Duration confirmationTtl, String credentialPrefix,
        Development development, SecretPlan secretPlan, boolean archivesProductionOptIn) {
        this(id, production, baseUrl, allowInsecureHttp, versionLine, write, tls, cli, inventory,
            timeout, cliTimeout, cliExportTimeout, hangingThreshold, confirmationTtl,
            credentialPrefix, development, secretPlan, archivesProductionOptIn, false, false,
            baseUrl == null || cli == null ? null : CliSources.of(baseUrl, cli));
    }

    public record Write(boolean enabled, boolean productionOptIn, ConfirmationMode confirmation) {
        public Write {
            Objects.requireNonNull(confirmation, "confirmation");
        }
    }

    /**
     * @param pinPerServer the stage's {@code tls.pinPerServer}: StartCLI calls are preceded by a
     *     certificate check against this server's own pin (feature 014, research R-15)
     */
    public record Tls(
        Optional<Path> trustStore,
        boolean disableHostnameVerification,
        Optional<String> pinnedCertificateSha256,
        boolean pinPerServer) {
        public Tls {
            Objects.requireNonNull(trustStore, "trustStore");
            Objects.requireNonNull(pinnedCertificateSha256, "pinnedCertificateSha256");
        }

        /** A server of a stage without {@code tls.pinPerServer}. */
        public Tls(Optional<Path> trustStore, boolean disableHostnameVerification,
            Optional<String> pinnedCertificateSha256) {
            this(trustStore, disableHostnameVerification, pinnedCertificateSha256, false);
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
     * A node or group value with its level (feature 017, data-model.md → CliSources).
     *
     * @param level {@link HomeSource#NODE} or {@link HomeSource#GROUP}
     */
    public record Layered<T>(T value, HomeSource level) {
        public Layered {
            Objects.requireNonNull(value, "value");
            if (level != HomeSource.NODE && level != HomeSource.GROUP) {
                throw new IllegalArgumentException("A layered value is a node or group value, not "
                    + level);
            }
        }

        static <T> Optional<Layered<T>> of(Optional<T> node, Optional<T> group) {
            return node.map(value -> new Layered<>(value, HomeSource.NODE))
                .or(() -> group.map(value -> new Layered<>(value, HomeSource.GROUP)));
        }
    }

    /**
     * The StartCLI settings of a node before the version is known (feature 017, research D-2,
     * D-3).
     *
     * @param url             the explicit {@code cli.url} (node → group)
     * @param home            {@code cli.home} of the node or group
     * @param javaHome        {@code cli.javaHome} of the node or group
     * @param clients         {@code defaults.cliClients}
     * @param defaultHome     {@code defaults.cliHome}
     * @param defaultJavaHome {@code defaults.cliJavaHome}
     */
    public record CliSources(Optional<URI> url, Optional<Layered<Path>> home,
        Optional<Layered<Path>> javaHome, Map<MajorMinor, CliClient> clients,
        Optional<Path> defaultHome, Optional<Path> defaultJavaHome) {
        public CliSources {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(home, "home");
            Objects.requireNonNull(javaHome, "javaHome");
            clients = Map.copyOf(clients);
            Objects.requireNonNull(defaultHome, "defaultHome");
            Objects.requireNonNull(defaultJavaHome, "defaultJavaHome");
        }

        /**
         * {@code cli} as node settings of a server at {@code baseUrl}; its address is explicit
         * unless it is the default servlet address.
         */
        public static CliSources of(URI baseUrl, Cli cli) {
            Optional<URI> url = Optional.of(cli.url())
                .filter(explicit -> !explicit.equals(URI.create(baseUrl + DEFAULT_CLI_PATH)));
            return new CliSources(url, Layered.of(cli.home(), Optional.empty()),
                Layered.of(cli.javaHome(), Optional.empty()), Map.of(), Optional.empty(),
                Optional.empty());
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

    /**
     * The development settings of feature 004 (research D-1, contracts/configuration-delta.md),
     * resolved node → group → {@code defaults} → built-in; {@code e2e.soap.baseUrl} node → group.
     *
     * @param enabled      {@code development.enabled} (built-in {@code false})
     * @param confirmation {@code development.confirmation} (built-in {@code SERVER})
     * @param e2eTests     {@code e2eTests} (built-in {@code FORBIDDEN})
     * @param soapBaseUrl  {@code e2e.soap.baseUrl}, if set
     * @param repositoryWrite the effective {@code development.repository} of feature 008
     *     (research D-2): {@code true} only if it resolves to {@code WRITE} and the node is a
     *     development stage ({@code enabled} and not {@code production})
     */
    public record Development(boolean enabled, ConfirmationMode confirmation,
        E2ePolicy e2eTests, Optional<URI> soapBaseUrl, boolean repositoryWrite) {
        public Development {
            Objects.requireNonNull(confirmation, "confirmation");
            Objects.requireNonNull(e2eTests, "e2eTests");
            Objects.requireNonNull(soapBaseUrl, "soapBaseUrl");
        }
    }

    /** No development settings: the built-in defaults. */
    public static final Development NO_DEVELOPMENT = new Development(
        Defaults.BUILTIN_DEVELOPMENT_ENABLED, Defaults.BUILTIN_DEVELOPMENT_CONFIRMATION,
        Defaults.BUILTIN_E2E_TESTS, Optional.empty(), false);

    /** The development settings as the guard and the writing services see them. */
    public DevelopmentPolicy developmentPolicy() {
        return new DevelopmentPolicy(id, production, development.enabled(),
            WritePolicy.Confirmation.valueOf(development.confirmation().name()), confirmationTtl,
            development.e2eTests(), development.soapBaseUrl(), development.repositoryWrite());
    }

    /** The same settings with another REST {@code timeout} (e.g. for short probes). */
    public EffectiveNodeConfig withTimeout(Duration newTimeout) {
        return new EffectiveNodeConfig(id, production, baseUrl, allowInsecureHttp, versionLine,
            write, tls, cli, inventory, newTimeout, cliTimeout, cliExportTimeout, hangingThreshold,
            confirmationTtl, credentialPrefix, development, secretPlan,
            archivesProductionOptIn, queueEntryRead, serverLogRead, cliSources);
    }

    /**
     * The same settings with {@code chosen} as StartCLI settings; {@link #cliSources()} stays, so
     * choosing again gives the same result (feature 017, research D-4).
     */
    public EffectiveNodeConfig withCli(Cli chosen) {
        return new EffectiveNodeConfig(id, production, baseUrl, allowInsecureHttp, versionLine,
            write, tls, chosen, inventory, timeout, cliTimeout, cliExportTimeout,
            hangingThreshold, confirmationTtl, credentialPrefix, development, secretPlan,
            archivesProductionOptIn, queueEntryRead, serverLogRead, cliSources);
    }

    /**
     * The StartCLI address of {@link #entryProbe}: {@code startcli -v} does not use it, and it
     * holds no character the Windows command interpreter interprets.
     */
    public static final URI ENTRY_PROBE_URL = URI.create("https://localhost");

    /**
     * A neutral copy of this node with {@code home} and {@code javaHome} as its sole StartCLI
     * client, chosen for every version: no trust store, host-name or pin setting and
     * {@link #ENTRY_PROBE_URL} as address, so that none of this node's own settings can refuse
     * the run. {@code --check-config} probes a {@code defaults.cliClients} entry with
     * {@code -v} this way (feature 017, T025).
     */
    public EffectiveNodeConfig entryProbe(Path home, Optional<Path> javaHome) {
        Cli only = new Cli(ENTRY_PROBE_URL, Optional.of(home), javaHome);
        return new EffectiveNodeConfig(id, production, baseUrl, allowInsecureHttp, versionLine,
            write, new Tls(Optional.empty(), false, Optional.empty()), only, inventory, timeout,
            cliTimeout, cliExportTimeout, hangingThreshold, confirmationTtl, credentialPrefix,
            development, secretPlan, archivesProductionOptIn, queueEntryRead, serverLogRead,
            CliSources.of(baseUrl, only));
    }

    /**
     * The StartCLI client, JDK and address for {@code version}, the node's effective version
     * (feature 017, research D-3): node or group {@code cli.home}, else the
     * {@code defaults.cliClients} entry of the version's major.minor (never for a line without
     * minor such as {@code V9_X}), else {@code defaults.cliHome}. JDK: node → group
     * {@code cli.javaHome} → the entry's {@code javaHome} (only with the entry's home) →
     * {@code defaults.cliJavaHome}. Address: explicit {@code cli.url}, else the servlet address
     * for a major below 9 or unknown, else {@code <baseUrl>}; the major is the version's, else the
     * line pin's.
     */
    public CliChoice cliFor(Optional<InubitVersion> version) {
        return choose(cliSources, baseUrl, versionLine, version);
    }

    /**
     * The choice for the version the {@code versionLine} alone gives: a pin or a line with minor;
     * {@code AUTO} and a line without minor give the version-less choice (as {@link #cli()}).
     */
    public CliChoice cliForSetting() {
        return cliFor(NodeVersionState.initial(versionLine).effectiveVersion());
    }

    /**
     * Whether this node's StartCLI client depends on its version (feature 017):
     * {@code defaults.cliClients} is configured or the {@code versionLine} has major 9 or later.
     * Otherwise the node shows exactly the 0.8.0 output (FR-009).
     */
    public boolean choosesCliByVersion() {
        OptionalInt major = cliForSetting().major();
        return !cliSources.clients().isEmpty() || major.isPresent() && major.getAsInt() >= 9;
    }

    private static CliChoice choose(CliSources sources, URI baseUrl, VersionSetting line,
        Optional<InubitVersion> version) {
        Objects.requireNonNull(version, "version");
        boolean wholeMajorLine = line instanceof VersionSetting.Line l && l.minor().isEmpty();
        Optional<MajorMinor> entryKey = version.filter(v -> !wholeMajorLine).map(MajorMinor::of)
            .filter(sources.clients()::containsKey);
        Optional<Path> home;
        HomeSource source;
        Optional<Path> entryJavaHome = Optional.empty();
        if (sources.home().isPresent()) {
            home = Optional.of(sources.home().get().value());
            source = sources.home().get().level();
            entryKey = Optional.empty();
        } else if (entryKey.isPresent()) {
            CliClient client = sources.clients().get(entryKey.get());
            home = Optional.of(client.home());
            source = HomeSource.VERSION_ENTRY;
            entryJavaHome = client.javaHome();
        } else {
            home = sources.defaultHome();
            source = home.isPresent() ? HomeSource.DEFAULTS : HomeSource.NONE;
        }
        Optional<Path> fromEntry = entryJavaHome;
        Optional<Path> javaHome = sources.javaHome().map(Layered::value).or(() -> fromEntry)
            .or(sources::defaultJavaHome);
        OptionalInt major = version.map(v -> OptionalInt.of(v.major()))
            .orElseGet(() -> lineMajor(line));
        AddressKind address;
        URI url;
        if (sources.url().isPresent()) {
            address = AddressKind.CONFIGURED;
            url = sources.url().get();
        } else if (major.isPresent() && major.getAsInt() >= 9) {
            address = AddressKind.BASE_URL;
            url = baseUrl;
        } else {
            address = AddressKind.SERVLET_PATH;
            url = URI.create(baseUrl + DEFAULT_CLI_PATH);
        }
        return new CliChoice(new Cli(url, home, javaHome), source, entryKey, address, major,
            version.isPresent());
    }

    private static OptionalInt lineMajor(VersionSetting line) {
        return switch (line) {
            case VersionSetting.Auto auto -> OptionalInt.empty();
            case VersionSetting.Line l -> OptionalInt.of(l.major());
            case VersionSetting.Pinned pinned -> OptionalInt.of(pinned.version().major());
        };
    }

    /**
     * The credential store entry names of this node: those of its plan; for an AWS node (which
     * reads no entries) those under the profile's prefix.
     */
    public CredentialNames credentialNames() {
        return secretPlan.entries()
            .orElseGet(() -> new CredentialNames(credentialPrefix, id));
    }

    /**
     * The names of this node's secrets in messages, per its source (research R-15); a credential
     * store node names the store of the running system (feature 011).
     */
    public SecretLabels secretLabels() {
        return SecretLabels.of(secretPlan, StoreKind.forOs(System.getProperty("os.name")));
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
            development.enabled() && !production, write.confirmation().name(),
            development.repositoryWrite(), versionLine.text(), cliAvailable(exists, windows),
            Optional.empty(), Optional.empty());
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
     * @param secretPlan the node's entry of {@link ProfileConfig#secretPlans()}
     */
    static EffectiveNodeConfig resolve(Defaults defaults, GroupConfig group,
        NodeConfig node, String credentialPrefix, SecretPlan secretPlan) {
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
            node.tls().pinnedCertificateSha256().or(group.tls()::pinnedCertificateSha256),
            group.tls().marksStage());
        VersionSetting versionLine = node.versionLine().or(group::versionLine)
            .orElse(Defaults.BUILTIN_VERSION_LINE);
        CliSources cliSources = new CliSources(
            node.cli().url().or(group.cli()::url),
            Layered.of(node.cli().home(), group.cli().home()),
            Layered.of(node.cli().javaHome(), group.cli().javaHome()),
            defaults.cliClients(),
            defaults.cliHome(),
            defaults.cliJavaHome());
        Cli cli = choose(cliSources, baseUrl, versionLine,
            NodeVersionState.initial(versionLine).effectiveVersion()).cli();
        Inventory inventory = new Inventory(
            node.inventory().owner().or(group.inventory()::owner)
                .or(defaults.inventory()::owner),
            node.inventory().cacheTtl().or(group.inventory()::cacheTtl)
                .or(defaults.inventory()::cacheTtl).orElse(Defaults.BUILTIN_INVENTORY_CACHE_TTL));
        boolean developmentEnabled = node.development().enabled()
            .or(group.development()::enabled).or(defaults.development()::enabled)
            .orElse(Defaults.BUILTIN_DEVELOPMENT_ENABLED);
        RepositoryMode repository = node.development().repository()
            .or(group.development()::repository).or(defaults.development()::repository)
            .orElse(Defaults.BUILTIN_DEVELOPMENT_REPOSITORY);
        return new EffectiveNodeConfig(
            id,
            group.production(),
            baseUrl,
            node.allowInsecureHttp(),
            versionLine,
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
            credentialPrefix,
            new Development(
                developmentEnabled,
                node.development().confirmation().or(group.development()::confirmation)
                    .or(defaults.development()::confirmation)
                    .orElse(Defaults.BUILTIN_DEVELOPMENT_CONFIRMATION),
                node.e2eTests().or(group::e2eTests).or(defaults::e2eTests)
                    .orElse(Defaults.BUILTIN_E2E_TESTS),
                node.e2e().soap().baseUrl().or(group.e2e().soap()::baseUrl)
                    .map(Urls::withoutTrailingSlash),
                repository == RepositoryMode.WRITE && developmentEnabled
                    && !group.production()),
            secretPlan,
            group.archives().productionOptIn()
                .orElse(Defaults.BUILTIN_ARCHIVES_PRODUCTION_OPT_IN),
            group.unsupported().queueEntryRead().orElse(Defaults.BUILTIN_QUEUE_ENTRY_READ),
            group.unsupported().serverLogRead().orElse(Defaults.BUILTIN_SERVER_LOG_READ),
            cliSources);
    }
}
