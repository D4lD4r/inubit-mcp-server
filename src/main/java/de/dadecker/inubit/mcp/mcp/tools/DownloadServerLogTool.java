package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.ServerLogRequest;
import de.dadecker.inubit.mcp.domain.model.ContentKind;
import de.dadecker.inubit.mcp.domain.model.ExcerptLine;
import de.dadecker.inubit.mcp.domain.model.LogExcerpt;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ServerLogResult;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * {@code download_server_log} (feature 021, contracts/mcp-tools-delta.md, research D-20):
 * downloads the trace log or the web service log of one server into a local folder and returns a
 * masked excerpt ({@code ServerLogService}). The file holds clear-text content; the service checks
 * the input again, the folder and the version, and audits every call that passed the input schema.
 * Changes nothing on INUBIT, but writes a local file: a local write, so clients ask first (FR-014).
 */
public final class DownloadServerLogTool implements ToolHandler {

    static final String DESCRIPTION = "Downloads the trace log (trace.log) or the web service log"
        + " (webservice.log) of one INUBIT {node} into a local folder you name and returns the"
        + " newest lines, or the lines containing a search text, as a masked excerpt with line"
        + " numbers. Use it when a process or connector failed and find_processes, query_logs or"
        + " read_queue_entry do not show the cause (trace), or to see the SOAP messages a web"
        + " service connector really sent or received (webservice). The file is written IN CLEAR"
        + " TEXT: both logs may contain business data and credentials; only the excerpt is"
        + " masked. trace uses StartCLI's supported trace download. webservice is UNSUPPORTED: it"
        + " uses an undocumented internal INUBIT interface (the Workbench's Cluster Manager log"
        + " download) without vendor support or stability guarantee; it may stop working after"
        + " any INUBIT update; available only for groups with unsupported.serverLogRead. The"
        + " folder must be an absolute path outside the workspace, the server's directories, git"
        + " working trees and network paths; existing files are never replaced. Changes nothing"
        + " on INUBIT.";

    private final Function<ServerLogRequest, ServerLogResult> download;

    /** @param download the download of the profile ({@code ServerLogService::download}) */
    public DownloadServerLogTool(Function<ServerLogRequest, ServerLogResult> download) {
        this.download = Objects.requireNonNull(download, "download");
    }

    @Override
    public String name() {
        return "download_server_log";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    /** Local write: not read-only, not destructive, not idempotent, open world (D-20). */
    @Override
    public ToolHints annotations() {
        return ToolHints.localWrite("Download an INUBIT server log");
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        return handle(arguments, CallContext.NONE);
    }

    /** The arguments are validated by the SDK against {@code download_server_log.input.json}. */
    @Override
    public Object handle(Map<String, Object> arguments, CallContext context) {
        ToolArguments args = new ToolArguments(arguments);
        ServerLogResult result = download.apply(new ServerLogRequest(args.string("target"),
            args.string("log"), args.string("targetFolder"), optionalInt(args, "tailLines"),
            args.optionalString("contains"), optionalInt(args, "maxChars"), context.client()));
        return Result.of(result);
    }

    private static OptionalInt optionalInt(ToolArguments args, String name) {
        return args.optionalInteger(name).map(OptionalInt::of).orElseGet(OptionalInt::empty);
    }

    /**
     * The JSON shape of contracts/mcp-tools-delta.md "Output": the excerpt's own hints follow the
     * result's hints in the one top-level {@code hints}.
     */
    record Result(NodeId node, String log, Optional<String> inubitNode, String file,
        long sizeBytes, Instant downloadedAt, Excerpt excerpt, List<String> hints,
        List<String> warnings) {

        static Result of(ServerLogResult result) {
            List<String> hints = new ArrayList<>(result.hints());
            result.excerpt().ifPresent(excerpt -> hints.addAll(excerpt.hints()));
            return new Result(result.node(), result.log().wireName(), result.inubitNode(),
                result.file().toString(), result.sizeBytes(), result.downloadedAt(),
                result.excerpt().map(Excerpt::of).orElse(Excerpt.WITHHELD), hints,
                result.warnings());
        }
    }

    /**
     * {@code {"kind": "withheld"}} and {@code {"kind": "empty"}} carry only their kind,
     * {@code binary} also {@code detected}; {@code text} carries the window, the selection and the
     * lines, {@code charset} only if a window was decoded. {@code truncated} is {@code false} or
     * the {@link de.dadecker.inubit.mcp.domain.model.Truncation}.
     */
    record Excerpt(String kind, Optional<String> detected, Optional<String> charset,
        Optional<LogExcerpt.Window> window, Optional<String> selection,
        Optional<Long> matchingLines, Optional<Integer> returnedLines, Optional<List<Line>> lines,
        Optional<Object> truncated) {

        static final Excerpt WITHHELD = kindOnly("withheld");

        static Excerpt of(LogExcerpt excerpt) {
            String kind = excerpt.kind().name().toLowerCase(Locale.ROOT);
            if (excerpt.kind() == ContentKind.EMPTY) {
                return kindOnly(kind);
            }
            if (excerpt.kind() == ContentKind.BINARY) {
                return new Excerpt(kind, excerpt.detected().map(Enum::name), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty());
            }
            List<Line> lines = excerpt.lines().stream().map(Line::of).toList();
            return new Excerpt(kind, Optional.empty(), excerpt.charset().map(Charset::name),
                Optional.of(excerpt.window()),
                Optional.of(excerpt.selection().name().toLowerCase(Locale.ROOT)),
                excerpt.matchingLines().isPresent()
                    ? Optional.of(excerpt.matchingLines().getAsLong()) : Optional.empty(),
                Optional.of(lines.size()), Optional.of(lines),
                Optional.of(excerpt.truncated().<Object>map(t -> t).orElse(false)));
        }

        private static Excerpt kindOnly(String kind) {
            return new Excerpt(kind, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        }
    }

    /** An excerpt line; {@code cut} only for a line cut at {@code maxChars}. */
    record Line(long number, String text, Optional<Boolean> cut) {

        static Line of(ExcerptLine line) {
            return new Line(line.number(), line.text(),
                line.cut() ? Optional.of(true) : Optional.empty());
        }
    }
}
