package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one StartCLI health session read on an INUBIT 6.1 node (feature 011, research D-6).
 *
 * @param alive           the alive check succeeded (login and {@code uptime})
 * @param versionText     the version StartCLI reported
 * @param versionIsServer true if {@code versionText} is the server's version, not the client's
 *                        (FR-006)
 * @param maintenance     the maintenance mode
 * @param memory          the JVM memory
 * @param counts          the process instances per state
 * @param missing         the parts the session could not read, with the reason
 */
public record CliHealthSnapshot(boolean alive, Optional<String> versionText,
    boolean versionIsServer, Optional<Boolean> maintenance, Optional<MemoryFigures> memory,
    Optional<ProcessCounts> counts, List<UnavailablePart> missing) {

    public CliHealthSnapshot {
        Objects.requireNonNull(versionText, "versionText");
        Objects.requireNonNull(maintenance, "maintenance");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(counts, "counts");
        missing = List.copyOf(missing);
        if (versionIsServer && versionText.isEmpty()) {
            throw new IllegalArgumentException("a server version needs the version text");
        }
    }
}
