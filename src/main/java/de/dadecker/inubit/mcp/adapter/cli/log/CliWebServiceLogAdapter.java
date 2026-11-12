package de.dadecker.inubit.mcp.adapter.cli.log;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.TlsPeer;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest.ServerLogRead;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperTrustStore;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperVariant;
import de.dadecker.inubit.mcp.adapter.cli.log.WebServiceLogClassifier.Downloaded;
import de.dadecker.inubit.mcp.adapter.cli.log.WebServiceLogClassifier.Failure;
import de.dadecker.inubit.mcp.adapter.cli.log.WebServiceLogClassifier.Login;
import de.dadecker.inubit.mcp.adapter.cli.log.WebServiceLogClassifier.Outcome;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.DownloadedLog;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads {@code webservice.log} of one 8.1/9.0 node with the INUBIT client helper (feature
 * 021, research D-2, D-5, D-6, D-16), a JVM started per call on the node's client installation,
 * as {@code CliQueueEntryAdapter} does for feature 020.
 *
 * <p>One call: the variant for the version ({@code UNSUPPORTED_VERSION} otherwise), the node's
 * credentials, the client selection and its launch check, all before anything is created or
 * launched; then the private directory {@code inubit-mcp-log-…} ({@link HelperInstallation}). The
 * call deadline the caller passes covers the TLS handshake ({@code https} only, also bounded by
 * the node's {@code timeout}; its verified chain becomes the call's trust store) and the helper
 * run; a {@code TIMEOUT} of the run names {@code cliExportTimeout}. The request, with the
 * password, goes to the helper's stdin only; the {@link CredentialGuard} permit is settled from
 * the classifier's login state, as in {@code CliQueueEntryAdapter}.
 *
 * <p>A success needs a regular file at {@code <dir>/out/webservice.log} of the length the helper
 * reported, else {@code UNEXPECTED_RESPONSE}. The private directory is deleted on every failure,
 * after a success when the returned {@link DownloadedLog} is closed.
 */
