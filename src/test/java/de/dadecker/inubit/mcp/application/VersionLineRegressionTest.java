package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.domain.model.SupportState.SUPPORTED;
import static de.dadecker.inubit.mcp.domain.model.SupportState.UNSUPPORTED;
import static de.dadecker.inubit.mcp.domain.model.SupportState.UNVERIFIED;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import de.dadecker.inubit.mcp.application.VersionGate.Evaluation;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feature 025 (T003, research D-13, FR-020, SC-005): the regression baseline of the 6.1, 8.1 and
 * 9.0 lines, written against the code before 7.4 support. For the line values {@code V6_1},
 * {@code V8_1}, {@code V9_0}, {@code V9_X} and the pins {@code 6.1.0.89}, {@code 8.1.17},
 * {@code 9.0.10} it pins the parsed version, the client comparison, the production matrix state of
 * every capability, whether the version is CLI-only, and the refusal texts of the version gate.
 * The later tasks of the feature must pass it unchanged.
 */
class VersionLineRegressionTest {

    private static final NodeId NODE = NodeId.parse("dev/node1");
    private static final CapabilityMatrix MATRIX = CapabilityMatrix.production();

    /** The column of {@link #STATES} a line value or pin is looked up in. */
    private enum Column { V6_1, V8_1, V9_0, UNKNOWN }

