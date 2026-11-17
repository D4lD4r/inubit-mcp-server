package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.ChangePreview;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Feature 022 (T051, research D-13, D-14): a change is confirmed with a server code bound to the
 * tool, the node and the fingerprint of the canonical inputs, and carrying the state the preview
 * showed; a state that changed since is {@code CONFLICT} with a fresh preview and code. {@code
 * CLIENT} confirmation is honoured only for {@code OPERATION}.
 */
class ChangeChallengesTest {

    private static final NodeId NODE1 = NodeId.parse("test/node1");
    private static final NodeId NODE2 = NodeId.parse("test/node2");
    private static final String TOOL = "operate_server";
    private static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String STATE = "maintenance=off";
    private static final Map<String, Object> INPUT = Map.of("node", "test/node1",
        "action", "maintenanceOn", "readyOff", false);

    private final MutableClock clock = new MutableClock(NOW);
    private final ChangeChallenges challenges =
        new ChangeChallenges(new WriteChallengeRegistry(clock));

    private static ToolError errorOf(Runnable call) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            call::run);
        assertThat(exception).as("expected a ToolErrorException").isNotNull();
        return exception.error();
    }

    private String issue() {
        return challenges.issue(TOOL, NODE1, ChangeChallenges.fingerprint(INPUT), STATE, TTL)
            .code();
    }

    // ---- fingerprint -----------------------------------------------------------------------

    @Test
    void theFingerprintIsTheSha256OfTheCanonicalInputs() {
        assertThat(ChangeChallenges.fingerprint(INPUT)).matches("[0-9a-f]{64}");
        assertThat(ChangeChallenges.fingerprint(INPUT))
            .isEqualTo(ChangeChallenges.fingerprint(Map.of("readyOff", false,
                "action", "maintenanceOn", "node", "test/node1")));
    }

    @Test
    void theFingerprintLeavesOutConfirmationCodeAndReason() {
        Map<String, Object> confirmed = new LinkedHashMap<>(INPUT);
        confirmed.put("confirmationCode", "AAAAAAAAAAAAAAAAAAAAAA");
        confirmed.put("reason", "planned maintenance window");

        assertThat(ChangeChallenges.fingerprint(confirmed))
            .isEqualTo(ChangeChallenges.fingerprint(INPUT));
    }

    @Test
    void theFingerprintSortsNestedMapsAndKeepsTheOrderOfLists() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("b", 2);
        nested.put("a", 1);
        Map<String, Object> sorted = new LinkedHashMap<>();
        sorted.put("a", 1);
        sorted.put("b", 2);

        assertThat(ChangeChallenges.fingerprint(Map.of("filter", nested)))
            .isEqualTo(ChangeChallenges.fingerprint(Map.of("filter", sorted)));
        assertThat(ChangeChallenges.fingerprint(Map.of("ids", List.of("p1", "p2"))))
            .isNotEqualTo(ChangeChallenges.fingerprint(Map.of("ids", List.of("p2", "p1"))));
    }

    @Test
    void theFingerprintTellsValuesApartThatPrintAlike() {
        assertThat(ChangeChallenges.fingerprint(Map.of("value", "1")))
            .isNotEqualTo(ChangeChallenges.fingerprint(Map.of("value", 1)));
        assertThat(ChangeChallenges.fingerprint(Map.of("a", "x\",\"b\":\"y")))
            .isNotEqualTo(ChangeChallenges.fingerprint(Map.of("a", "x", "b", "y")));
        assertThat(ChangeChallenges.fingerprint(Map.of("ids", List.of("a,b"))))
            .isNotEqualTo(ChangeChallenges.fingerprint(Map.of("ids", List.of("a", "b"))));
        assertThat(ChangeChallenges.fingerprint(singleNull()))
            .isNotEqualTo(ChangeChallenges.fingerprint(Map.of("value", "null")));
    }

    private static Map<String, Object> singleNull() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("value", null);
        return input;
    }

    // ---- issue and redeem ------------------------------------------------------------------

    @Test
    void theCodeOfThePreviewRedeemsToThePreviewState() {
        ChangePreview.Confirmation issued = challenges.issue(TOOL, NODE1,
            ChangeChallenges.fingerprint(INPUT), STATE, TTL);

        assertThat(issued.code()).matches("[A-Za-z0-9_-]{22}");
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(TTL));
        Map<String, Object> confirmed = new LinkedHashMap<>(INPUT);
        confirmed.put("confirmationCode", issued.code());
        confirmed.put("reason", "now with a reason");
        assertThat(challenges.redeem(issued.code(), TOOL, NODE1,
            ChangeChallenges.fingerprint(confirmed))).isEqualTo(STATE);
    }

    @Test
    void aCodeForAnotherToolIsConfirmationInvalid() {
        String code = issue();

        ToolError error = errorOf(() -> challenges.redeem(code, "manage_tags", NODE1,
            ChangeChallenges.fingerprint(INPUT)));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    @Test
    void aCodeForAnotherNodeIsConfirmationInvalid() {
        String code = issue();

        ToolError error = errorOf(() -> challenges.redeem(code, TOOL, NODE2,
            ChangeChallenges.fingerprint(INPUT)));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(error.node()).contains(NODE2);
    }

    @Test
    void aCodeForOtherInputsIsConfirmationInvalid() {
        String code = issue();
        Map<String, Object> other = new LinkedHashMap<>(INPUT);
        other.put("readyOff", true);

        ToolError error = errorOf(() -> challenges.redeem(code, TOOL, NODE1,
            ChangeChallenges.fingerprint(other)));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    /** A code whose preview could not be audited is withdrawn (T059). */
    @Test
    void aDiscardedCodeIsConfirmationInvalid() {
        String code = issue();

        challenges.discard(code);

        assertThat(errorOf(() -> challenges.redeem(code, TOOL, NODE1,
            ChangeChallenges.fingerprint(INPUT))).code())
            .isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    // ---- confirmOrConflict -----------------------------------------------------------------

    private static ChangePreview preview(String before) {
        return new ChangePreview(NODE1, TOOL, "maintenanceOn", "operation", "maintenance",
            before, "maintenance=on", Optional.empty(),
            List.of("New messages are not accepted while maintenance is on"), "none: no object",
            Optional.empty(), List.of());
    }

    @Test
    void anUnchangedStateIsConfirmed() {
        String fingerprint = ChangeChallenges.fingerprint(INPUT);
        String redeemed = challenges.redeem(issue(), TOOL, NODE1, fingerprint);

        challenges.confirmOrConflict(redeemed, STATE, fingerprint, preview(STATE), TTL);
    }

    @Test
    void aChangedStateIsConflictWithAFreshPreviewAndCode() {
        String fingerprint = ChangeChallenges.fingerprint(INPUT);
        String redeemed = challenges.redeem(issue(), TOOL, NODE1, fingerprint);
        String reread = "maintenance=on " + TestSecrets.CONFIGURED_PASSWORD;

        ChangeChallenges.Conflict conflict = catchThrowableOfType(
            ChangeChallenges.Conflict.class, () -> challenges.confirmOrConflict(redeemed, reread,
                fingerprint, preview("maintenance=on"), TTL));

        assertThat(conflict).isNotNull();
        ToolError error = conflict.error();
        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.node()).contains(NODE1);
        assertThat(error.message()).contains("maintenance").contains("test/node1")
            .contains("nothing was sent");
        ChangePreview fresh = conflict.preview();
        assertThat(fresh.before()).isEqualTo("maintenance=on");
        assertThat(fresh.confirmation()).isPresent();
        ChangePreview.Confirmation confirmation = fresh.confirmation().orElseThrow();
        assertThat(confirmation.expiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(error.nextStep()).contains(confirmation.expiresAt().toString());
        TestSecrets.assertNoLeak(error.toString());
        assertThat(error.toString()).doesNotContain(confirmation.code()).doesNotContain(STATE);

        assertThat(challenges.redeem(confirmation.code(), TOOL, NODE1, fingerprint))
            .as("the fresh code carries the re-read state").isEqualTo(reread);
    }

    @Test
    void theFreshCodeIsBoundToTheSameToolNodeAndInputs() {
        String fingerprint = ChangeChallenges.fingerprint(INPUT);
        String redeemed = challenges.redeem(issue(), TOOL, NODE1, fingerprint);
        String code = catchThrowableOfType(ChangeChallenges.Conflict.class,
            () -> challenges.confirmOrConflict(redeemed, "maintenance=on", fingerprint,
                preview("maintenance=on"), TTL)).preview().confirmation().orElseThrow().code();
        Map<String, Object> other = new LinkedHashMap<>(INPUT);
        other.put("readyOff", true);

        ToolError error = errorOf(() -> challenges.redeem(code, TOOL, NODE1,
            ChangeChallenges.fingerprint(other)));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    // ---- CLIENT confirmation ---------------------------------------------------------------

    private static WritePolicy policy(WritePolicy.Confirmation confirmation) {
        return new WritePolicy(NODE1, false, true, false, confirmation, TTL, Optional.empty());
    }

    @Test
    void clientConfirmationSkipsTheCodeOnlyForOperation() {
        assertThat(ChangeChallenges.needsCode(policy(WritePolicy.Confirmation.CLIENT),
            ActionSpec.Area.OPERATION)).isFalse();
        assertThat(ChangeChallenges.needsCode(policy(WritePolicy.Confirmation.SERVER),
            ActionSpec.Area.OPERATION)).isTrue();
    }

    @Test
    void administrationAreasIgnoreClientConfirmation() {
        Arrays.stream(ActionSpec.Area.values())
            .filter(area -> area != ActionSpec.Area.OPERATION)
            .forEach(area -> {
                assertThat(ChangeChallenges.needsCode(policy(WritePolicy.Confirmation.CLIENT),
                    area)).as(area.name()).isTrue();
                assertThat(ChangeChallenges.needsCode(policy(WritePolicy.Confirmation.SERVER),
                    area)).as(area.name()).isTrue();
            });
    }
}