public final class CliWebServiceLogAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(CliWebServiceLogAdapter.class);
    private static final String LABEL = "the INUBIT client helper";
    private static final String BUDGET = "cliExportTimeout";
    /** The helper's output file below the private directory's {@code out/} (research D-7). */
    private static final String FILE_NAME = "webservice.log";

    private final EffectiveNodeConfig server;
    private final Supplier<NodeCredentials> credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final HelperInstallation.Host host;
    private final ProcessRun processRun;
    private final WebServiceLogClassifier classifier;
    private final TlsPeer tlsPeer = new TlsPeer(LABEL);

    /**
     * @param credentials the node's current credentials ({@code NodeSecrets.current}); their
     *     failure is the call's failure
     * @param guard the node's guard of authenticated attempts
     * @param runner chooses and checks the client installation
     * @param host what the launch needs from the host
     * @param processRun runs the helper; shares the runner's {@code CliResources}
     * @param scrubber masks the helper's texts
     */
    public CliWebServiceLogAdapter(EffectiveNodeConfig server,
        Supplier<NodeCredentials> credentials, CredentialGuard guard, CliRunner runner,
        HelperInstallation.Host host, ProcessRun processRun, SecretScrubber scrubber) {
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.host = Objects.requireNonNull(host, "host");
        this.processRun = Objects.requireNonNull(processRun, "processRun");
        this.classifier = new WebServiceLogClassifier(scrubber, server.secretLabels());
    }

    /**
     * Downloads {@code webservice.log} of a node running {@code version} within the call
     * deadline {@code deadlineNanos} ({@link System#nanoTime()}). The caller closes the result,
     * which deletes the private directory.
     *
     * @throws ToolErrorException with the node: {@code UNSUPPORTED_VERSION}, the credential
     *     failure or {@code CLI_UNAVAILABLE} before anything is created or launched;
     *     {@code TLS_ERROR}, {@code UNREACHABLE} or {@code TIMEOUT} of the handshake (nothing
     *     launched); {@code TIMEOUT} naming {@code cliExportTimeout} of the run; the classified
     *     failure of the run ({@link WebServiceLogClassifier}); the private directory is deleted
     *     then
     */
    public DownloadedLog download(InubitVersion version, long deadlineNanos) {
        Objects.requireNonNull(version, "version");
        MajorMinor release = MajorMinor.of(version);
        HelperVariant variant;
        try {
            variant = HelperVariant.forVersion(release);
        } catch (ToolErrorException e) {
            throw located(e);
        }
        NodeCredentials current = credentials();
        CliRunner.Selection selection = runner.select(server, Optional.of(version));
        runner.checkLaunch(selection);
        EffectiveNodeConfig selected = selection.server();
        Optional<String> javaHome = runner.javaHome(selected);
        HelperInstallation installation = HelperInstallation.install(host, selection, javaHome,
            HelperInstallation.Use.SERVER_LOG);
        boolean handedOver = false;
        try {
            Path clientHome = selected.cli().home().orElseThrow();
            LOG.debug("{}: web service log download, INUBIT client helper {}, client {}, JDK {}",
                server.id(), variant, clientHome, javaHome.orElse("-"));
            Optional<HelperRequest.TrustStore> trustStore = trustStore(selected, installation,
                deadlineNanos);
            Path logOut = installation.outDirectory().resolve(FILE_NAME);
            HelperRequest request = new HelperRequest(HelperRequest.newNonce(), variant.name(),
                selected.cli().url(), trustStore,
                !selected.tls().disableHostnameVerification(),
                current.username().orElseThrow().value(),
                current.password().orElseThrow().value(),
                new ServerLogRead(ServerLogKind.WEBSERVICE, logOut));
            Outcome outcome = run(installation, request, clientHome, release, deadlineNanos);
            if (outcome instanceof Failure failure) {
                throw new ToolErrorException(located(failure.error()));
            }
            Downloaded downloaded = (Downloaded) outcome;
            requireFile(logOut, downloaded.size());
            DownloadedLog log = new DownloadedLog(logOut, downloaded.size(),
                Optional.of(downloaded.inubitNode()), downloaded.presence(), installation::close);
            handedOver = true;
            return log;
        } finally {
            if (!handedOver) {
                installation.close();
            }
        }
    }

    /** The node's credentials; nothing is launched without both username and password. */
    private NodeCredentials credentials() {
        NodeCredentials current;
        try {
            current = credentials.get();
        } catch (ToolErrorException e) {
            throw located(e);
        }
        if (!current.complete()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                "No INUBIT credentials are configured for " + server.id()
                    + ", so the INUBIT client helper cannot log in",
                "The username or password variable for " + server.id() + " is not set",
                guard.fixCredentials()).withNode(server.id()));
        }
        return current;
    }

    /**
     * For {@code https}: the verified chain of a handshake within the deadline (and the node's
     * {@code timeout}) as the call's trust store; nothing for {@code http}.
     */
    private Optional<HelperRequest.TrustStore> trustStore(EffectiveNodeConfig selected,
        HelperInstallation installation, long deadlineNanos) {
        String scheme = selected.cli().url().getScheme();
        if (scheme == null || !scheme.toLowerCase(Locale.ROOT).equals("https")) {
            return Optional.empty();
        }
        Duration remaining = remaining(deadlineNanos);
        Duration bound = selected.timeout().compareTo(remaining) < 0 ? selected.timeout()
            : remaining;
        List<X509Certificate> chain = tlsPeer.handshake(selected, bound);
        return Optional.of(HelperTrustStore.write(selected.id(), installation.trustStoreFile(),
            chain, host.resources().files()));
    }

    /**
     * Runs the helper under a guard permit, after the handshake: a refused address neither waits
     * for nor counts as a login. The wait for the permit ends at the deadline in {@code TIMEOUT}
     * naming {@code cliExportTimeout}; a deadline that passed by then launches nothing, the
     * permit is closed without a login result.
     */
    private Outcome run(HelperInstallation installation, HelperRequest request, Path clientHome,
        MajorMinor release, long deadlineNanos) {
        try (CredentialGuard.Permit permit = guard.acquire(remaining(deadlineNanos), BUDGET)) {
            Duration left = remaining(deadlineNanos);
            if (left.isZero()) {
                throw deadlinePassed();
            }
            CliResult result;
            try {
                result = processRun.run(installation.launchSpec(), request.encode(), left, LABEL,
                    server.id(), BUDGET);
            } catch (ToolErrorException e) {
                throw located(e);
            }
            Outcome outcome = classifier.classify(request, result, clientHome, release);
            if (outcome.login() == Login.ACCEPTED) {
                permit.accepted();
            } else if (outcome.login() == Login.REJECTED) {
                permit.rejected();
            }
            return outcome;
        }
    }

    /** The helper's file must be a regular file of the reported length. */
    private void requireFile(Path file, long reported) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            if (attributes.isRegularFile() && attributes.size() == reported) {
                return;
            }
        } catch (IOException e) {
            // reported below
        }
        throw new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
            "download_server_log got an unexpected answer from " + server.id()
                + "; the unsupported INUBIT interface may have changed after an INUBIT update",
            "The INUBIT client helper reported " + reported + " bytes, but did not leave a"
                + " regular file of that length",
            "Download the log in the Workbench; report the INUBIT version")
            .withNode(server.id()));
    }

    private ToolErrorException deadlinePassed() {
        return new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
            BUDGET + " (" + Durations.human(server.cliExportTimeout()) + ") on " + server.id()
                + " passed before the login; the INUBIT client helper was not started",
            "Another login attempt with the unconfirmed credentials, or their re-read, took the"
                + " rest of " + BUDGET,
            "Retry; check get_health; raise " + BUDGET + " if INUBIT is just slow")
            .withNode(server.id()));
    }

    private static Duration remaining(long deadlineNanos) {
        long left = deadlineNanos - System.nanoTime();
        return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
    }

    private ToolErrorException located(ToolErrorException e) {
        return e.error().node().isPresent() ? e : new ToolErrorException(located(e.error()));
    }

    private ToolError located(ToolError error) {
        return error.node().isPresent() ? error : error.withNode(server.id());
    }
}
