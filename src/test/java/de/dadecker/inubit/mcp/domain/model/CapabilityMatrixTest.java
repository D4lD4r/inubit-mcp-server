package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.dadecker.inubit.mcp.domain.model.Capability.Kind;
import de.dadecker.inubit.mcp.testing.TestMatrices;
import java.util.EnumMap;
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

    /** Feature 024: the capabilities of the four unsupported node tools. */
    private static final Set<Capability> NODE_TOOLS = Set.of(Capability.CLUSTER_NODES_READ,
        Capability.IO_LOG_READ, Capability.NODE_CONFIG_READ, Capability.LOG_LEVEL_WRITE);

    private final CapabilityMatrix production = CapabilityMatrix.production();

    /**
     * Feature 021: the server-log reads are supported since their 8.1 recordings (trace T033, web
     * service T046); feature 022: the server settings (T025), the users (T037) and the model
     * details (T046); feature 024: the cluster's node list, the node configuration and the
     * temporary log levels since their 8.1 recordings (T025, T038, T058), the I/O log waits for
     * its recording (below); feature 022 US4: operating the server since its recordings (T054,
     * contract tests T066), managing tags waits for its active-tag check (T055, below).
     */
    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"IO_LOG_READ", "TAG_ADMINISTRATION"},
        mode = Mode.EXCLUDE)
    void everyCapabilityIsSupportedOn81(Capability capability) {
        assertThat(production.state(version("8.1.17"), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.state(version("8.1"), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.stateOfLine(8, OptionalInt.of(1), capability))
            .isEqualTo(SupportState.SUPPORTED);
    }

    /**
     * Feature 017 (T004, research D-9): 9.0 is declared like 8.1, REST and CLI; partner
     * management is the exception of feature 018 (below); feature 020 (T038): reading Queue
     * Manager entries included since its 9.0 recording; feature 021: the server-log reads
     * included since their 9.0 recordings (T051, T052); feature 022: the server settings, the
     * users and the model details included since their 9.0 contract tests (T026, T037a, T046a);
     * feature 024: the cluster's node list, the node configuration and the temporary log levels
     * since their 9.0 recordings (T062, T064, T065), the I/O log waits for its recording (below);
     * feature 022 US4: operating the server and managing tags wait for their recordings (below);
     * feature 026: checking module management waits for a 9.0 recording (below).
     */
    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"PARTNER_MANAGEMENT", "IO_LOG_READ",
        "SERVER_OPERATION", "TAG_ADMINISTRATION", "MODULE_MANAGEMENT"},
        mode = EnumSource.Mode.EXCLUDE)
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

    /**
     * Feature 018 (FR-016, research D-7): partner management is supported on 8.1 only, runs with
     * a warning on 9.0, every other and an unknown version, and is refused on 6.1.
     */
    @Test
    void partnerManagementIsSupportedOn81UnverifiedOn90AndOthersAndUnsupportedOn61() {
        Capability partners = Capability.PARTNER_MANAGEMENT;
        assertThat(production.state(version("8.1.17"), partners))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.stateOfLine(8, OptionalInt.of(1), partners))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.state(version("9.0.7"), partners))
            .isEqualTo(SupportState.UNVERIFIED);
        assertThat(production.stateOfLine(9, OptionalInt.of(0), partners))
            .isEqualTo(SupportState.UNVERIFIED);
        assertThat(production.stateOfLine(9, OptionalInt.empty(), partners)).as("V9_X")
            .isEqualTo(SupportState.UNVERIFIED);
        assertThat(production.state(version("9.1.0"), partners))
            .isEqualTo(SupportState.UNVERIFIED);
        assertThat(production.state(Optional.empty(), partners))
            .isEqualTo(SupportState.UNVERIFIED);
        assertThat(production.state(version("6.1.0.89-HF1"), partners))
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(production.stateOfLine(6, OptionalInt.of(1), partners))
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(production.channels(version("9.0.7"), partners))
            .isEqualTo(production.channels(version("8.1.17"), partners))
            .containsExactly(Channel.REST);
    }

    /** Feature 018 (FR-012a, research D-5): equal stored secrets are comparable on 8.1 only. */
    @Test
    void storedSecretsAreComparableOn81Only() {
        assertThat(CapabilityMatrix.COMPARABLE_SECRET_LINES)
            .containsExactly(new MajorMinor(8, 1));
    }

    /** Feature 017 (T004, research D-9): {@code V9_X} still selects no 9.0 support. */
    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"QUEUE_ENTRY_READ", "TRACE_LOG_READ",
        "WEBSERVICE_LOG_READ", "SERVER_SETTINGS", "USER_DIRECTORY", "MODEL_DETAILS",
        "CLUSTER_NODES_READ", "IO_LOG_READ", "NODE_CONFIG_READ", "MODULE_MANAGEMENT"},
        mode = Mode.EXCLUDE)
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
    @EnumSource(value = Capability.class, names = {"QUEUE_ENTRY_READ", "TRACE_LOG_READ",
        "WEBSERVICE_LOG_READ", "SERVER_SETTINGS", "USER_DIRECTORY", "MODEL_DETAILS",
        "CLUSTER_NODES_READ", "IO_LOG_READ", "NODE_CONFIG_READ", "MODULE_MANAGEMENT"},
        mode = Mode.EXCLUDE)
    void anOtherKnownVersionIsTreatedLikeUnknown(Capability capability) {
        assertThat(production.state(version("10.0"), capability))
            .isEqualTo(notBaseline(capability));
        assertThat(production.state(version("8.2.0"), capability))
            .isEqualTo(notBaseline(capability));
        assertThat(production.state(version("8.0.9"), capability))
            .isEqualTo(notBaseline(capability));
    }

    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"QUEUE_ENTRY_READ", "TRACE_LOG_READ",
        "WEBSERVICE_LOG_READ", "SERVER_SETTINGS", "USER_DIRECTORY", "MODEL_DETAILS",
        "CLUSTER_NODES_READ", "IO_LOG_READ", "NODE_CONFIG_READ", "MODULE_MANAGEMENT"},
        mode = Mode.EXCLUDE)
    void anUnknownVersionGivesReadsUnverifiedAndWritesUnsupported(Capability capability) {
        assertThat(production.state(Optional.empty(), capability))
            .isEqualTo(notBaseline(capability));
    }

    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"QUEUE_ENTRY_READ", "TRACE_LOG_READ",
        "WEBSERVICE_LOG_READ", "SERVER_SETTINGS", "USER_DIRECTORY", "MODEL_DETAILS",
        "CLUSTER_NODES_READ", "IO_LOG_READ", "NODE_CONFIG_READ", "MODULE_MANAGEMENT"},
        mode = Mode.EXCLUDE)
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
                Capability.LOG_DIAGNOSIS, Capability.INVENTORY, Capability.PARTNER_MANAGEMENT,
                Capability.WORKSPACE_EXPORT, Capability.QUEUE_ENTRY_READ,
                Capability.TRACE_LOG_READ, Capability.WEBSERVICE_LOG_READ,
                Capability.SERVER_SETTINGS, Capability.USER_DIRECTORY, Capability.MODEL_DETAILS,
                Capability.CLUSTER_NODES_READ, Capability.IO_LOG_READ,
                Capability.NODE_CONFIG_READ, Capability.MODULE_MANAGEMENT);
        assertThat(Capability.values()).filteredOn(c -> c.kind() == Kind.WRITE)
            .containsExactly(Capability.DEVELOPMENT_WRITE, Capability.DEPLOYMENT,
                Capability.E2E_TEST, Capability.PROCESS_CONTROL, Capability.LOG_LEVEL_WRITE,
                Capability.SERVER_OPERATION, Capability.TAG_ADMINISTRATION);
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
        assertThat(Capability.PARTNER_MANAGEMENT.channels())
            .containsExactlyInAnyOrder(Channel.REST);
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
        assertThat(Capability.TRACE_LOG_READ.channels()).containsExactly(Channel.CLI);
        assertThat(Capability.WEBSERVICE_LOG_READ.channels()).containsExactly(Channel.CLI);
        assertThat(Capability.SERVER_SETTINGS.channels())
            .containsExactlyInAnyOrder(Channel.REST, Channel.CLI);
        assertThat(Capability.USER_DIRECTORY.channels())
            .containsExactlyInAnyOrder(Channel.REST, Channel.CLI);
        assertThat(Capability.MODEL_DETAILS.channels())
            .containsExactlyInAnyOrder(Channel.REST, Channel.CLI);
        assertThat(Capability.CLUSTER_NODES_READ.channels()).containsExactly(Channel.CLI);
        assertThat(Capability.IO_LOG_READ.channels()).containsExactly(Channel.CLI);
        assertThat(Capability.NODE_CONFIG_READ.channels()).containsExactly(Channel.CLI);
        assertThat(Capability.LOG_LEVEL_WRITE.channels()).containsExactly(Channel.CLI);
        assertThat(Capability.SERVER_OPERATION.channels())
            .containsExactlyInAnyOrder(Channel.REST, Channel.CLI);
        assertThat(Capability.MODULE_MANAGEMENT.channels()).containsExactly(Channel.REST);
    }

    /**
     * Feature 012 (FR-005, research D-3): 6.1 is CLI-only and nothing is supported until
     * recorded; feature 025 (FR-036): monitoring and process diagnosis are unverified, without a
     * note, everything else stays unsupported.
     */
    @ParameterizedTest
    @EnumSource(Capability.class)
    void onlyMonitoringAndProcessDiagnosisAreUnverifiedOn61(Capability capability) {
        SupportState expected = capability == Capability.MONITORING
            || capability == Capability.PROCESS_DIAGNOSIS
            ? SupportState.UNVERIFIED : SupportState.UNSUPPORTED;
        assertThat(production.state(version("6.1.0.89-HF1"), capability)).isEqualTo(expected);
        assertThat(production.stateOfLine(6, OptionalInt.of(1), capability)).isEqualTo(expected);
        assertThat(production.note(version("6.1.0.89"), capability)).isEmpty();
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
    void queueEntryReadIsSupportedOn81And90WithoutNote() {
        assertThat(Capability.QUEUE_ENTRY_READ.kind()).isEqualTo(Kind.READ);
        assertThat(Capability.QUEUE_ENTRY_READ.label()).isEqualTo("reading Queue Manager entries");
        assertThat(production.state(version("8.1.17"), Capability.QUEUE_ENTRY_READ))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.stateOfLine(8, OptionalInt.of(1), Capability.QUEUE_ENTRY_READ))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.note(version("8.1.17"), Capability.QUEUE_ENTRY_READ)).isEmpty();
        // T038: the recorded 9.0 error entry enabled 9.0; the "not yet verified" note is gone
        assertThat(production.state(version("9.0.7"), Capability.QUEUE_ENTRY_READ))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.stateOfLine(9, OptionalInt.of(0), Capability.QUEUE_ENTRY_READ))
            .isEqualTo(SupportState.SUPPORTED);
        for (Capability capability : Capability.values()) {
            if (!NODE_TOOLS.contains(capability) && capability != Capability.SERVER_OPERATION
                && capability != Capability.TAG_ADMINISTRATION
                && capability != Capability.MODULE_MANAGEMENT) {
                assertThat(production.note(version("9.0.7"), capability)).as("%s", capability)
                    .isEmpty();
            }
        }
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
    @EnumSource(value = Capability.class, names = {"QUEUE_ENTRY_READ", "TRACE_LOG_READ",
        "WEBSERVICE_LOG_READ", "SERVER_SETTINGS", "USER_DIRECTORY", "MODEL_DETAILS",
        "CLUSTER_NODES_READ", "IO_LOG_READ", "NODE_CONFIG_READ", "MODULE_MANAGEMENT"},
        mode = Mode.EXCLUDE)
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

    // --- feature 021 T009: downloading the server logs (FR-002, FR-017, D-6) --------------------

    @Test
    void theServerLogReadsAreCliReadsWithTheirLabels() {
        assertThat(Capability.TRACE_LOG_READ.kind()).isEqualTo(Kind.READ);
        assertThat(Capability.TRACE_LOG_READ.label()).isEqualTo("downloading the trace log");
        assertThat(Capability.WEBSERVICE_LOG_READ.kind()).isEqualTo(Kind.READ);
        assertThat(Capability.WEBSERVICE_LOG_READ.label())
            .isEqualTo("downloading the web service log");
    }

    /**
     * T031 (feature 021, FR-002): a version not yet known (an {@code AUTO} node) may run a
     * capability only if some range or the unknown versions admit it; since their 8.1 recordings
     * (T033, T046) 8.1 admits both server-log reads, and since their contract tests the server
     * settings (T025), the users (T037) and the model details (feature 022 T046); feature 024:
     * since their 8.1 recordings (T025, T038, T058) the cluster's node list, the node
     * configuration and the temporary log levels, the I/O log not before its recording (below);
     * feature 022 US4: operating the server since its 8.1 recordings (T054, contract tests T066),
     * managing tags not before its active-tag check (below).
     */
    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"IO_LOG_READ", "TAG_ADMINISTRATION"},
        mode = Mode.EXCLUDE)
    void everyCapabilityIsPossiblySupportedOnTheProductionMatrix(Capability capability) {
        assertThat(production.possiblySupported(capability)).isTrue();
    }

    @Test
    void theUnknownVersionsAloneMakeACapabilityPossiblySupported() {
        CapabilityMatrix matrix = new CapabilityMatrix(List.of(new CapabilityMatrix.Entry(
            new VersionRange(8, 1, 8, 1), Map.of(Capability.INVENTORY, SupportState.SUPPORTED))),
            Map.of(Capability.MONITORING, SupportState.UNVERIFIED));

        assertThat(matrix.possiblySupported(Capability.MONITORING)).isTrue();
        assertThat(matrix.possiblySupported(Capability.INVENTORY)).isTrue();
        assertThat(matrix.possiblySupported(Capability.PROCESS_DIAGNOSIS)).isFalse();
    }

    /**
     * T033, T046 (FR-017, D-22): the recorded 8.1 trace and web service logs enabled 8.1; their
     * notes are gone.
     */
    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"TRACE_LOG_READ", "WEBSERVICE_LOG_READ"})
    void aServerLogReadIsSupportedOn81WithoutNote(Capability capability) {
        assertThat(production.state(version("8.1.17"), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.stateOfLine(8, OptionalInt.of(1), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.note(version("8.1.17"), capability)).isEmpty();
        assertThat(production.channels(version("8.1.17"), capability))
            .containsExactly(Channel.CLI);
        assertThat(production.lines(capability)).containsExactly("8.1", "9.0");
    }

    /**
     * T051, T052 (FR-017, D-22): the recorded 9.0 trace log and the recorded empty 9.0 web service
     * log enabled 9.0; their notes are gone.
     */
    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"TRACE_LOG_READ", "WEBSERVICE_LOG_READ"})
    void aServerLogReadIsSupportedOn90WithoutNote(Capability capability) {
        assertThat(production.state(version("9.0.7"), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.stateOfLine(9, OptionalInt.of(0), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.note(version("9.0.7"), capability)).isEmpty();
        assertThat(production.channels(version("9.0.7"), capability))
            .containsExactly(Channel.CLI);
    }

    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"TRACE_LOG_READ", "WEBSERVICE_LOG_READ"})
    void aServerLogReadIsUnsupportedOn61On91AndOnUnknownVersionsWithoutNote(
        Capability capability) {
        for (Optional<InubitVersion> other : List.of(version("6.1.3"), version("9.1.0"),
                version("7.0"), version("10.0"), Optional.<InubitVersion>empty())) {
            assertThat(production.state(other, capability)).as("%s", other)
                .isEqualTo(SupportState.UNSUPPORTED);
            assertThat(production.note(other, capability)).as("%s", other).isEmpty();
        }
        assertThat(production.stateOfLine(9, OptionalInt.empty(), capability))
            .isEqualTo(SupportState.UNSUPPORTED);
    }

    // --- feature 022 T011: reading the server settings (US1, research D-1, D-2) -----------------

    @Test
    void serverSettingsIsARestAndCliReadWithItsLabel() {
        assertThat(Capability.SERVER_SETTINGS.kind()).isEqualTo(Kind.READ);
        assertThat(Capability.SERVER_SETTINGS.label()).isEqualTo("reading server settings");
    }

    /** Supported on 8.1 and 9.0 since their contract tests (T025, T026). */
    @Test
    void serverSettingsIsSupportedOn81And90() {
        assertThat(production.state(version("8.1.17"), Capability.SERVER_SETTINGS))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.note(version("8.1.17"), Capability.SERVER_SETTINGS)).isEmpty();
        assertThat(production.state(version("9.0.10"), Capability.SERVER_SETTINGS))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.note(version("9.0.10"), Capability.SERVER_SETTINGS)).isEmpty();
        assertThat(production.stateOfLine(8, OptionalInt.of(1), Capability.SERVER_SETTINGS))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.stateOfLine(9, OptionalInt.of(0), Capability.SERVER_SETTINGS))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.lines(Capability.SERVER_SETTINGS)).containsExactly("8.1", "9.0");
        assertThat(production.possiblySupported(Capability.SERVER_SETTINGS)).isTrue();
    }

    /**
     * The coverage list only knows 8.1 and 9.0, so no other and no unknown version runs it; 6.1
     * gets no new reads (spec: "INUBIT 6.1 stays read-only and gets no new capabilities").
     */
    @Test
    void serverSettingsIsUnsupportedOn61OtherAndUnknownVersionsWithoutNote() {
        for (Optional<InubitVersion> other : List.of(version("6.1.0.89-HF1"), version("9.1.0"),
                version("10.0"), Optional.<InubitVersion>empty())) {
            assertThat(production.state(other, Capability.SERVER_SETTINGS)).as("%s", other)
                .isEqualTo(SupportState.UNSUPPORTED);
            assertThat(production.note(other, Capability.SERVER_SETTINGS)).as("%s", other)
                .isEmpty();
        }
        assertThat(production.stateOfLine(9, OptionalInt.empty(), Capability.SERVER_SETTINGS))
            .isEqualTo(SupportState.UNSUPPORTED);
    }

    // --- feature 022 T033: reading the users (US2, research D-1, D-20) --------------------------

    @Test
    void userDirectoryIsARestAndCliReadWithItsLabel() {
        assertThat(Capability.USER_DIRECTORY.kind()).isEqualTo(Kind.READ);
        assertThat(Capability.USER_DIRECTORY.label()).isEqualTo("reading users");
    }

    /** Supported on 8.1 and 9.0 since their contract tests (T037, T037a). */
    @Test
    void userDirectoryIsSupportedOn81And90() {
        assertThat(production.state(version("8.1.17"), Capability.USER_DIRECTORY))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.note(version("8.1.17"), Capability.USER_DIRECTORY)).isEmpty();
        assertThat(production.state(version("9.0.10"), Capability.USER_DIRECTORY))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.note(version("9.0.10"), Capability.USER_DIRECTORY)).isEmpty();
        assertThat(production.stateOfLine(8, OptionalInt.of(1), Capability.USER_DIRECTORY))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.stateOfLine(9, OptionalInt.of(0), Capability.USER_DIRECTORY))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.lines(Capability.USER_DIRECTORY)).containsExactly("8.1", "9.0");
        assertThat(production.possiblySupported(Capability.USER_DIRECTORY)).isTrue();
    }

    /** As the server settings: 6.1, other and unknown versions refuse it without a note. */
    @Test
    void userDirectoryIsUnsupportedOn61OtherAndUnknownVersionsWithoutNote() {
        for (Optional<InubitVersion> other : List.of(version("6.1.0.89-HF1"), version("9.1.0"),
                version("10.0"), Optional.<InubitVersion>empty())) {
            assertThat(production.state(other, Capability.USER_DIRECTORY)).as("%s", other)
                .isEqualTo(SupportState.UNSUPPORTED);
            assertThat(production.note(other, Capability.USER_DIRECTORY)).as("%s", other)
                .isEmpty();
        }
        assertThat(production.stateOfLine(9, OptionalInt.empty(), Capability.USER_DIRECTORY))
            .isEqualTo(SupportState.UNSUPPORTED);
    }

    // --- feature 022 T042: reading the model details (US3, research D-1, D-21) ----------------

    @Test
    void modelDetailsIsARestAndCliReadWithItsLabel() {
        assertThat(Capability.MODEL_DETAILS.kind()).isEqualTo(Kind.READ);
        assertThat(Capability.MODEL_DETAILS.label()).isEqualTo("reading model details");
        assertThat(Capability.MODEL_DETAILS.admittedPerChannel()).isFalse();
    }

    /** Supported on 8.1 and 9.0 since their contract tests (T046, T046a). */
    @Test
    void modelDetailsIsSupportedOn81And90() {
        assertThat(production.state(version("8.1.17"), Capability.MODEL_DETAILS))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.note(version("8.1.17"), Capability.MODEL_DETAILS)).isEmpty();
        assertThat(production.state(version("9.0.10"), Capability.MODEL_DETAILS))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.note(version("9.0.10"), Capability.MODEL_DETAILS)).isEmpty();
        assertThat(production.stateOfLine(8, OptionalInt.of(1), Capability.MODEL_DETAILS))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.stateOfLine(9, OptionalInt.of(0), Capability.MODEL_DETAILS))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.lines(Capability.MODEL_DETAILS)).containsExactly("8.1", "9.0");
        assertThat(production.possiblySupported(Capability.MODEL_DETAILS))
            .as("8.1 and 9.0 run it, so read_model_details is announced").isTrue();
    }

    /** As the users: 6.1, other and unknown versions refuse it without a note. */
    @Test
    void modelDetailsIsUnsupportedOn61OtherAndUnknownVersionsWithoutNote() {
        for (Optional<InubitVersion> other : List.of(version("6.1.0.89-HF1"), version("9.1.0"),
                version("10.0"), Optional.<InubitVersion>empty())) {
            assertThat(production.state(other, Capability.MODEL_DETAILS)).as("%s", other)
                .isEqualTo(SupportState.UNSUPPORTED);
            assertThat(production.note(other, Capability.MODEL_DETAILS)).as("%s", other)
                .isEmpty();
        }
        assertThat(production.stateOfLine(9, OptionalInt.empty(), Capability.MODEL_DETAILS))
            .isEqualTo(SupportState.UNSUPPORTED);
    }

    // --- feature 024 T004: the unsupported node tools (FR-005, D-4) ------------------------------

    @Test
    void theNodeToolsAreCliCapabilitiesWithTheirKindsAndLabels() {
        assertThat(Capability.CLUSTER_NODES_READ.kind()).isEqualTo(Kind.READ);
        assertThat(Capability.CLUSTER_NODES_READ.label())
            .isEqualTo("reading the cluster's node list");
        assertThat(Capability.IO_LOG_READ.kind()).isEqualTo(Kind.READ);
        assertThat(Capability.IO_LOG_READ.label())
            .isEqualTo("reading module input/output logging");
        assertThat(Capability.NODE_CONFIG_READ.kind()).isEqualTo(Kind.READ);
        assertThat(Capability.NODE_CONFIG_READ.label())
            .isEqualTo("reading node configuration values");
        assertThat(Capability.LOG_LEVEL_WRITE.kind()).isEqualTo(Kind.WRITE);
        assertThat(Capability.LOG_LEVEL_WRITE.label()).isEqualTo("changing temporary log levels");
    }

    /**
     * T025, T062; T038, T064 and T058, T065 (FR-005, D-27): the recorded 8.1 and 9.0
     * cluster-nodes, node-config and log-level cases enabled the cluster's node list, the node
     * configuration and the temporary log levels on both lines; their notes are gone.
     */
    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"CLUSTER_NODES_READ", "NODE_CONFIG_READ",
        "LOG_LEVEL_WRITE"})
    void aNodeToolRecordedOn81And90IsSupportedOnBothWithoutNote(Capability capability) {
        for (Optional<InubitVersion> recorded : List.of(version("8.1.17"), version("9.0.7"))) {
            assertThat(production.state(recorded, capability)).as("%s", recorded)
                .isEqualTo(SupportState.SUPPORTED);
            assertThat(production.note(recorded, capability)).as("%s", recorded).isEmpty();
            assertThat(production.channels(recorded, capability)).as("%s", recorded)
                .containsExactly(Channel.CLI);
        }
        assertThat(production.stateOfLine(8, OptionalInt.of(1), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.stateOfLine(9, OptionalInt.of(0), capability))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.lines(capability)).containsExactly("8.1", "9.0");
        assertThat(production.possiblySupported(capability)).isTrue();
    }

    /**
     * Until its recording (T032, T063) the I/O log is refused on 8.1 and 9.0 as "not yet
     * verified", and no version may run it.
     */
    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"IO_LOG_READ"})
    void aNodeToolIsNotYetVerifiedOn81And90(Capability capability) {
        for (Optional<InubitVersion> verified : List.of(version("8.1.17"), version("9.0.7"))) {
            assertThat(production.state(verified, capability)).as("%s", verified)
                .isEqualTo(SupportState.UNSUPPORTED);
            assertThat(production.note(verified, capability)).as("%s", verified)
                .contains("not yet verified");
            assertThat(production.channels(verified, capability)).as("%s", verified)
                .containsExactly(Channel.CLI);
        }
        assertThat(production.stateOfLine(8, OptionalInt.of(1), capability))
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(production.stateOfLine(9, OptionalInt.of(0), capability))
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(production.lines(capability)).containsExactly("8.1", "9.0");
        assertThat(production.possiblySupported(capability)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"CLUSTER_NODES_READ", "IO_LOG_READ",
        "NODE_CONFIG_READ", "LOG_LEVEL_WRITE"})
    void aNodeToolIsUnsupportedOn61On91AndOnUnknownVersionsWithoutNote(Capability capability) {
        for (Optional<InubitVersion> other : List.of(version("6.1.3"), version("9.1.0"),
                version("7.0"), version("10.0"), Optional.<InubitVersion>empty())) {
            assertThat(production.state(other, capability)).as("%s", other)
                .isEqualTo(SupportState.UNSUPPORTED);
            assertThat(production.note(other, capability)).as("%s", other).isEmpty();
        }
        assertThat(production.stateOfLine(6, OptionalInt.of(1), capability))
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(production.stateOfLine(9, OptionalInt.empty(), capability))
            .isEqualTo(SupportState.UNSUPPORTED);
    }

    // --- feature 022 T058: operating a server (US4, D-1, D-22) ----------------------------------

    @Test
    void serverOperationIsARestAndCliWriteWithItsLabel() {
        assertThat(Capability.SERVER_OPERATION.kind()).isEqualTo(Kind.WRITE);
        assertThat(Capability.SERVER_OPERATION.label()).isEqualTo("operating the server");
        assertThat(Capability.SERVER_OPERATION.admittedPerChannel()).isFalse();
    }

    /**
     * T066: operating the server is supported on 8.1 since its recordings (T054) and contract
     * tests ({@code OperateV81ContractTest}); 9.0 is refused as "not yet verified" until the US4
     * 9.0 follow-up records it.
     */
    @Test
    void serverOperationIsSupportedOn81AndNotYetVerifiedOn90() {
        assertThat(production.state(version("8.1.17"), Capability.SERVER_OPERATION))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.note(version("8.1.17"), Capability.SERVER_OPERATION)).isEmpty();
        assertThat(production.stateOfLine(8, OptionalInt.of(1), Capability.SERVER_OPERATION))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.state(version("9.0.10"), Capability.SERVER_OPERATION))
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(production.note(version("9.0.10"), Capability.SERVER_OPERATION))
            .contains("not yet verified");
        assertThat(production.stateOfLine(9, OptionalInt.of(0), Capability.SERVER_OPERATION))
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(production.lines(Capability.SERVER_OPERATION)).containsExactly("8.1", "9.0");
        assertThat(production.possiblySupported(Capability.SERVER_OPERATION)).isTrue();
    }

    /**
     * Managing tags (T063) is refused on 8.1 and 9.0 as "not yet verified": T055 did not verify
     * its active-tag actions (research D-18), and no version may run it.
     */
    @Test
    void tagAdministrationIsNotYetVerifiedOn81And90() {
        for (Optional<InubitVersion> verified : List.of(version("8.1.17"), version("9.0.10"))) {
            assertThat(production.state(verified, Capability.TAG_ADMINISTRATION))
                .as("%s", verified).isEqualTo(SupportState.UNSUPPORTED);
            assertThat(production.note(verified, Capability.TAG_ADMINISTRATION))
                .as("%s", verified).contains("not yet verified");
        }
        assertThat(production.stateOfLine(8, OptionalInt.of(1), Capability.TAG_ADMINISTRATION))
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(production.stateOfLine(9, OptionalInt.of(0), Capability.TAG_ADMINISTRATION))
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(production.lines(Capability.TAG_ADMINISTRATION)).containsExactly("8.1", "9.0");
        assertThat(production.possiblySupported(Capability.TAG_ADMINISTRATION)).isFalse();
    }

    /** 6.1, other and unknown versions refuse them without a note. */
    @ParameterizedTest
    @EnumSource(value = Capability.class, names = {"SERVER_OPERATION", "TAG_ADMINISTRATION"})
    void theUs4CapabilitiesAreUnsupportedOn61OtherAndUnknownVersionsWithoutNote(
        Capability capability) {
        for (Optional<InubitVersion> other : List.of(version("6.1.0.89-HF1"), version("9.1.0"),
                version("10.0"), Optional.<InubitVersion>empty())) {
            assertThat(production.state(other, capability)).as("%s", other)
                .isEqualTo(SupportState.UNSUPPORTED);
            assertThat(production.note(other, capability)).as("%s", other)
                .isEmpty();
        }
        assertThat(production.stateOfLine(9, OptionalInt.empty(), capability))
            .isEqualTo(SupportState.UNSUPPORTED);
    }

    // --- feature 022 T063: managing tags (US4, D-1, D-18) ---------------------------------------

    @Test
    void tagAdministrationIsACliWriteWithItsLabel() {
        assertThat(Capability.TAG_ADMINISTRATION.kind()).isEqualTo(Kind.WRITE);
        assertThat(Capability.TAG_ADMINISTRATION.channels()).containsExactly(Channel.CLI);
        assertThat(Capability.TAG_ADMINISTRATION.label()).isEqualTo("managing tags");
        assertThat(Capability.TAG_ADMINISTRATION.admittedPerChannel()).isFalse();
    }

    // --- feature 026 T003: checking module management (research D-2) --------------------------

    @Test
    void moduleManagementIsARestReadWithItsLabel() {
        assertThat(Capability.MODULE_MANAGEMENT.kind()).isEqualTo(Kind.READ);
        assertThat(Capability.MODULE_MANAGEMENT.channels()).containsExactly(Channel.REST);
        assertThat(Capability.MODULE_MANAGEMENT.label()).isEqualTo("checking module management");
        assertThat(Capability.MODULE_MANAGEMENT.admittedPerChannel()).isFalse();
    }

    /**
     * Owner decision 2026-11-28 (research D-2): supported on 8.1, whose endpoints have recorded
     * contracts; 9.0 is refused as "not yet verified" until a 9.0 {@code sdExtSystem} recording
     * exists.
     */
    @Test
    void moduleManagementIsSupportedOn81AndNotYetVerifiedOn90() {
        assertThat(production.state(version("8.1.17"), Capability.MODULE_MANAGEMENT))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.note(version("8.1.17"), Capability.MODULE_MANAGEMENT)).isEmpty();
        assertThat(production.stateOfLine(8, OptionalInt.of(1), Capability.MODULE_MANAGEMENT))
            .isEqualTo(SupportState.SUPPORTED);
        assertThat(production.state(version("9.0.10"), Capability.MODULE_MANAGEMENT))
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(production.note(version("9.0.10"), Capability.MODULE_MANAGEMENT))
            .contains("not yet verified");
        assertThat(production.stateOfLine(9, OptionalInt.of(0), Capability.MODULE_MANAGEMENT))
            .isEqualTo(SupportState.UNSUPPORTED);
        assertThat(production.lines(Capability.MODULE_MANAGEMENT)).containsExactly("8.1", "9.0");
        assertThat(production.possiblySupported(Capability.MODULE_MANAGEMENT)).isTrue();
    }

    /** 6.1, other and unknown versions refuse it without a note (7.4: with it, below). */
    @Test
    void moduleManagementIsUnsupportedOn61OtherAndUnknownVersionsWithoutNote() {
        for (Optional<InubitVersion> other : List.of(version("6.1.0.89-HF1"), version("9.1.0"),
                version("10.0"), Optional.<InubitVersion>empty())) {
            assertThat(production.state(other, Capability.MODULE_MANAGEMENT)).as("%s", other)
                .isEqualTo(SupportState.UNSUPPORTED);
            assertThat(production.note(other, Capability.MODULE_MANAGEMENT)).as("%s", other)
                .isEmpty();
        }
        assertThat(production.stateOfLine(9, OptionalInt.empty(), Capability.MODULE_MANAGEMENT))
            .isEqualTo(SupportState.UNSUPPORTED);
    }

    // --- feature 025 T007: the 7.4 entry (FR-003, FR-006, research D-3, D-4) -------------------

    /**
     * Feature 025 (FR-030): monitoring and process diagnosis are unverified on 7.4 without a note;
     * every other capability is refused with the note "not yet verified".
     */
    @ParameterizedTest
    @EnumSource(Capability.class)
    void onlyMonitoringAndProcessDiagnosisAreUnverifiedOn74(Capability capability) {
        boolean unverified = capability == Capability.MONITORING
            || capability == Capability.PROCESS_DIAGNOSIS;
        SupportState expected = unverified ? SupportState.UNVERIFIED : SupportState.UNSUPPORTED;
        for (Optional<InubitVersion> v74 : List.of(version("7.4.0.90"), version("7.4.0"),
                version("7.4"))) {
            assertThat(production.state(v74, capability)).as("%s", v74).isEqualTo(expected);
            assertThat(production.note(v74, capability)).as("%s", v74)
                .isEqualTo(unverified ? Optional.empty() : Optional.of("not yet verified"));
        }
        assertThat(production.stateOfLine(7, OptionalInt.of(4), capability)).isEqualTo(expected);
    }

    @Test
    void v74IsCliOnlyWithStartCliForMonitoringAndProcessDiagnosis() {
        assertThat(production.isCliOnly(version("7.4.0.90"))).isTrue();
        assertThat(production.isCliOnly(version("7.4"))).isTrue();
        assertThat(production.declares(new MajorMinor(7, 4))).isTrue();
        assertThat(production.channels(version("7.4.0.90"), Capability.MONITORING))
            .containsExactly(Channel.CLI);
        assertThat(production.channels(version("7.4.0.90"), Capability.PROCESS_DIAGNOSIS))
            .containsExactly(Channel.CLI);
        assertThat(production.channels(version("7.4.0.90"), Capability.LOG_DIAGNOSIS))
            .as("a capability without declared channels keeps its own")
            .isEqualTo(Capability.LOG_DIAGNOSIS.channels());
        assertThat(production.isCliOnly(version("7.3.0"))).isFalse();
        assertThat(production.isCliOnly(version("7.5.0"))).isFalse();
    }

    @Test
    void thePatchDepthIsFourOn74AndThreeElsewhere() {
        assertThat(production.patchDepth(version("7.4.0.90"))).isEqualTo(PatchDepth.FOUR);
        assertThat(production.patchDepth(version("7.4"))).isEqualTo(PatchDepth.FOUR);
        for (Optional<InubitVersion> other : List.of(version("6.1.0.89"), version("8.1.17"),
                version("9.0.7"), version("7.0"), version("10.0"),
                Optional.<InubitVersion>empty())) {
            assertThat(production.patchDepth(other)).as("%s", other).isEqualTo(PatchDepth.THREE);
        }
        assertThat(new CapabilityMatrix.Entry(new VersionRange(7, 9, 7, 9), Map.of(), Map.of(),
                false, Map.of()).patchDepth())
            .as("the existing constructors keep THREE").isEqualTo(PatchDepth.THREE);
    }

    @Test
    void ownClientOnlyIsTheFlagOfTheVersionsEntry() {
        // feature 025 T033 (FR-029): production sets the flag on 7.4 only
        assertThat(production.ownClientOnly(version("7.4.0.90"))).isTrue();
        assertThat(production.ownClientOnly(version("7.4"))).isTrue();
        for (Optional<InubitVersion> other : List.of(version("6.1.0.89"), version("8.1.17"),
                version("9.0.7"), Optional.<InubitVersion>empty())) {
            assertThat(production.ownClientOnly(other)).as("%s", other).isFalse();
        }
        CapabilityMatrix flagged = TestMatrices.unverified74();

        assertThat(flagged.ownClientOnly(version("7.4.0.90"))).isTrue();
        assertThat(flagged.ownClientOnly(version("8.1.17"))).isFalse();
        assertThat(flagged.ownClientOnly(version("6.1.0.89"))).as("outside every range").isFalse();
        assertThat(flagged.ownClientOnly(Optional.empty())).as("unknown").isFalse();
        assertThat(new CapabilityMatrix.Entry(new VersionRange(7, 9, 7, 9), Map.of(), Map.of(),
                false, Map.of(), PatchDepth.FOUR).ownClientOnly())
            .as("the existing constructors keep false").isFalse();
    }

    @Test
    void comparePatchUsesTheDepthOfTheServerVersion() {
        assertThat(production.comparePatch(client("7.4.0.80"), version("7.4.0.90")))
            .isEqualTo(ClientVersion.Comparison.MISMATCH);
        assertThat(production.comparePatch(client("7.4.0.90"), version("7.4.0.90")))
            .isEqualTo(ClientVersion.Comparison.MATCH);
        assertThat(production.comparePatch(client("7.4.0"), version("7.4.0.90")))
            .isEqualTo(ClientVersion.Comparison.NOT_COMPARABLE);
        assertThat(production.comparePatch(client("6.1.0.89"), version("6.1.0.80")))
            .as("6.1 keeps ignoring the fourth part").isEqualTo(ClientVersion.Comparison.MATCH);
        assertThat(production.comparePatch(client("8.1.17"), Optional.empty()))
            .isEqualTo(ClientVersion.Comparison.NOT_COMPARABLE);
    }

    @Test
    void theCliOnlyLinesAre61And74() {
        assertThat(production.cliOnlyLines()).containsExactly("6.1", "7.4");
    }

    @Test
    void onlyEveryCapabilityNotedIsAllNotYetVerified() {
        // feature 025 (FR-035): no production entry since 7.4 declares two capabilities unverified
        for (Optional<InubitVersion> any : List.of(version("7.4.0.90"), version("6.1.0.89"),
                version("8.1.17"), version("9.0.7"), version("10.0"),
                Optional.<InubitVersion>empty())) {
            assertThat(production.allNotYetVerified(any)).as("%s", any).isFalse();
        }
        Map<Capability, String> everyNote = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            everyNote.put(capability, "not yet verified");
        }
        CapabilityMatrix allNoted = new CapabilityMatrix(List.of(new CapabilityMatrix.Entry(
            new VersionRange(7, 4, 7, 4), Map.of(), Map.of(), true, everyNote)), Map.of());
        assertThat(allNoted.allNotYetVerified(version("7.4.0.90"))).isTrue();
    }

    /** Research D-4: the notes of 7.4 do not make it a "supported once verified" line. */
    @Test
    void v74IsNoSupportedLine() {
        assertThat(production.lines(Capability.QUEUE_ENTRY_READ)).containsExactly("8.1", "9.0");
        assertThat(production.lines(Capability.IO_LOG_READ)).containsExactly("8.1", "9.0");
        for (Capability capability : Capability.values()) {
            assertThat(production.lines(capability)).as("%s", capability).doesNotContain("7.4");
        }
    }

    /**
     * Feature 025 T039 (spec FR-035): once 7.4 declares two capabilities unverified, not every
     * capability carries a note; the notes of a CLI-only entry still make it no supported line.
     */
    @Test
    void theNotesOfACliOnlyEntryMakeItNoSupportedLine() {
        CapabilityMatrix matrix = TestMatrices.unverified74();
        assertThat(matrix.allNotYetVerified(version("7.4.0.90"))).isFalse();
        assertThat(matrix.lines(Capability.QUEUE_ENTRY_READ)).containsExactly("8.1");
        assertThat(matrix.lines(Capability.IO_LOG_READ)).containsExactly("8.1");
    }

    private static ClientVersion client(String value) {
        return new ClientVersion(version(value).orElseThrow(), Optional.empty(), Optional.empty());
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
