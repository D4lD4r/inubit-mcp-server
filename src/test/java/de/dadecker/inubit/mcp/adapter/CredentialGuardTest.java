package de.dadecker.inubit.mcp.adapter;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.environmentLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.NodeSecrets.Reread;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.ParameterNames;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.config.StoreLocation;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Phase 3 review M2: the per-server credential circuit breaker against account lockout. At most
 * one failed login per server per {@link CredentialGuard#PAUSE} (60 s).
 */
@Timeout(30)
class CredentialGuardTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T08:00:00Z"));
    private final CredentialGuard guard =
        new CredentialGuard(environmentLabels("INUBIT_ACME", DEV), clock);

    private void reject() {
        try (CredentialGuard.Permit permit = guard.acquire()) {
            permit.rejected();
        }
    }

    @Test
    void afterARejectionEveryAttemptIsRefusedForSixtySecondsWithACachedAuthFailed() {
        reject();

        clock.advance(Duration.ofSeconds(18));
        assertThatThrownBy(guard::acquire).isInstanceOfSatisfying(ToolErrorException.class,
            e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().message()).contains("dev/node1",
                    "authentication failed recently", "not retried for 42 s",
                    "avoid account lockout");
                // 002 research D-6: the variables under the profile's effective prefix;
                // unchanged by feature 010 for environment nodes
                assertThat(e.error().nextStep()).isEqualTo("Fix INUBIT_ACME_DEV_NODE1_PASSWORD"
                    + " (or INUBIT_ACME_DEV_PASSWORD) and _USERNAME, then restart the MCP client"
                    + " from a shell with the new values");
            });

        clock.advance(Duration.ofSeconds(41));
        assertThatThrownBy(guard::acquire).isInstanceOf(ToolErrorException.class);

        clock.advance(Duration.ofSeconds(1));
        try (CredentialGuard.Permit permit = guard.acquire()) {
            permit.accepted();
        }
        assertThat(guard.confirmed()).isTrue();
    }

    private static SecretLabels awsLabels() {
        return new SecretLabels.Aws(DEV,
            new StoreLocation("eu-central-1", Optional.of("inubit")), new ParameterNames(
                Optional.of("/inubit/dev/node1/username"),
                Optional.of("/inubit/dev/node1/password"), Optional.empty(), Optional.empty(),
                Optional.empty()));
    }

    @Test
    void theCachedAuthFailedOfAnAwsNodeNamesItsParametersAndNeedsNoRestart() {
        // feature 010 (T021, research R-10/R-15, FR-008, contracts/mcp-tools-delta.md)
        CredentialGuard aws = new CredentialGuard(awsLabels(), clock);
        try (CredentialGuard.Permit permit = aws.acquire()) {
            permit.rejected();
        }

        assertThatThrownBy(aws::acquire).isInstanceOfSatisfying(ToolErrorException.class,
            e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().nextStep()).isEqualTo("Correct the parameter value(s)"
                    + " /inubit/dev/node1/username, /inubit/dev/node1/password in Parameter Store"
                    + " (eu-central-1, profile inubit); no restart is needed")
                    .doesNotContain("INUBIT_");
            });
        // the direct AUTH_FAILED of REST (HTTP 401) and StartCLI uses the same next step
        assertThat(aws.fixCredentials())
            .contains("/inubit/dev/node1/password", "no restart is needed")
            .doesNotContain("INUBIT_", "restart the MCP client");
    }

    @Test
    void credentialsAreConfirmedByAnAcceptedAttemptOnly() {
        assertThat(guard.confirmed()).isFalse();
        try (CredentialGuard.Permit permit = guard.acquire()) {
            // neither accepted nor rejected, e.g. a timeout
            assertThat(permit).isNotNull();
        }
        assertThat(guard.confirmed()).isFalse();

        try (CredentialGuard.Permit permit = guard.acquire()) {
            permit.accepted();
        }
        assertThat(guard.confirmed()).isTrue();

        reject();
        assertThat(guard.confirmed()).isFalse();
    }

    @Test
    void whileUnconfirmedOnlyOneAttemptIsInFlight() throws Exception {
        CredentialGuard.Permit first = guard.acquire();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        CompletableFuture<Throwable> second = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            try (CredentialGuard.Permit permit = guard.acquire()) {
                attempts.incrementAndGet();
                return null;
            } catch (ToolErrorException e) {
                return e;
            }
        });
        started.await();
        Thread.sleep(200);
        assertThat(attempts).as("the second attempt waits for the first").hasValue(0);

        first.rejected();
        first.close();

        Throwable outcome = second.get(5, TimeUnit.SECONDS);
        assertThat(outcome).as("the waiting attempt gets the cached failure, no second login")
            .isInstanceOf(ToolErrorException.class);
        assertThat(attempts).hasValue(0);
    }

    @Test
    void confirmedCredentialsAllowParallelAttempts() throws Exception {
        try (CredentialGuard.Permit permit = guard.acquire()) {
            permit.accepted();
        }
        CredentialGuard.Permit first = guard.acquire();
        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> {
            try (CredentialGuard.Permit permit = guard.acquire()) {
                return true;
            }
        });

        assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
        first.close();
    }

    @Test
    void aWaitingAttemptProceedsInParallelOnceTheFirstIsAccepted() throws Exception {
        CredentialGuard.Permit first = guard.acquire();
        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> {
            try (CredentialGuard.Permit permit = guard.acquire()) {
                return guard.confirmed();
            }
        });
        Thread.sleep(100);
        first.accepted();
        first.close();

        assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
    }

    // --- feature 010 T016: re-read on rejection (R-10, FR-010, data-model.md → CredentialGuard) --

    /**
     * The re-read hook of an AWS node: {@link #held()} answers the credentials held now,
     * {@link #reread()} answers {@link #next} (after {@link #gate} opens) and, for
     * {@code CHANGED}, holds new credentials first, as {@link NodeSecrets#reread()} does.
     */
    private static final class FakeSecrets implements CredentialGuard.Rereadable {

        private final AtomicInteger rereads = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private volatile CountDownLatch gate = new CountDownLatch(0);
        private volatile Reread next = Reread.UNCHANGED;
        private volatile NodeCredentials held = credentials(0);

        private static NodeCredentials credentials(int generation) {
            return new NodeCredentials(DEV,
                Optional.of(new SourcedValue<>("test-user", "/inubit/dev/node1/username")),
                Optional.of(new SourcedValue<>(Secret.of("test-password-" + generation),
                    "/inubit/dev/node1/password")),
                Optional.empty(), Optional.empty());
        }

        @Override
        public Reread reread() {
            int count = rereads.incrementAndGet();
            entered.countDown();
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (next == Reread.CHANGED) {
                held = credentials(count);
            }
            return next;
        }

        @Override
        public Optional<NodeCredentials> held() {
            return Optional.of(held);
        }
    }

    private final FakeSecrets secrets = new FakeSecrets();
    private final CredentialGuard rereading = new CredentialGuard(awsLabels(), clock, secrets);

    /** The guard as a client built with the credentials held now sees it. */
    private CredentialGuard client() {
        return rereading.bind(secrets.held);
    }

    private static void reject(CredentialGuard guard) {
        try (CredentialGuard.Permit permit = guard.acquire()) {
            permit.rejected();
        }
    }

    private static void assertPaused(CredentialGuard guard, String remaining) {
        assertThatThrownBy(guard::acquire).isInstanceOfSatisfying(ToolErrorException.class,
            e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
                assertThat(e.error().message()).contains("not retried for " + remaining,
                    "avoid account lockout");
            });
    }

    private static void assertReplaced(CredentialGuard guard) {
        assertThatThrownBy(guard::acquire).isInstanceOfSatisfying(ToolErrorException.class,
            e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().message()).contains("dev/node1", "replaced",
                    "AWS Parameter Store");
                assertThat(e.error().nextStep()).contains("Retry");
            });
    }

    @Test
    void changedCredentialsAllowOneFurtherAttemptWithoutPauseThenARejectionPauses() {
        secrets.next = Reread.CHANGED;
        CredentialGuard old = client();

        reject(old);

        assertThat(secrets.rereads).hasValue(1);
        assertReplaced(old); // a caller holding the old client does not log in with old values
        CredentialGuard rebuilt = client();
        assertThat(rebuilt.confirmed()).isFalse();

        reject(rebuilt); // the one further attempt, at once: no pause in between

        assertThat(secrets.rereads).as("no re-read for the grace attempt").hasValue(1);
        assertPaused(rebuilt, "60 s");
        clock.advance(Duration.ofSeconds(59));
        assertPaused(rebuilt, "1 s");
        clock.advance(Duration.ofSeconds(1));
        try (CredentialGuard.Permit permit = rebuilt.acquire()) {
            permit.accepted();
        }
        assertThat(rereading.confirmed()).isTrue();
    }

    @Test
    void anAcceptedGraceAttemptConfirmsAndALaterRejectionReReadsAgain() {
        secrets.next = Reread.CHANGED;
        reject(client());

        try (CredentialGuard.Permit permit = client().acquire()) {
            permit.accepted();
        }

        assertThat(rereading.confirmed()).isTrue();
        reject(client());
        assertThat(secrets.rereads).hasValue(2);
    }

    @ParameterizedTest
    @EnumSource(value = Reread.class, names = {"UNCHANGED", "FAILED"})
    void unchangedOrFailedReReadPausesAsToday(Reread outcome) {
        secrets.next = outcome;
        CredentialGuard same = client();

        reject(same);

        assertThat(secrets.rereads).hasValue(1);
        assertPaused(same, "60 s");
        assertPaused(client(), "60 s");
        clock.advance(Duration.ofSeconds(60));
        reject(same);
        assertThat(secrets.rereads).as("the next rejection after the pause re-reads again")
            .hasValue(2);
    }

    @Test
    void atMostTwoFailedLoginsPerSixtySeconds() {
        secrets.next = Reread.CHANGED; // the worst case: every re-read finds new values
        List<Instant> failedLogins = new ArrayList<>();
        for (int second = 0; second < 300; second++) {
            try (CredentialGuard.Permit permit = client().acquire()) {
                failedLogins.add(clock.instant());
                permit.rejected();
            } catch (ToolErrorException refused) {
                // paused: no login
            }
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(failedLogins).hasSizeGreaterThanOrEqualTo(4);
        for (Instant login : failedLogins) {
            assertThat(failedLogins.stream().filter(other -> !other.isBefore(login)
                && other.isBefore(login.plus(CredentialGuard.PAUSE))))
                .as("failed logins in the 60 s from %s", login).hasSizeLessThanOrEqualTo(2);
        }
    }

    @Test
    void concurrentRejectionsShareOneReReadAndWaitingAttemptsAwaitIt() throws Exception {
        try (CredentialGuard.Permit permit = client().acquire()) {
            permit.accepted();
        }
        CredentialGuard old = client();
        // confirmed: three attempts with the old values are in flight at the same time
        CredentialGuard.Permit first = old.acquire();
        CredentialGuard.Permit second = old.acquire();
        CredentialGuard.Permit third = old.acquire();
        secrets.next = Reread.CHANGED;
        secrets.gate = new CountDownLatch(1);

        CompletableFuture<Void> firstRejection = CompletableFuture.runAsync(first::rejected);
        assertThat(secrets.entered.await(5, TimeUnit.SECONDS)).isTrue();
        second.rejected(); // shares the running re-read and returns at once
        CompletableFuture<Throwable> waiting = CompletableFuture.supplyAsync(() -> {
            try (CredentialGuard.Permit permit = old.acquire()) {
                return null;
            } catch (ToolErrorException e) {
                return e;
            }
        });
        Thread.sleep(200);
        assertThat(waiting).as("a new attempt waits for the re-read").isNotDone();

        secrets.gate.countDown();
        firstRejection.get(5, TimeUnit.SECONDS);

        assertThat(waiting.get(5, TimeUnit.SECONDS)).as("then is refused: old values")
            .isInstanceOf(ToolErrorException.class);
        third.accepted(); // an attempt from before the re-read says nothing about the new values
        third.rejected(); // nor does its rejection: shared with the re-read, grace kept
        first.close();
        second.close();
        third.close();
        assertThat(secrets.rereads).hasValue(1);
        assertThat(rereading.confirmed()).isFalse();
        try (CredentialGuard.Permit permit = client().acquire()) {
            assertThat(permit).as("the grace attempt is still allowed").isNotNull();
        }
    }
}
