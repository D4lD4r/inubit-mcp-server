package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.QueueEntryService;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * {@code read_queue_entry} (feature 020, contracts/mcp-tools-delta.md): reads the metadata, data
 * stream and workflow variables of one Queue Manager entry in state ERROR through an
 * unsupported, undocumented INUBIT interface ({@link QueueEntryService}). Offered only for groups
 * with {@code unsupported.queueEntryRead}; the service repeats every input check.
 */
public final class ReadQueueEntryTool implements ToolHandler {

    static final String DESCRIPTION = "Reads the content of one INUBIT Queue Manager entry in"
        + " state ERROR: the entry's metadata, the data stream that ended in the queue (text cut"
        + " at maxChars; binary content only as size and kind) and the workflow variables, with"
        + " credentials masked as ${secret:…}. Use it after find_processes or query_logs (logType"
        + " queueLog) showed an ERROR entry whose cause is not in the error text. UNSUPPORTED:"
        + " uses an undocumented internal INUBIT interface (the Workbench's Queue Manager \"View"
        + " file\") without vendor support or stability guarantee; it may stop working or behave"
        + " differently after any INUBIT update; for diagnosis only. Returns business data of"
        + " the entry; credentials under unusual names may not be masked. Available only for"
        + " {groups} with unsupported.queueEntryRead.";

    private final QueueEntryService service;

    public ReadQueueEntryTool(QueueEntryService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String name() {
        return "read_queue_entry";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("Read a Queue Manager entry", true, true);
    }

    /** The arguments are validated by the SDK against {@code read_queue_entry.input.json}. */
    @Override
    public Object handle(Map<String, Object> arguments) {
        ToolArguments args = new ToolArguments(arguments);
        return service.read(args.string("target"), args.string("processId"),
            args.optionalString("fileName"), args.optionalInteger("maxChars")
                .map(OptionalInt::of).orElse(OptionalInt.empty()));
    }
}
