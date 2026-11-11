package de.dadecker.inubit.mcp.domain.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of {@code download_server_log} (feature 021; shape per contracts/mcp-tools-delta.md):
 * the server and log, the INUBIT node name ({@code webservice} only), the absolute path of the
 * saved file, its size, when it was downloaded (UTC, second precision, the file name), and the
 * excerpt; an empty {@code excerpt} means it was withheld (D-11).
 */
public record ServerLogResult(NodeId node, ServerLogKind log, Optional<String> inubitNode,
    Path file, long sizeBytes, Instant downloadedAt, Optional<LogExcerpt> excerpt,
    List<String> hints, List<String> warnings) {

    public ServerLogResult {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(inubitNode, "inubitNode");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(downloadedAt, "downloadedAt");
        Objects.requireNonNull(excerpt, "excerpt");
        hints = List.copyOf(hints);
        warnings = List.copyOf(warnings);
    }
}
