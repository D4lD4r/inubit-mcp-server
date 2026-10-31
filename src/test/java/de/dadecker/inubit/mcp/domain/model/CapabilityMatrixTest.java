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

    @ParameterizedTest
    @EnumSource(Capability.class)
    void readsAreUnverifiedAndWritesUnsupportedOn90(Capability capability) {
        assertThat(production.state(version("9.0.7"), capability))
            .isEqualTo(notBaseline(capability));
    }

    @ParameterizedTest
    @EnumSource(Capability.class)
    void anOtherKnownVersionIsTreatedLikeUnknown(Capability capability) {
        assertThat(production.state(version("10.0"), capability))
            .isEqualTo(notBaseline(capability));
        assertThat(production.state(version("8.2.0"), capability))
            .isEqualTo(notBaseline(capability));
        assertThat(production.state(version("8.0.9"), capability))
            .isEqualTo(notBaseline(capability));
    }

    @ParameterizedTest
    @EnumSource(Capability.class)
    void anUnknownVersionGivesReadsUnverifiedAndWritesUnsupported(Capability capability) {
        assertThat(production.state(Optional.empty(), capability))
            .isEqualTo(notBaseline(capability));
    }

    @ParameterizedTest
    @EnumSource(Capability.class)
    void aMajorOnlyLineIsLookedUpAsUnknown(Capability capability) {
        assertThat(production.stateOfLine(9, OptionalInt.empty(), capability))
            .isEqualTo(production.state(Optional.empty(), capability))
            .isEqualTo(production.state(version("9.0.7"), capability));
        assertThat(production.stateOfLine(8, OptionalInt.empty(), capability))
            .as("a major-only line is unknown even if a minor of that major is supported")
            .isEqualTo(notBaseline(capability));
    }

    @Test
    void readAndWriteCapabilitiesFollowTheToolClassification() {
        assertThat(Capability.values()).filteredOn(c -> c.kind() == Kind.READ)
            .containsExactly(Capability.MONITORING, Capability.PROCESS_DIAGNOSIS,
                Capability.LOG_DIAGNOSIS, Capability.INVENTORY, Capability.WORKSPACE_EXPORT);
        assertThat(Capability.values()).filteredOn(c -> c.kind() == Kind.WRITE)
            .containsExactly(Capability.DEVELOPMENT_WRITE, Capability.DEPLOYMENT,
                Capability.E2E_TEST, Capability.PROCESS_CONTROL);
    }

    /** Feature 011 (FR-004): both halves of the former "diagnosis" keep its state. */
    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"PROCESS_DIAGNOSIS", "LOG_DIAGNOSIS"})
    void bothDiagnosisCapabilitiesKeepTheFormerDiagnosisState(Capability capability) {
        assertThat(production.state(version("8.1.17"), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.state(version("9.0.7"), capability))
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
    }

    /** Feature 011 (FR-005, research D-3): 6.1 is CLI-only and nothing runs until recorded. */
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

    private static SupportState notBaseline(Capability capability) {
        return capability.kind() == Kind.READ ? SupportState.UNVERIFIED : SupportState.UNSUPPORTED;
    }

    private static Optional<InubitVersion> version(String value) {
        Optional<InubitVersion> parsed = InubitVersion.parse(value);
        assertThat(parsed).as(value).isPresent();
        return parsed;
    }
}
