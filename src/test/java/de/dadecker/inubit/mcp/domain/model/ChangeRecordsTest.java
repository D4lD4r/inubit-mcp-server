package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** ChangePreview, ChangeResult and ChangeOutcome (feature 022, T048, data-model "Changes"). */
class ChangeRecordsTest {

    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final UUID AUDIT_ID = UUID.fromString("00000000-0000-0000-0000-000000000048");
    private static final String SECRET_ANSWER = "Done. " + TestSecrets.FREE_TEXT_SECRETS;

    @Test
    void outcomesAreExactlyTheThreeOfTheDataModel() {
        assertThat(ChangeOutcome.values()).extracting(Enum::name)
            .containsExactly("EXECUTED", "NOTHING_TO_DO", "FAILED");
    }

    @Test
    void affectedKeepsAtMostFiftyIdsAndMarksTheRestTruncated() {
        List<String> ids = IntStream.rangeClosed(1, 51).mapToObj(i -> "P-" + i).toList();

        ChangePreview.Affected affected = ChangePreview.Affected.of(ids);

        assertThat(affected.count()).isEqualTo(51);
        assertThat(affected.ids()).hasSize(ChangePreview.Affected.MAX_IDS)
            .isEqualTo(ids.subList(0, 50));
        assertThat(affected.truncated()).isTrue();
    }

    @Test
    void affectedWithFiftyIdsOrFewerIsNotTruncated() {
        List<String> ids = IntStream.rangeClosed(1, 50).mapToObj(i -> "P-" + i).toList();

        assertThat(ChangePreview.Affected.of(ids)).isEqualTo(
            new ChangePreview.Affected(50, ids, false));
        assertThat(ChangePreview.Affected.of(List.of()))
            .isEqualTo(new ChangePreview.Affected(0, List.of(), false));
    }

    @Test
    void affectedWithACountOnlyIsTruncated() {
        ChangePreview.Affected affected = new ChangePreview.Affected(7, List.of(), true);

        assertThat(affected.ids()).isEmpty();
        assertThat(affected.count()).isEqualTo(7);
    }

