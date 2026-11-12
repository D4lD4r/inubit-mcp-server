package de.dadecker.inubit.mcp.domain.model;

/** The server log files {@code download_server_log} can fetch (feature 021, D-19). */
public enum ServerLogKind {
    TRACE("trace", "trace.log"),
    WEBSERVICE("webservice", "webservice.log");

    private final String wireName;
    private final String fileName;

    ServerLogKind(String wireName, String fileName) {
        this.wireName = wireName;
        this.fileName = fileName;
    }

    /** The value of the tool input {@code log}. */
    public String wireName() {
        return wireName;
    }

    /** The file name on the server; used only for messages and the helper constant check. */
    public String fileName() {
        return fileName;
    }

    /** The kind of a tool input {@code log} (case-sensitive); anything else is rejected. */
    public static ServerLogKind fromWire(String wireName) {
        for (ServerLogKind kind : values()) {
            if (kind.wireName.equals(wireName)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("log must be trace or webservice");
    }
}
