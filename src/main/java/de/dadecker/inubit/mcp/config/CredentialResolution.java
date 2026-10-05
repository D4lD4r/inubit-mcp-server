package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Result of {@link CredentialResolver#resolve}: credentials per server (in the given order) plus
 * startup errors and warnings. Messages name variables, never values.
 */
public record CredentialResolution(
    List<NodeCredentials> all,
    List<String> errors,
    List<String> warnings) {

    public CredentialResolution {
        all = List.copyOf(all);
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public NodeCredentials credentials(NodeId server) {
        return all.stream()
            .filter(credentials -> credentials.node().equals(server))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("No credentials resolved for " + server));
    }
}
