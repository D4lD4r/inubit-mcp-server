package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.nodetools.IoLogRequest;
import de.dadecker.inubit.mcp.domain.model.BinarySignature;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.node.IoDirection;
import de.dadecker.inubit.mcp.domain.model.node.IoLogEntry;
import de.dadecker.inubit.mcp.domain.model.node.IoLogResult;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * {@code read_io_log} (feature 024, contracts/mcp-tools-delta.md, research D-25): lists the logged
 * input and output messages of one process run, or returns one of them masked and bounded, through
 * an unsupported, undocumented INUBIT interface ({@code IoLogService}). Offered only for groups
 * with {@code unsupported.ioLogRead}; the service repeats every input check.
 */
public final class ReadIoLogTool implements ToolHandler {

    static final String DESCRIPTION = "Lists the logged input and output messages of the System"
        + " Connector and Message Logger Utility modules of one process run on one INUBIT {node},"
        + " as the Workbench's input/output logging view shows them, or returns one of them. Call"
        + " it with workflow and processId (from query_logs) to get the list; call it again with"
        + " message set to a listed ref to get that message's text, masked and cut at maxChars,"
        + " with its full size; binary messages only by size and type. Input/output logging must"
        + " be switched on for the process and its modules in INUBIT. Messages can contain"
        + " business and personal data that masking does not cover. UNSUPPORTED: it uses an"
        + " undocumented internal INUBIT interface without vendor support or stability guarantee,"
        + " for diagnosis only; it may stop working after any INUBIT update; INUBIT requires a"
        + " right of the configured user for the listing (FORBIDDEN without it); available only"
        + " for groups with unsupported.ioLogRead. Changes nothing on INUBIT.";

    private final Function<IoLogRequest, IoLogResult> read;

    /** @param read the listing and message read of the profile ({@code IoLogService::read}) */
    public ReadIoLogTool(Function<IoLogRequest, IoLogResult> read) {
        this.read = Objects.requireNonNull(read, "read");
    }

    @Override
    public String name() {
        return "read_io_log";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("Read INUBIT module input/output logging", true);
    }

    /** The arguments are validated by the SDK against {@code read_io_log.input.json}. */
    @Override
    public Object handle(Map<String, Object> arguments) {
        ToolArguments args = new ToolArguments(arguments);
        return Result.of(read.apply(new IoLogRequest(args.string("target"),
            args.string("workflow"), args.string("processId"), args.optionalString("message"),
            args.optionalString("module"), args.optionalInteger("maxChars")
                .map(OptionalInt::of).orElse(OptionalInt.empty()))));
    }

    /** The JSON shape of contracts/mcp-tools-delta.md "Output"; a message's hint is in hints. */
    record Result(NodeId node, String inubitNode, String workflow, String processId, int total,
        int matching, List<Entry> entries, boolean truncated, Optional<Message> message,
        List<String> hints, List<String> warnings) {

        static Result of(IoLogResult result) {
            return new Result(result.node(), result.answeringNode(), result.workflow(),
                result.processId(), result.total(), result.matching(),
                result.entries().stream().map(Entry::of).toList(), result.truncated(),
                result.message().map(Message::of), result.hints(), result.warnings());
        }
    }

    /** A listed entry without its raw text; {@code moduleId} absent for a raw module part. */
    record Entry(String ref, String moduleName, Optional<String> moduleId, IoDirection direction,
        int index) {

        static Entry of(IoLogEntry entry) {
            return new Entry(entry.ref().value(), entry.moduleName(), entry.moduleId(),
                entry.direction(), entry.index());
        }
    }

    /** The read message: its entry, kind and size, and the masked text of a text message. */
    record Message(String ref, String moduleName, Optional<String> moduleId,
        IoDirection direction, int index, String kind, Optional<String> charset,
        Optional<BinarySignature> detected, long sizeBytes, Optional<Integer> textChars,
        Optional<String> text, Optional<Boolean> cut) {

        static Message of(IoLogResult.Message message) {
            Entry entry = Entry.of(message.entry());
            return new Message(entry.ref(), entry.moduleName(), entry.moduleId(),
                entry.direction(), entry.index(), message.kind(), message.charset(),
                message.detected(), message.sizeBytes(), message.textChars(), message.text(),
                message.cut());
        }
    }
}
