package de.dadecker.inubit.mcp.adapter.cli.queue;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.TlsPeer;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest.QueueEntryRead;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperTrustStore;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperVariant;
import de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryHelperClassifier.Failure;
import de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryHelperClassifier.Login;
import de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryHelperClassifier.Outcome;
import de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryHelperClassifier.PartReport;
import de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryHelperClassifier.Reported;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.QueueEntryBounds;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRef;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent.PartResult;
import de.dadecker.inubit.mcp.domain.model.RawVariable;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.QueueEntryContentPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads Queue Manager entries of one node with the queue-entry helper (feature 020, research
 * D-3…D-8, D-12, D-14), a JVM started per call on the node's client installation.
 *
 * <p>One call: the variant for the version ({@code UNSUPPORTED_VERSION} otherwise), the node's
 * credentials, the client selection and its launch check, then the private directory
 * ({@link HelperInstallation}), deleted after every outcome. From there one deadline of the node's
 * {@code cliTimeout} covers the wait for a slot of the node's StartCLI limit, the TLS handshake
 * ({@code https} only, also bounded by the node's {@code timeout}; its verified chain becomes the
 * call's trust store) and the helper run. The request, with the password, goes to the helper's
 * stdin only; the {@link CredentialGuard} permit is settled by the login frame. A failure of the
 * whole call returns no content; the parts are read from the files only if they are at most
 * {@link QueueEntryBounds#MAX_DATA_BYTES} long.
 *
 * <p>A part the helper reports as written is checked against its file in this order: a file over
 * the limit is {@code TooLarge} whatever length was reported (nothing of it is read); otherwise a
 * file that does not have the reported length fails the part with the fixed
 * {@link #LENGTH_MISMATCH} (row and file disagree, D-12). A missing file has length 0: reported as
 * 0 it is read as empty (the helper reports 0 if the vendor client wrote no file), i.e. an empty
 * data stream, and for the variables the {@link WfspParser} failure, as for any other empty file.
 *
 * <p>The {@link CredentialGuard} permit is taken after the slot and the handshake, with the
 * guard's unbounded {@link CredentialGuard#acquire()}: while the node's credentials are
 * unconfirmed it waits for another attempt's login, or for a re-read of the credentials, outside
 * the {@code cliTimeout} deadline and holding the slot. That wait ends with the other attempt
 * (itself bounded by its own deadline) or the re-read; the run then gets only what is left of the
 * deadline. A call whose deadline passed meanwhile ends in {@code TIMEOUT} without launching the
 * helper or writing the request: the permit is closed without a login result, as when a launch
 * fails.
 */
public final class CliQueueEntryAdapter implements QueueEntryContentPort {

    private static final Logger LOG = LoggerFactory.getLogger(CliQueueEntryAdapter.class);
    private static final String LABEL = "the queue-entry helper";
    private static final String DATA_FILE = "data.bin";
    private static final String VARIABLES_FILE = "variables.wfsp";
    private static final String NOT_REQUESTED = "not requested";
    private static final String UNREADABLE = "the file the queue-entry helper wrote could not be"
        + " read";
    /** The reason of a part whose file is not as long as the helper reported (D-12). */
    static final String LENGTH_MISMATCH = "the file the queue-entry helper wrote does not have"
        + " the length it reported";

    private final EffectiveNodeConfig server;
    private final Supplier<NodeCredentials> credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final HelperInstallation.Host host;
    private final ProcessRun processRun;
    private final CliConcurrencyLimiter limiter;
    private final QueueEntryHelperClassifier classifier;
    private final TlsPeer tlsPeer = new TlsPeer(LABEL);

    /**
     * @param credentials the node's current credentials, e.g. those its REST reads use
     *     ({@code NodeSecrets.current}); their failure is the call's failure
     * @param guard the node's guard of authenticated attempts
     * @param runner chooses and checks the client installation
     * @param host what the launch needs from the host
     * @param processRun runs the helper; shares the runner's {@code CliResources}
     * @param limiter the StartCLI concurrency limit the helper runs count against
     * @param scrubber masks the helper's texts
     */
    public CliQueueEntryAdapter(EffectiveNodeConfig server, Supplier<NodeCredentials> credentials,
        CredentialGuard guard, CliRunner runner, HelperInstallation.Host host,
        ProcessRun processRun, CliConcurrencyLimiter limiter, SecretScrubber scrubber) {
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.host = Objects.requireNonNull(host, "host");
        this.processRun = Objects.requireNonNull(processRun, "processRun");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.classifier = new QueueEntryHelperClassifier(scrubber, server.secretLabels());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The helper always fetches the variables; a read of the data alone reports them as not
     * requested.
     *
     * @throws IllegalArgumentException if {@code ref} is of another node or no part is requested
     * @throws ToolErrorException {@code UNSUPPORTED_VERSION}, the credential failure or
     *     {@code CLI_UNAVAILABLE} before anything is launched; {@code TIMEOUT} if the slot
     *     wait, the handshake and the run together outlast {@code cliTimeout} (nothing launched
     *     if it passed before the run) or the call is interrupted; {@code TLS_ERROR} or
     *     {@code UNREACHABLE} of the handshake (nothing launched); the classified failure of the
     *     run ({@link QueueEntryHelperClassifier})
     */
    @Override
    public RawEntryContent read(QueueEntryRef ref, InubitVersion version, Set<EntryPart> parts) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(parts, "parts");
        NodeId node = server.id();
        if (!ref.node().equals(node)) {
            throw new IllegalArgumentException("The entry is of " + ref.node() + ", not " + node);
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("No part requested");
        }
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
        long deadline = System.nanoTime() + server.cliTimeout().toNanos();
        Optional<String> javaHome = runner.javaHome(selected);
        try (HelperInstallation installation =
                 HelperInstallation.install(host, selection, javaHome);
             CliConcurrencyLimiter.Permit slot =
                 limiter.acquireForHelper(selected, remaining(deadline))) {
            Path clientHome = selected.cli().home().orElseThrow();
            LOG.debug("{}: queue-entry helper {}, client {}, JDK {}", node, variant, clientHome,
                javaHome.orElse("-"));
            Optional<HelperRequest.TrustStore> trustStore = trustStore(selected, installation,
                deadline);
            boolean data = parts.contains(EntryPart.DATA);
            Path out = installation.outDirectory();
            QueueEntryRead read = new QueueEntryRead(variant.objectType(ref.workflow()),
                ref.fileName(), data ? Optional.of(out.resolve(DATA_FILE)) : Optional.empty(),
                out.resolve(VARIABLES_FILE));
            HelperRequest request = new HelperRequest(HelperRequest.newNonce(), variant.name(),
                selected.cli().url(), trustStore,
                !selected.tls().disableHostnameVerification(),
                current.username().orElseThrow().value(),
                current.password().orElseThrow().value(), read);
            Outcome outcome = run(installation, request, clientHome, release, deadline);
            if (outcome instanceof Failure failure) {
                throw new ToolErrorException(located(failure.error()));
            }
            return content((Reported) outcome, read, parts);
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
                    + ", so the queue-entry helper cannot log in",
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
        HelperInstallation installation, long deadline) {
        String scheme = selected.cli().url().getScheme();
        if (scheme == null || !scheme.toLowerCase(Locale.ROOT).equals("https")) {
            return Optional.empty();
        }
        Duration remaining = remaining(deadline);
        Duration bound = selected.timeout().compareTo(remaining) < 0 ? selected.timeout()
            : remaining;
        List<X509Certificate> chain = tlsPeer.handshake(selected, bound);
        return Optional.of(HelperTrustStore.write(selected.id(), installation.trustStoreFile(),
            chain, host.resources().files()));
    }

    /**
     * Runs the helper under a guard permit, after the handshake: a refused address neither waits
     * for nor counts as a login.
     */
    private Outcome run(HelperInstallation installation, HelperRequest request, Path clientHome,
        MajorMinor release, long deadline) {
        try (CredentialGuard.Permit permit = guard.acquire()) {
            Duration left = remaining(deadline);
            if (left.isZero()) {
                // closed unsettled, as when a launch fails: no login was tried
                throw deadlinePassed();
            }
            CliResult result;
            try {
                result = processRun.run(installation.launchSpec(), request.encode(), left, LABEL,
                    server.id());
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

    private ToolErrorException deadlinePassed() {
        return new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
            "cliTimeout (" + Durations.human(server.cliTimeout()) + ") on " + server.id()
                + " passed before the login; the queue-entry helper was not started",
            "Another login attempt with the unconfirmed credentials, or their re-read, took the"
                + " rest of cliTimeout",
            "Retry; check get_health; raise cliTimeout if INUBIT is just slow")
            .withNode(server.id()));
    }

    private RawEntryContent content(Reported reported, QueueEntryRead read,
        Set<EntryPart> parts) {
        PartResult<byte[]> data = parts.contains(EntryPart.DATA)
            ? part(reported.parts().get(EntryPart.DATA), read.dataOut().orElseThrow(),
                bytes -> new PartResult.Read<>(bytes))
            : new PartResult.NotRequested<>(NOT_REQUESTED);
        PartResult<List<RawVariable>> variables = parts.contains(EntryPart.VARIABLES)
            ? part(reported.parts().get(EntryPart.VARIABLES), read.variablesOut(),
                WfspParser::parse)
            : new PartResult.NotRequested<>(NOT_REQUESTED);
        return new RawEntryContent(data, variables);
    }

    private static <T> PartResult<T> part(PartReport report, Path file,
        Function<byte[], PartResult<T>> parse) {
        return switch (Objects.requireNonNull(report, "report")) {
            case PartReport.NotFound notFound -> new PartResult.NotFound<>();
            case PartReport.Failed failed -> new PartResult.Failed<>(failed.reason());
            case PartReport.Written written -> read(file, written.bytes(), parse);
        };
    }

    /**
     * The file if it is at most the limit, else {@code TooLarge}; then {@link #LENGTH_MISMATCH}
     * unless it has the {@code reported} length (a missing file has length 0).
     */
    private static <T> PartResult<T> read(Path file, long reported,
        Function<byte[], PartResult<T>> parse) {
        long max = QueueEntryBounds.MAX_DATA_BYTES;
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            long size = Files.size(file);
            if (size > max) {
                return new PartResult.TooLarge<>(size);
            }
            bytes = in.readNBytes(Math.toIntExact(max + 1));
            if (bytes.length > max) {
                return new PartResult.TooLarge<>(Math.max(size, bytes.length));
            }
        } catch (NoSuchFileException e) {
            bytes = new byte[0];
        } catch (IOException e) {
            return new PartResult.Failed<>(UNREADABLE + " (" + e.getClass().getSimpleName() + ")");
        }
        if (bytes.length != reported) {
            return new PartResult.Failed<>(LENGTH_MISMATCH);
        }
        return parse.apply(bytes);
    }

    private static Duration remaining(long deadline) {
        Duration left = Duration.ofNanos(deadline - System.nanoTime());
        return left.isNegative() ? Duration.ZERO : left;
    }

    private ToolErrorException located(ToolErrorException e) {
        return e.error().node().isPresent() ? e : new ToolErrorException(located(e.error()));
    }

    private ToolError located(ToolError error) {
        return error.node().isPresent() ? error : error.withNode(server.id());
    }
}
