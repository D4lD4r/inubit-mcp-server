package de.dadecker.inubit.mcp.domain.model.node;

/** The state of a temporary log level lease (data-model "Lease lifecycle"). */
public enum LeaseState {
    ACTIVE,
    RESET,
    RESET_FAILED,
    FORGOTTEN;

    /** True while the node may still run a raised level: {@link #ACTIVE} or {@link #RESET_FAILED}. */
    public boolean open() {
        return this == ACTIVE || this == RESET_FAILED;
    }
}
