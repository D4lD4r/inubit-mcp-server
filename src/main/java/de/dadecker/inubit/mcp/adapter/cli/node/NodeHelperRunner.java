package de.dadecker.inubit.mcp.adapter.cli.node;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.TlsPeer;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Frame;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperTrustStore;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperVariant;
import de.dadecker.inubit.mcp.adapter.cli.node.NodeFramesClassifier.Answered;
import de.dadecker.inubit.mcp.adapter.cli.node.NodeFramesClassifier.Failure;
import de.dadecker.inubit.mcp.adapter.cli.node.NodeFramesClassifier.Login;
import de.dadecker.inubit.mcp.adapter.cli.node.NodeFramesClassifier.Outcome;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One run of the INUBIT client helper for a node tool (feature 024, research D-3, D-20, D-24),
 * a JVM started per call on the node's client installation, as {@code CliQueueEntryAdapter}
 * (feature 020) and {@code CliWebServiceLogAdapter} (feature 021) do.
 *
 * <p>One call: the variant for the version ({@code UNSUPPORTED_VERSION} otherwise), the node's
 * credentials, the client selection and its launch check, all before anything is created or
 * launched; then the private directory {@code inubit-mcp-node-…} ({@link HelperInstallation}).
 * The call deadline the caller passes covers the wait for a slot of the node's StartCLI limit,
 * the TLS handshake ({@code https} only, also bounded by the node's {@code timeout}; its verified
 * chain becomes the call's trust store), the wait for a {@link CredentialGuard} permit and the
 * run. The {@code TIMEOUT} of the slot wait, the permit wait and the run names the caller's
 * budget setting ({@code cliTimeout} or {@code cliExportTimeout}); the handshake's is
 * {@link TlsPeer}'s own. The request, with the password, goes to the helper's stdin only;
 * the argv names the helper class and nothing of the request.
 *
 * <p>The frames are judged by the caller's {@link NodeFramesClassifier}; a failure is thrown with
 * the node. The private directory is deleted on every failure, after an answer when the returned
 * {@link Run} is closed, and by {@code CliResources} on shutdown.
 */
public final class NodeHelperRunner {

    private static final Logger LOG = LoggerFactory.getLogger(NodeHelperRunner.class);
    private static final String LABEL = "the INUBIT client helper";

    private final EffectiveNodeConfig server;
    private final Supplier<NodeCredentials> credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final HelperInstallation.Host host;
    private final ProcessRun processRun;
    private final CliConcurrencyLimiter limiter;
    private final TlsPeer tlsPeer = new TlsPeer(LABEL);

    /**
     * @param credentials the node's current credentials ({@code NodeSecrets.current}); their
     *     failure is the call's failure
     * @param guard the node's guard of authenticated attempts
     * @param runner chooses and checks the client installation
     * @param host what the launch needs from the host
     * @param processRun runs the helper; shares the runner's {@code CliResources}
     * @param limiter the StartCLI concurrency limit the helper runs count against
     */
    public NodeHelperRunner(EffectiveNodeConfig server, Supplier<NodeCredentials> credentials,
        CredentialGuard guard, CliRunner runner, HelperInstallation.Host host,
        ProcessRun processRun, CliConcurrencyLimiter limiter) {
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.host = Objects.requireNonNull(host, "host");
        this.processRun = Objects.requireNonNull(processRun, "processRun");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    /**
     * The answer of the helper: the INUBIT node and the operation's frames; the files the helper
     * wrote are below {@link #outDirectory()} until it is closed.
     */
    public static final class Run implements AutoCloseable {

        private final HelperInstallation installation;
        private final HelperRequest request;
        private final Answered answer;

        private Run(HelperInstallation installation, HelperRequest request, Answered answer) {
            this.installation = installation;
            this.request = request;
            this.answer = answer;
        }

        /** The checked name of the INUBIT node that answered. */
        public String inubitNode() {
            return answer.inubitNode();
        }

        /** The operation's frames between {@code node ok} and {@code end}, in order. */
        public List<Frame> frames() {
            return answer.frames();
        }

        /** The private {@code out/} directory the operation's files were written to. */
        public Path outDirectory() {
            return installation.outDirectory();
        }

        /** The request of the run, for the excerpts of the operation's error frames. */
        HelperRequest request() {
            return request;
        }

        /** Deletes the private directory; idempotent. */
        @Override
        public void close() {
            installation.close();
        }
    }

    /**
     * Runs the helper for a node running {@code version} within the call deadline
     * {@code deadlineNanos} ({@link System#nanoTime()}). The caller closes the result, which
     * deletes the private directory.
     *
     * @param operation the operation, given the private {@code out/} directory for its files
     * @param classifier judges the frames with the tool's texts
     * @param budgetSetting the node setting the deadline comes from, named in a {@code TIMEOUT}
     * @throws ToolErrorException with the node: {@code UNSUPPORTED_VERSION}, the credential
     *     failure or {@code CLI_UNAVAILABLE} before anything is created or launched;
     *     {@code TIMEOUT} naming {@code budgetSetting} of the slot wait, the login wait or the
     *     run (nothing launched if the deadline passed before the run); {@code TLS_ERROR},
     *     {@code UNREACHABLE} or {@link TlsPeer}'s own {@code TIMEOUT} of the handshake (nothing
     *     launched);
     *     {@code CLI_UNAVAILABLE} of the launch; the classified failure of the run; the private
     *     directory is deleted then
     */
    public Run run(InubitVersion version, Function<Path, HelperRequest.Operation> operation,
        NodeFramesClassifier classifier, String budgetSetting, long deadlineNanos) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(classifier, "classifier");
        Objects.requireNonNull(budgetSetting, "budgetSetting");
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
            HelperInstallation.Use.NODE_TOOLS);
        boolean handedOver = false;
        try (CliConcurrencyLimiter.Permit slot = limiter.acquireForHelper(selected,
            remaining(deadlineNanos), budgetSetting)) {
            Path clientHome = selected.cli().home().orElseThrow();
            HelperRequest.Operation requested = operation.apply(installation.outDirectory());
            LOG.debug("{}: INUBIT client helper {}, {}, client {}, JDK {}", server.id(),
                requested.getClass().getSimpleName(), variant, clientHome, javaHome.orElse("-"));
            Optional<HelperRequest.TrustStore> trustStore = trustStore(selected, installation,
                deadlineNanos);
            HelperRequest request = new HelperRequest(HelperRequest.newNonce(), variant.name(),
                selected.cli().url(), trustStore,
                !selected.tls().disableHostnameVerification(),
                current.username().orElseThrow().value(),
                current.password().orElseThrow().value(), requested);
            Outcome outcome = run(installation, request, classifier, clientHome, release,
                budgetSetting, deadlineNanos);
            if (outcome instanceof Failure failure) {
                throw new ToolErrorException(located(failure.error()));
            }
            Run answered = new Run(installation, request, (Answered) outcome);
            handedOver = true;
            return answered;
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
     * naming {@code budgetSetting}; a deadline that passed by then launches nothing, the permit
     * is closed without a login result.
     */
    private Outcome run(HelperInstallation installation, HelperRequest request,
        NodeFramesClassifier classifier, Path clientHome, MajorMinor release,
        String budgetSetting, long deadlineNanos) {
        try (CredentialGuard.Permit permit = guard.acquire(remaining(deadlineNanos),
            budgetSetting)) {
            Duration left = remaining(deadlineNanos);
            if (left.isZero()) {
                throw deadlinePassed(budgetSetting);
            }
            CliResult result;
            try {
                result = processRun.run(installation.launchSpec(), request.encode(), left, LABEL,
                    server.id(), budgetSetting);
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

    private ToolErrorException deadlinePassed(String budgetSetting) {
        return new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
            budgetSetting + " on " + server.id() + " passed before the login; the INUBIT client"
                + " helper was not started",
            "The earlier steps of the call, another login attempt with the unconfirmed"
                + " credentials or their re-read took the rest of " + budgetSetting,
            "Retry; check get_health; raise " + budgetSetting + " if INUBIT is just slow")
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
