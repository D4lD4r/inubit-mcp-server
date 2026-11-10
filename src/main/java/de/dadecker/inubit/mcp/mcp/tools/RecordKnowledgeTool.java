package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.knowledge.KnowledgeService;
import de.dadecker.inubit.mcp.application.knowledge.Record;
import de.dadecker.inubit.mcp.application.knowledge.Recorded;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code record_knowledge} (feature 019, contracts/mcp-tools-delta.md, FR-008): creates a note,
 * appends a section to one or replaces its content through {@link KnowledgeService#record}. The
 * input schema holds structural rules only (research D-13); every other check is the service's,
 * so it is audited. Never contacts INUBIT, so closed-world.
 */
public final class RecordKnowledgeTool implements ToolHandler {

    static final String DESCRIPTION = "Write a note to this profile's local knowledge on this"
        + " computer: create a new note, append a section to a note, or replace a note's content"
        + " (replace needs the versionToken from read_knowledge). Only after the operator agreed"
        + " in this conversation to the proposed name, title, topics and content, or asked for"
        + " the note - propose first, never record on your own. Never write passwords, keys,"
        + " tokens or other secrets: text that contains one is refused. Notes are never deleted;"
        + " the previous version is kept in the history. Does not contact INUBIT.";

    /** The output schema's shape: the mode as the tool names it, the last change as ISO text. */
    record Result(String name, String mode, String lastChange, String versionToken,
        long sizeBefore, long sizeAfter, int historyVersions, String auditId,
        List<String> warnings) {

        static Result of(Recorded recorded) {
            return new Result(recorded.name(), recorded.mode().value(),
                recorded.lastChange().toString(), recorded.versionToken(), recorded.sizeBefore(),
                recorded.sizeAfter(), recorded.historyVersions(), recorded.auditId(),
                recorded.warnings());
        }
    }

    private final KnowledgeService service;

    public RecordKnowledgeTool(KnowledgeService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String name() {
        return "record_knowledge";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.localOnlyWrite("Record a note in the local knowledge");
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        return handle(arguments, CallContext.NONE);
    }

    /** The arguments are validated by the SDK against {@code record_knowledge.input.json}. */
    @Override
    public Object handle(Map<String, Object> arguments, CallContext context) {
        ToolArguments args = new ToolArguments(arguments);
        Optional<List<String>> topics = arguments.containsKey("topics")
            ? Optional.of(args.strings("topics")) : Optional.empty();
        Record.Mode mode = Record.Mode.valueOf(args.string("mode").toUpperCase(Locale.ROOT));
        return Result.of(service.record(new Record(args.optionalString("name"),
            args.optionalString("ref"), mode, args.optionalString("title"), topics,
            args.string("content"), args.optionalString("versionToken"), context.client())));
    }
}
