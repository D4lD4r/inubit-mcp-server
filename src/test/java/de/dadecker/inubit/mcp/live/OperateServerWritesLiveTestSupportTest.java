package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.Read;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.CliWrite;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.Node;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.Outcome;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.Property;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.ReadyRead;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.ReadySwitch;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.State;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.Step;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.Stop;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opentest4j.TestAbortedException;

/**
 * Feature 022 T054 (write set of L-2, L-3): the offline parts of
 * {@link OperateServerWritesLiveTest} — the steps and their order and forms, the gate, the
 * derived state of the four state reads, the sequence with its stop and restore at every step, the
 * W2u condition, the task folder and the state records. A plain unit test (not tagged
 * {@code live}): fake environments, a simulated node and temporary directories; nothing is
 * contacted.
 */
class OperateServerWritesLiveTestSupportTest {

    private static final NodeId NODE = NodeId.of("test", "node1");
    private static final String STEP_LIST = "W1,W2a,W2b,W3,W2u,W4,W5,W6,W7,W7b,W8,W8b,W9,W10";
    private static final String RAW_LINE = "mail.smtp.password=example-only-0001";

    @TempDir(factory = GitFreeTempDir.class)
    Path temp;

    private final Map<String, String> env = new HashMap<>();

    // --- the write set ---------------------------------------------------------------------------

    @Test
    void theWriteSetIsTheApprovedStepListInOrderAndForm() {
        assertThat(OperateServerWritesLiveTestSupport.STEPS).extracting(Step::id)
            .containsExactly(STEP_LIST.split(","));
        assertThat(OperateServerWritesLiveTestSupport.STEPS).extracting(Step::form)
            .containsExactly("maintenance on -eq false", "GET /ready", "POST /ready/off",
                "maintenance off", "POST /ready/on", "POST /ready/off", "POST /ready/on",
                "trace on", "trace off", "trace on", "trace -l debug", "trace -l info",
                "debug on", "debug off");
        assertThat(OperateServerWritesLiveTestSupport.STEPS).extracting(Step::stem)
            .doesNotHaveDuplicates();
    }

    @Test
    void eachStepExpectsTheApprovedAfterState() {
        assertThat(OperateServerWritesLiveTestSupport.STEPS)
            .extracting(step -> step.expected().describe(step.expected().properties()))
            .containsExactly("maintenance on", "", "ready not READY", "maintenance off",
                "ready READY", "ready not READY", "ready READY", "trace on, trace level info",
                "trace off", "trace on", "trace level debug", "trace level info", "debug on",
                "debug off");
        assertThat(OperateServerWritesLiveTestSupport.STEPS).filteredOn(Step::onlyIfNeeded)
            .extracting(Step::id).containsExactly("W2u");
    }

    @Test
    void theCommandsAreTheProductionFormsAndEndpoint() {
        List<String> cli = OperateServerWritesLiveTestSupport.STEPS.stream()
            .filter(step -> step.action() instanceof CliWrite)
            .map(step -> ((CliWrite) step.action()).command().commandLine()).toList();

        assertThat(cli).containsExactly(
            CliCommand.maintenanceSwitch(true, false).commandLine(),
            CliCommand.maintenanceSwitch(false, null).commandLine(),
            CliCommand.traceSwitch(true).commandLine(),
            CliCommand.traceSwitch(false).commandLine(),
            CliCommand.traceSwitch(true).commandLine(),
            CliCommand.traceLevel(TraceLevel.DEBUG).commandLine(),
            CliCommand.traceLevel(TraceLevel.INFO).commandLine(),
            CliCommand.debugSwitch(true).commandLine(),
            CliCommand.debugSwitch(false).commandLine());
        assertThat(RestEndpoint.READY_SWITCH.method()).isEqualTo(RestEndpoint.Method.POST);
        assertThat(new ReadySwitch(true).form()).isEqualTo("POST /ready/on");
        assertThat(new ReadySwitch(false).form()).isEqualTo("POST /ready/off");
        ReadyRead w2a = (ReadyRead) step("W2a").action();
        assertThat(w2a.read()).isSameAs(read("R3"));
    }