    /** The production state of every capability on 6.1, 8.1, 9.0 and an unknown version. */
    private static final Map<Capability, List<SupportState>> STATES = Map.ofEntries(
        entry(Capability.MONITORING, List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNVERIFIED)),
        entry(Capability.PROCESS_DIAGNOSIS,
            List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNVERIFIED)),
        entry(Capability.LOG_DIAGNOSIS, List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNVERIFIED)),
        entry(Capability.INVENTORY, List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNVERIFIED)),
        entry(Capability.PARTNER_MANAGEMENT,
            List.of(UNSUPPORTED, SUPPORTED, UNVERIFIED, UNVERIFIED)),
        entry(Capability.WORKSPACE_EXPORT,
            List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNVERIFIED)),
        entry(Capability.DEVELOPMENT_WRITE,
            List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNSUPPORTED)),
        entry(Capability.DEPLOYMENT, List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNSUPPORTED)),
        entry(Capability.E2E_TEST, List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNSUPPORTED)),
        entry(Capability.PROCESS_CONTROL,
            List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNSUPPORTED)),
        entry(Capability.QUEUE_ENTRY_READ,
            List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNSUPPORTED)),
        entry(Capability.TRACE_LOG_READ, List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNSUPPORTED)),
        entry(Capability.WEBSERVICE_LOG_READ,
            List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNSUPPORTED)),
        entry(Capability.SERVER_SETTINGS,
            List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNSUPPORTED)),
        entry(Capability.USER_DIRECTORY, List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNSUPPORTED)),
        entry(Capability.MODEL_DETAILS, List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNSUPPORTED)),
        entry(Capability.CLUSTER_NODES_READ,
            List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNSUPPORTED)),
        entry(Capability.IO_LOG_READ, List.of(UNSUPPORTED, UNSUPPORTED, UNSUPPORTED, UNSUPPORTED)),
        entry(Capability.NODE_CONFIG_READ,
            List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNSUPPORTED)),
        entry(Capability.LOG_LEVEL_WRITE,
            List.of(UNSUPPORTED, SUPPORTED, SUPPORTED, UNSUPPORTED)),
        entry(Capability.SERVER_OPERATION,
            List.of(UNSUPPORTED, SUPPORTED, UNSUPPORTED, UNSUPPORTED)),
        entry(Capability.TAG_ADMINISTRATION,
            List.of(UNSUPPORTED, UNSUPPORTED, UNSUPPORTED, UNSUPPORTED)));

    /**
     * The four components of an {@link InubitVersion} this baseline pins; compared on its own so
     * that a component added to the record later leaves the baseline unchanged.
     */
    private record Parsed(int major, int minor, OptionalInt patch, String raw) {

        static Parsed of(InubitVersion version) {
            return new Parsed(version.major(), version.minor(), version.patch(), version.raw());
        }
    }

    /**
     * A line value or pin with what the code makes of it.
     *
     * @param parsed     the version reads use without an observation (empty: unknown)
     * @param name       the version as refusals name it ({@link VersionGate#name})
     * @param majorMinor the release as refusals name it ({@link VersionGate#majorMinor})
     */
    private record Line(String setting, Optional<Parsed> parsed, String name,
        String majorMinor, Column column, boolean cliOnly) {

        /** The version the code parses from {@link #setting} (the effective version of a node). */
        Optional<InubitVersion> version() {
            return NodeVersionState.initial(VersionSetting.parse(setting).orElseThrow())
                .effectiveVersion();
        }

        @Override
        public String toString() {
            return setting;
        }
    }

    static Stream<Line> lines() {
        return Stream.of(
            new Line("V6_1", parsed(6, 1, OptionalInt.empty(), "6.1"),
                "INUBIT 6.1", "6.1", Column.V6_1, true),
            new Line("V8_1", parsed(8, 1, OptionalInt.empty(), "8.1"),
                "INUBIT 8.1", "8.1", Column.V8_1, false),
            new Line("V9_0", parsed(9, 0, OptionalInt.empty(), "9.0"),
                "INUBIT 9.0", "9.0", Column.V9_0, false),
            new Line("V9_X", Optional.empty(), "INUBIT 9.x (versionLine V9_X)", "9.x",
                Column.UNKNOWN, false),
            new Line("6.1.0.89", parsed(6, 1, OptionalInt.of(0), "6.1.0.89"),
                "INUBIT 6.1.0.89", "6.1", Column.V6_1, true),
            new Line("8.1.17", parsed(8, 1, OptionalInt.of(17), "8.1.17"),
                "INUBIT 8.1.17", "8.1", Column.V8_1, false),
            new Line("9.0.10", parsed(9, 0, OptionalInt.of(10), "9.0.10"),
                "INUBIT 9.0.10", "9.0", Column.V9_0, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lines")
    void theSettingParsesToItsVersionAndName(Line line) {
        VersionSetting setting = VersionSetting.parse(line.setting()).orElseThrow();
        NodeVersionState state = NodeVersionState.initial(setting);
        Optional<InubitVersion> version = state.effectiveVersion();

        assertThat(setting.text()).isEqualTo(line.setting());
        assertThat(version.map(Parsed::of)).isEqualTo(line.parsed());
        if (setting instanceof VersionSetting.Pinned) {
            assertThat(InubitVersion.parse(line.setting()).map(Parsed::of))
                .isEqualTo(line.parsed());
        }
        assertThat(VersionGate.name(state, version)).contains(line.name());
        assertThat(VersionGate.majorMinor(state, version)).isEqualTo(line.majorMinor());
    }

    /**
     * Today's three-part rule: the patch is the third component, a fourth is ignored; a missing
     * patch or an unknown server (empty, as on {@code V9_X}) is not comparable.
     */
    @ParameterizedTest(name = "client {0} vs INUBIT {1}: {2}")
    @CsvSource({
        "6.1.0.89, 6.1.0.89, MATCH",
        "6.1.0.89, 6.1.0.80, MATCH",
        "6.1.0.89, 6.1.1,    MISMATCH",
        "6.1,      6.1.0.89, NOT_COMPARABLE",
        "6.1.0.89, 6.1,      NOT_COMPARABLE",
        "8.1.17,   8.1.17,   MATCH",
        "8.1.17,   8.1.16,   MISMATCH",
        "8.1,      8.1.17,   NOT_COMPARABLE",
        "8.1.17,   8.1,      NOT_COMPARABLE",
        "9.0.10,   9.0.10,   MATCH",
        "9.0.10,   9.0.9,    MISMATCH",
        "9.0,      9.0.10,   NOT_COMPARABLE",
        "9.0.10,   9.0,      NOT_COMPARABLE",
        "9.0.10,           , NOT_COMPARABLE",
    })
    void theClientIsComparedOnMajorMinorAndPatch(String client, String server,
        ClientVersion.Comparison expected) {
        ClientVersion version = new ClientVersion(InubitVersion.parse(client).orElseThrow(),
            Optional.empty(), Optional.empty());

        assertThat(version.compare(InubitVersion.parse(server))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lines")
    void theProductionMatrixDeclaresEveryCapability(Line line) {
        Optional<InubitVersion> version = line.version();
        Map<Capability, SupportState> expected = new EnumMap<>(Capability.class);
        STATES.forEach((capability, states) ->
            expected.put(capability, states.get(line.column().ordinal())));
        Map<Capability, SupportState> actual = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            actual.put(capability, MATRIX.state(version, capability));
        }

        assertThat(actual).isEqualTo(expected);
        if (VersionSetting.parse(line.setting()).orElseThrow()
            instanceof VersionSetting.Line configured) {
            for (Capability capability : Capability.values()) {
                assertThat(MATRIX.stateOfLine(configured.major(), configured.minor(), capability))
                    .as("%s", capability).isEqualTo(expected.get(capability));
            }
        }
        assertThat(MATRIX.isCliOnly(version)).isEqualTo(line.cliOnly());
    }

    /** One refused capability per line value and pin, with the refusal's summary. */
    static Stream<Arguments> refusals() {
        return Stream.of(
            arguments("V6_1", Capability.LOG_DIAGNOSIS, "INUBIT 6.1 (versionLine V6_1) on"
                + " dev/node1 does not support log diagnosis; read the logs of INUBIT 6.1 in the"
                + " Workbench"),
            arguments("6.1.0.89", Capability.LOG_DIAGNOSIS, "INUBIT 6.1.0.89 on dev/node1 does"
                + " not support log diagnosis; read the logs of INUBIT 6.1 in the Workbench"),
            arguments("V8_1", Capability.IO_LOG_READ, "INUBIT 8.1 on dev/node1: reading module"
                + " input/output logging is not yet verified for INUBIT 8.1"),
            arguments("8.1.17", Capability.IO_LOG_READ, "INUBIT 8.1.17 on dev/node1: reading"
                + " module input/output logging is not yet verified for INUBIT 8.1"),
            arguments("V9_0", Capability.IO_LOG_READ, "INUBIT 9.0 on dev/node1: reading module"
                + " input/output logging is not yet verified for INUBIT 9.0"),
            arguments("9.0.10", Capability.IO_LOG_READ, "INUBIT 9.0.10 on dev/node1: reading"
                + " module input/output logging is not yet verified for INUBIT 9.0"),
            arguments("V9_X", Capability.SERVER_SETTINGS, "INUBIT 9.x (versionLine V9_X) on"
                + " dev/node1 does not support reading server settings yet"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("refusals")
    void theGateRefusesWithTheSummaryOfItsLine(String setting, Capability capability,
        String cause) {
        assertRefusal(evaluate(setting, capability), cause);
    }

    /** The {@code supportedLines} text of a queue-entry refusal, on and outside every range. */
    static Stream<Arguments> queueEntryRefusals() {
        return Stream.of(
            arguments("V6_1", "INUBIT 6.1 (versionLine V6_1) on dev/node1 does not support"
                + " reading Queue Manager entries; supported: 8.1, 9.0; use the Queue Manager in"
                + " the Workbench"),
            arguments("8.0.5", "INUBIT 8.0.5 on dev/node1 does not support reading Queue Manager"
                + " entries yet; supported: 8.1, 9.0"),
            arguments("V9_X", "INUBIT 9.x (versionLine V9_X) on dev/node1 does not support"
                + " reading Queue Manager entries yet; supported: 8.1, 9.0"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queueEntryRefusals")
    void aQueueEntryReadRefusalNamesTheSupportedLines(String setting, String cause) {
        assertRefusal(evaluate(setting, Capability.QUEUE_ENTRY_READ), cause);
        assertThat(MATRIX.lines(Capability.QUEUE_ENTRY_READ)).containsExactly("8.1", "9.0");
    }

    /** The gate's judgement of {@code capability} on a node set to {@code setting}. */
    private static Evaluation evaluate(String setting, Capability capability) {
        NodeVersions versions = new NodeVersions(Clock.systemUTC(),
            node -> VersionSetting.parse(setting).orElseThrow(), node -> Duration.ofMinutes(10));
        FakeVersionDetection detection = new FakeVersionDetection(versions);
        Evaluation evaluation = FakeVersionDetection.gateWithoutClients(versions, detection,
            MATRIX).evaluate(NODE, capability, capability.kind(), capability.channels());
        assertThat(detection.requests()).as("no version detection").isEmpty();
        return evaluation;
    }

    private static void assertRefusal(Evaluation evaluation, String cause) {
        assertThat(evaluation.cause()).contains(cause);
        assertThat(evaluation.refusal()).map(ToolError::code)
            .contains(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(evaluation.refusal()).map(ToolError::message)
            .contains(cause + "; nothing was sent");
    }

    private static Optional<Parsed> parsed(int major, int minor, OptionalInt patch, String raw) {
        return Optional.of(new Parsed(major, minor, patch, raw));
    }
}
