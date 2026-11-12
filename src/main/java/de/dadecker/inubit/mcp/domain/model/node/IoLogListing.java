package de.dadecker.inubit.mcp.domain.model.node;

import java.util.List;
import java.util.Optional;

/**
 * The I/O log listing of a process run (research D-8).
 *
 * @param answeringNode the INUBIT node that answered (name rule of {@link ClusterNode})
 * @param entries       all listed entries in server order, unparsable module parts included
 */
public record IoLogListing(String answeringNode, List<IoLogEntry> entries) {

    public IoLogListing {
        ClusterNode.checkName(answeringNode);
        entries = List.copyOf(entries);
    }

    /**
     * The entries whose {@code moduleName} or {@code moduleId} equals {@code module} exactly, in
     * server order; all entries without {@code module}.
     */
    public List<IoLogEntry> filter(Optional<String> module) {
        return module.map(name -> entries.stream()
                .filter(entry -> entry.moduleName().equals(name)
                    || entry.moduleId().filter(name::equals).isPresent())
                .toList())
            .orElse(entries);
    }
}
