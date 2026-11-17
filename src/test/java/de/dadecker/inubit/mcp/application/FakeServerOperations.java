package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.CliSettingsSnapshot;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.LoadFigures;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.ServerOperationPort;
import de.dadecker.inubit.mcp.domain.port.ServerSettingsPort;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Test double for {@code operate_server} (feature 022, US4): one INUBIT server whose maintenance
 * mode, readiness, trace, trace level, debug mode and processes in state ERROR are fields the
 * switches and {@code processdelete error} change, with the reads of {@link MonitoringPort} and
 * {@link ServerSettingsPort} the service takes its states from. While maintenance mode is on, the
 * REST reads other than {@code GET /ready} (the processes in ERROR included) fail with
 * {@code MAINTENANCE_MODE}, as INUBIT answers them with HTTP 503; {@code GET /ready} answers
 * NOT READY.
 */
final class FakeServerOperations implements GatewayFactory {

    /** Every read and switch, in order, e.g. {@code maintenance -s}, {@code trace on}. */
    final List<String> calls = new CopyOnWriteArrayList<>();
    /** The permits of the switches, in order. */
    final List<WritePermit> permits = new CopyOnWriteArrayList<>();

    volatile boolean maintenance;
    /** Thrown by {@code maintenance -s} if set. */
    volatile ToolErrorException maintenanceReadFailure;
    volatile boolean ready = true;
    volatile boolean trace;
    /** Whether the system information states whether the trace is on. */
    volatile boolean traceReported = true;
    volatile TraceLevel traceLevel = TraceLevel.ERROR;
    /** Whether a read form of the trace level exists (none is known yet, research D-22). */
    volatile boolean traceLevelReadable;
    volatile boolean debug;
    /** Whether a read form of the debug mode exists (none is known yet, research D-22). */
    volatile boolean debugReadable;
    /** The {@code MidnightTaskTriggerTime} of {@code GET /configurations}; empty: not there. */
    volatile Optional<String> midnightTaskTriggerTime =
        Optional.of("Sat Oct 31 00:00:00 CET 2026");
    volatile String midnightTaskAnswer = "Midnight task triggered.";
    /** The processes in state ERROR as {@code POST /log/queueLog} lists them; none by default. */
    volatile ServerOperationPort.ErrorProcesses errorProcesses =
        new ServerOperationPort.ErrorProcesses(0, List.of(), List.of());
    /**
     * The count {@code processdelete error} answers with; empty: the count of
     * {@link #errorProcesses}. The deletion empties {@link #errorProcesses} in any case.
     */
    volatile OptionalLong deletedCount = OptionalLong.empty();
    volatile MonitoringPort.Metrics metrics = new MonitoringPort.Metrics.Available(
        new LoadFigures(512, 512, 1024, Optional.empty(), Optional.of(3L), Optional.of(10L),
            Optional.of(10L), Optional.of(12L), Optional.of(100L), Optional.empty()));
    /** Thrown by every switch before it changes anything, if set. */
    volatile ToolErrorException switchFailure;
    /** Runs inside every switch before it answers (e.g. to start a second call). */
    volatile Runnable duringSwitch = () -> { };

