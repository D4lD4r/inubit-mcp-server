package de.dadecker.inubit.mcp.domain.model.node;

/** Whether a logged message is a module's input or its output (research D-8). */
public enum IoDirection {
    INPUT("Input"),
    OUTPUT("Output");

    private final String wireName;

    IoDirection(String wireName) {
        this.wireName = wireName;
    }

    /** The name in an I/O log entry ({@code …@@@Input.<n>}). */
    public String wireName() {
        return wireName;
    }
}
