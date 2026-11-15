package de.dadecker.inubit.mcp.domain.model.node;

/** Why a reset of a lease did not happen (research D-16, D-22), with a fixed text. */
public enum LeaseReason {
    OTHER_NODE_ANSWERS("other node answers"),
    NODE_UNREACHABLE("node unreachable"),
    PRECONDITION("cleanup precondition not met"),
    CLI_FAILED("StartCLI failed"),
    /** The cleanup's {@code PENDING} audit record could not be written; nothing was sent. */
    AUDIT_NOT_WRITABLE("audit record not writable"),
    SERVER_NOT_CONFIGURED("server not configured");

    private final String text;

    LeaseReason(String text) {
        this.text = text;
    }

    /** The fixed text shown to operators. */
    public String text() {
        return text;
    }
}
