package de.dadecker.inubit.mcp.adapter.cli.helper;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The client helper's own list of the node configuration names {@code nodeConfig} may read, its
 * second line of defence (feature 024, research D-10, SC-003): the same names as
 * {@link NodeConfigName}, and none that holds a secret. The list is read reflectively from the
 * helper as it is shipped, in its resource directory.
 */
class NodeConfigAllowlistTest {

    /** The names of D-10 whose values are or contain secrets. */
    private static final List<String> SECRET_HOLDING_NAMES = List.of("Database",
        "ClusterPassword", "initialRootUserPassword", "IdentityProvider",
        "IdentityProviderConfiguration", "RuntimeDataBackupStore",
        "ClusterCacheTaskDataModesConfig", "MaxEntriesInMemoryLimit");

    @Test
    void theHelpersListEqualsTheServersAllowlistAndHoldsNoSecretName() throws Exception {
        List<String> helperNames = helperNames();

        assertThat(helperNames).containsExactlyElementsOf(Arrays.stream(NodeConfigName.values())
            .map(NodeConfigName::wireName).toList());
        assertThat(helperNames).hasSize(20).doesNotContainAnyElementsOf(SECRET_HOLDING_NAMES);
    }

    private static List<String> helperNames() throws Exception {
        URL dir = ClientHelperPackagingTest.resourceDirectory().toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir},
            NodeConfigAllowlistTest.class.getClassLoader())) {
            Class<?> helper = Class.forName(ClientHelperPackagingTest.HELPER_CLASS, false, loader);
            Field names = helper.getDeclaredField("NODE_CONFIG_NAMES");
            names.setAccessible(true);
            return ((List<?>) names.get(null)).stream().map(String.class::cast).toList();
        }
    }
}
