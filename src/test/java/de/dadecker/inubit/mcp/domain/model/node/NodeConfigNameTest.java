package de.dadecker.inubit.mcp.domain.model.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Feature 024 (T006, FR-030, research D-10): the allowlist of node configuration names. */
class NodeConfigNameTest {

    @Test
    void holdsExactlyTheTwentyNamesInSpecOrder() {
        assertThat(Arrays.stream(NodeConfigName.values()).map(NodeConfigName::wireName))
            .containsExactly("OperationMode", "ClusterName", "ZoneName", "NodeName",
                "LocalMaintenanceMode", "ActivateClusterManager", "ClusterDebug",
                "ContainerShutdownOnNodeFailure", "RegisterMBeans", "RegisterInternalMBeans",
                "RuntimeDataCacheXMXPercentage", "socketTimeoutInMillisecs", "indexEnabled",
                "iterationCount", "DataSourceLocation", "LogsDBDataSourceLocation",
                "RepositoryDataSourceLocation", "ClusterBindAddress", "noOfRetries",
                "retryInterval");
    }

    @Test
    void parsesEveryWireName() {
        for (NodeConfigName name : NodeConfigName.values()) {
            assertThat(NodeConfigName.parse(name.wireName())).contains(name);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"operationMode", "OPERATIONMODE", "SocketTimeoutInMillisecs",
        "NoOfRetries", " ClusterName", "ClusterName ", "OPERATION_MODE", "", "Database",
        "ClusterPassword", "initialRootUserPassword", "IdentityProvider",
        "IdentityProviderConfiguration", "RuntimeDataBackupStore",
        "ClusterCacheTaskDataModesConfig", "MaxEntriesInMemoryLimit"})
    void refusesAnyOtherNameCaseSensitively(String name) {
        assertThat(NodeConfigName.parse(name)).isEmpty();
    }

    @Test
    void refusesNull() {
        assertThat(NodeConfigName.parse(null)).isEmpty();
    }
}