    /** The calls that changed (or tried to change) state. */
    List<String> switches() {
        return calls.stream().filter(call -> !call.equals("maintenance -s")
            && !call.startsWith("GET ") && !call.startsWith("check ")
            && !call.equals("trace level?") && !call.equals("debug?")).toList();
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    private void restRead(NodeId node, String call) {
        calls.add(call);
        if (maintenance) {
            throw new ToolErrorException(ToolError.of(ErrorCode.MAINTENANCE_MODE,
                node + " is in maintenance mode", "HTTP 503", "Wait").withNode(node));
        }
    }

    private <T> T change(String call, WritePermit permit, Supplier<T> apply) {
        permits.add(permit);
        calls.add(call);
        duringSwitch.run();
        if (switchFailure != null) {
            throw switchFailure;
        }
        return apply.get();
    }

    @Override
    public ServerOperationPort serverOperation(NodeId node) {
        return new ServerOperationPort() {
            @Override
            public boolean maintenanceState() {
                calls.add("maintenance -s");
                if (maintenanceReadFailure != null) {
                    throw maintenanceReadFailure;
                }
                return maintenance;
            }

            @Override
            public boolean switchMaintenance(boolean on, Optional<Boolean> executeQueued,
                WritePermit permit) {
                return change("maintenance " + onOff(on)
                    + executeQueued.map(value -> " -eq " + value).orElse(""), permit, () -> {
                        maintenance = on;
                        return on;
                    });
            }

            @Override
            public void switchReadiness(boolean on, WritePermit permit) {
                change("POST /ready/" + onOff(on), permit, () -> {
                    ready = on;
                    return on;
                });
            }

            @Override
            public boolean switchTrace(boolean on, WritePermit permit) {
                return change("trace " + onOff(on), permit, () -> {
                    trace = on;
                    return on;
                });
            }

            @Override
            public Optional<TraceLevel> traceLevel() {
                calls.add("trace level?");
                return traceLevelReadable ? Optional.of(traceLevel) : Optional.empty();
            }

            @Override
            public TraceLevel setTraceLevel(TraceLevel level, WritePermit permit) {
                return change("trace -l " + level.name().toLowerCase(Locale.ROOT), permit, () -> {
                    traceLevel = level;
                    return level;
                });
            }

            @Override
            public Optional<Boolean> debugState() {
                calls.add("debug?");
                return debugReadable ? Optional.of(debug) : Optional.empty();
            }

            @Override
            public boolean switchDebug(boolean on, WritePermit permit) {
                return change("debug " + onOff(on), permit, () -> {
                    debug = on;
                    return on;
                });
            }

            @Override
            public String triggerMidnightTask(WritePermit permit) {
                return change("triggerMidnightTask", permit, () -> midnightTaskAnswer);
            }

            @Override
            public ErrorProcesses errorProcesses(Instant now) {
                restRead(node, "GET /log/queueLog ERROR");
                return errorProcesses;
            }

            @Override
            public long deleteErrorProcesses(WritePermit permit) {
                return change("processdelete error", permit, () -> {
                    long deleted = deletedCount.orElse(errorProcesses.count());
                    errorProcesses = new ErrorProcesses(0, List.of(), List.of());
                    return deleted;
                });
            }
        };
    }

    @Override
    public MonitoringPort monitoring(NodeId node) {
        return new MonitoringPort() {
            @Override
            public Healthcheck healthcheck() {
                throw new AssertionError("the maintenance mode is read with maintenance -s");
            }

            @Override
            public Readiness ready() {
                calls.add("GET /ready");
                return new Readiness(ready && !maintenance, Optional.empty());
            }

            @Override
            public SystemInfo systemInfo() {
                restRead(node, "GET /system");
                return new SystemInfo(Optional.of("8.1.17"), Optional.empty(), Optional.empty(),
                    Optional.empty(), traceReported ? Optional.of(trace) : Optional.empty(),
                    Optional.empty(), Map.of());
            }

            @Override
            public Metrics metrics() {
                restRead(node, "GET /metrics");
                return metrics;
            }

            @Override
            public boolean credentialsConfirmed() {
                return true;
            }
        };
    }

    @Override
    public ServerSettingsPort serverSettings(NodeId node) {
        return new ServerSettingsPort() {
            @Override
            public LicenseFacts license() {
                throw new AssertionError("not used by operate_server");
            }

            @Override
            public ConfigurationView configuration(Optional<String> section, int maxValues,
                int maxChars) {
                restRead(node, "GET /configurations " + section.orElse(""));
                if (midnightTaskTriggerTime.isEmpty()) {
                    throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                        "No section " + section.orElse(""), "not there", "Check").withNode(node));
                }
                return ConfigurationView.section(section.orElseThrow(),
                    List.of(ConfigurationView.Leaf.shown("", midnightTaskTriggerTime.get())),
                    false);
            }

            @Override
            public CliSettingsSnapshot cliSettings(Set<String> parts) {
                throw new AssertionError("not used by operate_server");
            }
        };
    }

    @Override
    public ProcessControlPort processControl(NodeId node) {
        return new ProcessControlPort() {
            @Override
            public void checkAvailable() {
                calls.add("check " + node);
            }

            @Override
            public String restart(String processId, WritePermit permit) {
                throw new AssertionError("not used by operate_server");
            }

            @Override
            public String kill(String processId, WritePermit permit) {
                throw new AssertionError("not used by operate_server");
            }
        };
    }

    @Override
    public Gateway forServer(NodeId node) {
        throw new AssertionError("operate_server takes its ports from the factory");
    }

    @Override
    public InubitVersion detect(NodeId node) {
        throw new AssertionError("the versions are pinned");
    }

    @Override
    public ProcessQueryPort processes(NodeId node) {
        throw new AssertionError("the processes in ERROR come from the operation port");
    }

    @Override
    public LogPort logs(NodeId node) {
        throw new AssertionError("not used by operate_server");
    }

    @Override
    public InventoryPort inventory(NodeId node) {
        throw new AssertionError("not used by operate_server");
    }

    @Override
    public Optional<Gateway> knownGateway(NodeId node) {
        throw new AssertionError("not used by operate_server");
    }
}
