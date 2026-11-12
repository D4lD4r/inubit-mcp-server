package de.dadecker.inubit.mcp.domain.model;

import java.util.Set;

/**
 * What a tool needs from an INUBIT node, with its kind and channels as in the spec's
 * "Tool classification" (data-model.md → Capability). {@code list_nodes} and
 * {@code check_artifacts} need no capability.
 */
public enum Capability {
    MONITORING(Kind.READ, Set.of(Channel.REST)),
    /** {@code find_processes}. */
    PROCESS_DIAGNOSIS(Kind.READ, Set.of(Channel.REST)),
    /** {@code query_logs}. */
    LOG_DIAGNOSIS(Kind.READ, Set.of(Channel.REST)),
    /** Diagrams via REST; modules and the history via StartCLI, each admitted on its own. */
    INVENTORY(Kind.READ, Set.of(Channel.REST, Channel.CLI), Admission.PER_CHANNEL),
    /** {@code find_partners}: the Partner Management of system diagrams (feature 018). */
    PARTNER_MANAGEMENT(Kind.READ, Set.of(Channel.REST)),
    WORKSPACE_EXPORT(Kind.READ, Set.of(Channel.CLI)),
    /** StartCLI plus REST reads. */
    DEVELOPMENT_WRITE(Kind.WRITE, Set.of(Channel.CLI, Channel.REST)),
    DEPLOYMENT(Kind.WRITE, Set.of(Channel.CLI, Channel.REST)),
    E2E_TEST(Kind.WRITE, Set.of(Channel.SOAP, Channel.REST)),
    /** REST read plus StartCLI. */
    PROCESS_CONTROL(Kind.WRITE, Set.of(Channel.REST, Channel.CLI)),
    /**
     * {@code read_queue_entry}: REST lookup plus a helper on the INUBIT client (feature 020),
     * admitted for both in one call.
     */
    QUEUE_ENTRY_READ(Kind.READ, Set.of(Channel.REST, Channel.CLI)),
    /** {@code download_server_log} with {@code log: trace}: StartCLI (feature 021). */
    TRACE_LOG_READ(Kind.READ, Set.of(Channel.CLI)),
    /**
     * {@code download_server_log} with {@code log: webservice}: a helper on the INUBIT client
     * (feature 021).
     */
    WEBSERVICE_LOG_READ(Kind.READ, Set.of(Channel.CLI)),
    /**
     * {@code get_server_settings}: REST and StartCLI parts, each admitted by the coverage gate on
     * its own (feature 022, research D-1, D-2).
     */
    SERVER_SETTINGS(Kind.READ, Set.of(Channel.REST, Channel.CLI));

    /** Whether the capability only reads from INUBIT or changes it. */
    public enum Kind {
        READ,
        WRITE
    }

    /** Whether the version gate admits a tool call for all its channels at once or per part. */
    private enum Admission {
        WHOLE_CALL,
        PER_CHANNEL
    }

    private final Kind kind;
    private final Set<Channel> channels;
    private final Admission admission;

    Capability(Kind kind, Set<Channel> channels) {
        this(kind, channels, Admission.WHOLE_CALL);
    }

    Capability(Kind kind, Set<Channel> channels, Admission admission) {
        this.kind = kind;
        this.channels = channels;
        this.admission = admission;
    }

    public Kind kind() {
        return kind;
    }

    /** The channels the capability's tools use (unmodifiable). */
    public Set<Channel> channels() {
        return channels;
    }

    /**
     * Whether its tools ask the version gate per channel, so that a part over one channel still
     * runs while another is refused ({@code inventory}); otherwise one call is admitted for all
     * its channels at once.
     */
    public boolean admittedPerChannel() {
        return admission == Admission.PER_CHANNEL;
    }

    /**
     * The capability as named in the spec's tool classification, in refusals and in
     * {@code --check-config}.
     */
    public String label() {
        return switch (this) {
            case MONITORING -> "monitoring";
            case PROCESS_DIAGNOSIS -> "process diagnosis";
            case LOG_DIAGNOSIS -> "log diagnosis";
            case INVENTORY -> "inventory";
            case PARTNER_MANAGEMENT -> "partner management";
            case WORKSPACE_EXPORT -> "workspace export";
            case DEVELOPMENT_WRITE -> "development writes";
            case DEPLOYMENT -> "deployment";
            case E2E_TEST -> "end-to-end test";
            case PROCESS_CONTROL -> "process control";
            case QUEUE_ENTRY_READ -> "reading Queue Manager entries";
            case TRACE_LOG_READ -> "downloading the trace log";
            case WEBSERVICE_LOG_READ -> "downloading the web service log";
            case SERVER_SETTINGS -> "reading server settings";
        };
    }
}