    @Test
    void theRestoreCommandsAreCommandsOfTheSetOnePerProperty() {
        Set<String> catalogue = new HashSet<>(
            OperateServerWritesLiveTestSupport.STEPS.stream().map(Step::form).toList());

        assertThat(OperateServerWritesLiveTestSupport.RESTORE.keySet())
            .containsExactly(Property.values());
        assertThat(OperateServerWritesLiveTestSupport.RESTORE.values()).extracting(Step::form)
            .containsExactly("maintenance off", "POST /ready/on", "trace on", "trace -l info",
                "debug off")
            .allMatch(catalogue::contains);
        assertThat(OperateServerWritesLiveTestSupport.RESTORE_ROUNDS.get(0))
            .containsExactly(Property.MAINTENANCE);
        assertThat(OperateServerWritesLiveTestSupport.RESTORE_ROUNDS.get(1))
            .containsExactly(Property.READINESS, Property.TRACE, Property.TRACE_LEVEL,
                Property.DEBUG);
    }

    @Test
    void theRestorePlanCoversExactlyThePropertiesOffTheBaselineOrUnknown() {
        Set<Property> all = OperateServerWritesLiveTestSupport.ALL;
        State baseline = OperateServerWritesLiveTestSupport.BASELINE;

        assertThat(OperateServerWritesLiveTestSupport.restorePlan(baseline, all)).isEmpty();
        assertThat(OperateServerWritesLiveTestSupport.restorePlan(State.NONE, all))
            .extracting(Step::form).containsExactly("maintenance off", "POST /ready/on",
                "trace on", "trace -l info", "debug off");
        assertThat(OperateServerWritesLiveTestSupport.restorePlan(baseline
            .with(Property.TRACE_LEVEL, "debug").with(Property.READINESS, "not READY"), all))
            .extracting(Step::form).containsExactly("POST /ready/on", "trace -l info");
        assertThat(OperateServerWritesLiveTestSupport.restorePlan(State.NONE,
            Set.of(Property.MAINTENANCE))).extracting(Step::form)
            .containsExactly("maintenance off");
    }

    @Test
    void theStateReadsAreR1R3R6AndR8OfTheReadSet() {
        assertThat(OperateServerWritesLiveTestSupport.stateReads(
            OperateServerWritesLiveTestSupport.ALL)).extracting(Read::id)
            .containsExactly("R1", "R3", "R6", "R8");
        assertThat(OperateServerWritesLiveTestSupport.stateReads(
            Set.of(Property.TRACE, Property.TRACE_LEVEL))).extracting(Read::id)
            .containsExactly("R8");
        assertThat(OperateServerWritesLiveTestSupport.stateReads(Set.of(Property.MAINTENANCE)))
            .extracting(Read::form).containsExactly("maintenance -s");
    }

    // --- gate ------------------------------------------------------------------------------------

