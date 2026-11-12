package de.dadecker.inubit.mcp.config;

import java.util.Optional;

/**
 * {@code unsupported} block of a group as written in the YAML (feature 020, research D-15;
 * feature 021, research D-17; feature 024, research D-5). It exists only on groups, so
 * {@code unsupported} on a node or in {@code defaults}, and any other key below it, is an unknown
 * key. The opt-ins are independent of each other.
 *
 * @param queueEntryRead allows {@code read_queue_entry}, which reads Queue Manager entries through
 *                       an undocumented INUBIT interface without vendor support; built-in
 *                       {@code false}
 * @param serverLogRead  allows {@code download_server_log} to read the web service log through
 *                       an undocumented INUBIT interface without vendor support; built-in
 *                       {@code false}
 * @param nodeStatusRead allows {@code list_cluster_nodes} through an undocumented INUBIT
 *                       interface without vendor support; built-in {@code false}
 * @param ioLogRead      allows {@code read_io_log}, which reads module input and output messages
 *                       through an undocumented INUBIT interface without vendor support; built-in
 *                       {@code false}
 * @param nodeConfigRead allows {@code get_node_config} through an undocumented INUBIT interface
 *                       without vendor support; built-in {@code false}
 * @param logLevelWrite  allows the temporary log-level tools, which change log levels of a
 *                       running server through an undocumented INUBIT interface without vendor
 *                       support; built-in {@code false}
 */
public record UnsupportedConfig(Optional<Boolean> queueEntryRead,
    Optional<Boolean> serverLogRead, Optional<Boolean> nodeStatusRead,
    Optional<Boolean> ioLogRead, Optional<Boolean> nodeConfigRead,
    Optional<Boolean> logLevelWrite) {

    public static final UnsupportedConfig EMPTY = new UnsupportedConfig(Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    public UnsupportedConfig {
        queueEntryRead = Optionals.orEmpty(queueEntryRead);
        serverLogRead = Optionals.orEmpty(serverLogRead);
        nodeStatusRead = Optionals.orEmpty(nodeStatusRead);
        ioLogRead = Optionals.orEmpty(ioLogRead);
        nodeConfigRead = Optionals.orEmpty(nodeConfigRead);
        logLevelWrite = Optionals.orEmpty(logLevelWrite);
    }
}
