package de.dadecker.inubit.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeParameterStore;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feature 010 (T010, research R-7, R-11, R-14, R-17): lazy node secrets with the fake store and a
 * fake clock. Wall-clock assertions use generous margins; only SC-005's cached call is tight
 * (a lock and a field read).
 */
class NodeSecretsTest {

    private static final NodeId NODE1 = NodeId.parse("dev/node1");
    private static final NodeId NODE2 = NodeId.parse("dev/node2");
    private static final StoreLocation EU =
        new StoreLocation("eu-central-1", Optional.of("test-profile"));
    private static final String USERNAME = "/test/dev/node1/username";
    private static final String PASSWORD = "/test/dev/node1/password";
    private static final String TRUST = "/test/dev/node1/truststore-password";
    private static final String NODE2_USERNAME = "/test/dev/node2/username";
    private static final String NODE2_PASSWORD = "/test/dev/node2/password";
    private static final String AWS_DETAIL =
        "User arn:aws:iam::123456789012:role/test-detail is not authorized";
    private static final Duration LONG = Duration.ofSeconds(10);

    private final FakeParameterStore store = new FakeParameterStore();
    private final SecretScrubber scrubber = new SecretScrubber();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T08:00:00Z"));

    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(NodeSecrets.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    /** Successful fetches are logged at INFO (the production root level); tests run at WARN. */
    @BeforeEach
    void captureLog() {
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseLog() {
        logger.setLevel(null);
        logger.setAdditive(true);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static SecretPlan plan(NodeId node, String username, String password,
        Optional<String> trust) {
        return new SecretPlan(node,
            new SecretSourceConfig.AwsParameterStore(EU.region(), EU.awsProfile()),
            SecretPlan.Origin.NODE, Optional.empty(), Optional.of(new ParameterNames(
                Optional.of(username), Optional.of(password), trust, Optional.empty(),
                Optional.empty())));
    }

    private NodeSecrets secrets() {
        return NodeSecrets.aws(plan(NODE1, USERNAME, PASSWORD, Optional.empty()), store, scrubber,
            clock);
    }

    private NodeSecrets secretsWithTrustStore() {
        return NodeSecrets.aws(plan(NODE1, USERNAME, PASSWORD, Optional.of(TRUST)), store,
            scrubber, clock);
    }

    private void putValid() {
        store.put(EU, USERNAME, "test-user-1").put(EU, PASSWORD, "test-password-1");
    }

    private static ToolError failure(NodeSecrets secrets, Duration bound) {
        return catchThrowableOfType(ToolErrorException.class, () -> secrets.current(bound))
            .error();
    }

    private static Duration elapsed(Runnable call) {
        long start = System.nanoTime();
        call.run();
        return Duration.ofNanos(System.nanoTime() - start);
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
            .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private static String allText(ToolError error) {
        return error.message() + " | " + error.likelyCause() + " | " + error.nextStep() + " | "
            + error;
    }

    // --- environment nodes and first use ---

    @Test
    void anEnvironmentNodeReturnsItsStartupCredentialsWithoutTouchingTheStore() {
        NodeCredentials startup = new NodeCredentials(NODE1,
            Optional.of(new SourcedValue<>("test-user-1", "INUBIT_DEV_NODE1_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of("test-password-1"),
                "INUBIT_DEV_NODE1_PASSWORD")),
            Optional.empty(), Optional.empty());

        NodeSecrets secrets = NodeSecrets.environment(startup);

        assertThat(secrets.node()).isEqualTo(NODE1);
        assertThat(secrets.current(Duration.ZERO)).isSameAs(startup);
        assertThat(secrets.held()).containsSame(startup);
        assertThat(store.calls()).isZero();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void anAwsNodeFetchesNothingUntilFirstUseThenOnceForAllKinds() {
        putValid();
        store.put(EU, TRUST, "test-trust-1");
        NodeSecrets secrets = secretsWithTrustStore();

        assertThat(store.calls()).isZero();

        NodeCredentials first = secrets.current(LONG);
        NodeCredentials second = secrets.current(LONG);

        assertThat(second).isSameAs(first);
        assertThat(store.requests()).containsExactly(new FakeParameterStore.Request(EU,
            List.of(USERNAME, PASSWORD, TRUST), NodeSecrets.STORE_TIMEOUT));
        assertThat(NodeSecrets.STORE_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
        assertThat(first.node()).isEqualTo(NODE1);
        assertThat(first.deferred()).isFalse();
        assertThat(first.username().orElseThrow())
            .isEqualTo(new SourcedValue<>("test-user-1", USERNAME));
        assertThat(first.password().orElseThrow().value().reveal()).isEqualTo("test-password-1");
        assertThat(first.password().orElseThrow().sourceVariable()).isEqualTo(PASSWORD);
        assertThat(first.trustStorePassword().orElseThrow().value().reveal())
            .isEqualTo("test-trust-1");
        assertThat(first.basicAuthToken().orElseThrow().reveal()).isEqualTo(Base64.getEncoder()
            .encodeToString("test-user-1:test-password-1".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void withoutATrustStorePasswordNameOnlyUsernameAndPasswordAreRead() {
        putValid();

        NodeCredentials credentials = secrets().current(LONG);

        assertThat(store.requests()).singleElement()
            .satisfies(request -> assertThat(request.names()).containsExactly(USERNAME, PASSWORD));
        assertThat(credentials.trustStorePassword()).isEmpty();
    }

    // --- scrubber ---

    @Test
    void registersPasswordTrustStorePasswordAndBasicTokenBeforeReturningThem() {
        putValid();
        store.put(EU, TRUST, "test-trust-1");

        NodeCredentials credentials = secretsWithTrustStore().current(LONG);

        String token = credentials.basicAuthToken().orElseThrow().reveal();
        assertThat(scrubber.scrub("a test-password-1 b test-trust-1 c " + token))
            .isEqualTo("a *** b *** c ***");
    }

    @Test
    void aRefusedShortPasswordIsNotRegistered() {
        store.put(EU, USERNAME, "test-user-1").put(EU, PASSWORD, "abc");

        ToolError error = failure(secrets(), LONG);

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(scrubber.scrub("abc")).isEqualTo("abc");
    }

    // --- single flight, parallel nodes, bounds ---

    @Test
    void concurrentCallersShareOneFetch() throws Exception {
        putValid();
        store.delay(EU, Duration.ofMillis(500));
        NodeSecrets secrets = secrets();
        int callers = 8;
        List<Future<NodeCredentials>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                results.add(executor.submit(() -> secrets.current(LONG)));
            }
            NodeCredentials first = results.get(0).get();
            for (Future<NodeCredentials> result : results) {
                assertThat(result.get()).isSameAs(first);
            }
        }

        assertThat(store.calls()).isEqualTo(1);
        assertThat(appender.list).hasSize(1);
    }

    @Test
    void twoNodesFetchInParallelOnTheirOwnThreads() throws Exception {
        putValid();
        store.put(EU, NODE2_USERNAME, "test-user-2").put(EU, NODE2_PASSWORD, "test-password-2")
            .delay(EU, Duration.ofSeconds(1));
        NodeSecrets node1 = secrets();
        NodeSecrets node2 = NodeSecrets.aws(
            plan(NODE2, NODE2_USERNAME, NODE2_PASSWORD, Optional.empty()), store, scrubber, clock);

        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<NodeCredentials> first = executor.submit(() -> node1.current(LONG));
            Future<NodeCredentials> second = executor.submit(() -> node2.current(LONG));
            assertThat(first.get().node()).isEqualTo(NODE1);
            assertThat(second.get().node()).isEqualTo(NODE2);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(store.calls()).isEqualTo(2);
        assertThat(elapsed).as("about 1 s, not 2 s").isLessThan(Duration.ofMillis(1900));
    }

    @Test
    void aBoundShorterThanTheFetchTimesOutButTheNextCallGetsTheStoredResult() {
        putValid();
        store.delay(EU, Duration.ofMillis(500));
        NodeSecrets secrets = secrets();

        ToolError pending = failure(secrets, Duration.ofMillis(50));

        assertThat(pending.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(pending.node()).contains(NODE1);
        assertThat(pending.message()).contains("dev/node1", "not yet available",
            "AWS Parameter Store");
        assertThat(secrets.current(LONG).username().orElseThrow().value())
            .isEqualTo("test-user-1");
        assertThat(store.calls()).isEqualTo(1);
    }

    @Test
    void aWaitIsCappedAtTheStoreTimeoutEvenForALongerBound() {
        putValid();
        store.delay(EU, Duration.ofSeconds(60));
        NodeSecrets secrets = new NodeSecrets(plan(NODE1, USERNAME, PASSWORD, Optional.empty()),
            store, scrubber, clock, Duration.ofMillis(300));

        Duration waited = elapsed(() -> failure(secrets, Duration.ofMinutes(5)));

        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(300))
            .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aStoreCallThatNeverReturnsEndsAfterTheTimeoutAsUnreachable() {
        putValid();
        store.delay(EU, Duration.ofSeconds(60));
        Duration timeout = Duration.ofMillis(300);
        NodeSecrets secrets = new NodeSecrets(plan(NODE1, USERNAME, PASSWORD, Optional.empty()),
            store, scrubber, clock, timeout);

        ToolError error = failure(secrets, timeout);

        assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(error.message()).contains("dev/node1", "eu-central-1",
            "Parameter Store unreachable");
        assertThat(store.requests()).singleElement()
            .satisfies(request -> assertThat(request.timeout()).isEqualTo(timeout));
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(fields(event)).containsEntry("outcome", "UNREACHABLE");
        });

        assertThat(failure(secrets, LONG)).as("back-off").isEqualTo(error);
        assertThat(store.calls()).isEqualTo(1);
    }

    @Test
    void anInterruptedCallerGetsATimeoutAndTheFetchContinues() throws Exception {
        putValid();
        store.delay(EU, Duration.ofMillis(500));
        NodeSecrets secrets = secrets();
        List<ToolError> errors = new ArrayList<>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                secrets.current(LONG);
            } catch (ToolErrorException e) {
                errors.add(e.error());
            }
        });
        while (store.calls() == 0) {
            Thread.onSpinWait();
        }
        caller.interrupt();
        caller.join();

        assertThat(errors).singleElement()
            .satisfies(error -> assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT));
        assertThat(secrets.current(LONG).complete()).isTrue();
        assertThat(store.calls()).isEqualTo(1);
    }

    @Test
    void aStoreCallThatOutlivesTheTimeoutIsInterrupted() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        ParameterStore hanging = (location, names, timeout) -> {
            try {
                Thread.sleep(Duration.ofSeconds(60));
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return new Fetched.CallFailed(new Fetched.Failure(FailureKind.UNREACHABLE, "late"));
        };
        Duration timeout = Duration.ofMillis(300);
        NodeSecrets secrets = new NodeSecrets(plan(NODE1, USERNAME, PASSWORD, Optional.empty()),
            hanging, scrubber, clock, timeout);

        assertThat(failure(secrets, LONG).code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(interrupted.await(5, TimeUnit.SECONDS))
            .as("the fetch thread is interrupted when the fetch's bound fires").isTrue();
    }

    @Test
    void anUnexpectedStoreExceptionIsUnreachableAndLogsItsClassAndScrubbedMessage() {
        scrubber.register("test-secret-in-exception");
        ParameterStore broken = (location, names, timeout) -> {
            throw new IllegalStateException("broken store test-secret-in-exception");
        };
        NodeSecrets secrets = NodeSecrets.aws(plan(NODE1, USERNAME, PASSWORD, Optional.empty()),
            broken, scrubber, clock);

        ToolError error = failure(secrets, LONG);

        assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(allText(error)).doesNotContain("broken store", "IllegalStateException");
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(String.valueOf(fields(event).get("detail")))
                .contains("java.lang.IllegalStateException", "broken store", "***")
                .doesNotContain("test-secret-in-exception");
        });
    }

    @Test
    void heldAnswersWithoutStartingAFetch() {
        putValid();
        NodeSecrets secrets = secrets();

        assertThat(secrets.held()).isEmpty();
        assertThat(store.calls()).isZero();

        NodeCredentials fetched = secrets.current(LONG);

        assertThat(secrets.held()).containsSame(fetched);
        assertThat(store.calls()).isEqualTo(1);
    }

    // --- back-off ---

    @Test
    void afterAFailureTheSameErrorIsReturnedAtOnceFor30SecondsThenTheFetchIsRetried() {
        store.put(EU, USERNAME, "test-user-1")
            .fail(EU, PASSWORD, FailureKind.NOT_FOUND, AWS_DETAIL);
        NodeSecrets secrets = secrets();

        ToolError first = failure(secrets, LONG);
        clock.advance(Duration.ofSeconds(29));
        ToolError within = failure(secrets, LONG);

        assertThat(within).isEqualTo(first);
        assertThat(store.calls()).isEqualTo(1);
        assertThat(appender.list).as("no record within the back-off").hasSize(1);
        assertThat(NodeSecrets.BACK_OFF).isEqualTo(Duration.ofSeconds(30));

        clock.advance(Duration.ofSeconds(1));
        store.put(EU, PASSWORD, "test-password-1");

        assertThat(secrets.current(LONG).complete()).isTrue();
        assertThat(store.calls()).isEqualTo(2);
        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(1).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void aValidationFailureCountsAsAFailedFetch() {
        store.put(EU, USERNAME, "-test-user").put(EU, PASSWORD, "test-password-1");
        NodeSecrets secrets = secrets();

        ToolError error = failure(secrets, LONG);

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.message()).contains("dev/node1", USERNAME, "invalid value",
            "must not start with '-'");
        assertThat(allText(error)).doesNotContain("-test-user");
        assertThat(failure(secrets, LONG)).isEqualTo(error);
        assertThat(store.calls()).isEqualTo(1);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(fields(event)).containsEntry("outcome", "INVALID_VALUE");
            assertThat(event.getFormattedMessage()).doesNotContain("-test-user");
        });

