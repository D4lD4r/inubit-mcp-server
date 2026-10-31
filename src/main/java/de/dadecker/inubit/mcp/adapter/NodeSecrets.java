package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.config.CredentialNames;
import de.dadecker.inubit.mcp.config.CredentialRules;
import de.dadecker.inubit.mcp.config.CredentialStore;
import de.dadecker.inubit.mcp.config.E2eCredentials;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.Fetched;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.ParameterNames;
import de.dadecker.inubit.mcp.config.ParameterStore;
import de.dadecker.inubit.mcp.config.ParameterType;
import de.dadecker.inubit.mcp.config.SecretPlan;
import de.dadecker.inubit.mcp.config.SecretSourceConfig;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.config.StoreLocation;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * The credentials of one node (feature 010, data-model.md → NodeSecrets, research R-7, R-11,
 * R-14, R-17; feature 011, research R-7). Every node reads username, password and (if configured)
 * trust store password on first use, and its end-to-end test credentials in a separate read on their
 * own first use ({@link #e2e}). The store is AWS Parameter Store (all names in one
 * {@code GetParameters} call) or, since feature 011, the credential store of the running system
 * (one entry at a time, the node entry before the group entry). Each of the two reads follows the
 * same rules:
 *
 * <ul>
 *   <li>one read at a time (concurrent callers share it), each on its own virtual thread, so the
 *       reads of different nodes never wait for each other (the credential store itself serialises
 *       its entries, research R-8);
 *   <li>the store call and the shared read are both bounded by {@link #timeout()}
 *       ({@link #STORE_TIMEOUT} for Parameter Store, {@link #CREDENTIAL_STORE_TIMEOUT} for the
 *       credential store, which may wait for an access dialog); a read that does not end in time is
 *       {@code UNREACHABLE};
 *   <li>a caller waits at most {@code min(bound, timeout())}; a caller that gives up does not
 *       cancel the read, the next call gets its result;
 *   <li>a failed read, including a value refused by {@link CredentialRules}, is returned at once
 *       for {@link #BACK_OFF} without a new read;
 *   <li>passwords, trust store password and Basic tokens are registered with the
 *       {@link SecretScrubber} before anyone else sees them;
 *   <li>one {@code secret.fetch} log record per read: INFO, or WARN on failure (with the scrubbed
 *       store message) and for unencrypted {@code String} parameters; none within the back-off.
 * </ul>
 *
 * <p>Errors name the node, the store and the names read and the failure kind, never a value or the
 * raw store message. The source-specific part (what is read, texts, log keys) is a {@link Source};
 * the Parameter Store texts are those of feature 010, unchanged.
 *
 * <p>Rotation (research R-10, FR-010): after INUBIT rejected the login, the node's
 * {@link CredentialGuard} calls {@link #reread()} once; there is no time-based refresh.
 */
public final class NodeSecrets implements CredentialGuard.Rereadable {

    /** The outcome of {@link #reread()}. */
    public enum Reread {
        /** The username or the password differs; the new credentials are held. */
        CHANGED,
        /** Username and password are the same; the held credentials stay. */
        UNCHANGED,
        /** The fetch failed; the held credentials stay. */
        FAILED
    }

    /** Upper bound of a Parameter Store call and of the shared fetch (010 FR-011). */
    public static final Duration STORE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Upper bound of a credential store read of one node, all its entries and the wait for other
     * reads included; long enough to answer an access dialog (feature 011, FR-008).
     */
    public static final Duration CREDENTIAL_STORE_TIMEOUT = Duration.ofSeconds(60);

    /** How long a failed fetch is returned at once without a new fetch (FR-011). */
    public static final Duration BACK_OFF = Duration.ofSeconds(30);

    /**
     * How long a caller whose wait ended with the fetch's own bound still waits for the fetch to
     * record its outcome.
     */
    private static final Duration SETTLE_GRACE = Duration.ofSeconds(1);

    private static final Logger LOG = LoggerFactory.getLogger(NodeSecrets.class);

    // the credentials.parameters keys, also the keys of the read values of either source
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final String TRUST_STORE_PASSWORD = "trustStorePassword";
    private static final String E2E_USERNAME = "e2eUsername";
    private static final String E2E_PASSWORD = "e2ePassword";

    private final NodeId node;
    private final SecretScrubber scrubber;
    private final Clock clock;
    private final Duration timeout;
    /** Username, password and trust store password. */
    private final Cell<NodeCredentials> main;
    /** The end-to-end test credentials; {@code null} for an AWS node without such names. */
    private final Cell<Optional<E2eCredentials>> e2e;

    /** The checked values of one read per key, and the name each was read from. */
    private record Values(Map<String, String> values, Map<String, String> names) {
    }

    /** A recorded failure and when it happened. */
    private record Refusal(ToolError error, Instant at) {
    }

    /** The outcome of one fetch. */
    private sealed interface Attempt<T> permits Success, Failed {
    }

    private record Success<T>(T credentials, List<String> unencrypted) implements Attempt<T> {
    }

    /** @param detail scrubbed before logging; never part of {@code error} */
    private record Failed<T>(ToolError error, String outcome, String detail)
        implements Attempt<T> {
    }

    /** With {@code timeout} instead of {@link #STORE_TIMEOUT}, so tests need not wait 10 s. */
    NodeSecrets(SecretPlan plan, ParameterStore store, SecretScrubber scrubber, Clock clock,
        Duration timeout) {
        if (!(plan.source() instanceof SecretSourceConfig.AwsParameterStore source)) {
            throw new IllegalArgumentException("Not an AWS Parameter Store plan: " + plan.node());
        }
        ParameterNames names = plan.names().orElseThrow();
        this.node = plan.node();
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        StoreLocation location = new StoreLocation(source.region(), source.awsProfile());
        Objects.requireNonNull(store, "store");
        List<Wanted> kinds = new ArrayList<>();
        kinds.add(required(USERNAME, names.username()));
        kinds.add(required(PASSWORD, names.password()));
        names.trustStorePassword().ifPresent(name -> kinds.add(
            new Wanted(TRUST_STORE_PASSWORD, name)));
        this.main = new Cell<>("INUBIT credentials",
            new ParameterSource<>(location, store, kinds, this::credentials));
        this.e2e = names.e2eUsername().isEmpty() && names.e2ePassword().isEmpty() ? null
            : new Cell<>("end-to-end test credentials", new ParameterSource<>(location, store,
                List.of(required(E2E_USERNAME, names.e2eUsername()),
                    required(E2E_PASSWORD, names.e2ePassword())),
                values -> Optional.of(e2eCredentials(values))));
    }

    /**
     * A credential store node (feature 011, research R-7, R-11); {@code timeout} instead of
     * {@link #CREDENTIAL_STORE_TIMEOUT}, so tests need not wait 60 s.
     */
    NodeSecrets(EffectiveNodeConfig config, CredentialStore store, SecretScrubber scrubber,
        Clock clock, Duration timeout) {
        SecretPlan plan = config.secretPlan();
        CredentialNames names = plan.entries().orElseThrow(() -> new IllegalArgumentException(
            "Not a credential store plan: " + plan.node()));
        this.node = plan.node();
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(store, "store");
        List<String> kinds = new ArrayList<>(List.of(USERNAME, PASSWORD));
        if (config.tls().trustStore().isPresent()) {
            kinds.add(TRUST_STORE_PASSWORD);
        }
        boolean cli = config.cliConfigured();
        this.main = new Cell<>("INUBIT credentials", new EntrySource<>(store, names, kinds,
            List.of(USERNAME, PASSWORD), values -> {
                if (cli && values.values().containsKey(TRUST_STORE_PASSWORD)) {
                    String entry = values.names().get(TRUST_STORE_PASSWORD);
                    throw new Refused(FailureKind.INVALID_VALUE, entry, "the entry " + entry
                        + " is set, but this node has a CLI configured and needs a password-less"
                        + " trust store (StartCLI could only receive the password as a process"
                        + " argument)", "Delete the entry " + entry + " and use a trust store"
                        + " without password");
                }
                return credentials(values);
            }));
        boolean plainHttp = config.development().e2eTests() != E2ePolicy.FORBIDDEN
            && config.development().soapBaseUrl().map(url -> url.getScheme() != null
                && url.getScheme().toLowerCase(Locale.ROOT).equals("http")).orElse(false);
        this.e2e = new Cell<>("end-to-end test credentials", new EntrySource<>(store, names,
            List.of(E2E_USERNAME, E2E_PASSWORD), List.of(), values -> {
                if (values.values().isEmpty()) {
                    return Optional.empty();
                }
                for (String key : List.of(E2E_USERNAME, E2E_PASSWORD)) {
                    if (!values.values().containsKey(key)) {
                        String entry = names.either(kindOf(key));
                        throw new Refused(FailureKind.NOT_FOUND, entry, "not found for " + entry
                            + " (the end-to-end credentials need both "
                            + names.either(kindOf(E2E_USERNAME)) + " and "
                            + names.either(kindOf(E2E_PASSWORD)) + ")", "Create the entry "
                            + names.nodeName(kindOf(key)) + " (or " + names.groupName(kindOf(key))
                            + "), or delete the other end-to-end entry");
                    }
                }
                if (plainHttp) {
                    throw new Refused(FailureKind.INVALID_VALUE,
                        values.names().get(E2E_USERNAME), "e2e.soap.baseUrl uses http://, so the"
                            + " end-to-end credentials would travel unencrypted",
                        "Use an https:// e2e.soap.baseUrl or delete the end-to-end entries");
                }
                return Optional.of(e2eCredentials(values));
            }));
    }

    private Wanted required(String key, Optional<String> name) {
        return new Wanted(key, name.orElseThrow(
            () -> new IllegalArgumentException(node + ": no " + key + " parameter name")));
    }

    /** The secrets of an AWS node, fetched on first use; nothing is fetched here. */
    public static NodeSecrets aws(SecretPlan plan, ParameterStore store, SecretScrubber scrubber,
        Clock clock) {
        return new NodeSecrets(plan, store, scrubber, clock, STORE_TIMEOUT);
    }

    /**
     * The secrets of a credential store node, read on first use; nothing is read here (feature 011,
     * research R-7).
     */
    public static NodeSecrets credentialStore(EffectiveNodeConfig config, CredentialStore store,
        SecretScrubber scrubber, Clock clock) {
        return new NodeSecrets(config, store, scrubber, clock, CREDENTIAL_STORE_TIMEOUT);
    }

    /** The node. */
    public NodeId node() {
        return node;
    }

    /** How long a caller waits at most for a read of this node: the store's timeout. */
    public Duration timeout() {
        return timeout;
    }

    /** The credentials if they are held already; never starts a fetch and never waits. */
    @Override
    public Optional<NodeCredentials> held() {
        return main.held();
    }


    /**
     * Reads username, password and (if configured) trust store password again after INUBIT
     * rejected the login (research R-10, FR-010); the end-to-end test credentials are not read.
     * Waits for the read (at most {@link #timeout()}, also when interrupted, so that the outcome is
     * known). {@code CHANGED}: the new credentials are held from now on (their secrets registered
     * with the scrubber; the old ones stay registered). {@code UNCHANGED} and {@code FAILED} keep
     * the held credentials (the same instance); a failure starts no {@link #BACK_OFF}. One
     * {@code secret.fetch} log record with {@code changed}. Not single flight: the node's
     * {@link CredentialGuard} runs one re-read at a time.
     */
    @Override
    public Reread reread() {
        return main.reread(NodeSecrets::sameAccount);
    }

    private static boolean sameAccount(NodeCredentials held, NodeCredentials fetched) {
        return held.username().map(SourcedValue::value)
            .equals(fetched.username().map(SourcedValue::value))
            && held.password().map(password -> password.value().reveal())
            .equals(fetched.password().map(password -> password.value().reveal()));
    }

    /**
     * The node's credentials; starts or joins their read if they are not held yet.
     *
     * @param bound how long to wait for a read; at most {@link #timeout()} is waited
     * @throws ToolErrorException the classified read failure ({@code AUTH_FAILED} or
     *     {@code UNREACHABLE}, also at once within {@link #BACK_OFF} after it), or
     *     {@code TIMEOUT} if the read outlives {@code bound} or the caller is interrupted
     */
    public NodeCredentials current(Duration bound) {
        return main.current(bound);
    }

    /**
     * The end-to-end test credentials; starts or joins their own read (independent of
     * {@link #current}) if they are not held yet. Empty for an AWS node without
     * {@code e2eUsername}/{@code e2ePassword} parameters and for a credential store node without
     * end-to-end entries.
     *
     * @param bound as for {@link #current}
     * @throws ToolErrorException as {@link #current}, naming the end-to-end names
     */
    public Optional<E2eCredentials> e2e(Duration bound) {
        return e2e == null ? Optional.empty() : e2e.current(bound);
    }

    /** Registers the secrets with the scrubber, then builds the credentials. */
    private NodeCredentials credentials(Values read) {
        Map<String, String> values = read.values();
        String username = values.get(USERNAME);
        Secret password = scrubber.register(values.get(PASSWORD));
        Optional<SourcedValue<Secret>> trustStorePassword = Optional.ofNullable(
                values.get(TRUST_STORE_PASSWORD))
            .map(value -> new SourcedValue<>(scrubber.register(value),
                read.names().get(TRUST_STORE_PASSWORD)));
        Secret token = scrubber.registerBasicAuth(username, password);
        return new NodeCredentials(node, Optional.of(new SourcedValue<>(username,
            read.names().get(USERNAME))), Optional.of(new SourcedValue<>(password,
            read.names().get(PASSWORD))), trustStorePassword, Optional.of(token));
    }

    /** Registers the password and Basic token with the scrubber, then builds the credentials. */
    private E2eCredentials e2eCredentials(Values read) {
        String username = read.values().get(E2E_USERNAME);
        Secret password = scrubber.register(read.values().get(E2E_PASSWORD));
        scrubber.registerBasicAuth(username, password);
        return new E2eCredentials(new SourcedValue<>(username, read.names().get(E2E_USERNAME)),
            new SourcedValue<>(password, read.names().get(E2E_PASSWORD)));
    }

    /** The {@link CredentialNames} kind of a values key ({@code trustStorePassword} → …). */
    private static String kindOf(String key) {
        return switch (key) {
            case USERNAME -> CredentialNames.USERNAME;
            case PASSWORD -> CredentialNames.PASSWORD;
            case TRUST_STORE_PASSWORD -> CredentialNames.TRUSTSTORE_PASSWORD;
            case E2E_USERNAME -> CredentialNames.E2E_USERNAME;
            case E2E_PASSWORD -> CredentialNames.E2E_PASSWORD;
            default -> throw new IllegalArgumentException("Unknown key: " + key);
        };
    }

    /**
     * A rule of a build function was broken by values that were read correctly; the read fails
     * with {@code AUTH_FAILED}.
     */
    private static final class Refused extends RuntimeException {

        private final FailureKind kind;
        private final String name;
        private final String failure;
        private final String step;

        Refused(FailureKind kind, String name, String failure, String step) {
            super(failure, null, false, false);
            this.kind = kind;
            this.name = name;
            this.failure = failure;
            this.step = step;
        }
    }

    /**
     * What a {@link Cell} reads and how its messages and log records name the store.
     *
     * @param <T> the credentials it yields
     */
    private interface Source<T> {

        /** One read of all names of the cell; never throws for a store failure. */
        Attempt<T> fetch(String subject);

        /** A read that did not end in time ({@code timedOut}) or threw. */
        Failed<T> unreachable(String subject, String detail, boolean timedOut);

        /** "from AWS Parameter Store (eu-west-1)" / "from the macOS Keychain". */
        String from();

        /** The store, for "while waiting for its … from <store>". */
        String waitingFor();

        /** The verb of the pending message: "fetching" or "reading". */
        String pendingVerb();

        /** Why a read is still running. */
        String pendingCause();

        /** The source-specific keys of the {@code secret.fetch} record. */
        LoggingEventBuilder logKeys(LoggingEventBuilder record);
    }

    /**
     * One set of credentials fetched together, with its own held value, in-flight fetch and
     * back-off.
     *
     * @param <T> the credentials it yields
     */
    private final class Cell<T> {

        /** What the cell holds, for messages ("INUBIT credentials"). */
        private final String subject;
        /** What it reads. */
        private final Source<T> source;

        private final Object lock = new Object();
        private T held;
        private CompletableFuture<T> inFlight;
        private Refusal refusal;

        Cell(String subject, Source<T> source) {
            this.subject = subject;
            this.source = source;
        }

        Optional<T> held() {
            synchronized (lock) {
                return Optional.ofNullable(held);
            }
        }

        T current(Duration bound) {
            Objects.requireNonNull(bound, "bound");
            CompletableFuture<T> fetch;
            synchronized (lock) {
                if (held != null) {
                    return held;
                }
                if (refusal != null && clock.instant().isBefore(refusal.at().plus(BACK_OFF))) {
                    throw new ToolErrorException(refusal.error());
                }
                if (inFlight == null) {
                    inFlight = start();
                }
                fetch = inFlight;
            }
            Duration wait = bound.compareTo(timeout) < 0 ? bound : timeout;
            try {
                try {
                    return fetch.get(wait.toNanos(), TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    if (!wait.equals(timeout)) {
                        throw new ToolErrorException(pending());
                    }
                    // The fetch's own bound (armed before this wait) has expired as well, so its
                    // outcome is due; report it instead of "still fetching".
                    try {
                        return fetch.get(SETTLE_GRACE.toNanos(), TimeUnit.NANOSECONDS);
                    } catch (TimeoutException late) {
                        throw new ToolErrorException(pending());
                    }
                }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof ToolErrorException error) {
                    throw error;
                }
                throw new IllegalStateException("Unexpected fetch failure", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
                    "The call to " + node + " was cancelled while waiting for its " + subject
                        + " from " + source.waitingFor(),
                    "The overall deadline of the request expired",
                    "Retry").withNode(node));
            }
        }

        /** Starts a fetch; called under {@link #lock}, so {@link #settle} runs after it returns. */
        private CompletableFuture<T> start() {
            long started = System.nanoTime();
            return attempt(outcome -> settle(outcome, started));
        }

        /**
         * One fetch on its own virtual thread, bounded by {@code timeout}; {@code then} gets its
         * outcome (a fetch that does not end in time or throws is {@code UNREACHABLE}) on the
         * worker or timer thread. When the bound fires, the worker is interrupted, so that a
         * hanging store call does not keep it alive.
         */
        private <R> CompletableFuture<R> attempt(Function<Attempt<T>, R> then) {
            CompletableFuture<Attempt<T>> attempt = new CompletableFuture<>();
            Thread worker = Thread.ofVirtual().name("secret-fetch-" + node.value())
                .unstarted(() -> {
                    try {
                        attempt.complete(source.fetch(subject));
                    } catch (RuntimeException e) {
                        attempt.completeExceptionally(e);
                    }
                });
            CompletableFuture<R> done = attempt
                .orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS)
                .handle((result, thrown) -> {
                    if (thrown instanceof TimeoutException) {
                        worker.interrupt();
                    }
                    return then.apply(result != null ? result : source.unreachable(subject,
                        thrown instanceof TimeoutException
                            ? "no answer within " + timeout.toMillis() + " ms"
                            : thrown.getMessage() == null ? thrown.getClass().getName()
                            : thrown.getClass().getName() + ": " + thrown.getMessage(),
                        thrown instanceof TimeoutException));
                });
            worker.start();
            return done;
        }

        /**
         * {@link NodeSecrets#reread()}: one fetch outside the single flight and the back-off;
         * {@code join} waits uninterruptibly, bounded by the fetch's own {@code timeout}.
         */
        Reread reread(BiPredicate<T, T> same) {
            long started = System.nanoTime();
            return attempt(outcome -> reconcile(outcome, same, started)).join();
        }

        /** Holds the fetched credentials if they differ from the held ones, and logs. */
        private Reread reconcile(Attempt<T> outcome, BiPredicate<T, T> same, long started) {
            Reread result = Reread.FAILED;
            if (outcome instanceof Success<T> success) {
                synchronized (lock) {
                    if (held != null && same.test(held, success.credentials())) {
                        result = Reread.UNCHANGED;
                    } else {
                        held = success.credentials();
                        result = Reread.CHANGED;
                    }
                }
            }
            log(outcome, Duration.ofNanos(System.nanoTime() - started).toMillis(),
                Optional.of(result == Reread.CHANGED));
            return result;
        }

        /** Records and logs the outcome of a fetch (exactly once per fetch). */
        private T settle(Attempt<T> outcome, long started) {
            synchronized (lock) {
                inFlight = null;
                if (outcome instanceof Success<T> success) {
                    // feature 011 (FR-008): "no end-to-end entries" is not held, so that entries
                    // created later are found without a restart
                    if (!(success.credentials() instanceof Optional<?> optional
                        && optional.isEmpty())) {
                        held = success.credentials();
                    }
                } else {
                    refusal = new Refusal(((Failed<T>) outcome).error(), clock.instant());
                }
            }
            log(outcome, Duration.ofNanos(System.nanoTime() - started).toMillis(),
                Optional.empty());
            return switch (outcome) {
                case Success<T> success -> success.credentials();
                case Failed<T> failed -> throw new ToolErrorException(failed.error());
            };
        }

        private ToolError pending() {
            return ToolError.of(ErrorCode.TIMEOUT,
                node + ": the " + subject + " " + source.from() + " are not yet available"
                    + " (still " + source.pendingVerb() + ")",
                source.pendingCause(),
                "Retry shortly; the fetch continues and the next call uses its result")
                .withNode(node);
        }

        // --- logging (research R-14) ---

        /** @param changed present for a re-read: whether the values changed */
        private void log(Attempt<T> outcome, long durationMs, Optional<Boolean> changed) {
            Level level = outcome instanceof Success<T> success && success.unencrypted().isEmpty()
                ? Level.INFO : Level.WARN;
            LoggingEventBuilder record = source.logKeys(LOG.atLevel(level)
                .addKeyValue("event", "secret.fetch")
                .addKeyValue("node", node.value()))
                .addKeyValue("durationMs", durationMs);
            if (changed.isPresent()) {
                record = record.addKeyValue("changed", changed.get());
            }
            String verb = changed.isPresent() ? "re-read after a rejected login" : "fetched";
            switch (outcome) {
                case Success<T> success -> {
                    record = record.addKeyValue("outcome", "ok");
                    String message = node + ": " + subject + " " + verb + " " + source.from()
                        + " in " + durationMs + " ms"
                        + changed.map(c -> c ? "; changed" : "; unchanged").orElse("");
                    if (!success.unencrypted().isEmpty()) {
                        message += "; not encrypted (type String): "
                            + String.join(", ", success.unencrypted())
                            + "; store them as SecureString";
                    }
                    record.log(message);
                }
                case Failed<T> failed -> record.addKeyValue("outcome", failed.outcome())
                    .addKeyValue("detail", scrubber.scrub(failed.detail()))
                    .log(node + ": " + subject + " could not be "
                        + (changed.isPresent() ? "re-read" : "fetched") + " " + source.from()
                        + ": " + failed.outcome()
                        + (changed.isPresent() ? "; the held values are kept" : ""));
            }
        }
    }

    // --- AWS Parameter Store (feature 010) ---

    /** One parameter to fetch; {@code key} is its {@code credentials.parameters} key. */
    private record Wanted(String key, String name) {
    }

    /** A failed parameter; {@code rule} explains a refused value ({@code ""} if none). */
    private record Problem(Wanted wanted, FailureKind kind, String rule, String logDetail) {
    }

    /** The names of one cell, read in one {@code GetParameters} call (feature 010). */
    private final class ParameterSource<T> implements Source<T> {

        private final StoreLocation location;
        private final ParameterStore store;
        /** The parameters of one fetch, in kind order. */
        private final List<Wanted> wanted;
        /** Builds the credentials from the checked values. */
        private final Function<Values, T> build;

        ParameterSource(StoreLocation location, ParameterStore store, List<Wanted> wanted,
            Function<Values, T> build) {
            this.location = location;
            this.store = store;
            this.wanted = List.copyOf(wanted);
            this.build = build;
        }

        @Override
        public Attempt<T> fetch(String subject) {
            Fetched answer = store.get(location, wanted.stream().map(Wanted::name).toList(),
                timeout);
            if (answer instanceof Fetched.CallFailed(Fetched.Failure failure)
                && callLevel(failure.kind())) {
                return new Failed<>(callError(subject, failure.kind()), failure.kind().name(),
                    failure.logDetail());
            }
            List<Problem> problems = new ArrayList<>();
            Map<String, String> values = new HashMap<>();
            Map<String, String> names = new HashMap<>();
            List<String> unencrypted = new ArrayList<>();
            for (Wanted parameter : wanted) {
                names.put(parameter.key(), parameter.name());
                Fetched.Result result = switch (answer) {
                    case Fetched.CallFailed failed -> failed.failure();
                    case Fetched.PerName perName -> perName.results().getOrDefault(
                        parameter.name(),
                        new Fetched.Failure(FailureKind.NOT_FOUND, "no result for this name"));
                };
                switch (result) {
                    case Fetched.Failure failure -> problems.add(
                        new Problem(parameter, failure.kind(), "", failure.logDetail()));
                    case Fetched.Value value -> {
                        Optional<Problem> refused = check(parameter, value);
                        if (refused.isPresent()) {
                            problems.add(refused.get());
                        } else {
                            values.put(parameter.key(), value.value().reveal());
                            if (CredentialRules.checkType(value.type())
                                == CredentialRules.TypeVerdict.UNENCRYPTED) {
                                unencrypted.add(parameter.name());
                            }
                        }
                    }
                }
            }
            if (!problems.isEmpty()) {
                return failed(subject, problems);
            }
            return new Success<>(build.apply(new Values(values, names)),
                List.copyOf(unencrypted));
        }

        // --- errors (contracts/mcp-tools-delta.md, research R-11) ---

        private Failed<T> failed(String subject, List<Problem> problems) {
            String outcome = problems.stream().map(problem -> problem.kind().name()).distinct()
                .collect(Collectors.joining(","));
            String detail = problems.stream()
                .map(problem -> problem.wanted().name() + ": " + problem.logDetail())
                .collect(Collectors.joining("; "));
            Optional<FailureKind> callKind = problems.stream().map(Problem::kind)
                .filter(NodeSecrets::callLevel).findFirst();
            if (callKind.isPresent()) {
                return new Failed<>(callError(subject, callKind.get()), outcome, detail);
            }
            String failures = problems.stream().map(problem -> what(problem.kind()) + " for "
                    + problem.wanted().name()
                    + (problem.rule().isEmpty() ? "" : " (" + problem.rule() + ")"))
                .collect(Collectors.joining("; "));
            String causes = problems.stream().map(problem -> cause(problem.kind())).distinct()
                .collect(Collectors.joining("; "));
            String steps = problems.stream().map(this::nextStep).distinct()
                .collect(Collectors.joining("; "));
            return new Failed<>(ToolError.of(ErrorCode.AUTH_FAILED,
                unavailable(subject) + failures, causes, steps).withNode(node), outcome, detail);
        }

        @Override
        public Failed<T> unreachable(String subject, String detail, boolean timedOut) {
            return new Failed<>(callError(subject, FailureKind.UNREACHABLE),
                FailureKind.UNREACHABLE.name(), detail);
        }

        private ToolError callError(String subject, FailureKind kind) {
            String profile = location.awsProfile().map(p -> "the AWS profile " + p)
                .orElse("the default AWS profile");
            ToolError error = switch (kind) {
                case UNKNOWN_PROFILE -> ToolError.of(ErrorCode.AUTH_FAILED,
                    unavailable(subject) + profile + " is unknown",
                    "The AWS profile is not defined in the local AWS configuration files",
                    "Configure " + profile + " in the AWS configuration files and log in to it");
                case NO_CREDENTIALS -> ToolError.of(ErrorCode.AUTH_FAILED,
                    unavailable(subject) + "no AWS credentials",
                    "No AWS credentials could be resolved (not logged in, or the login expired)",
                    location.awsProfile()
                        .map(p -> "Log in to the AWS profile " + p + " (for example aws sso login"
                            + " --profile " + p + ") or configure its credentials")
                        .orElse("Configure AWS credentials for the default credential chain or"
                            + " log in (for example aws sso login)"));
                default -> ToolError.of(ErrorCode.UNREACHABLE,
                    unavailable(subject) + "Parameter Store unreachable",
                    "The Parameter Store could not be reached, throttled the request or did not"
                        + " answer within " + timeout.toSeconds() + " s",
                    "Check network access to ssm." + location.region() + ".amazonaws.com; the"
                        + " fetch is retried after " + BACK_OFF.toSeconds() + " s");
            };
            return error.withNode(node);
        }

        private String unavailable(String subject) {
            return node + ": " + subject + " " + from() + " are unavailable: ";
        }

        private String where() {
            return location.region()
                + location.awsProfile().map(p -> ", profile " + p).orElse("");
        }

        private String nextStep(Problem problem) {
            String name = problem.wanted().name();
            return switch (problem.kind()) {
                case NOT_FOUND -> "Create the parameter " + name + " in Parameter Store ("
                    + where() + ") or correct credentials.parameters." + problem.wanted().key();
                case ACCESS_DENIED -> "Grant ssm:GetParameters on " + name + " and kms:Decrypt on"
                    + " its KMS key to the AWS identity in use";
                default -> "Correct the value of " + name + " in Parameter Store (as SecureString;"
                    + " the value is never shown); no restart is needed";
            };
        }

        @Override
        public String from() {
            return "from AWS Parameter Store (" + where() + ")";
        }

        @Override
        public String waitingFor() {
            return "AWS Parameter Store";
        }

        @Override
        public String pendingVerb() {
            return "fetching";
        }

        @Override
        public String pendingCause() {
            return "The Parameter Store answers slowly";
        }

        @Override
        public LoggingEventBuilder logKeys(LoggingEventBuilder record) {
            record = record.addKeyValue("source", "awsParameterStore")
                .addKeyValue("region", location.region());
            if (location.awsProfile().isPresent()) {
                record = record.addKeyValue("profile", location.awsProfile().get());
            }
            return record.addKeyValue("parameters", wanted.stream().map(Wanted::name)
                .collect(Collectors.joining(",")));
        }
    }

    /** The {@link CredentialRules} checks of one value; the value itself is never described. */
    private static Optional<Problem> check(Wanted parameter, Fetched.Value value) {
        return CredentialRules.checkValue(CredentialRules.roleOf(parameter.key()), value.type(),
            value.value().reveal())
            .map(refusal -> new Problem(parameter, refusal.kind(), refusal.rule(),
                refusal.rule()));
    }

    /** Kinds that concern the store access as a whole, not one parameter. */
    private static boolean callLevel(FailureKind kind) {
        return kind == FailureKind.UNREACHABLE || kind == FailureKind.UNKNOWN_PROFILE
            || kind == FailureKind.NO_CREDENTIALS || kind == FailureKind.STORE_NOT_AVAILABLE
            || kind == FailureKind.TIMEOUT;
    }

    private static String what(FailureKind kind) {
        return switch (kind) {
            case NOT_FOUND -> "not found";
            case ACCESS_DENIED -> "access denied";
            default -> "invalid value";
        };
    }

    private static String cause(FailureKind kind) {
        return switch (kind) {
            case NOT_FOUND -> "The parameter does not exist in this region and account, or it is"
                + " empty";
            case ACCESS_DENIED -> "The AWS identity in use may not read the parameter or decrypt"
                + " it with its KMS key";
            default -> "The stored value breaks a credential rule";
        };
    }

    // --- the credential store of the running system (feature 011) ---

    /**
     * A kind whose read failed; {@code entry} names what was read (or both names);
     * {@code store} if the store itself failed (no further entry is read, so that a denied access
     * dialog is not followed by the next one).
     */
    private record EntryProblem(String key, String entry, FailureKind kind, String rule,
        String logDetail, boolean store) {
    }

    /**
     * The entries of one cell, read one after the other, each kind the node entry first and the
     * group entry when the node entry is missing or empty (feature 011, research R-7). The whole
     * read is bounded by {@code timeout}, the waits behind other reads included.
     */
    private final class EntrySource<T> implements Source<T> {

        private final CredentialStore store;
        private final CredentialNames names;
        /** The keys to read, in order. */
        private final List<String> keys;
        /** The keys that must be found. */
        private final List<String> required;
        /** Builds the credentials from the checked values; may throw {@link Refused}. */
        private final Function<Values, T> build;

        EntrySource(CredentialStore store, CredentialNames names, List<String> keys,
            List<String> required, Function<Values, T> build) {
            this.store = store;
            this.names = names;
            this.keys = List.copyOf(keys);
            this.required = List.copyOf(required);
            this.build = build;
        }

        @Override
        public Attempt<T> fetch(String subject) {
            long deadline = System.nanoTime() + timeout.toNanos();
            Map<String, String> values = new LinkedHashMap<>();
            Map<String, String> read = new LinkedHashMap<>();
            List<EntryProblem> problems = new ArrayList<>();
            for (String key : keys) {
                String kind = kindOf(key);
                Optional<EntryProblem> problem = readKind(key, kind, deadline, values, read);
                if (problem.isPresent()) {
                    problems.add(problem.get());
                    if (problem.get().store()) {
                        break;
                    }
                }
            }
            if (!problems.isEmpty()) {
                return failed(subject, problems, read);
            }
            try {
                return new Success<>(build.apply(new Values(values, read)), List.of());
            } catch (Refused refused) {
                return failed(subject, List.of(new EntryProblem("", refused.name, refused.kind,
                    "", refused.failure, false)), read, refused);
            }
        }

        /**
         * Reads one kind: the node entry, then the group entry if the node entry is missing or
         * empty. A failure or an invalid value of the node entry does not fall back.
         */
        private Optional<EntryProblem> readKind(String key, String kind, long deadline,
            Map<String, String> values, Map<String, String> read) {
            for (String account : names.candidates(kind)) {
                Duration left = Duration.ofNanos(deadline - System.nanoTime());
                if (left.isNegative() || left.isZero()) {
                    return Optional.of(new EntryProblem(key, account, FailureKind.TIMEOUT, "",
                        "no time left within " + timeout.toSeconds() + " s", true));
                }
                read.put(key, account);
                CredentialStore.Lookup lookup = store.read(account, left);
                switch (lookup) {
                    case CredentialStore.Lookup.NotFound notFound -> {
                        continue;
                    }
                    case CredentialStore.Lookup.Failed failed -> {
                        // an undecodable value is a value problem, not a store failure
                        boolean value = failed.kind() == FailureKind.INVALID_VALUE;
                        return Optional.of(new EntryProblem(key, account, failed.kind(),
                            value ? failed.detail() : "", failed.detail(), !value));
                    }
                    case CredentialStore.Lookup.Found found -> {
                        String value = found.value().reveal();
                        if (value.isEmpty()) {
                            continue;
                        }
                        Optional<CredentialRules.Refusal> refusal = CredentialRules.checkValue(
                            CredentialRules.roleOf(key), ParameterType.SECURE_STRING, value);
                        if (refusal.isPresent()) {
                            return Optional.of(new EntryProblem(key, account,
                                refusal.get().kind(), refusal.get().rule(),
                                refusal.get().rule(), false));
                        }
                        values.put(key, value);
                        return Optional.empty();
                    }
                }
            }
            read.remove(key);
            if (required.contains(key)) {
                return Optional.of(new EntryProblem(key, names.either(kind),
                    FailureKind.NOT_FOUND, "", "no entry " + names.either(kind), false));
            }
            return Optional.empty();
        }

        private Failed<T> failed(String subject, List<EntryProblem> problems,
            Map<String, String> read) {
            return failed(subject, problems, read, null);
        }

        private Failed<T> failed(String subject, List<EntryProblem> problems,
            Map<String, String> read, Refused refused) {
            String outcome = problems.stream().map(problem -> problem.kind().name()).distinct()
                .collect(Collectors.joining(","));
            String detail = problems.stream()
                .map(problem -> problem.entry() + ": " + problem.logDetail())
                .collect(Collectors.joining("; "));
            Optional<FailureKind> callKind = problems.stream().map(EntryProblem::kind)
                .filter(NodeSecrets::callLevel).findFirst();
            if (callKind.isPresent()) {
                String reason = problems.stream().filter(problem -> problem.kind()
                    == callKind.get()).map(EntryProblem::logDetail).findFirst().orElse("");
                return new Failed<>(callError(subject, callKind.get(), reason), outcome, detail);
            }
            if (refused != null) {
                return new Failed<>(ToolError.of(ErrorCode.AUTH_FAILED,
                    unavailable(subject) + refused.failure, "A credential rule is broken",
                    refused.step).withNode(node), outcome, detail);
            }
            String failures = problems.stream().map(problem -> what(problem.kind()) + " for "
                    + problem.entry()
                    + (problem.rule().isEmpty() ? "" : " (" + problem.rule() + ")"))
                .collect(Collectors.joining("; "));
            String causes = problems.stream().map(problem -> entryCause(problem.kind()))
                .distinct().collect(Collectors.joining("; "));
            String steps = problems.stream().map(this::nextStep).distinct()
                .collect(Collectors.joining("; "));
            return new Failed<>(ToolError.of(ErrorCode.AUTH_FAILED,
                unavailable(subject) + failures, causes, steps).withNode(node), outcome, detail);
        }

        /** @param reason the store's own reason; scrubbed, it never contains a value */
        private ToolError callError(String subject, FailureKind kind, String reason) {
            ToolError error = kind == FailureKind.STORE_NOT_AVAILABLE
                ? ToolError.of(ErrorCode.AUTH_FAILED,
                    unavailable(subject) + "no usable credential store ("
                        + scrubber.scrub(reason) + ")",
                    "The " + storeName() + " is not available on this machine (not installed, no"
                        + " desktop session, or locked without a way to ask)",
                    "Install or start the credential store of this system, or read the"
                        + " credentials from AWS Parameter Store (credentials.source type"
                        + " awsParameterStore)")
                : ToolError.of(ErrorCode.UNREACHABLE, unavailable(subject) + "timeout",
                    "The " + storeName() + " did not answer within " + timeout.toSeconds()
                        + " s, e.g. an access dialog was not answered",
                    "Answer the access dialog of the " + storeName() + "; the read is retried"
                        + " after " + BACK_OFF.toSeconds() + " s");
            return error.withNode(node);
        }

        @Override
        public Failed<T> unreachable(String subject, String detail, boolean timedOut) {
            if (timedOut) {
                return new Failed<>(callError(subject, FailureKind.TIMEOUT, detail),
                    FailureKind.TIMEOUT.name(), detail);
            }
            return new Failed<>(ToolError.of(ErrorCode.AUTH_FAILED,
                unavailable(subject) + "the read failed",
                "Reading the " + storeName() + " failed unexpectedly",
                "Retry; if it fails again, the log of the MCP server names the cause")
                .withNode(node), "FAILED", detail);
        }

        private String unavailable(String subject) {
            return node + ": " + subject + " " + from() + " are unavailable: ";
        }

        private String storeName() {
            return store.kind().displayName();
        }

        private String entryCause(FailureKind kind) {
            return switch (kind) {
                case NOT_FOUND -> "The " + storeName() + " has no such entry (service "
                    + CredentialStore.SERVICE + "), or it is empty";
                case ACCESS_DENIED -> "The " + storeName() + " refused the access (the access"
                    + " dialog was denied or the keyring stayed locked)";
                default -> "The stored value breaks a credential rule";
            };
        }

        private String nextStep(EntryProblem problem) {
            return switch (problem.kind()) {
                case NOT_FOUND -> {
                    String kind = kindOf(problem.key());
                    yield "Create the entry " + names.nodeName(kind) + " (or "
                        + names.groupName(kind) + ") with service " + CredentialStore.SERVICE
                        + " in the " + storeName() + command(names.groupName(kind));
                }
                case ACCESS_DENIED -> "Allow the access when the " + storeName() + " asks; the"
                    + " read is retried after " + BACK_OFF.toSeconds() + " s";
                default -> "Correct the entry " + problem.entry() + " in the " + storeName()
                    + " (the value is never shown); no restart is needed";
            };
        }

        /** The system command that creates {@code account}, as a suffix of a next step. */
        private String command(String account) {
            return switch (store.kind()) {
                case KEYCHAIN -> ", e.g. security add-generic-password -U -s "
                    + CredentialStore.SERVICE + " -a " + account + " -w";
                case CREDENTIAL_MANAGER -> ", e.g. cmdkey /generic:" + CredentialStore.SERVICE
                    + ":" + account + " /user:" + account + " /pass";
                case SECRET_SERVICE -> ", e.g. secret-tool store --label='"
                    + CredentialStore.SERVICE + " " + account + "' service "
                    + CredentialStore.SERVICE + " account " + account;
                case NONE -> "";
            };
        }

        @Override
        public String from() {
            return "from the " + storeName();
        }

        @Override
        public String waitingFor() {
            return "the " + storeName();
        }

        @Override
        public String pendingVerb() {
            return "reading";
        }

        @Override
        public String pendingCause() {
            return "The " + storeName() + " answers slowly or waits for an access dialog";
        }

        @Override
        public LoggingEventBuilder logKeys(LoggingEventBuilder record) {
            List<String> entries = new ArrayList<>();
            for (String key : keys) {
                entries.addAll(names.candidates(kindOf(key)));
            }
            return record.addKeyValue("source", "credentialStore")
                .addKeyValue("store", store.kind().name())
                .addKeyValue("entries", String.join(",", entries));
        }
    }

    @Override
    public String toString() {
        return "NodeSecrets[" + node + "]";
    }
}
