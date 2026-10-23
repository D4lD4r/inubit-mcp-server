package de.dadecker.inubit.mcp.domain.model;

import java.util.Set;

/**
 * What a tool needs from an INUBIT node, with its kind and channels as in the spec's
 * "Tool classification" (data-model.md → Capability). {@code list_nodes} and
 * {@code check_artifacts} need no capability.
 */
public enum Capability {
    MONITORING(Kind.READ, Set.of(Channel.REST)),
    DIAGNOSIS(Kind.READ, Set.of(Channel.REST)),
    /** Diagrams via REST; modules and the history via StartCLI. */
    INVENTORY(Kind.READ, Set.of(Channel.REST, Channel.CLI)),
    WORKSPACE_EXPORT(Kind.READ, Set.of(Channel.CLI)),
    /** StartCLI plus REST reads. */
    DEVELOPMENT_WRITE(Kind.WRITE, Set.of(Channel.CLI, Channel.REST)),
    DEPLOYMENT(Kind.WRITE, Set.of(Channel.CLI, Channel.REST)),
    E2E_TEST(Kind.WRITE, Set.of(Channel.SOAP, Channel.REST)),
    /** REST read plus StartCLI. */
    PROCESS_CONTROL(Kind.WRITE, Set.of(Channel.REST, Channel.CLI));

    /** Whether the capability only reads from INUBIT or changes it. */
    public enum Kind {
        READ,
        WRITE
    }

    private final Kind kind;
    private final Set<Channel> channels;

    Capability(Kind kind, Set<Channel> channels) {
        this.kind = kind;
        this.channels = channels;
    }

    public Kind kind() {
        return kind;
    }

    /** The channels the capability's tools use (unmodifiable). */
    public Set<Channel> channels() {
        return channels;
    }
}
