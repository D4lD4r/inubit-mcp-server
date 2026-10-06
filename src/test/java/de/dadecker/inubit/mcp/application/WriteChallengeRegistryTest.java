package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.application.DevelopmentGuard.Capability;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * T006 (feature 004, research D-2): confirmation codes of the development tools, bound to
 * capability, node, input fingerprint and the previewed state.
 */
class WriteChallengeRegistryTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId QA = NodeId.parse("qa/node1");
    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String INPUTS = "sha256:inputs-1";
    private static final String STATE = "base=abc;changeSet=def;server=123";

    private final MutableClock clock = new MutableClock(NOW);
    private final WriteChallengeRegistry registry = new WriteChallengeRegistry(clock);

    private String issue() {
        return registry.issue(Capability.IMPORT_ARTIFACTS, DEV, INPUTS, STATE, TTL).code();
    }

    private static ToolError errorOf(Runnable call) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            call::run);
        assertThat(exception).as("expected a ToolErrorException").isNotNull();
        return exception.error();
    }

    @Test
    void theCodeIs22UrlSafeBase64CharsOf16RandomBytes() {
        byte[] bytes = new byte[16];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (0xF0 + i);
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

        String code = new WriteChallengeRegistry(clock, fixed)
            .issue(Capability.TAG_ARTIFACTS, DEV, INPUTS, STATE, TTL).code();

        assertThat(requested).containsExactly(16);
        assertThat(code).hasSize(22).matches("^[A-Za-z0-9_-]{22}$")
            .isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    @Test
    void codesAreRandomAndUnique() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            codes.add(issue());
        }

        assertThat(codes).hasSize(200);
    }

    @Test
    void theExpiryIsIssuedAtPlusTtl() {
        assertThat(registry.issue(Capability.SET_ACTIVE, DEV, INPUTS, STATE,
            Duration.ofMinutes(2)).expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
    }

    @Test
    void redemptionReturnsThePreviewedStateOnce() {
        String code = issue();

        assertThat(registry.redeem(code, Capability.IMPORT_ARTIFACTS, DEV, INPUTS))
            .isEqualTo(STATE);
        ToolError second = errorOf(() -> registry.redeem(code, Capability.IMPORT_ARTIFACTS,
            DEV, INPUTS));

        assertThat(second.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(registry.pending()).isZero();
    }

    @Test
    void theCallerDecidesOnAChangedPreviewState() {
        String code = issue();

        String previewed = registry.redeem(code, Capability.IMPORT_ARTIFACTS, DEV, INPUTS);

        // the registry hands the previewed state back; the service compares it with a fresh
        // conflict check and refuses with CONFLICT if the server changed (research D-2)
        assertThat(previewed).isEqualTo(STATE).isNotEqualTo("base=abc;changeSet=def;server=999");
    }

    @Test
    void otherInputsAreInvalid() {
        String code = issue();

        ToolError error = errorOf(() -> registry.redeem(code, Capability.IMPORT_ARTIFACTS, DEV,
            "sha256:inputs-2"));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(error.message()).contains("other inputs").doesNotContain(code);
        assertThat(error.node()).contains(DEV);
    }

    @Test
    void anotherCapabilityIsInvalid() {
        String code = issue();

        assertThat(errorOf(() -> registry.redeem(code, Capability.RESTORE_BACKUP, DEV, INPUTS))
            .code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    @Test
    void anotherNodeIsInvalid() {
        String code = issue();

        assertThat(errorOf(() -> registry.redeem(code, Capability.IMPORT_ARTIFACTS, QA, INPUTS))
            .code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    @Test
    void aMismatchConsumesTheCode() {
        String code = issue();

        errorOf(() -> registry.redeem(code, Capability.IMPORT_ARTIFACTS, DEV, "other"));

        assertThat(errorOf(() -> registry.redeem(code, Capability.IMPORT_ARTIFACTS, DEV,
            INPUTS)).code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    @Test
    void anUnknownOrMissingCodeIsInvalid() {
        assertThat(errorOf(() -> registry.redeem("AAAAAAAAAAAAAAAAAAAAAA",
            Capability.IMPORT_ARTIFACTS, DEV, INPUTS)).code())
            .isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(errorOf(() -> registry.redeem(null, Capability.IMPORT_ARTIFACTS, DEV,
            INPUTS)).code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    @Test
    void aCodeIsValidUpToItsExpiryAndInvalidAfterwards() {
        String justInTime = issue();
        String late = issue();

        clock.advance(TTL);
        registry.redeem(justInTime, Capability.IMPORT_ARTIFACTS, DEV, INPUTS);
        clock.advance(Duration.ofMillis(1));
        ToolError expired = errorOf(() -> registry.redeem(late, Capability.IMPORT_ARTIFACTS,
            DEV, INPUTS));

        assertThat(expired.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(expired.message()).contains("expired");
    }

    @Test
    void expiredEntriesAreSwept() {
        issue();
        issue();
        clock.advance(TTL.plusSeconds(1));
        issue();

        assertThat(registry.pending()).isEqualTo(1);
    }

    @Test
    void aDiscardedCodeCannotBeRedeemed() {
        String code = issue();

        registry.discard(code);

        assertThat(errorOf(() -> registry.redeem(code, Capability.IMPORT_ARTIFACTS, DEV,
            INPUTS)).code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    @Test
    void concurrentRedemptionsOfOneCodeSucceedExactlyOnce() throws InterruptedException {
        String code = issue();
        AtomicInteger redeemed = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    registry.redeem(code, Capability.IMPORT_ARTIFACTS, DEV, INPUTS);
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

    @Test
    void atMost1000CodesArePending() {
        for (int i = 0; i < WriteChallengeRegistry.MAX_PENDING; i++) {
            issue();
        }

        ToolError error = errorOf(this::issue);

        assertThat(WriteChallengeRegistry.MAX_PENDING).isEqualTo(1000);
        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains("too many pending confirmations");
        assertThat(error.node()).contains(DEV);
        clock.advance(TTL.plusSeconds(1));
        issue();
        assertThat(registry.pending()).isEqualTo(1);
    }

    @Test
    void theRefusalSaysHowToGetANewCode() {
        ToolError error = errorOf(() -> registry.redeem("AAAAAAAAAAAAAAAAAAAAAA",
            Capability.TAG_ARTIFACTS, DEV, INPUTS));

        assertThat(error.nextStep()).contains("tag_artifacts", "without confirmationCode");
        assertThat(error.message()).contains("nothing was sent");
    }

    @Test
    void theStateAndInputsAreNeverInTheErrors() {
        String code = issue();
        clock.advance(TTL.plusSeconds(1));

        ToolError error = errorOf(() -> registry.redeem(code, Capability.IMPORT_ARTIFACTS, DEV,
            INPUTS));

        assertThat(error.toString()).doesNotContain(STATE).doesNotContain(INPUTS)
            .doesNotContain(code);
    }
}
