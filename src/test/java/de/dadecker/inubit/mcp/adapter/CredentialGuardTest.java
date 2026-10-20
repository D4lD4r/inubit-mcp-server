package de.dadecker.inubit.mcp.adapter;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.environmentLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.config.ParameterNames;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.config.StoreLocation;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

    @Test
    void theCachedAuthFailedOfAnAwsNodeNamesItsParametersAndNeedsNoRestart() {
        // feature 010 (T021, research R-10/R-15, FR-008, contracts/mcp-tools-delta.md)
        CredentialGuard aws = new CredentialGuard(new SecretLabels.Aws(DEV,
            new StoreLocation("eu-central-1", Optional.of("inubit")), new ParameterNames(
                Optional.of("/inubit/dev/node1/username"),
                Optional.of("/inubit/dev/node1/password"), Optional.empty(), Optional.empty(),
                Optional.empty())), clock);
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
}