        clock.advance(NodeSecrets.BACK_OFF);
        store.put(EU, USERNAME, "test-user-1");

        assertThat(secrets.current(LONG).username().orElseThrow().value())
            .isEqualTo("test-user-1");
    }

    // --- parameter types (CredentialRules) ---

    @Test
    void aStringParameterIsAcceptedWithANotEncryptedWarning() {
        store.put(EU, USERNAME, "test-user-1", ParameterType.STRING)
            .put(EU, PASSWORD, "test-password-1", ParameterType.STRING);

        NodeCredentials credentials = secrets().current(LONG);

        assertThat(credentials.complete()).isTrue();
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("not encrypted", USERNAME,
                PASSWORD);
            assertThat(fields(event)).containsEntry("outcome", "ok");
        });
    }

    @Test
    void aStringListParameterIsRefusedAsInvalidValue() {
        store.put(EU, USERNAME, "test-user-1")
            .put(EU, PASSWORD, "test-password-1,x", ParameterType.STRING_LIST);

        ToolError error = failure(secrets(), LONG);

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.message()).contains("dev/node1", PASSWORD, "invalid value",
            "StringList");
        assertThat(error.nextStep()).contains(PASSWORD, "SecureString");
        assertThat(allText(error)).doesNotContain("test-password-1");
    }

    @Test
    void aTooShortPasswordIsRefusedWithTheRule() {
        store.put(EU, USERNAME, "test-user-1").put(EU, PASSWORD, "abc");

        ToolError error = failure(secrets(), LONG);

        assertThat(error.message()).contains(PASSWORD, "invalid value", "shorter than 4");
        assertThat(error.nextStep()).contains(PASSWORD, "no restart");
    }

    @Test
    void anEmptyValueCountsAsNotFound() {
        store.put(EU, USERNAME, "test-user-1").put(EU, PASSWORD, "");

        ToolError error = failure(secrets(), LONG);

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.message()).contains(PASSWORD, "not found");
    }

    // --- error mapping (contracts/mcp-tools-delta.md) ---

    static Stream<Arguments> failureKinds() {
        return Stream.of(
            Arguments.of(perName(FailureKind.NOT_FOUND), ErrorCode.AUTH_FAILED,
                List.of(PASSWORD, "not found", "eu-central-1", "profile test-profile"),
                List.of("Create the parameter", PASSWORD, "credentials.parameters.password")),
            Arguments.of(perName(FailureKind.ACCESS_DENIED), ErrorCode.AUTH_FAILED,
                List.of(PASSWORD, "access denied", "eu-central-1", "profile test-profile"),
                List.of("ssm:GetParameters", PASSWORD, "kms:Decrypt")),
            Arguments.of(perName(FailureKind.INVALID_VALUE), ErrorCode.AUTH_FAILED,
                List.of(PASSWORD, "invalid value"),
                List.of(PASSWORD, "no restart")),
            Arguments.of(call(FailureKind.UNKNOWN_PROFILE), ErrorCode.AUTH_FAILED,
                List.of("AWS profile test-profile", "unknown"),
                List.of("test-profile")),
            Arguments.of(call(FailureKind.NO_CREDENTIALS), ErrorCode.AUTH_FAILED,
                List.of("no AWS credentials", "profile test-profile"),
                List.of("Log in", "test-profile")),
            Arguments.of(call(FailureKind.UNREACHABLE), ErrorCode.UNREACHABLE,
                List.of("Parameter Store unreachable", "eu-central-1"),
                List.of("ssm.eu-central-1.amazonaws.com", "30 s")),
            Arguments.of(perName(FailureKind.UNREACHABLE), ErrorCode.UNREACHABLE,
                List.of("Parameter Store unreachable", "eu-central-1"),
                List.of("ssm.eu-central-1.amazonaws.com", "30 s")));
    }

    private static Scripter perName(FailureKind kind) {
        return store -> store.put(EU, USERNAME, "test-user-1")
            .fail(EU, PASSWORD, kind, AWS_DETAIL);
    }

    private static Scripter call(FailureKind kind) {
        return store -> store.failCall(EU, kind, AWS_DETAIL);
    }

    /** Scripts one failure into the store. */
    interface Scripter {
        void script(FakeParameterStore store);
    }

    @ParameterizedTest
    @MethodSource("failureKinds")
    void eachFailureKindHasItsErrorTextWithoutTheRawStoreMessage(Scripter scripter,
        ErrorCode code, List<String> message, List<String> nextStep) {
        scripter.script(store);

        ToolError error = failure(secrets(), LONG);

        assertThat(error.code()).isEqualTo(code);
        assertThat(error.node()).contains(NODE1);
        assertThat(error.message()).contains("dev/node1").contains(message);
        assertThat(error.nextStep()).contains(nextStep);
        assertThat(allText(error)).doesNotContain("123456789012", "arn:aws", "test-user-1");
    }

    @Test
    void severalFailedParametersAreNamedInOneMessage() {
        store.fail(EU, USERNAME, FailureKind.ACCESS_DENIED, AWS_DETAIL);

        ToolError error = failure(secrets(), LONG);

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.message()).contains("access denied for " + USERNAME,
            "not found for " + PASSWORD);
        assertThat(error.nextStep()).contains("ssm:GetParameters", "Create the parameter");
    }

    // --- logging (R-14) ---

    @Test
    void aSuccessfulFetchLogsOneInfoRecordWithItsFieldsAndNoValue() {
        putValid();

        secrets().current(LONG);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(fields(event)).containsEntry("event", "secret.fetch")
                .containsEntry("node", "dev/node1")
                .containsEntry("source", "awsParameterStore")
                .containsEntry("region", "eu-central-1")
                .containsEntry("profile", "test-profile")
                .containsEntry("parameters", USERNAME + "," + PASSWORD)
                .containsEntry("outcome", "ok")
                .containsKey("durationMs");
            assertThat(event.getFormattedMessage() + fields(event))
                .doesNotContain("test-user-1", "test-password-1");
        });
    }

    @Test
    void aFailedFetchLogsOneWarnRecordWithTheScrubbedStoreMessage() {
        scrubber.register("test-secret-in-detail");
        store.put(EU, USERNAME, "test-user-1")
            .fail(EU, PASSWORD, FailureKind.ACCESS_DENIED, AWS_DETAIL + " test-secret-in-detail");

        failure(secrets(), LONG);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(fields(event)).containsEntry("outcome", "ACCESS_DENIED");
            assertThat(String.valueOf(fields(event).get("detail")))
                .contains("arn:aws:iam::123456789012:role/test-detail", "***")
                .doesNotContain("test-secret-in-detail");
        });
    }

    // --- SC-005 ---

    @Test
    void sc005FirstCallAtMostTwoSecondsWithASlowStoreLaterCallsWithin50Ms() {
        putValid();
        store.delay(EU, Duration.ofSeconds(1));
        NodeSecrets secrets = secrets();

        Duration first = elapsed(() -> secrets.current(LONG));
        Duration second = elapsed(() -> secrets.current(LONG));

        assertThat(first).isGreaterThanOrEqualTo(Duration.ofSeconds(1))
            .isLessThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(second).isLessThanOrEqualTo(Duration.ofMillis(50));
    }

    @Test
    void toStringNamesTheNodeOnly() {
        putValid();
        NodeSecrets secrets = secrets();
        secrets.current(LONG);

        assertThat(secrets.toString()).isEqualTo("NodeSecrets[dev/node1]");
    }
}
