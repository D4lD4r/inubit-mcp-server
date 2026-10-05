package de.dadecker.inubit.mcp.mcp;

import java.util.Optional;

/**
 * What a tool call knows about its MCP client (SDK-free, so that handlers do not depend on the
 * SDK's exchange): the {@code clientInfo} name and version from the {@code initialize} exchange,
 * as the client sent them. The write tools record them in the audit log (data-model.md →
 * AuditRecord {@code mcpClient}).
 */
public record CallContext(Optional<String> clientName, Optional<String> clientVersion) {

    /** Upper bound of each value; longer client-supplied values are cut. */
    public static final int MAX_LENGTH = 100;

    /** No client information. */
    public static final CallContext NONE = new CallContext(Optional.empty(), Optional.empty());

    public CallContext {
        clientName = clean(clientName);
        clientVersion = clean(clientVersion);
    }

    /** From the client's {@code initialize} values; {@code null} or blank values are absent. */
    public static CallContext of(String name, String version) {
        return new CallContext(Optional.ofNullable(name), Optional.ofNullable(version));
    }

    /** {@code name/version}, {@code name}, or empty if the client sent no name. */
    public Optional<String> client() {
        return clientName.map(name -> clientVersion.map(version -> name + "/" + version)
            .orElse(name));
    }

    /** Client-supplied text: control characters replaced, stripped, bounded. */
    private static Optional<String> clean(Optional<String> value) {
        if (value == null) {
            return Optional.empty();
        }
        return value.map(text -> text.replaceAll("\\p{Cntrl}", " ").strip())
            .filter(text -> !text.isEmpty())
            .map(text -> text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH));
    }
}
