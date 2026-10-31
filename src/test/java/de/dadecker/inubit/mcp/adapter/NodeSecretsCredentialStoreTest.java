package de.dadecker.inubit.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.config.ConfirmationMode;
import de.dadecker.inubit.mcp.config.E2eCredentials;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.StoreKind;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeCredentialStore;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Feature 011 T011/T012 (research R-7, R-11, quickstart Q1–Q4, Q8, Q14, Q24, Q25): the credentials
 * of a credential store node, read on first use, the node entry before the group entry.
 */
class NodeSecretsCredentialStoreTest {

    private static final Duration LONG = Duration.ofSeconds(10);
    private static final String NODE_USER = "INUBIT_ACME_DEV_NODE1_USERNAME";
    private static final String GROUP_USER = "INUBIT_ACME_DEV_USERNAME";
    private static final String NODE_PW = "INUBIT_ACME_DEV_NODE1_PASSWORD";
    private static final String GROUP_PW = "INUBIT_ACME_DEV_PASSWORD";

    private final FakeCredentialStore store = new FakeCredentialStore();
    private final SecretScrubber scrubber = new SecretScrubber();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-31T08:00:00Z"));

    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(NodeSecrets.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

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

    private static TestNodeConfig config() {
        return TestNodeConfig.node().id("dev/node1").credentialPrefix("INUBIT_ACME");
    }

    private NodeSecrets secrets(EffectiveNodeConfig config) {
        return new NodeSecrets(config, store, scrubber, clock, LONG);
    }

    private NodeSecrets secrets() {
        return secrets(config().build());
    }

    @Test
    void nothingIsReadUntilTheFirstUse() {
        NodeSecrets secrets = secrets();

        assertThat(store.reads()).isEmpty();
        assertThat(secrets.held()).isEmpty();
        assertThat(secrets.timeout()).isEqualTo(LONG);
        assertThat(secrets.rereadable()).isTrue();
    }

    @Test
    void theNodeEntryWinsAndTheGroupEntryIsTheFallback() {
        store.put(GROUP_USER, "group-user").put(NODE_PW, "node-Pw-1").put(GROUP_PW, "group-Pw-2");

        NodeCredentials credentials = secrets().current(LONG);

        assertThat(credentials.username().orElseThrow().value()).isEqualTo("group-user");
        assertThat(credentials.username().orElseThrow().sourceVariable()).isEqualTo(GROUP_USER);
        assertThat(credentials.password().orElseThrow().value().reveal()).isEqualTo("node-Pw-1");
        assertThat(credentials.password().orElseThrow().sourceVariable()).isEqualTo(NODE_PW);
        assertThat(store.reads()).extracting(FakeCredentialStore.Read::account)
            .containsExactly(NODE_USER, GROUP_USER, NODE_PW);
        assertThat(scrubber.scrub("x node-Pw-1 y")).doesNotContain("node-Pw-1");
    }

    @Test
    void anEmptyNodeEntryFallsBackToTheGroupEntry() {
        store.put(NODE_USER, "").put(GROUP_USER, "group-user").put(GROUP_PW, "group-Pw-2");

        assertThat(secrets().current(LONG).username().orElseThrow().value())
            .isEqualTo("group-user");
    }

    @Test
    void aFailedNodeEntryDoesNotFallBack() {
        store.fail(NODE_USER, FailureKind.ACCESS_DENIED, "exit 128: denied")
            .put(GROUP_USER, "group-user").put(GROUP_PW, "group-Pw-2");

        ToolErrorException error = catchThrowableOfType(ToolErrorException.class,
            () -> secrets().current(LONG));

        assertThat(error.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.error().message()).contains("dev/node1", "macOS Keychain",
            "access denied for " + NODE_USER);
        // no further entry (and so no further access dialog) after the store refused one
        assertThat(store.reads()).extracting(FakeCredentialStore.Read::account)
            .containsExactly(NODE_USER);
    }

    @Test
    void anInvalidNodeEntryDoesNotFallBack() {
        store.put(NODE_PW, "abc").put(GROUP_PW, "group-Pw-2").put(GROUP_USER, "jdoe");

        ToolErrorException error = catchThrowableOfType(ToolErrorException.class,
            () -> secrets().current(LONG));

        assertThat(error.error().message()).contains("invalid value for " + NODE_PW,
            "shorter than 4").doesNotContain("abc ");
    }

    @Test
    void aMissingEntryNamesTheStoreBothEntriesAndTheCommand() {
        store.put(GROUP_USER, "jdoe");

        ToolErrorException error = catchThrowableOfType(ToolErrorException.class,
            () -> secrets().current(LONG));

        assertThat(error.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.error().message()).contains("dev/node1: INUBIT credentials from the"
            + " macOS Keychain are unavailable: not found for " + NODE_PW + " (or " + GROUP_PW
            + ")");
        assertThat(error.error().nextStep()).contains("security add-generic-password -U -s"
            + " inubit-mcp -a " + GROUP_PW + " -w");
    }

    @Test
    void theNextStepNamesTheCommandOfEachStore() {
        FakeCredentialStore linux = new FakeCredentialStore(StoreKind.SECRET_SERVICE);
        FakeCredentialStore windows = new FakeCredentialStore(StoreKind.CREDENTIAL_MANAGER);

        assertThat(catchThrowableOfType(ToolErrorException.class,
            () -> new NodeSecrets(config().build(), linux, scrubber, clock, LONG).current(LONG))
            .error().nextStep()).contains("secret-tool store --label='inubit-mcp " + GROUP_USER
                + "' service inubit-mcp account " + GROUP_USER);
        assertThat(catchThrowableOfType(ToolErrorException.class,
            () -> new NodeSecrets(config().build(), windows, scrubber, clock, LONG).current(LONG))
            .error().nextStep()).contains("cmdkey /generic:inubit-mcp:" + GROUP_USER);
    }

    @Test
    void aFailedReadIsReturnedAtOnceDuringTheBackOff() {
        NodeSecrets secrets = secrets();
        catchThrowableOfType(ToolErrorException.class, () -> secrets.current(LONG));
        int reads = store.reads().size();

        catchThrowableOfType(ToolErrorException.class, () -> secrets.current(LONG));
        assertThat(store.reads()).hasSize(reads);

        store.put(GROUP_USER, "jdoe").put(GROUP_PW, "group-Pw-2");
        clock.advance(NodeSecrets.BACK_OFF);
        assertThat(secrets.current(LONG).username().orElseThrow().value()).isEqualTo("jdoe");
    }

    @Test
    void noStoreIsAuthFailedNamingParameterStoreAsTheAlternative() {
        store.fail(NODE_USER, FailureKind.STORE_NOT_AVAILABLE, "no D-Bus");

        ToolErrorException error = catchThrowableOfType(ToolErrorException.class,
            () -> secrets().current(LONG));

        assertThat(error.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.error().message()).contains("no usable credential store");
        assertThat(error.error().nextStep()).contains("awsParameterStore");
        assertThat(store.reads()).hasSize(1);
    }

    @Test
    void aTimeoutIsUnreachable() {
        store.fail(NODE_USER, FailureKind.TIMEOUT, "security did not exit within 60 s");

        ToolErrorException error = catchThrowableOfType(ToolErrorException.class,
            () -> secrets().current(LONG));

        assertThat(error.error().code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(error.error().nextStep()).contains("access dialog");
    }

    @Test
    void theWholeReadIsBoundedByTheTimeout() {
        store.put(NODE_USER, "jdoe").delay(NODE_USER, Duration.ofMillis(300))
            .put(NODE_PW, "node-Pw-1");
        NodeSecrets secrets = new NodeSecrets(config().build(), store, scrubber, clock,
            Duration.ofMillis(200));

        ToolErrorException error = catchThrowableOfType(ToolErrorException.class,
            () -> secrets.current(LONG));

        assertThat(error.error().code()).isIn(ErrorCode.UNREACHABLE, ErrorCode.TIMEOUT);
    }

    @Test
    void theTrustStorePasswordIsReadOnlyWithATrustStore() {
        store.put(GROUP_USER, "jdoe").put(GROUP_PW, "group-Pw-2")
            .put("INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD", "ts-Pw-3");

        assertThat(secrets().current(LONG).trustStorePassword()).isEmpty();
        assertThat(secrets(config().trustStore(Path.of("/tmp/ts.p12")).build()).current(LONG)
            .trustStorePassword().orElseThrow().value().reveal()).isEqualTo("ts-Pw-3");
    }

    @Test
    void aTrustStorePasswordOnANodeWithACliIsRefused() {
        store.put(GROUP_USER, "jdoe").put(GROUP_PW, "group-Pw-2")
            .put("INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD", "ts-Pw-3");
        EffectiveNodeConfig config = config().trustStore(Path.of("/tmp/ts.p12"))
            .cliHome(Path.of("/opt/inubit/client")).build();

        ToolErrorException error = catchThrowableOfType(ToolErrorException.class,
            () -> secrets(config).current(LONG));

        assertThat(error.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.error().message()).contains("INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD",
            "CLI configured").doesNotContain("ts-Pw-3");
        assertThat(error.error().nextStep()).contains("Delete the entry");
    }

    @Test
    void withoutEndToEndEntriesThereIsNoBasicAuthentication() {
        assertThat(secrets().e2e(LONG)).isEmpty();
    }

    @Test
    void bothEndToEndEntriesAreTheBasicAuthentication() {
        store.put("INUBIT_ACME_DEV_E2E_USERNAME", "e2e-user")
            .put("INUBIT_ACME_DEV_NODE1_E2E_PASSWORD", "e2e-Pw-4");

        E2eCredentials e2e = secrets().e2e(LONG).orElseThrow();

        assertThat(e2e.username().value()).isEqualTo("e2e-user");
        assertThat(e2e.password().value().reveal()).isEqualTo("e2e-Pw-4");
        assertThat(scrubber.scrub("e2e-Pw-4")).doesNotContain("e2e-Pw-4");
    }

    @Test
    void onlyOneEndToEndEntryIsAnError() {
        store.put("INUBIT_ACME_DEV_E2E_USERNAME", "e2e-user");

        ToolErrorException error = catchThrowableOfType(ToolErrorException.class,
            () -> secrets().e2e(LONG));

        assertThat(error.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.error().message()).contains("INUBIT_ACME_DEV_NODE1_E2E_PASSWORD");
    }

    @Test
    void anEndToEndPasswordShorterThanFourIsRefused() {
        store.put("INUBIT_ACME_DEV_E2E_USERNAME", "e2e-user")
            .put("INUBIT_ACME_DEV_E2E_PASSWORD", "xyz");

        assertThat(catchThrowableOfType(ToolErrorException.class, () -> secrets().e2e(LONG))
            .error().message()).contains("shorter than 4");
    }

    @Test
    void endToEndEntriesOverPlainHttpAreRefused() {
        store.put("INUBIT_ACME_DEV_E2E_USERNAME", "e2e-user")
            .put("INUBIT_ACME_DEV_E2E_PASSWORD", "e2e-Pw-4");
        EffectiveNodeConfig config = config().development(new EffectiveNodeConfig.Development(
            true, ConfirmationMode.SERVER, E2ePolicy.FREE,
            Optional.of(URI.create("http://inubit-dev.example.test:8080")), false)).build();

        ToolErrorException error = catchThrowableOfType(ToolErrorException.class,
            () -> secrets(config).e2e(LONG));

        assertThat(error.error().message()).contains("http://");
    }

    @Test
    void theFirstCallReadsAtMostFourEntriesQuickly() {
        // SC-006: 25 ms per entry read, at most four reads without a trust store
        store.put(GROUP_USER, "jdoe").put(GROUP_PW, "group-Pw-2");
        for (String account : new String[] {NODE_USER, GROUP_USER, NODE_PW, GROUP_PW}) {
            store.delay(account, Duration.ofMillis(25));
        }
        NodeSecrets secrets = secrets();

        long start = System.nanoTime();
        secrets.current(LONG);
        Duration first = Duration.ofNanos(System.nanoTime() - start);
        start = System.nanoTime();
        secrets.current(LONG);
        Duration second = Duration.ofNanos(System.nanoTime() - start);

        assertThat(store.reads()).hasSize(4);
        assertThat(first).isLessThan(Duration.ofMillis(100 + 200));
        assertThat(second).isLessThan(Duration.ofMillis(20));
    }

    @Test
    void everyReadIsLoggedWithStoreAndEntriesButNoValue() {
        store.put(GROUP_USER, "jdoe").put(GROUP_PW, "group-Pw-2");

        secrets().current(LONG);

        ILoggingEvent event = appender.list.get(appender.list.size() - 1);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
            assertThat(pair.key).isEqualTo("source");
            assertThat(pair.value).isEqualTo("credentialStore");
        }).anySatisfy(pair -> {
            assertThat(pair.key).isEqualTo("store");
            assertThat(pair.value).isEqualTo("KEYCHAIN");
        }).anySatisfy(pair -> {
            assertThat(pair.key).isEqualTo("entries");
            assertThat((String) pair.value).contains(NODE_USER, GROUP_PW);
        });
        assertThat(event.getFormattedMessage()).contains("from the macOS Keychain")
            .doesNotContain("group-Pw-2");
    }

    @Test
    void aFailedReadIsLoggedAtWarnWithTheScrubbedDetail() {
        store.fail(NODE_USER, FailureKind.ACCESS_DENIED, "exit 51: denied");

        catchThrowableOfType(ToolErrorException.class, () -> secrets().current(LONG));

        ILoggingEvent event = appender.list.get(appender.list.size() - 1);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
            assertThat(pair.key).isEqualTo("outcome");
            assertThat(pair.value).isEqualTo("ACCESS_DENIED");
        });
    }

    @Test
    void aReReadFindsAChangedPassword() {
        store.put(GROUP_USER, "jdoe").put(GROUP_PW, "group-Pw-2");
        NodeSecrets secrets = secrets();
        secrets.current(LONG);

        assertThat(secrets.reread()).isEqualTo(NodeSecrets.Reread.UNCHANGED);
        store.put(GROUP_PW, "group-Pw-5");
        assertThat(secrets.reread()).isEqualTo(NodeSecrets.Reread.CHANGED);
        assertThat(secrets.held().orElseThrow().password().orElseThrow().value().reveal())
            .isEqualTo("group-Pw-5");
    }
}
