package de.dadecker.inubit.mcp.config;

import java.util.Optional;

/**
 * {@code unsupported} block of a group as written in the YAML (feature 020, research D-15;
 * feature 021, research D-17). It exists only on groups, so {@code unsupported} on a node or in
 * {@code defaults}, and any other key below it, is an unknown key. The two opt-ins are
 * independent.
 *
 * @param queueEntryRead allows {@code read_queue_entry}, which reads Queue Manager entries through
 *                       an undocumented INUBIT interface without vendor support; built-in
 *                       {@code false}
 * @param serverLogRead  allows {@code download_server_log} to read the web service log through
 *                       an undocumented INUBIT interface without vendor support; built-in
 *                       {@code false}
 */
public record UnsupportedConfig(Optional<Boolean> queueEntryRead,
    Optional<Boolean> serverLogRead) {

    public static final UnsupportedConfig EMPTY =
        new UnsupportedConfig(Optional.empty(), Optional.empty());

    public UnsupportedConfig {
        queueEntryRead = Optionals.orEmpty(queueEntryRead);
        serverLogRead = Optionals.orEmpty(serverLogRead);
    }
}
