package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.config.CredentialResolver.E2eCredentials;
import de.dadecker.inubit.mcp.config.CredentialRules;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.Fetched;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.ParameterNames;
import de.dadecker.inubit.mcp.config.ParameterStore;
import de.dadecker.inubit.mcp.config.SecretPlan;
import de.dadecker.inubit.mcp.config.SecretSourceConfig;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.config.StoreLocation;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * The credentials of one node (feature 010, data-model.md → NodeSecrets, research R-7, R-11,
 * R-14, R-17). An environment node holds its startup credentials. An AWS node fetches username,
 * password and (if named) trust store password from the {@link ParameterStore} on first use, and
 * its end-to-end test credentials (if named) in a separate fetch on their own first use
 * ({@link #e2e}). Each of the two fetches follows the same rules:
 *
 * <ul>
 *   <li>one fetch at a time (concurrent callers share it), each on its own virtual thread, so the
 *       fetches of different nodes never wait for each other;
 *   <li>the store call and the shared fetch are both bounded by {@link #STORE_TIMEOUT}; a fetch
 *       that does not end in time is {@code UNREACHABLE};
 *   <li>a caller waits at most {@code min(bound, STORE_TIMEOUT)}; a caller that gives up does not
 *       cancel the fetch, the next call gets its result;
 *   <li>a failed fetch, including a value refused by {@link CredentialRules}, is returned at once
 *       for {@link #BACK_OFF} without a new fetch;
 *   <li>passwords, trust store password and Basic tokens are registered with the
 *       {@link SecretScrubber} before anyone else sees them;
 *   <li>one {@code secret.fetch} log record per fetch: INFO, or WARN on failure (with the scrubbed
 *       store message) and for unencrypted {@code String} parameters; none within the back-off.
 * </ul>
 *
 * <p>Errors name the node, region, AWS profile, parameter and failure kind, never a value or the
 * raw store message.
 */
public final class NodeSecrets {

    /** Upper bound of a store call and of the shared fetch (FR-011). */
    public static final Duration STORE_TIMEOUT = Duration.ofSeconds(10);

    /** How long a failed fetch is returned at once without a new fetch (FR-011). */
    public static final Duration BACK_OFF = Duration.ofSeconds(30);

    /**
     * How long a caller whose wait ended with the fetch's own bound still waits for the fetch to
     * record its outcome.
     */
    private static final Duration SETTLE_GRACE = Duration.ofSeconds(1);

    private static final Logger LOG = LoggerFactory.getLogger(NodeSecrets.class);

    // the credentials.parameters keys
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final String TRUST_STORE_PASSWORD = "trustStorePassword";
    private static final String E2E_USERNAME = "e2eUsername";
    private static final String E2E_PASSWORD = "e2ePassword";

    private final NodeId node;
    private final StoreLocation location;
    private final ParameterStore store;
    private final SecretScrubber scrubber;
    private final Clock clock;
    private final Duration timeout;
    /** Username, password and trust store password. */
    private final Cell<NodeCredentials> main;
    /** The end-to-end test credentials; {@code null} if the node has no such parameters. */
    private final Cell<E2eCredentials> e2e;

    /** One parameter to fetch; {@code key} is its {@code credentials.parameters} key. */
    private record Wanted(String key, String name) {
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

    /** A failed parameter; {@code rule} explains a refused value ({@code ""} if none). */
    private record Problem(Wanted wanted, FailureKind kind, String rule, String logDetail) {
    }

    private NodeSecrets(NodeCredentials credentials) {
        this.node = credentials.node();
        this.location = null;
        this.store = null;
        this.scrubber = null;
        this.clock = null;
        this.timeout = null;
        this.main = new Cell<>("INUBIT credentials", List.of(), values -> credentials);
        this.main.held = credentials;
        this.e2e = null;
    }

    /** With {@code timeout} instead of {@link #STORE_TIMEOUT}, so tests need not wait 10 s. */
    NodeSecrets(SecretPlan plan, ParameterStore store, SecretScrubber scrubber, Clock clock,
        Duration timeout) {
        if (!(plan.source() instanceof SecretSourceConfig.AwsParameterStore source)) {
            throw new IllegalArgumentException("Not an AWS Parameter Store plan: " + plan.node());
        }
        ParameterNames names = plan.names().orElseThrow();
        this.node = plan.node();
        this.location = new StoreLocation(source.region(), source.awsProfile());
        this.store = Objects.requireNonNull(store, "store");
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        List<Wanted> kinds = new ArrayList<>();
        kinds.add(required(USERNAME, names.username()));
        kinds.add(required(PASSWORD, names.password()));
        names.trustStorePassword().ifPresent(name -> kinds.add(
            new Wanted(TRUST_STORE_PASSWORD, name)));
        this.main = new Cell<>("INUBIT credentials", kinds, this::credentials);
        this.e2e = names.e2eUsername().isEmpty() && names.e2ePassword().isEmpty() ? null
            : new Cell<>("end-to-end test credentials", List.of(
                required(E2E_USERNAME, names.e2eUsername()),
                required(E2E_PASSWORD, names.e2ePassword())), this::e2eCredentials);
    }

    private Wanted required(String key, Optional<String> name) {
        return new Wanted(key, name.orElseThrow(
            () -> new IllegalArgumentException(node + ": no " + key + " parameter name")));
    }

    /** The startup credentials of an environment node. */
    public static NodeSecrets environment(NodeCredentials credentials) {
        if (credentials.deferred()) {
            throw new IllegalArgumentException("Deferred credentials belong to an AWS node: "
                + credentials.node());
        }
        return new NodeSecrets(credentials);
    }

    /** The secrets of an AWS node, fetched on first use; nothing is fetched here. */
    public static NodeSecrets aws(SecretPlan plan, ParameterStore store, SecretScrubber scrubber,
        Clock clock) {
        return new NodeSecrets(plan, store, scrubber, clock, STORE_TIMEOUT);
    }

    /** The node. */
    public NodeId node() {
        return node;
    }

    /** The credentials if they are held already; never starts a fetch and never waits. */
    public Optional<NodeCredentials> held() {
        return main.held();
    }

    /**
     * The node's credentials; starts or joins the fetch of an AWS node if they are not held yet.
     *
     * @param bound how long to wait for a fetch; at most {@link #STORE_TIMEOUT} is waited
     * @throws ToolErrorException the classified fetch failure ({@code AUTH_FAILED} or
     *     {@code UNREACHABLE}, also at once within {@link #BACK_OFF} after it), or
     *     {@code TIMEOUT} if the fetch outlives {@code bound} or the caller is interrupted
     */
    public NodeCredentials current(Duration bound) {
        return main.current(bound);
    }

    /**
     * The end-to-end test credentials of an AWS node with {@code e2eUsername}/{@code e2ePassword}
     * parameters; starts or joins their own fetch (independent of {@link #current}) if they are
     * not held yet. Empty for an AWS node without these parameters and for an environment node
     * (its end-to-end credentials come from
     * {@link de.dadecker.inubit.mcp.config.CredentialResolver#e2e}).
     *
     * @param bound as for {@link #current}
     * @throws ToolErrorException as {@link #current}, naming the end-to-end parameters
     */
    public Optional<E2eCredentials> e2e(Duration bound) {
        return e2e == null ? Optional.empty() : Optional.of(e2e.current(bound));
    }

    /** Registers the secrets with the scrubber, then builds the credentials. */
    private NodeCredentials credentials(Map<String, String> values) {
        String username = values.get(USERNAME);
        Secret password = scrubber.register(values.get(PASSWORD));
        Optional<SourcedValue<Secret>> trustStorePassword = Optional.ofNullable(
                values.get(TRUST_STORE_PASSWORD))
            .map(value -> new SourcedValue<>(scrubber.register(value),
                main.nameOf(TRUST_STORE_PASSWORD)));
        Secret token = scrubber.registerBasicAuth(username, password);
        return new NodeCredentials(node, Optional.of(new SourcedValue<>(username,
            main.nameOf(USERNAME))), Optional.of(new SourcedValue<>(password,
            main.nameOf(PASSWORD))), trustStorePassword, Optional.of(token));
    }

    /** Registers the password and Basic token with the scrubber, then builds the credentials. */
    private E2eCredentials e2eCredentials(Map<String, String> values) {
        String username = values.get(E2E_USERNAME);
        Secret password = scrubber.register(values.get(E2E_PASSWORD));
        scrubber.registerBasicAuth(username, password);
        return new E2eCredentials(new SourcedValue<>(username, e2e.nameOf(E2E_USERNAME)),
            new SourcedValue<>(password, e2e.nameOf(E2E_PASSWORD)));
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
        /** The parameters of one fetch, in kind order; empty for an environment node. */
        private final List<Wanted> wanted;
        /** Builds the credentials from the checked values per key. */
        private final Function<Map<String, String>, T> build;

        private final Object lock = new Object();
        private T held;
        private CompletableFuture<T> inFlight;
        private Refusal refusal;

        Cell(String subject, List<Wanted> wanted, Function<Map<String, String>, T> build) {
            this.subject = subject;
            this.wanted = List.copyOf(wanted);
            this.build = build;
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
                        + " from AWS Parameter Store",
                    "The overall deadline of the request expired",
                    "Retry").withNode(node));
            }
        }

        /**
         * Starts a fetch; called under {@link #lock}, so {@link #settle} runs after it returns.
         * When the fetch's bound fires, its thread is interrupted, so that a hanging store call
         * does not keep it alive.
         */
        private CompletableFuture<T> start() {
            long started = System.nanoTime();
            CompletableFuture<Attempt<T>> attempt = new CompletableFuture<>();
            Thread worker = Thread.ofVirtual().name("secret-fetch-" + node.value())
                .unstarted(() -> {
                    try {
                        attempt.complete(fetch());
                    } catch (RuntimeException e) {
                        attempt.completeExceptionally(e);
                    }
                });
            CompletableFuture<T> settled = attempt
                .orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS)
                .handle((result, thrown) -> {
                    if (thrown instanceof TimeoutException) {
                        worker.interrupt();
                    }
                    return settle(result, thrown, started);
                });
            worker.start();
            return settled;
        }

        /** Records and logs the outcome of a fetch (exactly once per fetch). */
        private T settle(Attempt<T> result, Throwable thrown, long started) {
            Attempt<T> outcome = result != null ? result : unreachable(
                thrown instanceof TimeoutException
                    ? "no answer within " + timeout.toMillis() + " ms"
                    : thrown.getMessage() == null ? thrown.getClass().getName()
                    : thrown.getClass().getName() + ": " + thrown.getMessage());
            synchronized (lock) {
                inFlight = null;
                if (outcome instanceof Success<T> success) {
                    held = success.credentials();
                } else {
                    refusal = new Refusal(((Failed<T>) outcome).error(), clock.instant());
                }
            }
            log(outcome, Duration.ofNanos(System.nanoTime() - started).toMillis());
            return switch (outcome) {
                case Success<T> success -> success.credentials();
                case Failed<T> failed -> throw new ToolErrorException(failed.error());
            };
        }

        private Attempt<T> fetch() {
            Fetched answer = store.get(location, wanted.stream().map(Wanted::name).toList(),
                timeout);
            if (answer instanceof Fetched.CallFailed(Fetched.Failure failure)
                && callLevel(failure.kind())) {
                return new Failed<>(callError(failure.kind()), failure.kind().name(),
                    failure.logDetail());
            }
            List<Problem> problems = new ArrayList<>();
            Map<String, String> values = new HashMap<>();
            List<String> unencrypted = new ArrayList<>();
            for (Wanted parameter : wanted) {
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
                return failed(problems);
            }
            return new Success<>(build.apply(values), List.copyOf(unencrypted));
        }

        private String nameOf(String key) {
            return wanted.stream().filter(parameter -> parameter.key().equals(key)).findFirst()
                .orElseThrow().name();
        }

        // --- errors (contracts/mcp-tools-delta.md, research R-11) ---

        private Failed<T> failed(List<Problem> problems) {
            String outcome = problems.stream().map(problem -> problem.kind().name()).distinct()
                .collect(Collectors.joining(","));
            String detail = problems.stream()
                .map(problem -> problem.wanted().name() + ": " + problem.logDetail())
                .collect(Collectors.joining("; "));
            Optional<FailureKind> callKind = problems.stream().map(Problem::kind)
                .filter(NodeSecrets::callLevel).findFirst();
            if (callKind.isPresent()) {
                return new Failed<>(callError(callKind.get()), outcome, detail);
            }
            String failures = problems.stream().map(problem -> what(problem.kind()) + " for "
                    + problem.wanted().name()
                    + (problem.rule().isEmpty() ? "" : " (" + problem.rule() + ")"))
                .collect(Collectors.joining("; "));
            String causes = problems.stream().map(problem -> cause(problem.kind())).distinct()
                .collect(Collectors.joining("; "));
            String steps = problems.stream().map(NodeSecrets.this::nextStep).distinct()
                .collect(Collectors.joining("; "));
            return new Failed<>(ToolError.of(ErrorCode.AUTH_FAILED, unavailable() + failures,
                causes, steps).withNode(node), outcome, detail);
        }

        private Failed<T> unreachable(String detail) {
            return new Failed<>(callError(FailureKind.UNREACHABLE),
                FailureKind.UNREACHABLE.name(), detail);
        }

        private ToolError callError(FailureKind kind) {
            String profile = location.awsProfile().map(p -> "the AWS profile " + p)
                .orElse("the default AWS profile");
            ToolError error = switch (kind) {
                case UNKNOWN_PROFILE -> ToolError.of(ErrorCode.AUTH_FAILED,
                    unavailable() + profile + " is unknown",
                    "The AWS profile is not defined in the local AWS configuration files",
                    "Configure " + profile + " in the AWS configuration files and log in to it");
                case NO_CREDENTIALS -> ToolError.of(ErrorCode.AUTH_FAILED,
                    unavailable() + "no AWS credentials",
                    "No AWS credentials could be resolved (not logged in, or the login expired)",
                    location.awsProfile()
                        .map(p -> "Log in to the AWS profile " + p + " (for example aws sso login"
                            + " --profile " + p + ") or configure its credentials")
                        .orElse("Configure AWS credentials for the default credential chain or"
                            + " log in (for example aws sso login)"));
                default -> ToolError.of(ErrorCode.UNREACHABLE,
                    unavailable() + "Parameter Store unreachable",
                    "The Parameter Store could not be reached, throttled the request or did not"
                        + " answer within " + timeout.toSeconds() + " s",
                    "Check network access to ssm." + location.region() + ".amazonaws.com; the"
                        + " fetch is retried after " + BACK_OFF.toSeconds() + " s");
            };
            return error.withNode(node);
        }

        private String unavailable() {
            return node + ": " + subject + " from AWS Parameter Store (" + where()
                + ") are unavailable: ";
        }

        private ToolError pending() {
            return ToolError.of(ErrorCode.TIMEOUT,
                node + ": the " + subject + " from AWS Parameter Store (" + where()
                    + ") are not yet available (still fetching)",
                "The Parameter Store answers slowly",
                "Retry shortly; the fetch continues and the next call uses its result")
                .withNode(node);
        }

        // --- logging (research R-14) ---

        private void log(Attempt<T> outcome, long durationMs) {
            Level level = outcome instanceof Success<T> success && success.unencrypted().isEmpty()
                ? Level.INFO : Level.WARN;
            LoggingEventBuilder record = LOG.atLevel(level)
                .addKeyValue("event", "secret.fetch")
                .addKeyValue("node", node.value())
                .addKeyValue("source", "awsParameterStore")
                .addKeyValue("region", location.region());
            if (location.awsProfile().isPresent()) {
                record = record.addKeyValue("profile", location.awsProfile().get());
            }
            record = record
                .addKeyValue("parameters", wanted.stream().map(Wanted::name)
                    .collect(Collectors.joining(",")))
                .addKeyValue("durationMs", durationMs);
            switch (outcome) {
                case Success<T> success -> {
                    record = record.addKeyValue("outcome", "ok");
                    String message = node + ": " + subject + " fetched from AWS Parameter Store ("
                        + where() + ") in " + durationMs + " ms";
                    if (!success.unencrypted().isEmpty()) {
                        message += "; not encrypted (type String): "
                            + String.join(", ", success.unencrypted())
                            + "; store them as SecureString";
                    }
                    record.log(message);
                }
                case Failed<T> failed -> record.addKeyValue("outcome", failed.outcome())
                    .addKeyValue("detail", scrubber.scrub(failed.detail()))
                    .log(node + ": " + subject + " could not be fetched from AWS Parameter Store ("
                        + where() + "): " + failed.outcome());
            }
        }
    }

    /** The {@link CredentialRules} checks of one value; the value itself is never described. */
    private static Optional<Problem> check(Wanted parameter, Fetched.Value value) {
        CredentialRules.ValueRole role = switch (parameter.key()) {
            case USERNAME, E2E_USERNAME -> CredentialRules.ValueRole.USERNAME;
            case PASSWORD, E2E_PASSWORD -> CredentialRules.ValueRole.PASSWORD;
            default -> CredentialRules.ValueRole.TRUST_STORE_PASSWORD;
        };
        return CredentialRules.checkValue(role, value.type(), value.value().reveal())
            .map(refusal -> new Problem(parameter, refusal.kind(), refusal.rule(),
                refusal.rule()));
    }

    /** Kinds that concern the store access as a whole, not one parameter. */
    private static boolean callLevel(FailureKind kind) {
        return kind == FailureKind.UNREACHABLE || kind == FailureKind.UNKNOWN_PROFILE
            || kind == FailureKind.NO_CREDENTIALS;
    }

    private String where() {
        return location.region() + location.awsProfile().map(p -> ", profile " + p).orElse("");
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

    private String nextStep(Problem problem) {
        String name = problem.wanted().name();
        return switch (problem.kind()) {
            case NOT_FOUND -> "Create the parameter " + name + " in Parameter Store (" + where()
                + ") or correct credentials.parameters." + problem.wanted().key();
            case ACCESS_DENIED -> "Grant ssm:GetParameters on " + name + " and kms:Decrypt on its"
                + " KMS key to the AWS identity in use";
            default -> "Correct the value of " + name + " in Parameter Store (as SecureString;"
                + " the value is never shown); no restart is needed";
        };
    }

    @Override
    public String toString() {
        return "NodeSecrets[" + node + "]";
    }
}
