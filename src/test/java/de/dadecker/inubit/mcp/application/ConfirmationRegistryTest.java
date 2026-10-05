package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessAction;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** T102: server-side confirmation codes (FR-022, research R-13). */
class ConfirmationRegistryTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId QA = NodeId.parse("qa/node1");
    private static final Instant NOW = Instant.parse("2026-10-03T08:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final ConfirmationRegistry.Previewed PREVIEWED =
        new ConfirmationRegistry.Previewed(ProcessState.ERROR, "Error",
            Instant.parse("2026-10-03T07:00:00Z"), Optional.of("W"), Optional.of("M"));

    private final MutableClock clock = new MutableClock(NOW);
    private final ConfirmationRegistry registry = new ConfirmationRegistry(clock);

    private static ToolError errorOf(Executable call) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class, () -> {
            try {
                call.execute();
            } catch (ToolErrorException e) {
                throw e;
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
        assertThat(exception).as("expected a ToolErrorException").isNotNull();
        return exception.error();
    }

    @Test
    void theCodeIs22UrlSafeBase64CharsOf16RandomBytes() {
        byte[] bytes = new byte[16];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (0xF0 + i); // high bits produce '-' and '_' in URL-safe Base64
        }
        List<Integer> requested = new ArrayList<>();
        SecureRandom fixed = new SecureRandom() {
            private static final long serialVersionUID = 1L;

            @Override
            public void nextBytes(byte[] target) {
                requested.add(target.length);
                System.arraycopy(bytes, 0, target, 0, target.length);
            }
        };

        String code = new ConfirmationRegistry(clock, fixed)
            .issue(DEV, ProcessAction.RESTART, "4711", PREVIEWED, TTL).code();

        assertThat(requested).containsExactly(16);
        assertThat(code).hasSize(22).matches("^[A-Za-z0-9_-]{22}$");
        assertThat(code).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        assertThat(Base64.getUrlDecoder().decode(code)).isEqualTo(bytes);
    }

    @Test
    void codesAreRandomAndUnique() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            codes.add(registry.issue(DEV, ProcessAction.RESTART, "4711", PREVIEWED, TTL).code());
        }

        assertThat(codes).hasSize(200).allMatch(code -> code.matches("^[A-Za-z0-9_-]{22}$"));
    }

    @Test
    void theExpiryIsIssuedAtPlusTtl() {
        assertThat(registry.issue(DEV, ProcessAction.KILL, "4711", PREVIEWED, Duration.ofMinutes(2))
            .expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
    }

    @Test
    void redemptionReturnsThePreviewedStateOfTheInstance() {
        String code = registry.issue(DEV, ProcessAction.KILL, "4711", PREVIEWED, TTL).code();

        assertThat(registry.redeem(code, DEV, ProcessAction.KILL, "4711")).isEqualTo(PREVIEWED);
    }

    @Test
    void aMatchingCodeIsRedeemedOnce() {
        String code = registry.issue(DEV, ProcessAction.RESTART, "4711", PREVIEWED, TTL).code();

        registry.redeem(code, DEV, ProcessAction.RESTART, "4711");
        ToolError second = errorOf(() -> registry.redeem(code, DEV, ProcessAction.RESTART,
            "4711"));

        assertThat(second.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(registry.pending()).isZero();
    }

    @Test
    void anotherServerIsAMismatch() {
        String code = registry.issue(DEV, ProcessAction.RESTART, "4711", PREVIEWED, TTL).code();

        assertThat(errorOf(() -> registry.redeem(code, QA, ProcessAction.RESTART, "4711"))
            .code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    @Test
    void anotherActionIsAMismatch() {
        String code = registry.issue(DEV, ProcessAction.RESTART, "4711", PREVIEWED, TTL).code();

        assertThat(errorOf(() -> registry.redeem(code, DEV, ProcessAction.KILL, "4711"))
            .code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    @Test
    void anotherProcessIsAMismatch() {
        String code = registry.issue(DEV, ProcessAction.RESTART, "4711", PREVIEWED, TTL).code();

        ToolError error = errorOf(() -> registry.redeem(code, DEV, ProcessAction.RESTART,
            "4712"));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(error.message()).doesNotContain(code);
    }

    @Test
    void aMismatchConsumesTheCodeSoThatItCannotBeRetried() {
        String code = registry.issue(DEV, ProcessAction.RESTART, "4711", PREVIEWED, TTL).code();

        errorOf(() -> registry.redeem(code, DEV, ProcessAction.RESTART, "4712"));

        assertThat(errorOf(() -> registry.redeem(code, DEV, ProcessAction.RESTART, "4711"))
            .code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    @Test
    void anUnknownCodeIsInvalid() {
        assertThat(errorOf(() -> registry.redeem("AAAAAAAAAAAAAAAAAAAAAA", DEV,
            ProcessAction.RESTART, "4711")).code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    @Test
    void aCodeIsValidUpToItsExpiryAndInvalidAfterwards() {
        String justInTime = registry.issue(DEV, ProcessAction.RESTART, "1", PREVIEWED, TTL).code();
        String late = registry.issue(DEV, ProcessAction.RESTART, "2", PREVIEWED, TTL).code();

        clock.advance(TTL);
        registry.redeem(justInTime, DEV, ProcessAction.RESTART, "1");
        clock.advance(Duration.ofMillis(1));
        ToolError expired = errorOf(() -> registry.redeem(late, DEV, ProcessAction.RESTART,
            "2"));

        assertThat(expired.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(expired.message()).contains("expired");
    }

    @Test
    void theDefaultTtlOfFiveMinutesExpires() {
        String code = registry
            .issue(DEV, ProcessAction.KILL, "4711", PREVIEWED, Duration.ofMinutes(5))
            .code();

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(errorOf(() -> registry.redeem(code, DEV, ProcessAction.KILL, "4711"))
            .code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    @Test
    void expiredEntriesAreSweptLazily() {
        registry.issue(DEV, ProcessAction.RESTART, "1", PREVIEWED, TTL);
        registry.issue(DEV, ProcessAction.RESTART, "2", PREVIEWED, TTL);
        clock.advance(TTL.plusSeconds(1));
        registry.issue(DEV, ProcessAction.RESTART, "3", PREVIEWED, TTL);

        assertThat(registry.pending()).isEqualTo(1);
    }

    @Test
    void aDiscardedCodeCannotBeRedeemed() {
        String code = registry.issue(DEV, ProcessAction.RESTART, "4711", PREVIEWED, TTL).code();

        registry.discard(code);

        assertThat(errorOf(() -> registry.redeem(code, DEV, ProcessAction.RESTART, "4711"))
            .code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    @Test
    void concurrentRedemptionsOfOneCodeSucceedExactlyOnce() throws InterruptedException {
        String code = registry.issue(DEV, ProcessAction.RESTART, "4711", PREVIEWED, TTL).code();
        AtomicInteger redeemed = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    registry.redeem(code, DEV, ProcessAction.RESTART, "4711");
                    redeemed.incrementAndGet();
                } catch (ToolErrorException e) {
                    // the others are refused
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(redeemed).hasValue(1);
    }

    // --- Phase 6 review W5: bounded number of pending codes -----------------------------------

    @Test
    void atMost1000CodesArePendingAndFurtherPreviewsAreRefused() {
        for (int i = 0; i < ConfirmationRegistry.MAX_PENDING; i++) {
            registry.issue(DEV, ProcessAction.RESTART, String.valueOf(i + 1), PREVIEWED, TTL);
        }

        ToolError error = errorOf(() -> registry.issue(DEV, ProcessAction.RESTART, "4711",
            PREVIEWED, TTL));

        assertThat(ConfirmationRegistry.MAX_PENDING).isEqualTo(1000);
        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains("too many pending confirmations");
        assertThat(error.node()).contains(DEV);
        assertThat(registry.pending()).isEqualTo(1000);
    }

    @Test
    void expiredCodesAreSweptBeforeTheLimitIsChecked() {
        for (int i = 0; i < ConfirmationRegistry.MAX_PENDING; i++) {
            registry.issue(DEV, ProcessAction.RESTART, String.valueOf(i + 1), PREVIEWED, TTL);
        }
        clock.advance(TTL.plusSeconds(1));

        registry.issue(DEV, ProcessAction.RESTART, "4711", PREVIEWED, TTL);

        assertThat(registry.pending()).isEqualTo(1);
    }

    @Test
    void theRefusalSaysHowToGetANewCode() {
        ToolError error = errorOf(() -> registry.redeem("AAAAAAAAAAAAAAAAAAAAAA", DEV,
            ProcessAction.KILL, "4711"));

        assertThat(error.nextStep()).contains("kill_process").contains("without confirmationCode");
        assertThat(error.node()).contains(DEV);
    }
}
