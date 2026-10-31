package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.adapter.NodeSecrets;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Credentials for tests (feature 011): the entry names of the credential store are the variable
 * names of earlier versions, so a map of names to values becomes a {@link FakeCredentialStore}.
 */
public final class TestSecrets {

    private TestSecrets() {
    }

    /** A credential store double holding {@code entries} (account → value). */
    public static FakeCredentialStore store(Map<String, String> entries) {
        FakeCredentialStore store = new FakeCredentialStore();
        entries.forEach(store::put);
        return store;
    }

    /** One credential store {@link NodeSecrets} per node, reading {@code entries} on first use. */
    public static Map<NodeId, NodeSecrets> credentialStore(List<EffectiveNodeConfig> servers,
        Map<String, String> entries, SecretScrubber scrubber) {
        FakeCredentialStore store = store(entries);
        Map<NodeId, NodeSecrets> secrets = new HashMap<>();
        for (EffectiveNodeConfig server : servers) {
            secrets.put(server.id(), NodeSecrets.credentialStore(server, store, scrubber,
                Clock.systemUTC()));
        }
        return secrets;
    }
}
