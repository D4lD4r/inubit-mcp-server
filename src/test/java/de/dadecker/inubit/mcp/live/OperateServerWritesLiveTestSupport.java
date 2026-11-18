package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.Case;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.Read;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.RestRead;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The offline parts of {@link OperateServerWritesLiveTest} (feature 022 T054, write set W1–W10 of
 * the live checks L-2 and L-3), tested by {@link OperateServerWritesLiveTestSupportTest}: the
 * owner-approved steps in their order and forms, the gate, the derived state of the four state
 * reads, the expected after-states, the sequence with its stop and restore, the state records and
 * their lines. The case files and summary lines of each command are those of
 * {@link OperateServerReadsLiveTestSupport}. Failure messages and lines name steps, codes, byte
 * counts and derived states (on/off, READY/not READY, level), never output.
 */
final class OperateServerWritesLiveTestSupport {

    /** The opt-in: the operator's statement that the owner approved every step of the list. */
    static final String APPROVAL_VARIABLE = "INUBIT_LIVE_T054_WRITES";
    /**
     * The directory below {@code INUBIT_LIVE_RECORD_DIR} that receives the case files; the read
     * set's {@code T054} stays untouched.
     */
    static final String TASK_DIRECTORY = "T054-writes";

    static final String ON = "on";
    static final String OFF = "off";
    static final String READY = "READY";
    static final String NOT_READY = "not READY";

    /** The answer of {@code debug} (recorded by R6). */
    private static final Pattern DEBUG_ANSWER =
        Pattern.compile("Debug mode is currently (on|off)\\.");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A server state the write set changes, with its value at the baseline. */
    enum Property {
        MAINTENANCE("maintenance", OFF, "R1"),
        READINESS("ready", READY, "R3"),
        TRACE("trace", ON, "R8"),
        TRACE_LEVEL("trace level", "info", "R8"),
        DEBUG("debug", OFF, "R6");

        private final String label;
        private final String baseline;
        /** The read of the read set that shows it. */
        private final String readId;

        Property(String label, String baseline, String readId) {
            this.label = label;
            this.baseline = baseline;
            this.readId = readId;
        }

        String baseline() {
            return baseline;
        }
    }

    static final Set<Property> ALL = Collections.unmodifiableSet(EnumSet.allOf(Property.class));

    /**
     * Derived values per property (on/off, READY/not READY, a level); a property that was not
     * read or whose answer is not one of the recorded forms is missing ("unknown").
     */
    record State(Map<Property, String> values) {

        static final State NONE = new State(Map.of());

        State {
            EnumMap<Property, String> copy = new EnumMap<>(Property.class);
            copy.putAll(values);
            values = Collections.unmodifiableMap(copy);
        }

        Optional<String> get(Property property) {
            return Optional.ofNullable(values.get(property));
        }

        State with(Property property, String value) {
            EnumMap<Property, String> next = new EnumMap<>(Property.class);
            next.putAll(values);
            next.put(property, value);
            return new State(next);
        }

        /** This state with every value of {@code other} put over it. */
        State with(State other) {
            EnumMap<Property, String> next = new EnumMap<>(Property.class);
            next.putAll(values);
            next.putAll(other.values);
            return new State(next);
        }

        State only(Set<Property> properties) {
            EnumMap<Property, String> kept = new EnumMap<>(Property.class);
            values.forEach((property, value) -> {
                if (properties.contains(property)) {
                    kept.put(property, value);
                }
            });
            return new State(kept);
        }

        Set<Property> properties() {
            return values.isEmpty() ? EnumSet.noneOf(Property.class)
                : EnumSet.copyOf(values.keySet());
        }

        /** Whether every value of {@code expected} is the value here. */
        boolean matches(State expected) {
            return expected.values.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(values.get(entry.getKey())));
        }

        /** The {@code properties} that are unknown here or differ from the baseline. */
        List<Property> offBaseline(Collection<Property> properties) {
            return Arrays.stream(Property.values()).filter(properties::contains)
                .filter(property -> !get(property).equals(Optional.of(property.baseline)))
                .toList();
        }

