package de.dadecker.inubit.mcp.domain.model.node;

import java.util.Arrays;
import java.util.Optional;

/**
 * The node configuration names {@code get_node_config} may read, the same for 8.1 and 9.0
 * (FR-030, research D-10). No other name is ever sent; the helper holds its own copy of this
 * list.
 */
public enum NodeConfigName {
    OPERATION_MODE("OperationMode"),
    CLUSTER_NAME("ClusterName"),
    ZONE_NAME("ZoneName"),
    NODE_NAME("NodeName"),
    LOCAL_MAINTENANCE_MODE("LocalMaintenanceMode"),
    ACTIVATE_CLUSTER_MANAGER("ActivateClusterManager"),
    CLUSTER_DEBUG("ClusterDebug"),
    CONTAINER_SHUTDOWN_ON_NODE_FAILURE("ContainerShutdownOnNodeFailure"),
    REGISTER_MBEANS("RegisterMBeans"),
    REGISTER_INTERNAL_MBEANS("RegisterInternalMBeans"),
    RUNTIME_DATA_CACHE_XMX_PERCENTAGE("RuntimeDataCacheXMXPercentage"),
    SOCKET_TIMEOUT_IN_MILLISECS("socketTimeoutInMillisecs"),
    INDEX_ENABLED("indexEnabled"),
    ITERATION_COUNT("iterationCount"),
    DATA_SOURCE_LOCATION("DataSourceLocation"),
    LOGS_DB_DATA_SOURCE_LOCATION("LogsDBDataSourceLocation"),
    REPOSITORY_DATA_SOURCE_LOCATION("RepositoryDataSourceLocation"),
    CLUSTER_BIND_ADDRESS("ClusterBindAddress"),
    NO_OF_RETRIES("noOfRetries"),
    RETRY_INTERVAL("retryInterval");

    private final String wireName;

    NodeConfigName(String wireName) {
        this.wireName = wireName;
    }

    /** The INUBIT name, used in tool inputs and results and sent to the helper. */
    public String wireName() {
        return wireName;
    }

    /** The name whose {@link #wireName()} equals {@code name} exactly (case-sensitive). */
    public static Optional<NodeConfigName> parse(String name) {
        return Arrays.stream(values()).filter(value -> value.wireName.equals(name)).findFirst();
    }
}
