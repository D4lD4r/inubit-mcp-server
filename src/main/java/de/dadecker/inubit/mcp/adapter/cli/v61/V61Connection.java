package de.dadecker.inubit.mcp.adapter.cli.v61;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.CliClientProbe;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.rest.TlsHandshakeCheck;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The connection sequence of one StartCLI session on an INUBIT 6.1 node (feature 011, FR-012,
 * FR-012a, research D-7, D-8), run once per tool call and node by {@link #session}:
 *
 * <ol>
 *   <li>StartCLI available and credentials set ({@link CliRunner#checkAvailable}): otherwise
 *       {@code CLI_UNAVAILABLE} or {@code AUTH_FAILED} without any contact;
 *   <li>the client options from {@link CliClientProbe#optionsFor}, cached or by a local
 *       {@code -h} run bounded by the node's {@code cliTimeout};
 *   <li>the refusals, all {@code TLS_ERROR} before any contact: {@code disableHostnameVerification}
 *       with a client that offers no {@code --disableHostNameVerification}; a PKCS12 trust store
 *       with a client runtime below Java 8 or of unknown version; a trust-store path with
 *       whitespace for a client without {@code --trustStoreFilePath} (it would have to go
 *       through {@code JAVA_TOOL_OPTIONS}); a StartCLI URL that is neither {@code https://} nor
 *       {@code http://} with {@code allowInsecureHttp};
 *   <li>a slot of the node's {@link CliConcurrencyLimiter}, held until the session is over;
 *   <li>for an {@code https://} StartCLI URL, the server's own handshake with its host and port
 *       ({@link TlsHandshakeCheck}); a rejected certificate starts no StartCLI process;
 *   <li>the session ({@link CliRunner#runSession}) with the node's {@code cliTimeout}.
 * </ol>
 *
 * <p>An option the probe could not read counts as absent (fail closed); so does every option of
 * a node the probe does not treat as CLI-only.
 */
public final class V61Connection {

    /** The first Java version that reads PKCS12 trust stores. */
    private static final int PKCS12_JAVA = 8;
    private static final CliClientOptions NOTHING_KNOWN =
        new CliClientOptions(false, false, false, Optional.empty());

    private final EffectiveNodeConfig server;
    private final NodeCredentials credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final CliClientProbe probe;
    private final CliConcurrencyLimiter limiter;

    public V61Connection(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliClientProbe probe,
        CliConcurrencyLimiter limiter) {
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    /**
     * Runs {@code commands} in one StartCLI session after the checks of this class.
     *
     * @throws ToolErrorException {@code CLI_UNAVAILABLE} or {@code AUTH_FAILED} before anything is
     *     launched; {@code TLS_ERROR} for a refusal or a rejected certificate, {@code UNREACHABLE}
     *     or {@code TIMEOUT} from the handshake, {@code TIMEOUT} without a free slot; any error of
     *     {@link CliRunner#runSession}
     */
    public CliResult session(List<CliSessionCommand> commands) {
        runner.checkAvailable(server);
        if (!credentials.complete()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                "No INUBIT credentials are configured for " + server.id()
                    + ", so StartCLI cannot log in",
                "The username or password variable for " + server.id() + " is not set",
                guard.fixCredentials()).withNode(server.id()));
        }
        CliClientOptions options =
            probe.optionsFor(server.id(), server.cliTimeout()).orElse(NOTHING_KNOWN);
        refuse(options);
        URI url = server.cli().url();
        try (CliConcurrencyLimiter.Permit permit = limiter.acquire(server)) {
            if (isHttps(url)) {
                TlsHandshakeCheck.verify(server, url);
            }
            return runner.runSession(server, credentials.username().orElseThrow().value(),
                credentials.password().orElseThrow().value(), options, commands,
                server.cliTimeout(), guard);
        }
    }

    /** The refusals of FR-012a and the HTTP rule, before any contact. */
    private void refuse(CliClientOptions options) {
        if (server.tls().disableHostnameVerification() && !options.disableHostNameVerification()) {
            throw refused("StartCLI on " + server.id() + " cannot skip the host-name check: the"
                    + " client offers no --disableHostNameVerification option",
                "disableHostnameVerification is set, but the StartCLI client has no option to"
                    + " skip host-name verification (or its options could not be read); it is"
                    + " never disabled by other means",
                "Use a StartCLI client that offers --disableHostNameVerification, or a host name"
                    + " the certificate is issued for without disableHostnameVerification");
        }
        Optional<Path> trustStore = server.tls().trustStore();
        if (trustStore.isPresent() && CliRunner.trustStoreType(trustStore.get()).equals("PKCS12")) {
            Optional<Integer> java = options.javaFeatureVersion();
            if (java.isEmpty()) {
                throw refused("The StartCLI runtime of " + server.id() + " (Java unknown) is not"
                        + " known to read the PKCS12 trust store",
                    "The Java version of the StartCLI runtime (cli.javaHome, else JAVA_HOME)"
                        + " could not be read from its release file; runtimes below Java 8"
                        + " cannot read PKCS12",
                    "Set cliJavaHome, or convert the trust store to JKS");
            }
            if (java.get() < PKCS12_JAVA) {
                throw refused("The StartCLI runtime of " + server.id() + " (Java " + java.get()
                        + ") cannot read the PKCS12 trust store",
                    "Java runtimes below 8 cannot read PKCS12 trust stores",
                    "Set cliJavaHome to a Java 8 or newer runtime, or convert the trust store to"
                        + " JKS");
            }
        }
        if (!options.trustStoreFilePath() && trustStore.isPresent()
            && trustStore.get().toString().chars().anyMatch(Character::isWhitespace)) {
            throw refused("The trust-store path of " + server.id() + " contains spaces, which"
                    + " the StartCLI runtime settings cannot carry",
                "The StartCLI client has no trust-store option, so the trust store goes through"
                    + " JAVA_TOOL_OPTIONS, which is split at whitespace",
                "Use a trust-store path without spaces");
        }
        URI url = server.cli().url();
        if (!isHttps(url) && !(isHttp(url) && server.allowInsecureHttp())) {
            throw refused("StartCLI on " + server.id() + " is refused: its URL does not use"
                    + " https://",
                "The StartCLI URL (cli.url, otherwise baseUrl) uses another scheme, and http://"
                    + " only with allowInsecureHttp",
                "Use https:// in cli.url (or baseUrl), or set allowInsecureHttp: true");
        }
    }

    private static boolean isHttps(URI url) {
        return "https".equals(scheme(url));
    }

    private static boolean isHttp(URI url) {
        return "http".equals(scheme(url));
    }

    private static String scheme(URI url) {
        return url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
    }

    private ToolErrorException refused(String message, String likelyCause, String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.TLS_ERROR, message, likelyCause,
            nextStep).withNode(server.id()));
    }
}
