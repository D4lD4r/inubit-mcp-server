package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The keys of the changes of feature 022 that are running (research D-17, edge case "second
 * change"): a change holds {@code node|area|object} while it runs, where the object is a name, a
 * path, an operation id or {@code *} for a bulk action. A second change with a key already held is
 * refused with {@code PRECONDITION_FAILED}, as for {@code restart_process}. Use it as
 * {@code try (var lock = locks.acquire(node, area, object)) { … }}, so the key is released in the
 * implicit {@code finally}, also after an exception.
 *
 * <p>Separate from {@link de.dadecker.inubit.mcp.domain.port.WriteLock#handOff}, which guards the
 * version the change was admitted on.
 */
public final class ChangeLocks {

    private final Map<String, Lock> held = new ConcurrentHashMap<>();

    /**
     * Takes the key {@code node|area|object}.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} when another change holds the key
     */
    public Lock acquire(NodeId node, ActionSpec.Area area, String object) {
        String key = node + "|" + area + "|" + object;
        Lock lock = new Lock(key);
        if (held.putIfAbsent(key, lock) != null) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "Another change of " + object + " (" + area + ") on " + node
                    + " is running; nothing was changed",
                "The same change was requested twice at the same time",
                "Wait for the running change to end and check the state again").withNode(node));
        }
        return lock;
    }

    /** A held key; {@link #close()} releases it, and only it, also when called twice. */
    public final class Lock implements AutoCloseable {

        private final String key;

        private Lock(String key) {
            this.key = key;
        }

        @Override
        public void close() {
            held.remove(key, this);
        }
    }
}