        /** E.g. {@code maintenance on, ready unknown}, in property order. */
        String describe(Collection<Property> properties) {
            return Arrays.stream(Property.values()).filter(properties::contains)
                .map(property -> property.label + " " + get(property).orElse("unknown"))
                .collect(Collectors.joining(", "));
        }
    }

    /** Every property at its baseline value. */
    static final State BASELINE = baseline();

    /** The command of a step: a raw StartCLI or REST command of the catalogue. */
    sealed interface Action permits CliWrite, ReadySwitch, ReadyRead {

        String form();
    }

    /** A StartCLI write through the production {@code CliRunner.run} with a write permit. */
    record CliWrite(CliCommand command) implements Action {

        @Override
        public String form() {
            return command.commandLine();
        }
    }

    /**
     * {@code POST /ready/on|off} through the production write request of the REST client
     * ({@code InubitHttpClient.post} with a permit) to the endpoint
     * {@link RestEndpoint#READY_SWITCH}.
     */
    record ReadySwitch(boolean on) implements Action {

        String state() {
            return on ? ON : OFF;
        }

        @Override
        public String form() {
            return RestEndpoint.READY_SWITCH.method() + " "
                + RestEndpoint.READY_SWITCH.template().replace("{status}", state());
        }
    }

    /** A read as a step of its own (W2a): {@code read} as the read set runs it. */
    record ReadyRead(RestRead read) implements Action {

        @Override
        public String form() {
            return read.form();
        }
    }

    /**
     * One step: its {@code action} runs once; afterwards the properties of {@code expected} are
     * read and must have its values. A step with {@code onlyIfNeeded} runs only if a read of those
     * properties right before it does not show {@code expected} already.
     */
    record Step(String id, String slug, Action action, State expected, boolean onlyIfNeeded)
        implements Case {

        @Override
        public String form() {
            return action.form();
        }
    }

    /** The owner-approved write set, in the order it runs. */
    static final List<Step> STEPS = List.of(
        step("W1", "maintenance-on-eq-false", CliCommand.maintenanceSwitch(true, false),
            Property.MAINTENANCE, ON),
        new Step("W2a", "get-ready", new ReadyRead(readyRead()), State.NONE, false),
        step("W2b", "post-ready-off", new ReadySwitch(false), Property.READINESS, NOT_READY),
        step("W3", "maintenance-off", CliCommand.maintenanceSwitch(false, null),
            Property.MAINTENANCE, OFF),
        new Step("W2u", "post-ready-on", new ReadySwitch(true),
            State.NONE.with(Property.READINESS, READY), true),
        step("W4", "post-ready-off", new ReadySwitch(false), Property.READINESS, NOT_READY),
        step("W5", "post-ready-on", new ReadySwitch(true), Property.READINESS, READY),
        new Step("W6", "trace-on", new CliWrite(CliCommand.traceSwitch(true)),
            State.NONE.with(Property.TRACE, ON).with(Property.TRACE_LEVEL, "info"), false),
        step("W7", "trace-off", CliCommand.traceSwitch(false), Property.TRACE, OFF),
        step("W7b", "trace-on", CliCommand.traceSwitch(true), Property.TRACE, ON),
        step("W8", "trace-l-debug", CliCommand.traceLevel(TraceLevel.DEBUG),
            Property.TRACE_LEVEL, "debug"),
        step("W8b", "trace-l-info", CliCommand.traceLevel(TraceLevel.INFO),
            Property.TRACE_LEVEL, "info"),
        step("W9", "debug-on", CliCommand.debugSwitch(true), Property.DEBUG, ON),
        step("W10", "debug-off", CliCommand.debugSwitch(false), Property.DEBUG, OFF));

    /**
     * The command that brings each property back to its baseline: each one is a command of
     * {@link #STEPS} (W3, W5, W6, W8b, W10).
     */
    static final Map<Property, Step> RESTORE = restoreSteps();

    /**
     * The restore reads its properties in these rounds: maintenance first, since while it is on
     * the other reads may not answer.
     */
    static final List<Set<Property>> RESTORE_ROUNDS = List.of(EnumSet.of(Property.MAINTENANCE),
        EnumSet.of(Property.READINESS, Property.TRACE, Property.TRACE_LEVEL, Property.DEBUG));

    private OperateServerWritesLiveTestSupport() {
    }

    // --- gate ------------------------------------------------------------------------------------

    /**
     * Skips without {@link #APPROVAL_VARIABLE}; fails unless it lists exactly the ids of
     * {@link #STEPS} in their order, comma-separated.
     */
    static void requireApproval(Map<String, String> environment) {
        String value = environment.get(APPROVAL_VARIABLE);
        String steps = STEPS.stream().map(Step::id).collect(Collectors.joining(","));
        Assumptions.assumeTrue(value != null && !value.isBlank(), APPROVAL_VARIABLE + " is not set"
            + " (" + steps + ", only after the owner approved each command); the T054 write set is"
            + " skipped");
        List<String> listed = Arrays.stream(value.split(",", -1)).map(String::strip).toList();
        if (!listed.equals(STEPS.stream().map(Step::id).toList())) {
            fail(APPROVAL_VARIABLE + " must list exactly " + steps + " (only after the owner"
                + " approved each command), or be unset to skip the T054 write set; nothing was"
                + " run");
        }
    }

    /** Fails unless the profile enables writes on {@code node}. */
    static void requireWriteEnabled(NodeId node, boolean writeEnabled) {
        if (!writeEnabled) {
            fail(node + " does not have write enabled in the profile; the T054 write set runs only"
                + " on a node with write enabled; nothing was run");
        }
    }

    // --- state reads -----------------------------------------------------------------------------

    /** The reads of the read set that show {@code properties}, in the read set's order. */
    static List<Read> stateReads(Set<Property> properties) {
        Set<String> ids = properties.stream().map(property -> property.readId)
            .collect(Collectors.toSet());
        return OperateServerReadsLiveTestSupport.READS.stream()
            .filter(read -> ids.contains(read.id())).toList();
    }

    /** The state StartCLI's output lines of the state read {@code readId} (R1 or R6) show. */
    static State parseCli(String readId, List<String> outputLines) {
        List<String> lines = outputLines.stream().map(String::strip)
            .filter(line -> !line.isEmpty()).toList();
        if (lines.size() != 1) {
            return State.NONE;
        }
        String line = lines.get(0);
        return switch (readId) {
            case "R1" -> line.equals(ON) || line.equals(OFF)
                ? State.NONE.with(Property.MAINTENANCE, line) : State.NONE;
            case "R6" -> {
                Matcher matcher = DEBUG_ANSWER.matcher(line);
                yield matcher.matches() ? State.NONE.with(Property.DEBUG, matcher.group(1))
                    : State.NONE;
            }
            default -> throw new IllegalArgumentException(readId + " is no StartCLI state read");
        };
    }

    /** The state the answer to the state read {@code readId} (R3 or R8) shows. */
    static State parseRest(String readId, int status, byte[] body) {
        return switch (readId) {
            case "R3" -> parseReady(status, body);
            case "R8" -> status == 200 ? parseSystemInfo(body) : State.NONE;
            default -> throw new IllegalArgumentException(readId + " is no REST state read");
        };
    }

    /** 200 with {@code "status": "READY"} is READY, 503 is not READY, anything else unknown. */
    private static State parseReady(int status, byte[] body) {
        if (status == 503) {
            return State.NONE.with(Property.READINESS, NOT_READY);
        }
        if (status != 200) {
            return State.NONE;
        }
        try {
            return JSON.readTree(body).path("status").asText().equals(READY)
                ? State.NONE.with(Property.READINESS, READY) : State.NONE;
        } catch (IOException e) {
            return State.NONE;
        }
    }

    /**
     * {@code TracingIsActive} {@code true|false} as on/off and {@code TracingLevel} as one of the
     * {@link TraceLevel}s of a {@code SystemInformationList}; the first entry of a name counts.
     */
    private static State parseSystemInfo(byte[] body) {
        Document document;
        try {
            document = XmlSupport.parse(body);
        } catch (ToolErrorException e) {
            return State.NONE;
        }
        if (!XmlSupport.localName(document.getDocumentElement()).equals("SystemInformationList")) {
            return State.NONE;
        }
        State state = State.NONE;
        Set<String> seen = new HashSet<>();
        for (Element info : XmlSupport.descendants(document, "SystemInformation")) {
            String name = XmlSupport.attribute(info, "name").map(String::strip).orElse("");
            String value = XmlSupport.attribute(info, "value").map(String::strip).orElse("");
            if (!seen.add(name)) {
                continue;
            }
            if (name.equals("TracingIsActive") && (value.equals("true") || value.equals("false"))) {
                state = state.with(Property.TRACE, value.equals("true") ? ON : OFF);
            } else if (name.equals("TracingLevel")) {
                Optional<String> level = Arrays.stream(TraceLevel.values())
                    .map(known -> known.name().toLowerCase(Locale.ROOT))
                    .filter(value::equalsIgnoreCase).findFirst();
                if (level.isPresent()) {
                    state = state.with(Property.TRACE_LEVEL, level.get());
                }
            }
        }
        return state;
    }

    /**
     * The failure of a command: its error code, or the exit code of a StartCLI run that ended
     * other than 0; empty if it succeeded.
     */
    static Optional<String> failure(Optional<ErrorCode> error, Optional<Integer> exit) {
        if (error.isPresent()) {
            return Optional.of(error.get().name());
        }
        return exit.filter(code -> code != 0).map(code -> "exit " + code);
    }

    // --- the sequence ----------------------------------------------------------------------------

    /** The node as the sequence sees it; each call contacts it once and records what it got. */
    interface Node {

        /**
         * Reads {@code properties} once with their state reads, recorded as {@code <label>-<R#>};
         * an unreadable property is missing.
         */
        State read(String label, Set<Property> properties) throws IOException;

        /** Runs the command of {@code step} once, never retried: its failure, if any. */
        Optional<String> run(Step step) throws IOException;

        /** Records the before and after state of {@code step}. */
        void recordStates(Step step, State before, State after) throws IOException;

        /** Records that {@code step} was not needed: {@code check} shows its expected state. */
        void recordNotNeeded(Step step, State check) throws IOException;
    }

    /** Where and why the sequence stopped: a code, or {@code DEVIATION} with the states. */
    record Stop(String step, String code) {
    }

    /**
     * The end of a run: where it stopped, the restore commands that ran (each with its failure),
     * and the state at the end.
     */
    record Outcome(Optional<Stop> stop, List<String> restored, State end) {
    }

    /**
     * Runs the write set on {@code node}: the baseline must hold (else it fails with nothing
     * written); then each step once, in order, its expected state read afterwards. At the first
     * error or deviation the sequence stops and {@link #restore} brings back the baseline. At the
     * end the full state is read again.
     */
    static Outcome run(Node node) throws IOException {
        State baseline = node.read("B0", ALL);
        List<Property> off = baseline.offBaseline(ALL);
        if (!off.isEmpty()) {
            fail("the baseline does not hold before W1 (" + baseline.describe(off) + ", expected "
                + BASELINE.describe(off) + "); nothing was written");
        }
        State known = baseline;
        Optional<Stop> stop = Optional.empty();
        for (Step step : STEPS) {
            Set<Property> checked = step.expected().properties();
            try {
                if (step.onlyIfNeeded()) {
                    State check = node.read(step.id() + "-check", checked);
                    known = known.with(check);
                    if (check.matches(step.expected())) {
                        node.recordNotNeeded(step, check);
                        continue;
                    }
                }
                Optional<String> failure = node.run(step);
                if (!checked.isEmpty()) {
                    State after = node.read(step.id() + "-after", checked);
                    node.recordStates(step, known.only(checked), after);
                    known = known.with(after);
                    if (failure.isEmpty() && !after.matches(step.expected())) {
                        failure = Optional.of("DEVIATION (" + after.describe(checked)
                            + ", expected " + step.expected().describe(checked) + ")");
                    }
                }
                stop = failure.map(code -> new Stop(step.id(), code));
            } catch (IOException | RuntimeException | AssertionError e) {
                stop = Optional.of(new Stop(step.id(), e.getClass().getSimpleName()));
            }
            if (stop.isPresent()) {
                break;
            }
        }
        List<String> restored = stop.isPresent() ? restore(node) : List.of();
        return new Outcome(stop, restored, node.read("B1", ALL));
    }

    /**
     * Brings back the baseline after a stop: per round of {@link #RESTORE_ROUNDS} it reads the
     * round's properties and runs the {@link #restorePlan} for them, each command once; a failed
     * command is not retried. An unreadable property counts as off the baseline.
     *
     * @return the commands that ran, each with its failure
     */
    static List<String> restore(Node node) {
        List<String> restored = new ArrayList<>();
        for (int round = 0; round < RESTORE_ROUNDS.size(); round++) {
            Set<Property> properties = RESTORE_ROUNDS.get(round);
            State observed;
            try {
                observed = node.read("X" + (round + 1), properties);
            } catch (IOException | RuntimeException | AssertionError e) {
                observed = State.NONE;
            }
            for (Step step : restorePlan(observed, properties)) {
                Optional<String> failure;
                try {
                    failure = node.run(step);
                } catch (IOException | RuntimeException | AssertionError e) {
                    failure = Optional.of(e.getClass().getSimpleName());
                }
                restored.add(step.form() + failure.map(code -> " failed: " + code).orElse(""));
            }
        }
        return restored;
    }

    /**
     * The restore commands for those {@code properties} that {@code observed} shows off the
     * baseline or not at all, in property order: only commands of {@link #RESTORE}.
     */
    static List<Step> restorePlan(State observed, Set<Property> properties) {
        return observed.offBaseline(properties).stream().map(RESTORE::get).toList();
    }

    /** Fails if the sequence stopped or the baseline does not hold at the end. */
    static void assertOutcome(Outcome outcome) {
        List<Property> off = outcome.end().offBaseline(ALL);
        String end = off.isEmpty() ? "the baseline holds again"
            : "the baseline does not hold (" + outcome.end().describe(off) + ", expected "
                + BASELINE.describe(off) + ")";
        if (outcome.stop().isPresent()) {
            Stop stop = outcome.stop().get();
            fail("T054 write " + stop.step() + " stopped the sequence: " + stop.code()
                + "; restored: " + (outcome.restored().isEmpty() ? "nothing"
                    : String.join(", ", outcome.restored())) + "; " + end);
        }
        if (!off.isEmpty()) {
            fail("T054 write set ran to the end, but " + end);
        }
    }

    // --- records and lines -----------------------------------------------------------------------

    /** The line of a state read labelled {@code label}: derived states only. */
    static String stateLine(NodeId node, String label, State state, Set<Property> properties) {
        return "[live] T054 " + label + " state on " + node + ": " + state.describe(properties);
    }

    /**
     * Writes the before, after and expected state of {@code step} as {@code <stem>.state},
     * owner-only.
     *
     * @return its line
     */
    static String recordStates(PrivateFileSystem files, Path directory, NodeId node, Step step,
        State before, State after) throws IOException {
        Set<Property> checked = step.expected().properties();
        String text = "before " + before.describe(checked) + "; after " + after.describe(checked)
            + "; expected " + step.expected().describe(checked);
        OperateServerReadsLiveTestSupport.write(files, directory.resolve(step.stem() + ".state"),
            (text + "\n").getBytes(StandardCharsets.US_ASCII));
        return OperateServerReadsLiveTestSupport.summary(node, step, text);
    }

    /** Writes that {@code step} was not needed, with the state {@code check} showed, owner-only. */
    static String recordNotNeeded(PrivateFileSystem files, Path directory, NodeId node, Step step,
        State check) throws IOException {
        String text = "not needed (" + check.describe(step.expected().properties()) + ")";
        OperateServerReadsLiveTestSupport.write(files, directory.resolve(step.stem() + ".state"),
            (text + "\n").getBytes(StandardCharsets.US_ASCII));
        return OperateServerReadsLiveTestSupport.summary(node, step, text);
    }

    // --- construction ----------------------------------------------------------------------------

    private static Step step(String id, String slug, CliCommand command, Property property,
        String value) {
        return step(id, slug, new CliWrite(command), property, value);
    }

    private static Step step(String id, String slug, Action action, Property property,
        String value) {
        return new Step(id, slug, action, State.NONE.with(property, value), false);
    }

    private static RestRead readyRead() {
        return OperateServerReadsLiveTestSupport.READS.stream()
            .filter(read -> read.id().equals(Property.READINESS.readId))
            .map(RestRead.class::cast).findFirst().orElseThrow();
    }

    private static State baseline() {
        State state = State.NONE;
        for (Property property : Property.values()) {
            state = state.with(property, property.baseline);
        }
        return state;
    }

    private static Map<Property, Step> restoreSteps() {
        Map<Property, Step> steps = new EnumMap<>(Property.class);
        steps.put(Property.MAINTENANCE, restore("maintenance-off",
            new CliWrite(CliCommand.maintenanceSwitch(false, null)), Property.MAINTENANCE));
        steps.put(Property.READINESS, restore("post-ready-on", new ReadySwitch(true),
            Property.READINESS));
        steps.put(Property.TRACE, restore("trace-on", new CliWrite(CliCommand.traceSwitch(true)),
            Property.TRACE));
        steps.put(Property.TRACE_LEVEL, restore("trace-l-info",
            new CliWrite(CliCommand.traceLevel(TraceLevel.INFO)), Property.TRACE_LEVEL));
        steps.put(Property.DEBUG, restore("debug-off",
            new CliWrite(CliCommand.debugSwitch(false)), Property.DEBUG));
        return Collections.unmodifiableMap(steps);
    }

    private static Step restore(String slug, Action action, Property property) {
        return new Step("restore", slug, action, State.NONE.with(property, property.baseline),
            false);
    }
}