    @Test
    void affectedRejectsInconsistentValues() {
        List<String> fiftyOne = IntStream.rangeClosed(1, 51).mapToObj(i -> "P-" + i).toList();
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ChangePreview.Affected(51, fiftyOne, true));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ChangePreview.Affected(1, List.of("P-1", "P-2"), false));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ChangePreview.Affected(3, List.of("P-1"), false));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ChangePreview.Affected(1, List.of("P-1"), true));
    }

    @Test
    void previewMasksTheShownStates() {
        ChangePreview preview = preview(SECRET_ANSWER, SECRET_ANSWER, Optional.empty());

        TestSecrets.assertNoLeak(preview.before());
        TestSecrets.assertNoLeak(preview.requested());
        assertThat(preview.before()).startsWith("Done. password=");
    }

    @Test
    void previewKeepsAPlainStateAsShown() {
        ChangePreview preview = preview("maintenance off", "maintenance on", Optional.empty());

        assertThat(preview.before()).isEqualTo("maintenance off");
        assertThat(preview.requested()).isEqualTo("maintenance on");
    }

    @Test
    void previewHasAConfirmationOrNoneInClientMode() {
        Instant expiry = Instant.parse("2026-11-17T10:05:00Z");
        ChangePreview.Confirmation confirmation =
            new ChangePreview.Confirmation("AbCdEfGhIjKlMnOpQrStUv", expiry);

        assertThat(preview("off", "on", Optional.of(confirmation)).confirmation())
            .contains(confirmation);
        assertThat(preview("off", "on", null).confirmation()).isEmpty();
    }

    @Test
    void previewRequiresItsFields() {
        assertThatNullPointerException().isThrownBy(() -> new ChangePreview(null,
            "operate_server", "setMaintenance", "operation", "maintenance", "off", "on",
            Optional.empty(), List.of(), "none: nothing is stored", Optional.empty(), List.of()));
        assertThatNullPointerException().isThrownBy(() -> new ChangePreview(NODE,
            "operate_server", "setMaintenance", "operation", "maintenance", "off", "on",
            Optional.empty(), List.of(), null, Optional.empty(), List.of()));
    }

    @Test
    void resultMasksAndBoundsTheInubitAnswer() {
        String longAnswer = SECRET_ANSWER + " " + "x".repeat(3_000);

        ChangeResult result = executed(longAnswer, Optional.of("on"), Optional.empty());

        TestSecrets.assertNoLeak(result.inubitAnswer());
        assertThat(result.inubitAnswer()).hasSize(ChangeResult.MAX_ANSWER_CHARS)
            .startsWith("Done. password=").endsWith(ItemBounds.TRUNCATION_MARKER);
    }

    @Test
    void resultKeepsAShortPlainAnswer() {
        ChangeResult result = executed("Maintenance mode on", Optional.of("on"), Optional.empty());

        assertThat(result.inubitAnswer()).isEqualTo("Maintenance mode on");
    }

    @Test
    void resultAnswerOfExactlyTheBoundIsNotCut() {
        String answer = "y".repeat(ChangeResult.MAX_ANSWER_CHARS);

        assertThat(executed(answer, Optional.of("on"), Optional.empty()).inubitAnswer())
            .isEqualTo(answer);
    }

    @Test
    void resultCutNeverSplitsASurrogatePair() {
        int keep = ChangeResult.MAX_ANSWER_CHARS - ItemBounds.TRUNCATION_MARKER.length();
        String answer = "z".repeat(keep - 1) + "😀" + "z".repeat(100);

        String cut = executed(answer, Optional.of("on"), Optional.empty()).inubitAnswer();

        assertThat(cut).isEqualTo("z".repeat(keep - 1) + ItemBounds.TRUNCATION_MARKER);
    }

    @Test
    void resultMasksTheReReadStates() {
        ChangeResult result = new ChangeResult(NODE, "operate_server", "setMaintenance",
            "maintenance", ChangeOutcome.EXECUTED, "ok", SECRET_ANSWER,
            Optional.of(SECRET_ANSWER), Optional.empty(), OptionalInt.empty(), Optional.empty(),
            ChangeResult.RestartNeeded.FALSE, AUDIT_ID);

        TestSecrets.assertNoLeak(result.before());
        TestSecrets.assertNoLeak(result.after().orElseThrow());
    }

    @Test
    void resultHoldsAReReadFailureWithItsReasonInsteadOfTheAfterState() {
        ChangeResult result = executed("ok", Optional.empty(),
            Optional.of("maintenance -s timed out after 30 s"));

        assertThat(result.after()).isEmpty();
        assertThat(result.reReadFailed()).contains("maintenance -s timed out after 30 s");
    }

    @Test
    void resultMasksTheReReadFailureReason() {
        ChangeResult result = executed("ok", Optional.empty(), Optional.of(SECRET_ANSWER));

        TestSecrets.assertNoLeak(result.reReadFailed().orElseThrow());
    }

    @Test
    void resultHasEitherAnAfterStateOrAReReadFailure() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> executed("ok", Optional.empty(), Optional.empty()));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> executed("ok", Optional.of("on"), Optional.of("timed out")));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> executed("ok", Optional.empty(), Optional.of(" ")));
    }

    @Test
    void resultOfABulkActionCarriesTheReportedCountAndItsBackup() {
        ChangeResult.Backup backup = new ChangeResult.Backup(
            "/private/backups/test/node1/manifest.json", AUDIT_ID.toString());
        ChangeResult result = new ChangeResult(NODE, "operate_server", "deleteErrorProcesses",
            "bulk", ChangeOutcome.NOTHING_TO_DO, "0 processes", "", Optional.of("0 processes"),
            Optional.empty(), OptionalInt.of(0), Optional.of(backup),
            ChangeResult.RestartNeeded.UNKNOWN, AUDIT_ID);

        assertThat(result.affectedCount()).hasValue(0);
        assertThat(result.backup()).contains(backup);
        assertThat(result.restartNeeded()).isEqualTo(ChangeResult.RestartNeeded.UNKNOWN);
    }

    @Test
    void resultRequiresItsFields() {
        assertThatNullPointerException().isThrownBy(() -> new ChangeResult(NODE,
            "operate_server", "setMaintenance", "maintenance", null, "off", "ok",
            Optional.of("on"), Optional.empty(), OptionalInt.empty(), Optional.empty(),
            ChangeResult.RestartNeeded.FALSE, AUDIT_ID));
        assertThatNullPointerException().isThrownBy(() -> new ChangeResult(NODE,
            "operate_server", "setMaintenance", "maintenance", ChangeOutcome.EXECUTED, "off",
            "ok", Optional.of("on"), Optional.empty(), OptionalInt.empty(), Optional.empty(),
            ChangeResult.RestartNeeded.FALSE, null));
    }

    private static ChangePreview preview(String before, String requested,
        Optional<ChangePreview.Confirmation> confirmation) {
        return new ChangePreview(NODE, "operate_server", "setMaintenance", "operation",
            "maintenance", before, requested, Optional.empty(),
            List.of("No process runs while maintenance mode is on."),
            "none: nothing is stored for this action", confirmation, List.of());
    }

    private static ChangeResult executed(String answer, Optional<String> after,
        Optional<String> reReadFailed) {
        return new ChangeResult(NODE, "operate_server", "setMaintenance", "maintenance",
            ChangeOutcome.EXECUTED, "off", answer, after, reReadFailed, OptionalInt.empty(),
            Optional.empty(), ChangeResult.RestartNeeded.FALSE, AUDIT_ID);
    }
}
