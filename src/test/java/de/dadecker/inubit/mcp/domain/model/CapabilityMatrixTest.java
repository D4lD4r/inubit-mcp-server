package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.dadecker.inubit.mcp.domain.model.Capability.Kind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;
import org.junit.jupiter.params.provider.ValueSource;

class CapabilityMatrixTest {

    private final CapabilityMatrix production = CapabilityMatrix.production();

    @ParameterizedTest
    @EnumSource(Capability.class)
    void everyCapabilityIsSupportedOn81(Capability capability) {
        assertThat(production.state(version("8.1.17"), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.state(version("8.1"), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.stateOfLine(8, OptionalInt.of(1), capability))
            .isEqualTo(SupportState.SUPPORTED);
    }

    /** Feature 017 (T004, research D-9): 9.0 is declared like 8.1, REST and CLI. */
    @ParameterizedTest
    @EnumSource(value = Capability.class, names = "QUEUE_ENTRY_READ", mode = Mode.EXCLUDE)
    void everyCapabilityIsSupportedOn90LikeOn81(Capability capability) {
        assertThat(production.state(version("9.0.7"), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.state(version("9.0"), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.stateOfLine(9, OptionalInt.of(0), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.channels(version("9.0.7"), capability))
            .isEqualTo(production.channels(version("8.1.17"), capability))
            .isEqualTo(capability.channels());
        assertThat(production.isCliOnly(version("9.0.7"))).isFalse();
    }

    /** Feature 017 (T004, research D-9): {@code V9_X} still selects no 9.0 support. */
    @ParameterizedTest
    @EnumSource(value = Capability.class, names = "QUEUE_ENTRY_READ", mode = Mode.EXCLUDE)
    void theMajorOnlyNineLineAndNineOneStayUnverified(Capability capability) {
        assertThat(production.stateOfLine(9, OptionalInt.empty(), capability))
            .isEqualTo(notBaseline(capability));
        assertThat(production.state(version("9.1.0"), capability))
            .isEqualTo(notBaseline(capability));
    }

    /** Feature 017 (T004): which major.minor values have an entry of their own. */
    @Test
    void declaresExactlyTheVersionsWithAnEntry() {
        assertThat(production.declares(new MajorMinor(6, 1))).isTrue();
        assertThat(production.declares(new MajorMinor(8, 1))).isTrue();
        assertThat(production.declares(new MajorMinor(9, 0))).isTrue();
        assertThat(production.declares(new MajorMinor(9, 1))).isFalse();
        assertThat(production.declares(new MajorMinor(7, 0))).isFalse();
        assertThat(production.declares(new MajorMinor(8, 0))).isFalse();
        assertThat(new CapabilityMatrix(List.of(new CapabilityMatrix.Entry(
                new VersionRange(7, 8, 7, 9), Map.of())), Map.of())
            .declares(new MajorMinor(7, 9)))
            .as("a range declares every major.minor within its bounds").isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Capability.class, names = "QUEUE_ENTRY_READ", mode = Mode.EXCLUDE)
    void anOtherKnownVersionIsTreatedLikeUnknown(Capability capability) {
        assertThat(production.state(version("10.0"), capability))
            .isEqualTo(notBaseline(capability));
        assertThat(production.state(version("8.2.0"), capability))
            .isEqualTo(notBaseline(capability));
        assertThat(production.state(version("8.0.9"), capability))
            .isEqualTo(notBaseline(capability));
    }

    @ParameterizedTest
    @EnumSource(value = Capability.class, names = "QUEUE_ENTRY_READ", mode = Mode.EXCLUDE)
    void anUnknownVersionGivesReadsUnverifiedAndWritesUnsupported(Capability capability) {
        assertThat(production.state(Optional.empty(), capability))
            .isEqualTo(notBaseline(capability));
    }

    @ParameterizedTest
    @EnumSource(value = Capability.class, names = "QUEUE_ENTRY_READ", mode = Mode.EXCLUDE)
    void aMajorOnlyLineIsLookedUpAsUnknown(Capability capability) {
        assertThat(production.stateOfLine(9, OptionalInt.empty(), capability))
            .isEqualTo(production.state(Optional.empty(), capability))
            .isEqualTo(production.state(version("9.1.0"), capability));
        assertThat(production.stateOfLine(8, OptionalInt.empty(), capability))
            .as("a major-only line is unknown even if a minor of that major is supported")
            .isEqualTo(notBaseline(capability));
    }

    @Test
    void readAndWriteCapabilitiesFollowTheToolClassification() {
        assertThat(Capability.values()).filteredOn(c -> c.kind() == Kind.READ)
            .containsExactly(Capability.MONITORING, Capability.PROCESS_DIAGNOSIS,
                Capability.LOG_DIAGNOSIS, Capability.INVENTORY, Capability.WORKSPACE_EXPORT,
                Capability.QUEUE_ENTRY_READ);
        assertThat(Capability.values()).filteredOn(c -> c.kind() == Kind.WRITE)
            .containsExactly(Capability.DEVELOPMENT_WRITE, Capability.DEPLOYMENT,
                Capability.E2E_TEST, Capability.PROCESS_CONTROL);
    }

    /** Feature 012 (FR-004): both halves of the former "diagnosis" keep its state. */
    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"PROCESS_DIAGNOSIS", "LOG_DIAGNOSIS"})
    void bothDiagnosisCapabilitiesKeepTheFormerDiagnosisState(Capability capability) {
        assertThat(production.state(version("8.1.17"), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.state(version("9.1.0"), capability))
            .isEqualTo(SupportState.UNVERIFIED);
        assertThat(production.state(Optional.empty(), capability))
            .isEqualTo(SupportState.UNVERIFIED);
    }

    @Test
    void channelsFollowTheToolClassification() {
        assertThat(Capability.MONITORING.channels()).containsExactlyInAnyOrder(Channel.REST);
        assertThat(Capability.PROCESS_DIAGNOSIS.channels())
            .containsExactlyInAnyOrder(Channel.REST);
        assertThat(Capability.LOG_DIAGNOSIS.channels()).containsExactlyInAnyOrder(Channel.REST);
        assertThat(Capability.INVENTORY.channels())
            .containsExactlyInAnyOrder(Channel.REST, Channel.CLI);
        assertThat(Capability.WORKSPACE_EXPORT.channels()).containsExactlyInAnyOrder(Channel.CLI);
        assertThat(Capability.DEVELOPMENT_WRITE.channels())
            .containsExactlyInAnyOrder(Channel.CLI, Channel.REST);
        assertThat(Capability.DEPLOYMENT.channels())
            .containsExactlyInAnyOrder(Channel.CLI, Channel.REST);
        assertThat(Capability.E2E_TEST.channels())
            .containsExactlyInAnyOrder(Channel.SOAP, Channel.REST);
        assertThat(Capability.PROCESS_CONTROL.channels())
            .containsExactlyInAnyOrder(Channel.REST, Channel.CLI);
        assertThat(Capability.QUEUE_ENTRY_READ.channels())
            .containsExactlyInAnyOrder(Channel.REST, Channel.CLI);
    }

    /** Feature 012 (FR-005, research D-3): 6.1 is CLI-only and nothing runs until recorded. */
    @ParameterizedTest
    @EnumSource(Capability.class)
    void everyCapabilityIsUnsupportedOn61(Capability capability) {
        assertThat(production.state(version("6.1.0.89-HF1"), capability))
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(production.stateOfLine(6, OptionalInt.of(1), capability))
            .isEqualTo(SupportState.UNSUPPORTED);
    }

    @Test
    void monitoringAndProcessDiagnosisUseStartCliOn61() {
        assertThat(production.channels(version("6.1.0.89"), Capability.MONITORING))
            .containsExactly(Channel.CLI);
        assertThat(production.channels(version("6.1.0.89"), Capability.PROCESS_DIAGNOSIS))
            .containsExactly(Channel.CLI);
        assertThat(production.channels(version("6.1.0.89"), Capability.LOG_DIAGNOSIS))
            .as("a capability without declared channels keeps its own")
            .isEqualTo(Capability.LOG_DIAGNOSIS.channels());
        assertThat(production.isCliOnly(version("6.1.0.89-HF1"))).isTrue();
        assertThat(production.isCliOnly(version("6.1"))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Capability.class)
    void otherVersionsKeepTheCapabilityChannelsAndAreNotCliOnly(Capability capability) {
        for (Optional<InubitVersion> other : List.of(version("8.1.17"), version("9.0.7"),
                version("6.0.5"), version("6.2"), Optional.<InubitVersion>empty())) {
            assertThat(production.channels(other, capability)).as("%s", other)
                .isEqualTo(capability.channels());
            assertThat(production.isCliOnly(other)).as("%s", other).isFalse();
        }
    }

    @Test
    void anEntryWithChannelsAndCliOnlyRoundTrips() {
        CapabilityMatrix.Entry entry = new CapabilityMatrix.Entry(new VersionRange(7, 9, 7, 9),
            Map.of(Capability.MONITORING, SupportState.SUPPORTED),
            Map.of(Capability.MONITORING, Set.of(Channel.CLI, Channel.SOAP)), true);
        CapabilityMatrix matrix = new CapabilityMatrix(List.of(entry), Map.of());

        assertThat(entry.channels())
            .isEqualTo(Map.of(Capability.MONITORING, Set.of(Channel.CLI, Channel.SOAP)));
        assertThat(entry.cliOnly()).isTrue();
        assertThat(matrix.state(version("7.9.3"), Capability.MONITORING))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(matrix.channels(version("7.9.3"), Capability.MONITORING))
            .containsExactlyInAnyOrder(Channel.CLI, Channel.SOAP);
        assertThat(matrix.isCliOnly(version("7.9.3"))).isTrue();
        assertThat(new CapabilityMatrix.Entry(new VersionRange(7, 9, 7, 9), Map.of()))
            .as("the two-argument entry declares no channels and is not CLI-only")
            .isEqualTo(new CapabilityMatrix.Entry(new VersionRange(7, 9, 7, 9), Map.of(),
                Map.of(), false));
    }

    @Test
    void anOtherDeclarationIsLookedUpByItsRanges() {
        CapabilityMatrix matrix = new CapabilityMatrix(
            List.of(new CapabilityMatrix.Entry(new VersionRange(7, 9, 7, 9),
                Map.of(Capability.MONITORING, SupportState.SUPPORTED,
                    Capability.DEPLOYMENT, SupportState.SUPPORTED))),
            Map.of(Capability.MONITORING, SupportState.UNVERIFIED));

        assertThat(matrix.state(version("7.9.3"), Capability.MONITORING))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(matrix.state(version("7.9.3"), Capability.DEPLOYMENT))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(matrix.state(version("7.9.3"), Capability.INVENTORY))
            .as("a capability the range does not declare is unsupported")
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(matrix.state(version("8.1.17"), Capability.MONITORING))
            .isEqualTo(SupportState.UNVERIFIED);
        assertThat(matrix.state(version("8.1.17"), Capability.DEPLOYMENT))
            .isEqualTo(SupportState.UNSUPPORTED);
    }

    @Test
    void theFirstMatchingRangeWins() {
        CapabilityMatrix matrix = new CapabilityMatrix(
            List.of(new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
                    Map.of(Capability.MONITORING, SupportState.SUPPORTED)),
                new CapabilityMatrix.Entry(new VersionRange(8, 0, 8, 9),
                    Map.of(Capability.MONITORING, SupportState.UNVERIFIED))),
            Map.of());

        assertThat(matrix.state(version("8.1.17"), Capability.MONITORING))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(matrix.state(version("8.5.0"), Capability.MONITORING))
            .isEqualTo(SupportState.UNVERIFIED);
    }

    @Test
    void rejectsUnverifiedForAWriteCapability() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CapabilityMatrix(List.of(),
                Map.of(Capability.DEPLOYMENT, SupportState.UNVERIFIED)))
            .withMessageContaining("DEPLOYMENT");
        assertThatIllegalArgumentException().isThrownBy(() -> new CapabilityMatrix(
                List.of(new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
                    Map.of(Capability.E2E_TEST, SupportState.UNVERIFIED))),
                Map.of()))
            .withMessageContaining("E2E_TEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.1", "8.1.0", "8.1.99-HF3", "8.5", "9.2.1"})
    void aRangeContainsEveryPatchOfItsBounds(String value) {
        assertThat(new VersionRange(8, 1, 9, 2).contains(version(value).orElseThrow())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.0.99", "9.3", "7.9", "10.0"})
    void aRangeExcludesVersionsOutsideItsBounds(String value) {
        assertThat(new VersionRange(8, 1, 9, 2).contains(version(value).orElseThrow())).isFalse();
    }

    @Test
    void aRangeRejectsReversedOrNegativeBounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new VersionRange(9, 0, 8, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new VersionRange(8, 2, 8, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new VersionRange(-1, 0, 8, 1));
    }

    // --- feature 020 T003: reading Queue Manager entries (FR-006, FR-019 fallback, D-6) -------

    @Test
    void queueEntryReadIsSupportedOn81Only() {
        assertThat(Capability.QUEUE_ENTRY_READ.kind()).isEqualTo(Kind.READ);
        assertThat(Capability.QUEUE_ENTRY_READ.label()).isEqualTo("reading Queue Manager entries");
        assertThat(production.state(version("8.1.17"), Capability.QUEUE_ENTRY_READ))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.stateOfLine(8, OptionalInt.of(1), Capability.QUEUE_ENTRY_READ))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.note(version("8.1.17"), Capability.QUEUE_ENTRY_READ)).isEmpty();
    }

    /** The FR-019 fallback: 9.0 stays refused until its recording exists (T038 flips it). */
    @Test
    void queueEntryReadIsUnsupportedOn90WithTheNoteNotYetVerified() {
        assertThat(production.state(version("9.0.7"), Capability.QUEUE_ENTRY_READ))
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(production.stateOfLine(9, OptionalInt.of(0), Capability.QUEUE_ENTRY_READ))
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(production.note(version("9.0.7"), Capability.QUEUE_ENTRY_READ))
            .contains("not yet verified");
        assertThat(production.note(version("9.0.7"), Capability.INVENTORY))
            .as("only the queue entry read carries a note").isEmpty();
        assertThat(production.channels(version("9.0.7"), Capability.QUEUE_ENTRY_READ))
            .isEqualTo(Capability.QUEUE_ENTRY_READ.channels());
    }

    @Test
    void queueEntryReadIsUnsupportedOn61On91AndOnUnknownVersionsWithoutNote() {
        for (Optional<InubitVersion> other : List.of(version("6.1.0.89-HF1"), version("9.1.0"),
                version("7.0"), version("10.0"), Optional.<InubitVersion>empty())) {
            assertThat(production.state(other, Capability.QUEUE_ENTRY_READ)).as("%s", other)
                .isEqualTo(SupportState.UNSUPPORTED);
            assertThat(production.note(other, Capability.QUEUE_ENTRY_READ)).as("%s", other)
                .isEmpty();
        }
        assertThat(production.stateOfLine(9, OptionalInt.empty(), Capability.QUEUE_ENTRY_READ))
            .isEqualTo(SupportState.UNSUPPORTED);
    }

    /** The other capabilities keep their unknown-version rule: reads unverified. */
    @ParameterizedTest
    @EnumSource(value = Capability.class, names = "QUEUE_ENTRY_READ", mode = Mode.EXCLUDE)
    void theUnknownRuleOfTheOtherCapabilitiesIsUnchanged(Capability capability) {
        assertThat(production.state(Optional.empty(), capability))
            .isEqualTo(notBaseline(capability));
        assertThat(production.note(Optional.empty(), capability)).isEmpty();
    }

    @Test
    void theQueueEntryReadLinesAreTheSupportedAndTheNotedOnes() {
        assertThat(production.lines(Capability.QUEUE_ENTRY_READ)).containsExactly("8.1", "9.0");
        assertThat(new CapabilityMatrix(List.of(
                new CapabilityMatrix.Entry(new VersionRange(7, 8, 7, 9),
                    Map.of(Capability.MONITORING, SupportState.SUPPORTED)),
                new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
                    Map.of(Capability.MONITORING, SupportState.UNSUPPORTED))),
                Map.of()).lines(Capability.MONITORING))
            .as("a range names both bounds; an unsupported entry without note is no line")
            .containsExactly("7.8–7.9");
    }

