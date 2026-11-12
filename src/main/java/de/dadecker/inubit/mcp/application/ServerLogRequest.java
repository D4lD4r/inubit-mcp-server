package de.dadecker.inubit.mcp.application;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The input of one {@code download_server_log} call (feature 021, data-model.md →
 * ServerLogRequest), as the tool received it after the input schema. {@link #toString()} never
 * shows the {@code contains} text, only whether one was given.
 *
 * @param target       the id of exactly one server ({@code <group>/<node>})
 * @param log          {@code trace} or {@code webservice}, as requested
 * @param targetFolder the folder for the log file, as given
 * @param tailLines    the number of newest (matching) lines of the excerpt; absent: the default
 * @param contains     plain text the excerpt lines must contain, if given
 * @param maxChars     the most characters of the excerpt lines; absent: the default
 * @param mcpClient    the MCP client's {@code name/version}, if sent
 */
public record ServerLogRequest(String target, String log, String targetFolder,
    OptionalInt tailLines, Optional<String> contains, OptionalInt maxChars,
    Optional<String> mcpClient) {

    public ServerLogRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(targetFolder, "targetFolder");
        Objects.requireNonNull(tailLines, "tailLines");
        Objects.requireNonNull(contains, "contains");
        Objects.requireNonNull(maxChars, "maxChars");
        Objects.requireNonNull(mcpClient, "mcpClient");
    }

    @Override
    public String toString() {
        return "ServerLogRequest[target=" + target + ", log=" + log + ", targetFolder="
            + targetFolder + ", tailLines=" + tailLines + ", containsGiven=" + contains.isPresent()
            + ", maxChars=" + maxChars + ", mcpClient=" + mcpClient + "]";
    }
}