    @Test
    void withoutTheApprovalTheWriteSetIsSkipped() {
        assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.requireApproval(env))
            .isInstanceOf(TestAbortedException.class)
            .hasMessageContaining("INUBIT_LIVE_T054_WRITES");
        env.put("INUBIT_LIVE_T054_WRITES", " ");
        assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.requireApproval(env))
            .isInstanceOf(TestAbortedException.class);
    }

    @Test
    void theFullStepListPasses() {
        env.put("INUBIT_LIVE_T054_WRITES", STEP_LIST);
        assertThatCode(() -> OperateServerWritesLiveTestSupport.requireApproval(env))
            .doesNotThrowAnyException();

        env.put("INUBIT_LIVE_T054_WRITES", " " + STEP_LIST.replace(",", ", ") + " ");
        assertThatCode(() -> OperateServerWritesLiveTestSupport.requireApproval(env))
            .doesNotThrowAnyException();
    }

    @Test
    void anyOtherListFailsBeforeContact() {
        for (String value : List.of("approved", "W1", STEP_LIST.replace("W2u,", ""),
            STEP_LIST + ",W11", STEP_LIST.replace("W4,W5", "W5,W4"), STEP_LIST + ",",
            STEP_LIST.toLowerCase(Locale.ROOT))) {
            env.put("INUBIT_LIVE_T054_WRITES", value);

            assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.requireApproval(env))
                .as(value)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("INUBIT_LIVE_T054_WRITES must list exactly " + STEP_LIST)
                .hasMessageContaining("nothing was run");
        }
    }

    @Test
    void aNodeWithoutWriteEnabledIsRefused() {
        assertThatCode(() -> OperateServerWritesLiveTestSupport.requireWriteEnabled(NODE, true))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.requireWriteEnabled(NODE,
            false))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("test/node1 does not have write enabled in the profile")
            .hasMessageContaining("nothing was run");
    }

    // --- state parsing ---------------------------------------------------------------------------

    @Test
    void maintenanceSShowsOnOrOff() {
        assertThat(cli("R1", "off")).isEqualTo(State.NONE.with(Property.MAINTENANCE, "off"));
        assertThat(cli("R1", "", " on ", "")).isEqualTo(State.NONE.with(Property.MAINTENANCE,
            "on"));
        for (List<String> other : List.of(List.<String>of(), List.of("Maintenance mode: on"),
            List.of("on", "off"), List.of("ON"), List.of(RAW_LINE))) {
            assertThat(OperateServerWritesLiveTestSupport.parseCli("R1", other)).as("%s", other)
                .isEqualTo(State.NONE);
        }
    }

    @Test
    void debugShowsTheRecordedSentence() {
        assertThat(cli("R6", "Debug mode is currently off."))
            .isEqualTo(State.NONE.with(Property.DEBUG, "off"));
        assertThat(cli("R6", "Debug mode is currently on."))
            .isEqualTo(State.NONE.with(Property.DEBUG, "on"));
        for (List<String> other : List.of(List.of("Debug mode: on"),
            List.of("Debug mode is currently on"), List.of("Debug mode is currently on.", "x"))) {
            assertThat(OperateServerWritesLiveTestSupport.parseCli("R6", other)).as("%s", other)
                .isEqualTo(State.NONE);
        }
    }

    @Test
    void getReadyIsReadyOnlyWith200AndStatusReady() {
        assertThat(rest("R3", 200, fixture("ready.json")))
            .isEqualTo(State.NONE.with(Property.READINESS, "READY"));
        assertThat(rest("R3", 503, fixture("ready_not_ready.json")))
            .isEqualTo(State.NONE.with(Property.READINESS, "not READY"));
        assertThat(rest("R3", 503, "<html>maintenance</html>"))
            .isEqualTo(State.NONE.with(Property.READINESS, "not READY"));
        assertThat(rest("R3", 200, fixture("ready_not_ready.json"))).isEqualTo(State.NONE);
        assertThat(rest("R3", 200, "<html/>")).isEqualTo(State.NONE);
        assertThat(rest("R3", 404, fixture("ready.json"))).isEqualTo(State.NONE);
    }

    @Test
    void systemInfoShowsTheTraceAndItsLevel() {
        String recorded = fixture("system_info.xml");

        assertThat(rest("R8", 200, recorded)).isEqualTo(State.NONE.with(Property.TRACE, "on")
            .with(Property.TRACE_LEVEL, "info"));
        assertThat(rest("R8", 200, recorded
            .replace("name=\"TracingIsActive\" value=\"true\"",
                "name=\"TracingIsActive\" value=\"false\"")
            .replace("name=\"TracingLevel\" value=\"info\"",
                "name=\"TracingLevel\" value=\"debug\"")))
            .isEqualTo(State.NONE.with(Property.TRACE, "off").with(Property.TRACE_LEVEL, "debug"));
        assertThat(rest("R8", 200, recorded.replace("name=\"TracingLevel\" value=\"info\"",
            "name=\"TracingLevel\" value=\"" + RAW_LINE + "\"")))
            .isEqualTo(State.NONE.with(Property.TRACE, "on"));
        assertThat(rest("R8", 503, recorded)).isEqualTo(State.NONE);
        assertThat(rest("R8", 200, "{\"status\": \"READY\"}")).isEqualTo(State.NONE);
        assertThat(rest("R8", 200, "<Other/>")).isEqualTo(State.NONE);
    }

    @Test
    void aStateShowsUnknownForWhatItLacks() {
        State state = State.NONE.with(Property.MAINTENANCE, "on");

        assertThat(state.describe(OperateServerWritesLiveTestSupport.ALL)).isEqualTo(
            "maintenance on, ready unknown, trace unknown, trace level unknown, debug unknown");
        assertThat(state.offBaseline(OperateServerWritesLiveTestSupport.ALL))
            .containsExactly(Property.values());
        assertThat(OperateServerWritesLiveTestSupport.BASELINE
            .offBaseline(OperateServerWritesLiveTestSupport.ALL)).isEmpty();
        assertThat(OperateServerWritesLiveTestSupport.BASELINE.describe(
            OperateServerWritesLiveTestSupport.ALL)).isEqualTo(
            "maintenance off, ready READY, trace on, trace level info, debug off");
    }

    @Test
    void aCommandFailsWithItsCodeOrAnExitOtherThanZero() {
        assertThat(OperateServerWritesLiveTestSupport.failure(Optional.empty(), Optional.of(0)))
            .isEmpty();
        assertThat(OperateServerWritesLiveTestSupport.failure(Optional.empty(), Optional.empty()))
            .isEmpty();
        assertThat(OperateServerWritesLiveTestSupport.failure(Optional.empty(), Optional.of(3)))
            .contains("exit 3");
        assertThat(OperateServerWritesLiveTestSupport.failure(Optional.of(ErrorCode.TIMEOUT),
            Optional.of(0))).contains("TIMEOUT");
    }

    // --- the sequence ----------------------------------------------------------------------------

    @Test
    void aCleanRunRunsEveryStepOnceInOrderAndRestoresNothing() throws IOException {
        SimulatedNode node = new SimulatedNode();

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node);

        assertThat(node.ran).containsExactly(STEP_LIST.split(","));
        assertThat(node.reads).containsExactly("B0", "W1-after", "W2b-after", "W3-after",
            "W2u-check", "W2u-after", "W4-after", "W5-after", "W6-after", "W7-after",
            "W7b-after", "W8-after", "W8b-after", "W9-after", "W10-after", "B1");
        assertThat(outcome).isEqualTo(new Outcome(Optional.empty(), List.of(),
            OperateServerWritesLiveTestSupport.BASELINE));
        assertThatCode(() -> OperateServerWritesLiveTestSupport.assertOutcome(outcome))
            .doesNotThrowAnyException();
        assertThat(node.records).contains(
            "W1 before maintenance off; after maintenance on",
            "W2b before ready READY; after ready not READY",
            "W6 before trace on, trace level info; after trace on, trace level info");
    }

    @Test
    void w2uRunsOnlyIfTheNodeIsNotReadyAfterW3() throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.maintenanceOffMakesReady = true;

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node);

        assertThat(node.ran).doesNotContain("W2u").contains("W3", "W4");
        assertThat(node.records).contains("W2u not needed (ready READY)");
        assertThat(outcome.stop()).isEmpty();
    }

    @Test
    void withoutTheBaselineNothingIsWritten() {
        SimulatedNode node = new SimulatedNode();
        node.server.put(Property.DEBUG, "on");

        assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.run(node))
            .isInstanceOf(AssertionError.class)
            .hasMessage("the baseline does not hold before W1 (debug on, expected debug off);"
                + " nothing was written");
        assertThat(node.ran).isEmpty();
        assertThat(node.reads).containsExactly("B0");
    }

    /**
     * An error at each step, the command without effect: the steps before it ran once each, none
     * after it, and only the properties then off the baseline are restored, in order, once.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "W1  |",
        "W2a | maintenance off",
        "W2b | maintenance off",
        "W3  | maintenance off, POST /ready/on",
        "W2u | POST /ready/on",
        "W4  |",
        "W5  | POST /ready/on",
        "W6  |",
        "W7  |",
        "W7b | trace on",
        "W8  |",
        "W8b | trace -l info",
        "W9  |",
        "W10 | debug off"})
    void anErrorStopsTheSequenceAndRestoresOnlyWhatIsOffTheBaseline(String step,
        String restores) throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.failing = step;

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node);

        List<String> ids = List.of(STEP_LIST.split(","));
        List<String> expected = restores == null ? List.of() : List.of(restores.split(", "));
        assertThat(node.ran).containsExactlyElementsOf(
            concat(ids.subList(0, ids.indexOf(step) + 1), expected));
        assertThat(outcome.stop()).contains(new Stop(step, "TIMEOUT"));
        assertThat(outcome.restored()).containsExactlyElementsOf(expected);
        assertThat(outcome.end()).isEqualTo(OperateServerWritesLiveTestSupport.BASELINE);
        assertThat(node.reads).endsWith("X1", "X2", "B1");
        assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.assertOutcome(outcome))
            .isInstanceOf(AssertionError.class)
            .hasMessage("T054 write " + step + " stopped the sequence: TIMEOUT; restored: "
                + (expected.isEmpty() ? "nothing" : String.join(", ", expected))
                + "; the baseline holds again");
    }

    @Test
    void aCommandWithoutEffectIsADeviation() throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.withoutEffect = "W7";

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node);

        assertThat(outcome.stop()).contains(new Stop("W7", "DEVIATION (trace on, expected trace"
            + " off)"));
        assertThat(node.ran).last().isEqualTo("W7");
        assertThat(outcome.restored()).isEmpty();
    }

    @Test
    void aDeviationStopsBeforeTheNextStep() throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.withoutEffect = "W8";

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node);

        assertThat(outcome.stop()).contains(new Stop("W8", "DEVIATION (trace level info,"
            + " expected trace level debug)"));
        assertThat(node.ran).last().isEqualTo("W8");
        assertThat(outcome.restored()).isEmpty();
    }

    @Test
    void aCommandThatChangedTheStateButFailedIsUndone() throws IOException {
        for (String step : List.of("W1", "W7", "W8", "W9")) {
            SimulatedNode node = new SimulatedNode();
            node.failingAfterEffect = step;

            Outcome outcome = OperateServerWritesLiveTestSupport.run(node);

            assertThat(outcome.stop()).as(step).contains(new Stop(step, "exit 1"));
            assertThat(outcome.restored()).as(step).containsExactly(switch (step) {
                case "W1" -> "maintenance off";
                case "W7" -> "trace on";
                case "W8" -> "trace -l info";
                default -> "debug off";
            });
            assertThat(outcome.end()).isEqualTo(OperateServerWritesLiveTestSupport.BASELINE);
        }
    }

    @Test
    void anExceptionInAStepStopsAndRestoresAsWell() throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.throwingAfterEffect = "W9";

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node);

        assertThat(outcome.stop()).contains(new Stop("W9", "IOException"));
        assertThat(outcome.restored()).containsExactly("debug off");
    }

    @Test
    void anUnreadablePropertyCountsAsOffTheBaseline() throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.failing = "W4";
        node.unreadable = "X2";

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node);

        assertThat(outcome.restored()).containsExactly("POST /ready/on", "trace on",
            "trace -l info", "debug off");
    }

    /**
     * The maintenance restore fails, so the node stays in maintenance: the trace is then
     * unreadable and counts as off the baseline; every restore command still runs once only.
     */
    @Test
    void aFailedRestoreIsNotRetriedAndTheTestNamesTheStateLeft() throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.failingAfterEffect = "W1";
        node.restoresFail = true;

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node);

        assertThat(outcome.restored()).containsExactly("maintenance off failed: TIMEOUT",
            "POST /ready/on failed: TIMEOUT", "trace on failed: TIMEOUT",
            "trace -l info failed: TIMEOUT");
        assertThat(node.ran).containsExactly("W1", "maintenance off", "POST /ready/on",
            "trace on", "trace -l info");
        assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.assertOutcome(outcome))
            .isInstanceOf(AssertionError.class)
            .hasMessage("T054 write W1 stopped the sequence: exit 1; restored: maintenance off"
                + " failed: TIMEOUT, POST /ready/on failed: TIMEOUT, trace on failed: TIMEOUT,"
                + " trace -l info failed: TIMEOUT; the baseline does not hold (maintenance on,"
                + " ready not READY, trace unknown, trace level unknown, expected maintenance off,"
                + " ready READY, trace on, trace level info)");
    }

    @Test
    void aRunThatEndsOffTheBaselineFails() {
        Outcome outcome = new Outcome(Optional.empty(), List.of(),
            OperateServerWritesLiveTestSupport.BASELINE.with(Property.TRACE_LEVEL, "debug"));

        assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.assertOutcome(outcome))
            .isInstanceOf(AssertionError.class)
            .hasMessage("T054 write set ran to the end, but the baseline does not hold (trace"
                + " level debug, expected trace level info)");
    }

    // --- task folder and records -----------------------------------------------------------------

    @Test
    void theWriteFolderIsSeparateAndLeavesTheReadRecordingUntouched() throws IOException {
        Path records = temp.resolve("records/022");
        Path reads = Files.createDirectories(records.resolve("T054"));
        Files.writeString(reads.resolve("R1-maintenance-s.stdout"), "off\n");

        assertThatCode(() -> OperateServerReadsLiveTestSupport.requireNoTaskDirectory(records,
            OperateServerWritesLiveTestSupport.TASK_DIRECTORY)).doesNotThrowAnyException();
        Path directory = OperateServerReadsLiveTestSupport.taskDirectory(PrivateFiles.forHost(),
            records, OperateServerWritesLiveTestSupport.TASK_DIRECTORY);

        assertThat(directory).isEqualTo(records.resolve("T054-writes"));
        assertOwnerOnly(directory, "rwx------");
        assertThat(fileNames(reads)).containsExactly("R1-maintenance-s.stdout");
        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.requireNoTaskDirectory(records,
            OperateServerWritesLiveTestSupport.TASK_DIRECTORY))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("T054-writes already exists")
            .hasMessageContaining("nothing was run");
    }

    @Test
    void theStatesOfAStepAreRecordedOwnerOnlyAndItsLineHoldsDerivedStatesOnly()
        throws IOException {
        Path directory = OperateServerReadsLiveTestSupport.taskDirectory(PrivateFiles.forHost(),
            temp.resolve("records"), OperateServerWritesLiveTestSupport.TASK_DIRECTORY);

        String line = OperateServerWritesLiveTestSupport.recordStates(PrivateFiles.forHost(),
            directory, NODE, step("W1"), State.NONE.with(Property.MAINTENANCE, "off"),
            State.NONE.with(Property.MAINTENANCE, "on"));
        String skipped = OperateServerWritesLiveTestSupport.recordNotNeeded(
            PrivateFiles.forHost(), directory, NODE, step("W2u"),
            State.NONE.with(Property.READINESS, "READY"));

        assertThat(fileNames(directory)).containsExactlyInAnyOrder(
            "W1-maintenance-on-eq-false.state", "W2u-post-ready-on.state");
        assertThat(directory.resolve("W1-maintenance-on-eq-false.state"))
            .hasContent("before maintenance off; after maintenance on; expected maintenance on\n");
        assertThat(directory.resolve("W2u-post-ready-on.state"))
            .hasContent("not needed (ready READY)\n");
        assertOwnerOnly(directory.resolve("W1-maintenance-on-eq-false.state"), "rw-------");
        assertOwnerOnly(directory.resolve("W2u-post-ready-on.state"), "rw-------");
        assertThat(line).isEqualTo("[live] T054 W1 maintenance on -eq false on test/node1: before"
            + " maintenance off; after maintenance on; expected maintenance on");
        assertThat(skipped).isEqualTo(
            "[live] T054 W2u POST /ready/on on test/node1: not needed (ready READY)");
    }

    @Test
    void theLinesOfAWriteAndAStateReadHoldNoOutput() throws IOException {
        Path directory = OperateServerReadsLiveTestSupport.taskDirectory(PrivateFiles.forHost(),
            temp.resolve("records"), OperateServerWritesLiveTestSupport.TASK_DIRECTORY);
        byte[] body = ("{\"status\": \"READY\", \"message\": \"" + RAW_LINE + "\"}")
            .getBytes(StandardCharsets.UTF_8);

        String post = OperateServerReadsLiveTestSupport.recordRest(PrivateFiles.forHost(),
            directory, NODE, step("W5"),
            new RestResponse(200, Optional.empty(), body));
        String state = OperateServerWritesLiveTestSupport.stateLine(NODE, "W5-after",
            OperateServerWritesLiveTestSupport.parseRest("R3", 200, body),
            Set.of(Property.READINESS));

        assertThat(post).isEqualTo("[live] T054 W5 POST /ready/on on test/node1: status 200, body "
            + body.length + " bytes");
        assertThat(state).isEqualTo("[live] T054 W5-after state on test/node1: ready READY");
        assertThat(post + state).doesNotContain(RAW_LINE);
        assertThat(Files.readAllBytes(directory.resolve("W5-post-ready-on.body"))).isEqualTo(body);
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * A node that applies the commands of the set to its state. While maintenance is on, {@code
     * GET /ready} answers not READY and {@code /system/info} is unreadable.
     */
    private static final class SimulatedNode implements Node {

        final Map<Property, String> server =
            new EnumMap<>(OperateServerWritesLiveTestSupport.BASELINE.values());
        /** The ids of the steps and the forms of the restore commands, as they ran. */
        final List<String> ran = new ArrayList<>();
        final List<String> reads = new ArrayList<>();
        final List<String> records = new ArrayList<>();
        boolean maintenanceOffMakesReady;
        String failing = "";
        String failingAfterEffect = "";
        String throwingAfterEffect = "";
        String withoutEffect = "";
        String unreadable = "";
        boolean restoresFail;

        @Override
        public State read(String label, Set<Property> properties) throws IOException {
            reads.add(label);
            if (label.equals(unreadable)) {
                throw new IOException("unreadable");
            }
            State state = State.NONE;
            for (Property property : properties) {
                Optional<String> value = visible(property);
                if (value.isPresent()) {
                    state = state.with(property, value.get());
                }
            }
            return state;
        }

        private Optional<String> visible(Property property) {
            boolean maintenance = server.get(Property.MAINTENANCE).equals("on");
            return switch (property) {
                case READINESS -> Optional.of(maintenance ? "not READY"
                    : server.get(Property.READINESS));
                case TRACE, TRACE_LEVEL -> maintenance ? Optional.empty()
                    : Optional.of(server.get(property));
                default -> Optional.of(server.get(property));
            };
        }

        @Override
        public Optional<String> run(Step step) throws IOException {
            boolean restore = step.id().equals("restore");
            ran.add(restore ? step.form() : step.id());
            if (restore && restoresFail || step.id().equals(failing)) {
                return Optional.of("TIMEOUT");
            }
            if (!step.id().equals(withoutEffect)) {
                apply(step.form());
            }
            if (step.id().equals(throwingAfterEffect)) {
                throw new IOException(RAW_LINE);
            }
            return step.id().equals(failingAfterEffect) ? Optional.of("exit 1") : Optional.empty();
        }

        private void apply(String form) {
            switch (form) {
                case "maintenance on -eq false" -> server.put(Property.MAINTENANCE, "on");
                case "maintenance off" -> {
                    server.put(Property.MAINTENANCE, "off");
                    if (maintenanceOffMakesReady) {
                        server.put(Property.READINESS, "READY");
                    }
                }
                case "GET /ready" -> { }
                case "POST /ready/off" -> server.put(Property.READINESS, "not READY");
                case "POST /ready/on" -> server.put(Property.READINESS, "READY");
                case "trace on" -> server.put(Property.TRACE, "on");
                case "trace off" -> server.put(Property.TRACE, "off");
                case "trace -l debug" -> server.put(Property.TRACE_LEVEL, "debug");
                case "trace -l info" -> server.put(Property.TRACE_LEVEL, "info");
                case "debug on" -> server.put(Property.DEBUG, "on");
                case "debug off" -> server.put(Property.DEBUG, "off");
                default -> throw new AssertionError("not a command of the set: " + form);
            }
        }

        @Override
        public void recordStates(Step step, State before, State after) {
            records.add(step.id() + " before " + before.describe(step.expected().properties())
                + "; after " + after.describe(step.expected().properties()));
        }

        @Override
        public void recordNotNeeded(Step step, State check) {
            records.add(step.id() + " not needed ("
                + check.describe(step.expected().properties()) + ")");
        }
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static Step step(String id) {
        return OperateServerWritesLiveTestSupport.STEPS.stream()
            .filter(step -> step.id().equals(id)).findFirst().orElseThrow();
    }

    private static Read read(String id) {
        return OperateServerReadsLiveTestSupport.READS.stream()
            .filter(read -> read.id().equals(id)).findFirst().orElseThrow();
    }

    private static State cli(String readId, String... lines) {
        return OperateServerWritesLiveTestSupport.parseCli(readId, List.of(lines));
    }

    private static State rest(String readId, int status, String body) {
        return OperateServerWritesLiveTestSupport.parseRest(readId, status,
            body.getBytes(StandardCharsets.UTF_8));
    }

    private static String fixture(String name) {
        try (InputStream in = OperateServerWritesLiveTestSupportTest.class.getResourceAsStream(
            "/fixtures/v8_1/rest/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).toList();
        }
    }

    private static void assertOwnerOnly(Path path, String posixPermissions) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
                .as(path.toString()).isEqualTo(posixPermissions);
        } else {
            assertThat(PrivateFiles.forHost().check(path)).as(path.toString()).isEmpty();
        }
    }
}