    @Test
    void anEntryWithNotesRoundTrips() {
        CapabilityMatrix.Entry entry = new CapabilityMatrix.Entry(new VersionRange(7, 9, 7, 9),
            Map.of(Capability.MONITORING, SupportState.UNSUPPORTED), Map.of(), false,
            Map.of(Capability.MONITORING, "not yet verified"));
        CapabilityMatrix matrix = new CapabilityMatrix(List.of(entry), Map.of());

        assertThat(entry.notes()).isEqualTo(Map.of(Capability.MONITORING, "not yet verified"));
        assertThat(matrix.note(version("7.9.3"), Capability.MONITORING))
            .contains("not yet verified");
        assertThat(matrix.note(version("8.1.17"), Capability.MONITORING)).isEmpty();
        assertThat(new CapabilityMatrix.Entry(new VersionRange(7, 9, 7, 9), Map.of(), Map.of(),
                false))
            .as("the entry without notes declares none")
            .isEqualTo(new CapabilityMatrix.Entry(new VersionRange(7, 9, 7, 9), Map.of(),
                Map.of(), false, Map.of()));
    }

    private static SupportState notBaseline(Capability capability) {
        return capability.kind() == Kind.READ ? SupportState.UNVERIFIED : SupportState.UNSUPPORTED;
    }

    private static Optional<InubitVersion> version(String value) {
        Optional<InubitVersion> parsed = InubitVersion.parse(value);
        assertThat(parsed).as(value).isPresent();
        return parsed;
    }
}
