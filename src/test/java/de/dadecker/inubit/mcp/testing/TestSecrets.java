package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.adapter.NodeSecrets;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.Map;
import java.util.stream.Collectors;

/** The {@link NodeSecrets} of environment nodes, for tests that build the gateway factory. */
public final class TestSecrets {

    private TestSecrets() {
    }

    /** One environment {@link NodeSecrets} per resolved node. */
    public static Map<NodeId, NodeSecrets> environment(CredentialResolution credentials) {
        return credentials.all().stream()
            .collect(Collectors.toMap(NodeCredentials::node, NodeSecrets::environment));
    }
}
