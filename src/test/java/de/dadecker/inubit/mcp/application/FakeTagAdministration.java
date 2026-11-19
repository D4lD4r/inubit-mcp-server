package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.TagAdministrationPort;
import de.dadecker.inubit.mcp.domain.port.WriteLock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for {@code manage_tags} (feature 022, US4): one INUBIT server with users and their
 * active tags as a field, which the changes change.
 */
final class FakeTagAdministration implements GatewayFactory {

    /** Every read and change, in order, e.g. {@code user jdoe}, {@code tag --tagSetActive …}. */
    final List<String> calls = new CopyOnWriteArrayList<>();
    /** The permits of the changes, in order. */
    final List<WritePermit> permits = new CopyOnWriteArrayList<>();

    /** The known users and their active tags ({@code ""}: none). */
    final Map<String, String> activeTags = new ConcurrentHashMap<>(Map.of("jdoe", "REL-1",
        "asmith", ""));
    /** Thrown by every change before it changes anything, if set. */
    volatile ToolErrorException changeFailure;
    /** Runs inside every read before it answers (e.g. to start a second call). */
    volatile Runnable duringRead = () -> { };
    /**
     * If set, the changes are handed off under this lock like the StartCLI adapter does
     * (research D-17, D-24); a refused hand-off records the permit but no call.
     */
    volatile WriteLock lock;

    /** The calls that changed (or tried to change) state. */
    List<String> changes() {
        return calls.stream().filter(call -> call.startsWith("tag ")).toList();
    }

    private void change(String call, WritePermit permit, Runnable apply) {
        permits.add(permit);
        WriteLock current = lock;
        if (current != null) {
            current.handOff(permit, version -> {
                change(call, apply);
                return null;
            });
            return;
        }
        change(call, apply);
    }

    private void change(String call, Runnable apply) {
        calls.add(call);
        if (changeFailure != null) {
            throw changeFailure;
        }
        apply.run();
    }

    @Override
    public TagAdministrationPort tagAdministration(NodeId node) {
        return new TagAdministrationPort() {
            @Override
            public Optional<String> activeTag(String user) {
                calls.add("user " + user);
                duringRead.run();
                String tag = activeTags.get(user);
                if (tag == null) {
                    throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                        "There is no user " + user + " on " + node, "unknown user",
                        "List the users with read_users").withNode(node));
                }
                return tag.isEmpty() ? Optional.empty() : Optional.of(tag);
            }

            @Override
            public void setActiveTag(String user, String tag, WritePermit permit) {
                change("tag --tagSetActive " + tag + " --tagUser " + user, permit,
                    () -> activeTags.put(user, tag));
            }

            @Override
            public void removeActiveTag(String user, WritePermit permit) {
                change("tag --tagRemoveActive --tagUser " + user, permit,
                    () -> activeTags.put(user, ""));
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
                throw new AssertionError("not used by manage_tags");
            }

            @Override
            public String kill(String processId, WritePermit permit) {
                throw new AssertionError("not used by manage_tags");
            }
        };
    }

    @Override
    public Gateway forServer(NodeId node) {
        throw new AssertionError("manage_tags takes its port from the factory");
    }

    @Override
    public InubitVersion detect(NodeId node) {
        throw new AssertionError("the versions are pinned");
    }

    @Override
    public MonitoringPort monitoring(NodeId node) {
        throw new AssertionError("not used by manage_tags");
    }

    @Override
    public ProcessQueryPort processes(NodeId node) {
        throw new AssertionError("not used by manage_tags");
    }

    @Override
    public LogPort logs(NodeId node) {
        throw new AssertionError("not used by manage_tags");
    }

    @Override
    public InventoryPort inventory(NodeId node) {
        throw new AssertionError("not used by manage_tags");
    }

    @Override
    public Optional<Gateway> knownGateway(NodeId node) {
        throw new AssertionError("not used by manage_tags");
    }
}
