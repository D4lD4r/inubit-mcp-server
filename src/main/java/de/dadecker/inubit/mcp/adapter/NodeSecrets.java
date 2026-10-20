package de.dadecker.inubit.mcp.adapter;

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
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * The credentials of one node (feature 010, data-model.md → NodeSecrets, research R-7, R-11,
 * R-14, R-17). An environment node holds its startup credentials. An AWS node fetches username,
 * password and (if named) trust store password from the {@link ParameterStore} on first use:
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
 *   <li>password, trust store password and Basic token are registered with the
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

    private static final Logger LOG = LoggerFactory.getLogger(NodeSecrets.class);

    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final String TRUST_STORE_PASSWORD = "trustStorePassword";

    private final NodeId node;
    private final StoreLocation location;
    /** The parameters of one fetch, in kind order; empty for an environment node. */
    private final List<Wanted> wanted;
    private final ParameterStore store;
    private final SecretScrubber scrubber;
    private final Clock clock;
    private final Duration timeout;

    private final Object lock = new Object();
    private NodeCredentials held;
    private CompletableFuture<NodeCredentials> inFlight;
    private Refusal refusal;

    /** One parameter to fetch; {@code key} is its {@code credentials.parameters} key. */
    private record Wanted(String key, String name) {
    }

    /** A recorded failure and when it happened. */
    private record Refusal(ToolError error, Instant at) {
    }

    /** The outcome of one fetch. */
    private sealed interface Attempt permits Success, Failed {
    }

    private record Success(NodeCredentials credentials, List<String> unencrypted)
        implements Attempt {
    }

    /** @param detail scrubbed before logging; never part of {@code error} */
    private record Failed(ToolError error, String outcome, String detail) implements Attempt {
    }

    /** A failed parameter; {@code rule} explains a refused value ({@code ""} if none). */
    private record Problem(Wanted wanted, FailureKind kind, String rule, String logDetail) {
    }

    private NodeSecrets(NodeCredentials credentials) {
        this.node = credentials.node();
        this.held = credentials;
        this.location = null;
        this.wanted = List.of();
        this.store = null;
        this.scrubber = null;
        this.clock = null;
        this.timeout = null;
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
        List<Wanted> kinds = new ArrayList<>();
        kinds.add(new Wanted(USERNAME, names.username().orElseThrow(
            () -> new IllegalArgumentException(node + ": no username parameter name"))));
        kinds.add(new Wanted(PASSWORD, names.password().orElseThrow(
            () -> new IllegalArgumentException(node + ": no password parameter name"))));
        names.trustStorePassword().ifPresent(name -> kinds.add(
            new Wanted(TRUST_STORE_PASSWORD, name)));
        this.wanted = List.copyOf(kinds);
        this.store = Objects.requireNonNull(store, "store");
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
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

    /**
     * The node's credentials; starts or joins the fetch of an AWS node if they are not held yet.
     *
     * @param bound how long to wait for a fetch; at most {@link #STORE_TIMEOUT} is waited
     * @throws ToolErrorException the classified fetch failure ({@code AUTH_FAILED} or
     *     {@code UNREACHABLE}, also at once within {@link #BACK_OFF} after it), or
     *     {@code TIMEOUT} if the fetch outlives {@code bound} or the caller is interrupted
     */
    public NodeCredentials current(Duration bound) {
        Objects.requireNonNull(bound, "bound");
        CompletableFuture<NodeCredentials> fetch;
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
                if (wait.equals(timeout)) {
                    // The fetch's own bound (armed before this wait) has expired as well, so its
                    // outcome is due; report it instead of "still fetching".
                    return fetch.get();
                }
                throw new ToolErrorException(pending());
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ToolErrorException error) {
                throw error;
            }
            throw new IllegalStateException("Unexpected fetch failure", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
                "The call to " + node + " was cancelled while waiting for its credentials from"
                    + " AWS Parameter Store",
                "The overall deadline of the request expired",
                "Retry").withNode(node));
        }
    }

    /** Starts a fetch; called under {@link #lock}, so {@link #settle} runs after it returns. */
    private CompletableFuture<NodeCredentials> start() {
        long started = System.nanoTime();
        CompletableFuture<Attempt> attempt = new CompletableFuture<>();
        CompletableFuture<NodeCredentials> settled = attempt
            .orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS)
            .handle((result, thrown) -> settle(result, thrown, started));
        Thread.ofVirtual().name("secret-fetch-" + node.value()).start(() -> {
            try {
                attempt.complete(fetch());
            } catch (RuntimeException e) {
                attempt.completeExceptionally(e);
            }
        });
        return settled;
    }

    /** Records and logs the outcome of a fetch (exactly once per fetch). */
    private NodeCredentials settle(Attempt result, Throwable thrown, long started) {
        Attempt outcome = result != null ? result : unreachable(thrown instanceof TimeoutException
            ? "no answer within " + timeout.toMillis() + " ms" : thrown.getClass().getName());
        synchronized (lock) {
            inFlight = null;
            if (outcome instanceof Success success) {
                held = success.credentials();
            } else {
                refusal = new Refusal(((Failed) outcome).error(), clock.instant());
            }
        }
        log(outcome, Duration.ofNanos(System.nanoTime() - started).toMillis());
        return switch (outcome) {
            case Success success -> success.credentials();
            case Failed failed -> throw new ToolErrorException(failed.error());
        };
    }

    private Attempt fetch() {
        Fetched answer = store.get(location, wanted.stream().map(Wanted::name).toList(), timeout);
        if (answer instanceof Fetched.CallFailed(Fetched.Failure failure)
            && callLevel(failure.kind())) {
            return new Failed(callError(failure.kind()), failure.kind().name(),
                failure.logDetail());
        }
        List<Problem> problems = new ArrayList<>();
        Map<String, String> values = new HashMap<>();
        List<String> unencrypted = new ArrayList<>();
        for (Wanted parameter : wanted) {
            Fetched.Result result = switch (answer) {
                case Fetched.CallFailed failed -> failed.failure();
                case Fetched.PerName perName -> perName.results().getOrDefault(parameter.name(),
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
        return new Success(credentials(values), List.copyOf(unencrypted));
    }

    /** The {@link CredentialRules} checks of one value; the value itself is never described. */
    private static Optional<Problem> check(Wanted parameter, Fetched.Value value) {
        String rule = null;
        FailureKind kind = FailureKind.INVALID_VALUE;
        String text = value.value().reveal();
        if (CredentialRules.checkType(value.type()) == CredentialRules.TypeVerdict.REFUSED) {
            rule = "a StringList is not accepted; store it as SecureString";
        } else if (!CredentialRules.isSet(text)) {
            kind = FailureKind.NOT_FOUND;
            rule = "the value is empty";
        } else if (parameter.key().equals(USERNAME) && !CredentialRules.isSafeUsername(text)) {
            rule = "the username " + CredentialRules.SAFE_USERNAME_RULE;
        } else if (parameter.key().equals(PASSWORD) && !CredentialRules.isScrubbable(text)) {
            rule = "the password " + CredentialRules.SHORT_PASSWORD_RULE;
        }
        return rule == null ? Optional.empty()
            : Optional.of(new Problem(parameter, kind, rule, rule));
    }

    /** Registers the secrets with the scrubber, then builds the credentials. */
    private NodeCredentials credentials(Map<String, String> values) {
        String username = values.get(USERNAME);
        Secret password = scrubber.register(values.get(PASSWORD));
        Optional<SourcedValue<Secret>> trustStorePassword = Optional.ofNullable(
                values.get(TRUST_STORE_PASSWORD))
            .map(value -> new SourcedValue<>(scrubber.register(value),
                nameOf(TRUST_STORE_PASSWORD)));
        Secret token = scrubber.registerBasicAuth(username, password);
        return new NodeCredentials(node, Optional.of(new SourcedValue<>(username,
            nameOf(USERNAME))), Optional.of(new SourcedValue<>(password, nameOf(PASSWORD))),
            trustStorePassword, Optional.of(token));
    }

    private String nameOf(String key) {
        return wanted.stream().filter(parameter -> parameter.key().equals(key)).findFirst()
            .orElseThrow().name();
    }

    // --- errors (contracts/mcp-tools-delta.md, research R-11) ---

    private Failed failed(List<Problem> problems) {
        String outcome = problems.stream().map(problem -> problem.kind().name()).distinct()
            .collect(Collectors.joining(","));
        String detail = problems.stream()
            .map(problem -> problem.wanted().name() + ": " + problem.logDetail())
            .collect(Collectors.joining("; "));
        Optional<FailureKind> callKind = problems.stream().map(Problem::kind)
            .filter(NodeSecrets::callLevel).findFirst();
        if (callKind.isPresent()) {
            return new Failed(callError(callKind.get()), outcome, detail);
        }
        String failures = problems.stream().map(problem -> what(problem.kind()) + " for "
                + problem.wanted().name()
                + (problem.rule().isEmpty() ? "" : " (" + problem.rule() + ")"))
            .collect(Collectors.joining("; "));
        String causes = problems.stream().map(problem -> cause(problem.kind())).distinct()
            .collect(Collectors.joining("; "));
        String steps = problems.stream().map(this::nextStep).distinct()
            .collect(Collectors.joining("; "));
        return new Failed(ToolError.of(ErrorCode.AUTH_FAILED, unavailable() + failures, causes,
            steps).withNode(node), outcome, detail);
    }

    private Failed unreachable(String detail) {
        return new Failed(callError(FailureKind.UNREACHABLE), FailureKind.UNREACHABLE.name(),
            detail);
    }

    /** Kinds that concern the store access as a whole, not one parameter. */
    private static boolean callLevel(FailureKind kind) {
        return kind == FailureKind.UNREACHABLE || kind == FailureKind.UNKNOWN_PROFILE
            || kind == FailureKind.NO_CREDENTIALS;
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
                    .orElse("Configure AWS credentials for the default credential chain or log"
                        + " in (for example aws sso login)"));
            default -> ToolError.of(ErrorCode.UNREACHABLE,
                unavailable() + "Parameter Store unreachable",
                "The Parameter Store could not be reached, throttled the request or did not"
                    + " answer within " + timeout.toSeconds() + " s",
                "Check network access to ssm." + location.region() + ".amazonaws.com; the fetch"
                    + " is retried after " + BACK_OFF.toSeconds() + " s");
        };
        return error.withNode(node);
    }

    private String unavailable() {
        return node + ": INUBIT credentials from AWS Parameter Store (" + where()
            + ") are unavailable: ";
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

    private ToolError pending() {
        return ToolError.of(ErrorCode.TIMEOUT,
            node + ": the INUBIT credentials from AWS Parameter Store (" + where()
                + ") are not yet available (still fetching)",
            "The Parameter Store answers slowly",
            "Retry shortly; the fetch continues and the next call uses its result")
            .withNode(node);
    }

    // --- logging (research R-14) ---

    private void log(Attempt outcome, long durationMs) {
        Level level = outcome instanceof Success success && success.unencrypted().isEmpty()
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
            case Success success -> {
                record = record.addKeyValue("outcome", "ok");
                String message = node + ": credentials fetched from AWS Parameter Store ("
                    + where() + ") in " + durationMs + " ms";
                if (!success.unencrypted().isEmpty()) {
                    message += "; not encrypted (type String): "
                        + String.join(", ", success.unencrypted()) + "; store them as SecureString";
                }
                record.log(message);
            }
            case Failed failed -> record.addKeyValue("outcome", failed.outcome())
                .addKeyValue("detail", scrubber.scrub(failed.detail()))
                .log(node + ": credentials could not be fetched from AWS Parameter Store ("
                    + where() + "): " + failed.outcome());
        }
    }

    @Override
    public String toString() {
        return "NodeSecrets[" + node + "]";
    }
}
