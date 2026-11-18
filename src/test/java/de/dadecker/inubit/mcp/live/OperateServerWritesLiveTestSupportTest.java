package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.cli.UnlistedWriteCommand;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.adapter.rest.TransportErrorTexts;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.Read;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.RestRead;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.Answer;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.CliWrite;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.ErrorSetRead;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.GatedNode;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
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
 * W2u condition, the owner-gated W11 and W12 (their list, W12's approved ERROR set and its
 * comparison, the trigger time), the task folder and the state records. A plain unit test (not
 * tagged {@code live}): fake environments, a simulated node and temporary directories; nothing is
 * contacted.
 */
class OperateServerWritesLiveTestSupportTest {

    private static final NodeId NODE = NodeId.of("test", "node1");
    private static final String STEP_LIST = "W1,W2a,W2b,W3,W2u,W4,W5,W6,W7,W7b,W8,W8b,W9,W10";
    private static final String SUBSET_LIST = "W5,W6,W7,W7b,W8,W8b,W9,W10";
    private static final List<Step> FULL = OperateServerWritesLiveTestSupport.STEPS;
    private static final List<Step> SUBSET = OperateServerWritesLiveTestSupport.W5_TO_W10;
    private static final Step W11 = OperateServerWritesLiveTestSupport.W11;
    private static final Step W12 = OperateServerWritesLiveTestSupport.W12;
    private static final List<Step> OWNER_GATED = OperateServerWritesLiveTestSupport.OWNER_GATED;
    private static final String RAW_LINE = "mail.smtp.password=example-only-0001";
    /** The answers of {@code GET /ready} recorded in live run 1 (INUBIT 8.1.17): maintenance on. */
    private static final String IN_MAINTENANCE =
        "{\"status\":\"NOT READY\",\"message\":\"No Service - Maintenance mode active.\"}";
    /** After {@code POST /ready/off}, with maintenance on or off. */
    private static final String READY_OFF =
        "{\"status\":\"NOT READY\",\"message\":\"No Service.\"}";
    /** After {@code POST /ready/on}. */
    private static final String READY_ON =
        "{\"status\":\"READY\",\"message\":\"Ready to serve.\"}";

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
            // debug on|off is excluded from production (T054); sent through the test seam
            UnlistedWriteCommand.DEBUG_ON.command().commandLine(),
            UnlistedWriteCommand.DEBUG_OFF.command().commandLine());
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

        assertThat(OperateServerWritesLiveTestSupport.restorePlan(baseline, all, FULL)).isEmpty();
        assertThat(OperateServerWritesLiveTestSupport.restorePlan(State.NONE, all, FULL))
            .extracting(Step::form).containsExactly("maintenance off", "POST /ready/on",
                "trace on", "trace -l info", "debug off");
        assertThat(OperateServerWritesLiveTestSupport.restorePlan(baseline
            .with(Property.TRACE_LEVEL, "debug").with(Property.READINESS, "not READY"), all,
            FULL)).extracting(Step::form).containsExactly("POST /ready/on", "trace -l info");
        assertThat(OperateServerWritesLiveTestSupport.restorePlan(State.NONE,
            Set.of(Property.MAINTENANCE), FULL)).extracting(Step::form)
            .containsExactly("maintenance off");
    }

    /** The second approved list: W5–W10 of the set, unchanged; W5 then finds the node READY. */
    @Test
    void theSubsetIsW5ToW10OfTheSetInOrderAndForm() {
        assertThat(SUBSET).extracting(Step::id).containsExactly(SUBSET_LIST.split(","));
        assertThat(SUBSET).containsExactlyElementsOf(FULL.subList(FULL.indexOf(step("W5")),
            FULL.size()));
        assertThat(SUBSET).extracting(Step::form)
            .containsExactly("POST /ready/on", "trace on", "trace off", "trace on",
                "trace -l debug", "trace -l info", "debug on", "debug off");
        assertThat(SUBSET)
            .extracting(step -> step.expected().describe(step.expected().properties()))
            .containsExactly("ready READY", "trace on, trace level info", "trace off",
                "trace on", "trace level debug", "trace level info", "debug on", "debug off");
        assertThat(SUBSET).noneMatch(Step::onlyIfNeeded);
        assertThat(OperateServerWritesLiveTestSupport.accepts(step("W5"), 503)).isFalse();
    }

    /** For W5–W10 the restore sends only commands of that list: never {@code maintenance off}. */
    @Test
    void theRestorePlanOfTheSubsetUsesOnlyItsCommands() {
        Set<Property> all = OperateServerWritesLiveTestSupport.ALL;
        Set<String> catalogue = new HashSet<>(SUBSET.stream().map(Step::form).toList());

        assertThat(OperateServerWritesLiveTestSupport.restorePlan(State.NONE, all, SUBSET))
            .extracting(Step::form)
            .containsExactly("POST /ready/on", "trace on", "trace -l info", "debug off")
            .allMatch(catalogue::contains);
        assertThat(OperateServerWritesLiveTestSupport.restorePlan(
            OperateServerWritesLiveTestSupport.BASELINE.with(Property.MAINTENANCE, "on"), all,
            SUBSET)).isEmpty();
        assertThat(OperateServerWritesLiveTestSupport.restorePlan(State.NONE,
            Set.of(Property.MAINTENANCE), SUBSET)).isEmpty();
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
        assertThat(OperateServerWritesLiveTestSupport.requireApproval(env)).isSameAs(FULL);

        env.put("INUBIT_LIVE_T054_WRITES", " " + STEP_LIST.replace(",", ", ") + " ");
        assertThat(OperateServerWritesLiveTestSupport.requireApproval(env)).isSameAs(FULL);
    }

    @Test
    void theSubsetW5ToW10PassesOnlyExactly() {
        env.put("INUBIT_LIVE_T054_WRITES", SUBSET_LIST);
        assertThat(OperateServerWritesLiveTestSupport.requireApproval(env)).isSameAs(SUBSET);

        env.put("INUBIT_LIVE_T054_WRITES", " " + SUBSET_LIST.replace(",", ", ") + " ");
        assertThat(OperateServerWritesLiveTestSupport.requireApproval(env)).isSameAs(SUBSET);

        for (String value : List.of("W5", "W5,W6", "W6,W7,W7b,W8,W8b,W9,W10",
            SUBSET_LIST.replace(",W10", ""), "W4," + SUBSET_LIST, SUBSET_LIST + ",W4",
            SUBSET_LIST.replace("W7,W7b", "W7b,W7"), SUBSET_LIST.replace("W9,W10", "W10,W9"),
            "W9,W10", "W1,W2a,W2b,W3,W2u", SUBSET_LIST + ",",
            SUBSET_LIST.toLowerCase(Locale.ROOT))) {
            env.put("INUBIT_LIVE_T054_WRITES", value);

            assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.requireApproval(env))
                .as(value)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("INUBIT_LIVE_T054_WRITES must list exactly " + STEP_LIST
                    + " or " + SUBSET_LIST)
                .hasMessageContaining("nothing was run");
        }
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
        State ready = State.NONE.with(Property.READINESS, "READY");
        State notReady = State.NONE.with(Property.READINESS, "not READY");

        assertThat(rest("R3", 200, fixture("ready.json")))
            .isEqualTo(ready.withReadyMessage("Ready to serve."));
        assertThat(rest("R3", 503, fixture("ready_not_ready.json")))
            .isEqualTo(notReady.withReadyMessage("Not ready to serve."));
        assertThat(rest("R3", 503, "<html>maintenance</html>")).isEqualTo(notReady);
        assertThat(rest("R3", 200, fixture("ready_not_ready.json"))).isEqualTo(State.NONE);
        assertThat(rest("R3", 200, "<html/>")).isEqualTo(State.NONE);
        assertThat(rest("R3", 404, fixture("ready.json"))).isEqualTo(State.NONE);
    }

    @Test
    void getReadyKeepsTheMessageOfTheAnswersOfLiveRun1() {
        assertThat(rest("R3", 503, IN_MAINTENANCE)).isEqualTo(State.NONE
            .with(Property.READINESS, "not READY")
            .withReadyMessage("No Service - Maintenance mode active."));
        assertThat(rest("R3", 503, READY_OFF)).isEqualTo(State.NONE
            .with(Property.READINESS, "not READY").withReadyMessage("No Service."));
        assertThat(rest("R3", 200, READY_ON)).isEqualTo(State.NONE
            .with(Property.READINESS, "READY").withReadyMessage("Ready to serve."));
        assertThat(rest("R3", 503, IN_MAINTENANCE).describe(OperateServerWritesLiveTestSupport.ALL))
            .doesNotContain("No Service");
    }

    @Test
    void theReadyOffShowsOnlyAsNotReadyWithAnotherMessageThanMaintenance() {
        Optional<String> inMaintenance = rest("R3", 503, IN_MAINTENANCE).readyMessage();

        assertThat(OperateServerWritesLiveTestSupport.readyOffTookEffect(inMaintenance,
            rest("R3", 503, READY_OFF))).isTrue();
        assertThat(OperateServerWritesLiveTestSupport.readyOffTookEffect(inMaintenance,
            rest("R3", 503, IN_MAINTENANCE))).isFalse();
        assertThat(OperateServerWritesLiveTestSupport.readyOffTookEffect(inMaintenance,
            rest("R3", 200, READY_ON))).isFalse();
        assertThat(OperateServerWritesLiveTestSupport.readyOffTookEffect(inMaintenance,
            rest("R3", 503, "<html>maintenance</html>"))).isFalse();
        assertThat(OperateServerWritesLiveTestSupport.readyOffTookEffect(Optional.empty(),
            rest("R3", 503, READY_OFF))).isFalse();
    }

    @Test
    void onlyW2bAlsoAccepts503() {
        List<Step> steps = new ArrayList<>(OperateServerWritesLiveTestSupport.STEPS);
        steps.addAll(OperateServerWritesLiveTestSupport.RESTORE.values());

        for (Step step : steps) {
            for (int status : new int[] {200, 204, 299}) {
                assertThat(OperateServerWritesLiveTestSupport.accepts(step, status))
                    .as(step.id() + " " + status).isTrue();
            }
            for (int status : new int[] {199, 300, 404, 500, 502, 504}) {
                assertThat(OperateServerWritesLiveTestSupport.accepts(step, status))
                    .as(step.id() + " " + status).isFalse();
            }
            assertThat(OperateServerWritesLiveTestSupport.accepts(step, 503)).as(step.id())
                .isEqualTo(step.id().equals("W2b"));
        }
    }

    /** The client's errors for {@code POST /ready/off}, as {@code InubitHttpClient} builds them. */
    @Test
    void a503IsReadFromTheClientsErrorAndAConnectionFailureIsNot() {
        ToolError answered = ToolError.of(ErrorCode.UNREACHABLE,
            "INUBIT is not serving (HTTP 503 for POST /ready/off)", "INUBIT answered 503: starting"
                + " up, overloaded, or behind a proxy that is not serving", "run get_health");
        ToolError refused = ToolError.of(ErrorCode.UNREACHABLE,
            "Cannot connect to the node for POST /ready/off (ConnectException)",
            TransportErrorTexts.UNREACHABLE_LIKELY_CAUSE, "Check baseUrl, DNS and the VPN");
        ToolError interrupted = ToolError.of(ErrorCode.UNREACHABLE,
            "Cannot connect to the node for POST /ready/off (IOException)",
            TransportErrorTexts.INTERRUPTED_LIKELY_CAUSE, "Check baseUrl, DNS and the VPN");
        ToolError timeout = ToolError.of(ErrorCode.TIMEOUT, "no answer",
            TransportErrorTexts.TIMEOUT_LIKELY_CAUSE, TransportErrorTexts.TIMEOUT_NEXT_STEP);

        assertThat(OperateServerWritesLiveTestSupport.answeredStatus(answered)).contains(503);
        assertThat(OperateServerWritesLiveTestSupport.answeredStatus(refused)).isEmpty();
        assertThat(OperateServerWritesLiveTestSupport.answeredStatus(interrupted)).isEmpty();
        assertThat(OperateServerWritesLiveTestSupport.answeredStatus(timeout)).isEmpty();
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

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, FULL);

        assertThat(node.ran).containsExactly(STEP_LIST.split(","));
        assertThat(node.reads).containsExactly("B0", "W1-after", "W2b-after", "W3-after",
            "W2u-check", "W2u-after", "W4-after", "W5-after", "W6-after", "W7-after",
            "W7b-after", "W8-after", "W8b-after", "W9-after", "W10-after", "B1");
        assertThat(outcome).isEqualTo(new Outcome(Optional.empty(), List.of(),
            OperateServerWritesLiveTestSupport.BASELINE.withReadyMessage("Ready to serve.")));
        assertThatCode(() -> OperateServerWritesLiveTestSupport.assertOutcome(outcome))
            .doesNotThrowAnyException();
        assertThat(node.records).contains(
            "W1 before maintenance off; after maintenance on",
            "W2b before ready READY; after ready not READY; answer 503, message changed: yes",
            "W6 before trace on, trace level info; after trace on, trace level info");
    }

    /** As live run 1, but INUBIT answers W2b's {@code POST /ready/off} with 2xx. */
    @Test
    void w2bIsDoneWith2xxIfItsEffectShows() throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.readySwitchInMaintenanceAnswers503 = false;

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, FULL);

        assertThat(outcome.stop()).isEmpty();
        assertThat(node.records).contains(
            "W2b before ready READY; after ready not READY; answer 2xx, message changed: yes");
    }

    /**
     * W2b answered (503 or 2xx) but {@code GET /ready} still shows the maintenance message: a
     * deviation; only maintenance is restored, as the readiness stayed READY.
     */
    @Test
    void aW2bWhoseEffectDoesNotShowIsADeviation() throws IOException {
        for (boolean answers503 : new boolean[] {true, false}) {
            SimulatedNode node = new SimulatedNode();
            node.readySwitchInMaintenanceAnswers503 = answers503;
            node.withoutEffect = "W2b";

            Outcome outcome = OperateServerWritesLiveTestSupport.run(node, FULL);

            String answer = answers503 ? "503" : "2xx";
            assertThat(outcome.stop()).as(answer).contains(new Stop("W2b", "DEVIATION (ready not"
                + " READY, message changed: no, expected ready not READY, message changed: yes)"));
            assertThat(node.records).as(answer).contains("W2b before ready READY; after ready not"
                + " READY; answer " + answer + ", message changed: no");
            assertThat(node.ran).as(answer).containsExactly("W1", "W2a", "W2b",
                "maintenance off");
            assertThat(outcome.restored()).as(answer).containsExactly("maintenance off");
            assertThat(outcome.end().values()).as(answer)
                .isEqualTo(OperateServerWritesLiveTestSupport.BASELINE.values());
        }
    }

    @Test
    void aConnectionFailureAtW2bStopsTheSequence() throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.unreachable = "W2b";

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, FULL);

        assertThat(outcome.stop()).contains(new Stop("W2b", "UNREACHABLE"));
        assertThat(node.records).contains("W2b before ready READY; after ready not READY");
        assertThat(outcome.restored()).containsExactly("maintenance off");
    }

    /** W4 and W5 switch the readiness, but INUBIT answers 503: both count only a 2xx. */
    @Test
    void w4AndW5StopAtA503EvenWithEffect() throws IOException {
        for (String step : List.of("W4", "W5")) {
            SimulatedNode node = new SimulatedNode();
            node.answering503 = step;

            Outcome outcome = OperateServerWritesLiveTestSupport.run(node, FULL);

            assertThat(outcome.stop()).as(step).contains(new Stop(step, "UNREACHABLE"));
            assertThat(node.ran).as(step).doesNotContain("W6");
            assertThat(outcome.restored()).as(step)
                .containsExactlyElementsOf(step.equals("W4") ? List.of("POST /ready/on")
                    : List.of());
        }
    }

    @Test
    void w2uRunsOnlyIfTheNodeIsNotReadyAfterW3() throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.maintenanceOffMakesReady = true;

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, FULL);

        assertThat(node.ran).doesNotContain("W2u").contains("W3", "W4");
        assertThat(node.records).contains("W2u not needed (ready READY)");
        assertThat(outcome.stop()).isEmpty();
    }

    @Test
    void withoutTheBaselineNothingIsWritten() {
        SimulatedNode node = new SimulatedNode();
        node.server.put(Property.DEBUG, "on");

        assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.run(node, FULL))
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

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, FULL);

        List<String> ids = List.of(STEP_LIST.split(","));
        List<String> expected = restores == null ? List.of() : List.of(restores.split(", "));
        assertThat(node.ran).containsExactlyElementsOf(
            concat(ids.subList(0, ids.indexOf(step) + 1), expected));
        assertThat(outcome.stop()).contains(new Stop(step, "TIMEOUT"));
        assertThat(outcome.restored()).containsExactlyElementsOf(expected);
        assertThat(outcome.end().values())
            .isEqualTo(OperateServerWritesLiveTestSupport.BASELINE.values());
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

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, FULL);

        assertThat(outcome.stop()).contains(new Stop("W7", "DEVIATION (trace on, expected trace"
            + " off)"));
        assertThat(node.ran).last().isEqualTo("W7");
        assertThat(outcome.restored()).isEmpty();
    }

    @Test
    void aDeviationStopsBeforeTheNextStep() throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.withoutEffect = "W8";

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, FULL);

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

            Outcome outcome = OperateServerWritesLiveTestSupport.run(node, FULL);

            assertThat(outcome.stop()).as(step).contains(new Stop(step, "exit 1"));
            assertThat(outcome.restored()).as(step).containsExactly(switch (step) {
                case "W1" -> "maintenance off";
                case "W7" -> "trace on";
                case "W8" -> "trace -l info";
                default -> "debug off";
            });
            assertThat(outcome.end().values())
            .isEqualTo(OperateServerWritesLiveTestSupport.BASELINE.values());
        }
    }

    @Test
    void anExceptionInAStepStopsAndRestoresAsWell() throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.throwingAfterEffect = "W9";

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, FULL);

        assertThat(outcome.stop()).contains(new Stop("W9", "IOException"));
        assertThat(outcome.restored()).containsExactly("debug off");
    }

    @Test
    void anUnreadablePropertyCountsAsOffTheBaseline() throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.failing = "W4";
        node.unreadable = "X2";

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, FULL);

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

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, FULL);

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
    void aFailedFinalReadLeavesTheEndUnknownAndStillNamesTheStopAndTheRestores()
        throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.failing = "W7b";
        node.unreadable = "B1";

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, FULL);

        assertThat(outcome.end()).isEqualTo(State.NONE);
        assertThat(node.reads).endsWith("X1", "X2", "B1");
        assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.assertOutcome(outcome))
            .isInstanceOf(AssertionError.class)
            .hasMessage("T054 write W7b stopped the sequence: TIMEOUT; restored: trace on; the"
                + " baseline does not hold (maintenance unknown, ready unknown, trace unknown,"
                + " trace level unknown, debug unknown, expected maintenance off, ready READY,"
                + " trace on, trace level info, debug off)");
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

    @Test
    void aCleanSubsetRunRunsW5ToW10OnceInOrderAndRestoresNothing() throws IOException {
        SimulatedNode node = new SimulatedNode();

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, SUBSET);

        assertThat(node.ran).containsExactly(SUBSET_LIST.split(","));
        assertThat(node.reads).containsExactly("B0", "W5-after", "W6-after", "W7-after",
            "W7b-after", "W8-after", "W8b-after", "W9-after", "W10-after", "B1");
        assertThat(outcome).isEqualTo(new Outcome(Optional.empty(), List.of(),
            OperateServerWritesLiveTestSupport.BASELINE.withReadyMessage("Ready to serve.")));
        assertThat(node.records).contains("W5 before ready READY; after ready READY",
            "W6 before trace on, trace level info; after trace on, trace level info");
    }

    @Test
    void withoutTheBaselineTheSubsetWritesNothing() {
        SimulatedNode node = new SimulatedNode();
        node.server.put(Property.MAINTENANCE, "on");

        assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.run(node, SUBSET))
            .isInstanceOf(AssertionError.class)
            .hasMessageStartingWith("the baseline does not hold before W5 (maintenance on,")
            .hasMessageEndingWith("; nothing was written");
        assertThat(node.ran).isEmpty();
    }

    /** W5 of the subset counts a 2xx only, even though the node stays READY. */
    @Test
    void w5OfTheSubsetStopsAtA503() throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.answering503 = "W5";

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, SUBSET);

        assertThat(outcome.stop()).contains(new Stop("W5", "UNREACHABLE"));
        assertThat(node.ran).containsExactly("W5");
        assertThat(outcome.restored()).isEmpty();
    }

    /**
     * An error at each step of the subset: the steps before it ran once each, none after it, and
     * only what is then off the baseline is restored, with commands of the subset.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "W5  |",
        "W6  |",
        "W7  |",
        "W7b | trace on",
        "W8  |",
        "W8b | trace -l info",
        "W9  |",
        "W10 | debug off"})
    void anErrorInTheSubsetStopsAndRestoresOnlyWhatIsOffTheBaseline(String step,
        String restores) throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.failing = step;

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, SUBSET);

        List<String> ids = List.of(SUBSET_LIST.split(","));
        List<String> expected = restores == null ? List.of() : List.of(restores.split(", "));
        assertThat(node.ran).containsExactlyElementsOf(
            concat(ids.subList(0, ids.indexOf(step) + 1), expected));
        assertThat(outcome.stop()).contains(new Stop(step, "TIMEOUT"));
        assertThat(outcome.restored()).containsExactlyElementsOf(expected);
        assertThat(outcome.end().values())
            .isEqualTo(OperateServerWritesLiveTestSupport.BASELINE.values());
    }

    /** At each step of the subset, with nothing readable, the restore stays within the subset. */
    @Test
    void theRestoreAtEachSubsetStepSendsOnlyCommandsOfTheSubset() throws IOException {
        for (Step step : SUBSET) {
            SimulatedNode node = new SimulatedNode();
            node.failing = step.id();
            node.unreadable = "X2";

            Outcome outcome = OperateServerWritesLiveTestSupport.run(node, SUBSET);

            assertThat(outcome.restored()).as(step.id())
                .containsExactly("POST /ready/on", "trace on", "trace -l info", "debug off");
            assertThat(node.ran).as(step.id()).doesNotContain("maintenance off");
        }
    }

    /**
     * Maintenance found on (or unknown) during the restore of the subset: {@code maintenance off}
     * is not in that list, so it is not sent but reported for the owner.
     */
    @Test
    void maintenanceFoundOnInTheSubsetIsReportedNotRestored() throws IOException {
        SimulatedNode node = new SimulatedNode();
        node.maintenanceOnAt = "W7";

        Outcome outcome = OperateServerWritesLiveTestSupport.run(node, SUBSET);

        assertThat(outcome.stop()).contains(new Stop("W7", "DEVIATION (trace unknown, expected"
            + " trace off)"));
        assertThat(outcome.restored()).containsExactly("DEVIATION maintenance on not restored:"
            + " maintenance off is not in the approved list, the owner must fix it",
            "POST /ready/on failed: UNREACHABLE", "trace on", "trace -l info");
        assertThat(node.ran).containsExactly("W5", "W6", "W7", "POST /ready/on", "trace on",
            "trace -l info");
        assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.assertOutcome(outcome))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("restored: DEVIATION maintenance on not restored")
            .hasMessageContaining("the baseline does not hold (maintenance on,");

        SimulatedNode unknown = new SimulatedNode();
        unknown.failing = "W10";
        unknown.unreadable = "X1";

        assertThat(OperateServerWritesLiveTestSupport.run(unknown, SUBSET).restored())
            .containsExactly("DEVIATION maintenance unknown not restored: maintenance off is"
                + " not in the approved list, the owner must fix it", "debug off");
        assertThat(unknown.ran).doesNotContain("maintenance off");
    }

    // --- the owner-gated writes W11 and W12 ------------------------------------------------------

    @Test
    void theOwnerGatedStepsAreTheProductionFormsOutsideTheSet() {
        assertThat(OWNER_GATED).extracting(Step::id).containsExactly("W11", "W12");
        assertThat(OWNER_GATED).extracting(Step::stem)
            .containsExactly("W11-trigger-midnight-task", "W12-processdelete-error");
        assertThat(OWNER_GATED).extracting(step -> ((CliWrite) step.action()).command()
            .commandLine()).containsExactly(CliCommand.triggerMidnightTask().commandLine(),
                CliCommand.processDeleteError().commandLine());
        assertThat(OWNER_GATED).extracting(Step::form)
            .containsExactly("triggerMidnightTask", "processdelete error");
        assertThat(FULL).doesNotContainAnyElementsOf(OWNER_GATED);
        assertThat(OperateServerWritesLiveTestSupport.CONFIGURATIONS_READ).isSameAs(read("R9"));
    }

    @Test
    void theOwnerGatedListsPassOnlyExactly() {
        env.put("INUBIT_LIVE_T054_WRITES", "W11");
        assertThat(OperateServerWritesLiveTestSupport.requireApproval(env)).containsExactly(W11);
        env.put("INUBIT_LIVE_T054_WRITES", " W12 ");
        assertThat(OperateServerWritesLiveTestSupport.requireApproval(env)).containsExactly(W12);
        env.put("INUBIT_LIVE_T054_WRITES", "W11,W12");
        assertThat(OperateServerWritesLiveTestSupport.requireApproval(env)).isSameAs(OWNER_GATED);
        env.put("INUBIT_LIVE_T054_WRITES", " W11 , W12 ");
        assertThat(OperateServerWritesLiveTestSupport.requireApproval(env)).isSameAs(OWNER_GATED);

        for (String value : List.of("W12,W11", "W11,W11", "W11,W12,", "W11 W12", "w11",
            "W11,W12,W1", "W10,W11", SUBSET_LIST + ",W11", STEP_LIST + ",W11,W12", "W13")) {
            env.put("INUBIT_LIVE_T054_WRITES", value);

            assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.requireApproval(env))
                .as(value)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("INUBIT_LIVE_T054_WRITES must list exactly " + STEP_LIST
                    + " or " + SUBSET_LIST + " or W11, W12 or W11,W12")
                .hasMessageContaining("nothing was run");
        }
    }

    @Test
    void withoutW12TheErrorIdsAreNotRead() {
        for (List<Step> steps : List.of(List.of(W11), FULL, SUBSET)) {
            assertThat(OperateServerWritesLiveTestSupport.approvedErrorIds(env, steps)).isEmpty();
            env.put("INUBIT_LIVE_T054_ERROR_IDS", "x");
            assertThat(OperateServerWritesLiveTestSupport.approvedErrorIds(env, steps)).isEmpty();
            env.remove("INUBIT_LIVE_T054_ERROR_IDS");
        }
    }

    @Test
    void w12FailsBeforeContactWithoutTheApprovedErrorIds() {
        for (List<Step> steps : List.of(List.of(W12), OWNER_GATED)) {
            env.remove("INUBIT_LIVE_T054_ERROR_IDS");
            assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.approvedErrorIds(env,
                steps)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("INUBIT_LIVE_T054_ERROR_IDS must hold the ids of the"
                    + " processes in state ERROR the owner approved for W12, comma-separated, or"
                    + " none for none; it is not set; nothing was run");
            env.put("INUBIT_LIVE_T054_ERROR_IDS", " ");
            assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.approvedErrorIds(env,
                steps)).isInstanceOf(AssertionError.class).hasMessageContaining("it is not set");
        }
    }

    @Test
    void theApprovedErrorIdsAreNoneOrDistinctProcessIds() {
        env.put("INUBIT_LIVE_T054_ERROR_IDS", "none");
        assertThat(OperateServerWritesLiveTestSupport.approvedErrorIds(env, List.of(W12)))
            .contains(Set.of());
        env.put("INUBIT_LIVE_T054_ERROR_IDS", " none ");
        assertThat(OperateServerWritesLiveTestSupport.approvedErrorIds(env, OWNER_GATED))
            .contains(Set.of());
        env.put("INUBIT_LIVE_T054_ERROR_IDS", "1002, 1001,17");
        Set<String> ids = OperateServerWritesLiveTestSupport.approvedErrorIds(env, List.of(W12))
            .orElseThrow();
        assertThat(ids).isEqualTo(Set.of("17", "1001", "1002"));
        assertThat(OperateServerWritesLiveTestSupport.describeIds(ids))
            .isEqualTo("3 (17, 1001, 1002)");
        assertThat(OperateServerWritesLiveTestSupport.describeIds(Set.of())).isEqualTo("0 (none)");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1001,1001|it lists 1001 twice",
        "1001, 1002 ,1001|it lists 1001 twice",
        "abc|entry 1 is not a process id",
        "1001,|entry 2 is not a process id",
        ",1001|entry 1 is not a process id",
        "1001;1002|entry 1 is not a process id",
        "1001 1002|entry 1 is not a process id",
        "none,1001|entry 1 is not a process id",
        "NONE|entry 1 is not a process id",
        "-1|entry 1 is not a process id",
        "12345678901234567890|entry 1 is not a process id"})
    void anyOtherErrorIdListFailsBeforeContact(String value, String reason) {
        env.put("INUBIT_LIVE_T054_ERROR_IDS", value);

        assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.approvedErrorIds(env,
            List.of(W12)))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("INUBIT_LIVE_T054_ERROR_IDS must hold")
            .hasMessageContaining("; " + reason + "; nothing was run")
            .hasMessageNotContaining("abc");
    }

    @Test
    void theTriggerTimeIsReadFromTheConfigurationsAnswerAlone() {
        String time = "Sat Oct 31 00:00:00 CET 2026";
        assertThat(midnight(200, fixture("admin-configurations.json"))).contains(time);
        assertThat(midnight(200, "{\"MidnightTaskTriggerTime\":\"" + time + "\"}")).contains(time);
        assertThat(midnight(200, "{\"ServerConfiguration\":{\"Mail\":\"" + RAW_LINE
            + "\",\"MidnightTaskTriggerTime\":\"" + time + "\"}}")).contains(time);
        for (String body : List.of("", "not json", "{}", "{\"MidnightTaskTriggerTime\":1}",
            "{\"MidnightTaskTriggerTime\":null}", "[\"MidnightTaskTriggerTime\"]")) {
            assertThat(midnight(200, body)).as(body).isEmpty();
        }
        assertThat(midnight(401, "{\"MidnightTaskTriggerTime\":\"" + time + "\"}")).isEmpty();
        assertThat(midnight(503, "{\"MidnightTaskTriggerTime\":\"" + time + "\"}")).isEmpty();
    }

    @Test
    void theErrorQueryIsFindProcessesWithStateErrorAlone() {
        Instant now = Instant.parse("2026-11-18T12:00:00Z");
        ProcessQuery query = OperateServerWritesLiveTestSupport.errorQuery(200, 100, now);

        assertThat(query.states()).containsExactly(ProcessState.ERROR);
        assertThat(query.hangingOnly()).isFalse();
        assertThat(query.workflow()).isEmpty();
        assertThat(query.tag()).isEmpty();
        assertThat(query.since()).isEmpty();
        assertThat(query.until()).isEmpty();
        assertThat(query.userColumn()).isEmpty();
        assertThat(query.offset()).isEqualTo(200);
        assertThat(query.limit()).isEqualTo(100);
        assertThat(query.now()).isEqualTo(now);
    }

    @Test
    void theErrorSetIsPagedWholeWithTheLimitsOfFindProcesses() {
        List<String> asked = new ArrayList<>();
        List<String> first = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            first.add(String.valueOf(1000 + i));
        }

        Optional<Set<String>> ids = OperateServerWritesLiveTestSupport.errorIds((offset, limit) -> {
            asked.add(offset + "+" + limit);
            // rows of sub-workflows repeat their process id
            return offset == 0 ? page(0, limit, 150, first)
                : page(offset, limit, 150, concat(List.of("1001", "2001"),
                    Collections.nCopies(48, "2002")));
        });

        assertThat(asked).containsExactly("0+100", "100+100");
        assertThat(ids).hasValueSatisfying(set -> assertThat(set).hasSize(102)
            .contains("1001", "1100", "2001", "2002"));

        asked.clear();
        assertThat(OperateServerWritesLiveTestSupport.errorIds((offset, limit) -> {
            asked.add(offset + "+" + limit);
            return page(offset, limit, 0, List.of());
        })).contains(Set.of());
        assertThat(asked).containsExactly("0+100");
    }

    @Test
    void anErrorSetThatCannotBeReadWholeIsUnknown() {
        List<String> asked = new ArrayList<>();
        // more rows than find_processes can page
        assertThat(OperateServerWritesLiveTestSupport.errorIds((offset, limit) -> {
            asked.add(offset + "+" + limit);
            return page(offset, limit, 10_001, List.of("1001"));
        })).isEmpty();
        assertThat(asked).containsExactly("0+100");
        // no total
        assertThat(OperateServerWritesLiveTestSupport.errorIds((offset, limit) ->
            new Page<>(List.of(errorProcess("1001")), offset, limit, OptionalLong.empty(), true,
                false, OptionalInt.empty()))).isEmpty();
        // a page that does not advance
        assertThat(OperateServerWritesLiveTestSupport.errorIds((offset, limit) ->
            new Page<>(List.of(), offset, limit, OptionalLong.of(5), false, false,
                OptionalInt.of(offset)))).isEmpty();
        // a total that changes between pages (a process entered ERROR while paging)
        List<String> hundred = Collections.nCopies(100, "1001");
        assertThat(OperateServerWritesLiveTestSupport.errorIds((offset, limit) ->
            offset == 0 ? page(0, limit, 150, hundred)
                : page(offset, limit, 151, Collections.nCopies(51, "1002")))).isEmpty();
        // fewer rows than the total (a process left ERROR while paging)
        assertThat(OperateServerWritesLiveTestSupport.errorIds((offset, limit) ->
            new Page<>(List.of(errorProcess("1001")), offset, limit, OptionalLong.of(2), false,
                false, OptionalInt.empty()))).isEmpty();
        // more rows than the total
        assertThat(OperateServerWritesLiveTestSupport.errorIds((offset, limit) ->
            new Page<>(List.of(errorProcess("1001"), errorProcess("1001")), offset, limit,
                OptionalLong.of(1), false, false, OptionalInt.empty()))).isEmpty();
    }

    @Test
    void w12DeletesTheApprovedSetOnceAndReportsNewErrorProcesses() throws IOException {
        SimulatedGatedNode node = new SimulatedGatedNode();
        node.errorSet = Optional.of(Set.of("1001", "1002"));
        node.afterDelete = Optional.of(Set.of("1003"));

        Optional<Stop> stop = OperateServerWritesLiveTestSupport.runOwnerGated(node, List.of(W12),
            Optional.of(Set.of("1002", "1001")));

        assertThat(stop).isEmpty();
        assertThat(node.calls).containsExactly("B0", "W12-before", "W12", "W12-after", "B1");
        assertThat(node.notes).containsExactly("W12: before 2 (1001, 1002); after 1"
            + " (1003); approved still in ERROR 0 (none); new in ERROR after the delete 1 (1003)");
        assertThatCode(() -> OperateServerWritesLiveTestSupport.assertOwnerGated(stop))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1001,1002|1001,1002,1003|2|3|now 3 (1001, 1002, 1003)",
        "1001,1002|1001|2|1|now 1 (1001)",
        "1001|1002|1|1|now 1 (1002)",
        "none|1001|0|1|now 1 (1001)",
        "1001|none|1|0|now 0 (none)"})
    void aChangedErrorSetIsNotDeletedAndNeedsANewPreview(String approved, String current,
        int approvedCount, int currentCount, String recorded) throws IOException {
        SimulatedGatedNode node = new SimulatedGatedNode();
        node.errorSet = Optional.of(ids(current));

        Optional<Stop> stop = OperateServerWritesLiveTestSupport.runOwnerGated(node, List.of(W12),
            Optional.of(ids(approved)));

        String code = "ERROR set changed: approved " + approvedCount + ", now " + currentCount
            + "; new preview needed";
        assertThat(stop).contains(new Stop("W12", code));
        assertThat(node.calls).containsExactly("B0", "W12-before", "B1");
        assertThat(node.notes).singleElement().asString().startsWith("W12: approved ")
            .contains("; " + recorded + "; not run, new preview needed");
        assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.assertOwnerGated(stop))
            .isInstanceOf(AssertionError.class)
            .hasMessage("T054 write W12 stopped the sequence: " + code + "; nothing is restored"
                + " (W11 and W12 have no undo)");
    }

    @Test
    void anApprovedEmptySetThatIsStillEmptyIsSkipped() throws IOException {
        SimulatedGatedNode node = new SimulatedGatedNode();

        Optional<Stop> stop = OperateServerWritesLiveTestSupport.runOwnerGated(node, List.of(W12),
            Optional.of(Set.of()));

        assertThat(stop).isEmpty();
        assertThat(node.calls).containsExactly("B0", "W12-before", "B1");
        assertThat(node.notes)
            .containsExactly("W12: nothing to delete (approved none, now none); not run");
    }

    @Test
    void anUnreadableErrorSetIsNotDeleted() throws IOException {
        SimulatedGatedNode node = new SimulatedGatedNode();
        node.errorSet = Optional.empty();

        Optional<Stop> stop = OperateServerWritesLiveTestSupport.runOwnerGated(node, List.of(W12),
            Optional.of(Set.of("1001")));

        assertThat(stop).contains(new Stop("W12",
            "ERROR set unknown; processdelete error was not run"));
        assertThat(node.calls).containsExactly("B0", "W12-before", "B1");
        assertThat(node.notes).containsExactly("W12: approved 1 (1001); now unknown; not run");
    }

    @Test
    void anApprovedIdStillInErrorAfterTheDeleteIsADeviation() throws IOException {
        SimulatedGatedNode node = new SimulatedGatedNode();
        node.errorSet = Optional.of(Set.of("1001", "1002"));
        node.afterDelete = Optional.of(Set.of("1002", "1003"));

        Optional<Stop> stop = OperateServerWritesLiveTestSupport.runOwnerGated(node, List.of(W12),
            Optional.of(Set.of("1001", "1002")));

        assertThat(stop).contains(new Stop("W12",
            "DEVIATION (approved ids still in ERROR: 1 (1002))"));
        assertThat(node.calls).containsExactly("B0", "W12-before", "W12", "W12-after", "B1");
        assertThat(node.notes).containsExactly("W12: before 2 (1001, 1002); after"
            + " 2 (1002, 1003); approved still in ERROR 1 (1002); new in ERROR after the delete 1"
            + " (1003)");
    }

    @Test
    void aFailedDeleteStopsWithItsCodeAndTheSetAfterIsStillRecorded() throws IOException {
        SimulatedGatedNode node = new SimulatedGatedNode();
        node.errorSet = Optional.of(Set.of("1001"));
        node.failures.put("W12", "exit 1");

        Optional<Stop> stop = OperateServerWritesLiveTestSupport.runOwnerGated(node, List.of(W12),
            Optional.of(Set.of("1001")));

        assertThat(stop).contains(new Stop("W12", "exit 1"));
        assertThat(node.calls).containsExactly("B0", "W12-before", "W12", "W12-after", "B1");
        assertThat(node.notes).containsExactly("W12: before 1 (1001); after 0"
            + " (none); approved still in ERROR 0 (none); new in ERROR after the delete 0 (none)");

        SimulatedGatedNode unread = new SimulatedGatedNode();
        unread.errorSet = Optional.of(Set.of("1001"));
        unread.afterDelete = Optional.empty();
        assertThat(OperateServerWritesLiveTestSupport.runOwnerGated(unread, List.of(W12),
            Optional.of(Set.of("1001"))))
            .contains(new Stop("W12", "ERROR set after processdelete error unknown"));
        assertThat(unread.notes)
            .containsExactly("W12: before 1 (1001); after unknown");
    }

    @Test
    void w11RunsTheMidnightTaskOnceAndRecordsTheTriggerTimeBeforeAndAfter() throws IOException {
        SimulatedGatedNode node = new SimulatedGatedNode();

        Optional<Stop> stop = OperateServerWritesLiveTestSupport.runOwnerGated(node, List.of(W11),
            Optional.empty());

        assertThat(stop).isEmpty();
        assertThat(node.calls).containsExactly("B0", "W11-before", "W11", "W11-after", "B1");
        assertThat(node.notes).containsExactly("W11: MidnightTaskTriggerTime before Sat Oct 31"
            + " 00:00:00 CET 2026; after Sun Nov 01 00:00:00 CET 2026");

        // an unchanged or unreadable time is recorded, not judged
        SimulatedGatedNode same = new SimulatedGatedNode();
        same.timeAfter = same.timeBefore;
        same.timeBefore = Optional.empty();
        assertThat(OperateServerWritesLiveTestSupport.runOwnerGated(same, List.of(W11),
            Optional.empty())).isEmpty();
        assertThat(same.notes).containsExactly("W11: MidnightTaskTriggerTime before unknown;"
            + " after Sat Oct 31 00:00:00 CET 2026");
    }

    @Test
    void aFailedW11StopsBeforeW12() throws IOException {
        SimulatedGatedNode node = new SimulatedGatedNode();
        node.errorSet = Optional.of(Set.of("1001"));
        node.failures.put("W11", "exit 2");

        Optional<Stop> stop = OperateServerWritesLiveTestSupport.runOwnerGated(node, OWNER_GATED,
            Optional.of(Set.of("1001")));

        assertThat(stop).contains(new Stop("W11", "exit 2"));
        assertThat(node.calls).containsExactly("B0", "W11-before", "W11", "W11-after", "B1");
    }

    @Test
    void bothRunOnceInOrderAndTheFullStateIsInformationOnly() throws IOException {
        SimulatedGatedNode node = new SimulatedGatedNode();
        node.errorSet = Optional.of(Set.of("1001"));
        node.unreadableEnd = true;

        Optional<Stop> stop = OperateServerWritesLiveTestSupport.runOwnerGated(node, OWNER_GATED,
            Optional.of(Set.of("1001")));

        // B0 shows maintenance on and the rest unknown, B1 fails: neither stops anything
        assertThat(stop).isEmpty();
        assertThat(node.calls).containsExactly("B0", "W11-before", "W11", "W11-after",
            "W12-before", "W12", "W12-after", "B1");
    }

    @Test
    void eachSequenceRunsOnlyItsOwnSteps() {
        SimulatedNode simulated = new SimulatedNode();
        for (List<Step> steps : List.of(List.of(W11), List.of(W12), concat(SUBSET, List.of(W12)))) {
            assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.run(simulated, steps))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(simulated.reads).isEmpty();
        assertThat(simulated.ran).isEmpty();

        SimulatedGatedNode node = new SimulatedGatedNode();
        for (List<Step> steps : List.of(List.<Step>of(), SUBSET, List.of(W11, step("W5")))) {
            assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.runOwnerGated(node, steps,
                Optional.of(Set.of()))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.runOwnerGated(node,
            List.of(W12), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThat(node.calls).isEmpty();
    }

    @Test
    void theOwnerGatedRecordsGoToTheirRunFolderAndTheirLinesHoldNoOutput() throws IOException {
        env.put("INUBIT_LIVE_T054_RUN", "4");
        Path records = temp.resolve("records/022");
        Path directory = OperateServerReadsLiveTestSupport.taskDirectory(PrivateFiles.forHost(),
            records, OperateServerWritesLiveTestSupport.taskDirectory(env));
        assertThat(directory).isEqualTo(records.resolve("T054-writes-4"));
        byte[] body = ("{\"ServerConfiguration\":{\"Mail\":\"" + RAW_LINE
            + "\",\"MidnightTaskTriggerTime\":\"Sat Oct 31 00:00:00 CET 2026\"}}")
            .getBytes(StandardCharsets.UTF_8);
        RestRead r9 = OperateServerWritesLiveTestSupport.CONFIGURATIONS_READ;

        String configurations = OperateServerReadsLiveTestSupport.recordRest(
            PrivateFiles.forHost(), directory, NODE, new RestRead("W11-before-R9", r9.slug(),
                r9.endpoint(), r9.authenticated(), r9.range()),
            new RestResponse(200, Optional.empty(), body));
        String time = OperateServerWritesLiveTestSupport.midnightTriggerTime(200, body)
            .orElseThrow();
        String note = OperateServerWritesLiveTestSupport.recordNote(PrivateFiles.forHost(),
            directory, NODE, W11, "MidnightTaskTriggerTime before " + time + "; after " + time);
        String before = OperateServerWritesLiveTestSupport.recordErrorSet(PrivateFiles.forHost(),
            directory, NODE, new ErrorSetRead("W12-before"), Optional.of(Set.of("1002", "1001")));
        String unknown = OperateServerWritesLiveTestSupport.recordErrorSet(PrivateFiles.forHost(),
            directory, NODE, new ErrorSetRead("W12-after"), Optional.empty());

        assertThat(fileNames(directory)).containsExactlyInAnyOrder(
            "W11-before-R9-get-configurations.status", "W11-before-R9-get-configurations.body",
            "W11-trigger-midnight-task.state", "W12-before-find-processes-error.ids");
        assertThat(directory.resolve("W11-trigger-midnight-task.state")).hasContent(
            "MidnightTaskTriggerTime before Sat Oct 31 00:00:00 CET 2026; after Sat Oct 31"
                + " 00:00:00 CET 2026\n");
        assertThat(directory.resolve("W12-before-find-processes-error.ids"))
            .hasContent("1001\n1002\n");
        assertOwnerOnly(directory.resolve("W11-trigger-midnight-task.state"), "rw-------");
        assertOwnerOnly(directory.resolve("W12-before-find-processes-error.ids"), "rw-------");
        assertThat(configurations).isEqualTo("[live] T054 W11-before-R9 GET /configurations on"
            + " test/node1: status 200, body " + body.length + " bytes");
        assertThat(note).isEqualTo("[live] T054 W11 triggerMidnightTask on test/node1:"
            + " MidnightTaskTriggerTime before Sat Oct 31 00:00:00 CET 2026; after Sat Oct 31"
            + " 00:00:00 CET 2026");
        assertThat(before).isEqualTo("[live] T054 W12-before find_processes states=[ERROR] on"
            + " test/node1: ERROR set 2 (1001, 1002)");
        assertThat(unknown).isEqualTo("[live] T054 W12-after find_processes states=[ERROR] on"
            + " test/node1: not read whole (no total, more than 10000 rows, or a page that does"
            + " not advance)");
        assertThat(configurations + note + before + unknown).doesNotContain(RAW_LINE);
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
    void aLaterRunRecordsInItsOwnFolderAndLeavesTheEarlierRunUntouched() throws IOException {
        assertThat(OperateServerWritesLiveTestSupport.taskDirectory(env)).isEqualTo("T054-writes");
        env.put("INUBIT_LIVE_T054_RUN", " ");
        assertThat(OperateServerWritesLiveTestSupport.taskDirectory(env)).isEqualTo("T054-writes");
        env.put("INUBIT_LIVE_T054_RUN", "12");
        assertThat(OperateServerWritesLiveTestSupport.taskDirectory(env))
            .isEqualTo("T054-writes-12");
        for (String value : List.of("1", "0", "02", "-2", "x", "../2", "2/x")) {
            env.put("INUBIT_LIVE_T054_RUN", value);
            assertThatThrownBy(() -> OperateServerWritesLiveTestSupport.taskDirectory(env))
                .as(value).isInstanceOf(AssertionError.class)
                .hasMessageContaining("INUBIT_LIVE_T054_RUN must be the number of the run")
                .hasMessageContaining("nothing was run");
        }
        env.put("INUBIT_LIVE_T054_RUN", "2");
        String name = OperateServerWritesLiveTestSupport.taskDirectory(env);
        Path records = temp.resolve("records/022");
        Path run1 = Files.createDirectories(records.resolve("T054-writes"));
        Files.writeString(run1.resolve("W1-maintenance-on-eq-false.exit"), "0\n");

        assertThatCode(() -> OperateServerReadsLiveTestSupport.requireNoTaskDirectory(records,
            name)).doesNotThrowAnyException();
        Path directory = OperateServerReadsLiveTestSupport.taskDirectory(PrivateFiles.forHost(),
            records, name);

        assertThat(directory).isEqualTo(records.resolve("T054-writes-2"));
        assertThat(fileNames(run1)).containsExactly("W1-maintenance-on-eq-false.exit");
        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.requireNoTaskDirectory(records,
            name)).isInstanceOf(AssertionError.class)
            .hasMessageContaining("T054-writes-2 already exists");
    }

    /**
     * W2b's {@code POST /ready/off} answered with 503 is recorded with its status beside the
     * client's code; a connection failure only with the code.
     */
    @Test
    void anAnswered503OfASwitchIsRecordedWithItsStatusAndNoOutputInItsLine()
        throws IOException {
        Path directory = OperateServerReadsLiveTestSupport.taskDirectory(PrivateFiles.forHost(),
            temp.resolve("records"), OperateServerWritesLiveTestSupport.TASK_DIRECTORY);
        String excerpt = "No Service. " + RAW_LINE;
        ToolError answered = ToolError.of(ErrorCode.UNREACHABLE,
            "INUBIT is not serving (HTTP 503 for POST /ready/off)", "INUBIT answered 503: starting"
                + " up, overloaded, or behind a proxy that is not serving", "run get_health")
            .withExcerpt(excerpt);
        ToolError refused = ToolError.of(ErrorCode.UNREACHABLE,
            "Cannot connect to the node for POST /ready/on (ConnectException)",
            TransportErrorTexts.UNREACHABLE_LIKELY_CAUSE, "Check baseUrl, DNS and the VPN");

        String line = OperateServerWritesLiveTestSupport.recordSwitchFailure(
            PrivateFiles.forHost(), directory, NODE, step("W2b"), answered);
        String failed = OperateServerWritesLiveTestSupport.recordSwitchFailure(
            PrivateFiles.forHost(), directory, NODE, step("W5"), refused);

        assertThat(fileNames(directory)).containsExactlyInAnyOrder("W2b-post-ready-off.error",
            "W2b-post-ready-off.status", "W2b-post-ready-off.excerpt", "W5-post-ready-on.error");
        assertThat(directory.resolve("W2b-post-ready-off.status")).hasContent("503\n");
        assertThat(directory.resolve("W2b-post-ready-off.error")).hasContent("UNREACHABLE\n");
        assertThat(directory.resolve("W2b-post-ready-off.excerpt")).hasContent(excerpt);
        assertThat(directory.resolve("W5-post-ready-on.error")).hasContent("UNREACHABLE\n");
        assertOwnerOnly(directory.resolve("W2b-post-ready-off.excerpt"), "rw-------");
        assertThat(line).isEqualTo("[live] T054 W2b POST /ready/off on " + NODE + ": status 503,"
            + " error UNREACHABLE, excerpt " + excerpt.length() + " bytes");
        assertThat(failed).isEqualTo("[live] T054 W5 POST /ready/on on " + NODE
            + ": error UNREACHABLE");
        assertThat(line).doesNotContain(RAW_LINE).doesNotContain("No Service");
    }

    @Test
    void theStatesOfAStepAreRecordedOwnerOnlyAndItsLineHoldsDerivedStatesOnly()
        throws IOException {
        Path directory = OperateServerReadsLiveTestSupport.taskDirectory(PrivateFiles.forHost(),
            temp.resolve("records"), OperateServerWritesLiveTestSupport.TASK_DIRECTORY);

        String line = OperateServerWritesLiveTestSupport.recordStates(PrivateFiles.forHost(),
            directory, NODE, step("W1"), State.NONE.with(Property.MAINTENANCE, "off"),
            State.NONE.with(Property.MAINTENANCE, "on"), Optional.empty());
        String readyOff = OperateServerWritesLiveTestSupport.recordStates(PrivateFiles.forHost(),
            directory, NODE, step("W2b"), rest("R3", 200, READY_ON), rest("R3", 503, READY_OFF),
            Optional.of("answer 503, message changed: yes"));
        String skipped = OperateServerWritesLiveTestSupport.recordNotNeeded(
            PrivateFiles.forHost(), directory, NODE, step("W2u"),
            State.NONE.with(Property.READINESS, "READY"));

        assertThat(fileNames(directory)).containsExactlyInAnyOrder(
            "W1-maintenance-on-eq-false.state", "W2b-post-ready-off.state",
            "W2u-post-ready-on.state");
        assertThat(directory.resolve("W1-maintenance-on-eq-false.state"))
            .hasContent("before maintenance off; after maintenance on; expected maintenance on\n");
        assertThat(directory.resolve("W2b-post-ready-off.state")).hasContent("before ready READY;"
            + " after ready not READY; expected ready not READY; answer 503, message changed: yes\n");
        assertThat(readyOff).endsWith(": before ready READY; after ready not READY; expected ready"
            + " not READY; answer 503, message changed: yes").doesNotContain("Service");
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
     * A node that applies the commands of the set to its state, as live run 1 showed: while
     * maintenance is on, {@code GET /ready} answers not READY (with the maintenance message unless
     * the readiness is off), {@code POST /ready/on|off} is answered with 503 yet takes effect, and
     * {@code /system/info} is unreadable.
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
        /** A step whose request finds no connection: nothing is sent. */
        String unreachable = "";
        /** A step whose {@code POST /ready/on|off} is answered with 503 outside maintenance. */
        String answering503 = "";
        /** A step after which the node is in maintenance (switched on by someone else). */
        String maintenanceOnAt = "";
        boolean readySwitchInMaintenanceAnswers503 = true;
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
            return properties.contains(Property.READINESS)
                ? state.withReadyMessage(readyMessage()) : state;
        }

        private boolean maintenance() {
            return server.get(Property.MAINTENANCE).equals("on");
        }

        /** The message of {@code GET /ready}: the readiness off wins over maintenance. */
        private String readyMessage() {
            if (server.get(Property.READINESS).equals("not READY")) {
                return "No Service.";
            }
            return maintenance() ? "No Service - Maintenance mode active." : "Ready to serve.";
        }

        private Optional<String> visible(Property property) {
            boolean maintenance = maintenance();
            return switch (property) {
                case READINESS -> Optional.of(maintenance ? "not READY"
                    : server.get(Property.READINESS));
                case TRACE, TRACE_LEVEL -> maintenance ? Optional.empty()
                    : Optional.of(server.get(property));
                default -> Optional.of(server.get(property));
            };
        }

        @Override
        public Answer run(Step step) throws IOException {
            boolean restore = step.id().equals("restore");
            ran.add(restore ? step.form() : step.id());
            if (restore && restoresFail || step.id().equals(failing)) {
                return Answer.of(Optional.of("TIMEOUT"));
            }
            if (step.id().equals(unreachable)) {
                return Answer.of(Optional.of("UNREACHABLE"));
            }
            boolean maintenance = maintenance();
            if (!step.id().equals(withoutEffect)) {
                apply(step.form());
            }
            if (step.id().equals(maintenanceOnAt)) {
                server.put(Property.MAINTENANCE, "on");
            }
            if (step.id().equals(throwingAfterEffect)) {
                throw new IOException(RAW_LINE);
            }
            if (step.action() instanceof ReadyRead) {
                return new Answer(Optional.empty(), Optional.of(maintenance ? 503 : 200),
                    State.NONE.with(Property.READINESS, visible(Property.READINESS).orElseThrow())
                        .withReadyMessage(readyMessage()));
            }
            if (step.action() instanceof ReadySwitch) {
                // the production write request reports a 503 as UNREACHABLE
                return maintenance && readySwitchInMaintenanceAnswers503
                    || step.id().equals(answering503)
                    ? new Answer(Optional.of("UNREACHABLE"), Optional.of(503), State.NONE)
                    : new Answer(Optional.empty(), Optional.of(200), State.NONE);
            }
            return Answer.of(step.id().equals(failingAfterEffect) ? Optional.of("exit 1")
                : Optional.empty());
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
        public void recordStates(Step step, State before, State after, Optional<String> note) {
            records.add(step.id() + " before " + before.describe(step.expected().properties())
                + "; after " + after.describe(step.expected().properties())
                + note.map(added -> "; " + added).orElse(""));
        }

        @Override
        public void recordNotNeeded(Step step, State check) {
            records.add(step.id() + " not needed ("
                + check.describe(step.expected().properties()) + ")");
        }
    }

    /**
     * A node for the owner-gated writes: the trigger time changes once W11 ran, the ERROR set is
     * {@code errorSet} until W12 ran and {@code afterDelete} afterwards; every call is listed.
     */
    private static final class SimulatedGatedNode implements GatedNode {

        final List<String> calls = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        /** The failure each step answers with, by step id; none: done. */
        final Map<String, String> failures = new HashMap<>();
        Optional<String> timeBefore = Optional.of("Sat Oct 31 00:00:00 CET 2026");
        Optional<String> timeAfter = Optional.of("Sun Nov 01 00:00:00 CET 2026");
        Optional<Set<String>> errorSet = Optional.of(Set.of());
        Optional<Set<String>> afterDelete = Optional.of(Set.of());
        boolean unreadableEnd;
        private boolean triggered;
        private boolean ranDelete;

        /** Off the baseline: the owner-gated sequence must not care. */
        @Override
        public State read(String label, Set<Property> properties) throws IOException {
            calls.add(label);
            if (unreadableEnd && label.equals("B1")) {
                throw new IOException("unreadable");
            }
            return State.NONE.with(Property.MAINTENANCE, "on");
        }

        @Override
        public Answer run(Step step) {
            calls.add(step.id());
            triggered |= step.equals(W11);
            return Answer.of(Optional.ofNullable(failures.get(step.id())));
        }

        @Override
        public Optional<String> delete() {
            calls.add(W12.id());
            ranDelete = true;
            return Optional.ofNullable(failures.get(W12.id()));
        }

        @Override
        public void recordStates(Step step, State before, State after, Optional<String> note) {
            throw new AssertionError("not part of the owner-gated sequence");
        }

        @Override
        public void recordNotNeeded(Step step, State check) {
            throw new AssertionError("not part of the owner-gated sequence");
        }

        @Override
        public Optional<String> midnightTriggerTime(String label) {
            calls.add(label);
            return triggered ? timeAfter : timeBefore;
        }

        @Override
        public Optional<Set<String>> errorProcesses(String label) {
            calls.add(label);
            return ranDelete ? afterDelete : errorSet;
        }

        @Override
        public void recordNote(Step step, String text) {
            notes.add(step.id() + ": " + text);
        }
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static Optional<String> midnight(int status, String body) {
        return OperateServerWritesLiveTestSupport.midnightTriggerTime(status,
            body.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code none} or comma-separated ids, as the owner approves them. */
    private static Set<String> ids(String list) {
        return list.equals("none") ? Set.of() : Set.of(list.split(","));
    }

    /** A page as the 8.1 process query builds it from {@code total} rows. */
    private static Page<ProcessInstance> page(int offset, int limit, long total,
        List<String> ids) {
        int next = offset + ids.size();
        return new Page<>(ids.stream().map(OperateServerWritesLiveTestSupportTest::errorProcess)
            .toList(), offset, limit, OptionalLong.of(total), false, false,
            next < total ? OptionalInt.of(next) : OptionalInt.empty());
    }

    private static ProcessInstance errorProcess(String id) {
        return new ProcessInstance(NODE, id, Optional.empty(), ProcessState.ERROR, "ERROR",
            Instant.EPOCH, Duration.ZERO, false, null, null, null, null, null, null, null, null);
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
