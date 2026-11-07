package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import java.util.Optional;
import java.util.function.Function;

/**
 * Hands a write off only on the version it was admitted on (feature 017, FR-018, research D-24,
 * data-model.md → WriteLock). Implemented by {@code NodeVersions}: the node's version state cannot
 * change between the check and the end of the action.
 */
public interface WriteLock {

    /**
     * Takes the lock of {@code permit.node()}, refuses unless the node's known version has the
     * permit's major.minor and runs {@code action} with that version while holding the lock. The
     * action covers only the irreversible hand-off (a process start, an HTTP request handed to the
     * client), never a wait for its result; it must neither change a node's version state nor hand
     * off again ({@link IllegalStateException}).
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code UNSUPPORTED_VERSION}
     *     when the node's version changed or may have changed since the admission; the action was
     *     not run
     */
    <T> T handOff(WritePermit permit, Function<Optional<InubitVersion>, T> action);
}
