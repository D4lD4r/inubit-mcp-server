package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol.Action;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol.Entry;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol.Kind;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The import protocol of StartCLI 8.1 ({@code --returnProtocol}, research D-8, spike §1;
 * recorded in {@code fixtures/v8_1/cli/import_*}): a fixed-width table with the columns
 * {@code TYPE}, {@code DESCRIPTION}, {@code DIAGRAM/MODULE} and {@code GROUP/USER} (the header
 * gives the column starts, so names with spaces are kept), then {@code Total: <n>}. Lines end in
 * {@code \r\n}; the preamble before the header is ignored.
 */
public final class ImportProtocolParser {

    private static final Pattern DESCRIPTION =
        Pattern.compile("^(Module|Diagram) \\[(.+)\\] was (created|modified)\\.$");
    private static final Pattern TOTAL = Pattern.compile("^Total: (\\d+)$");

    private ImportProtocolParser() {
    }

    /**
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} without a protocol table, without
     *     {@code Total} or if {@code Total} differs from the number of rows
     */
    public static ImportProtocol parse(String stdout) {
        List<String> lines = stdout.lines().map(line -> line.replace("\r", "")).toList();
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("TYPE") && line.contains("DESCRIPTION")
                && line.contains("DIAGRAM/MODULE") && line.contains("GROUP/USER")) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            throw unexpected("StartCLI printed no import protocol");
        }
        String columns = lines.get(header);
        int description = columns.indexOf("DESCRIPTION");
        int diagram = columns.indexOf("DIAGRAM/MODULE");
        int user = columns.indexOf("GROUP/USER");
        List<Entry> entries = new ArrayList<>();
        Integer total = null;
        for (String line : lines.subList(header + 1, lines.size())) {
            Matcher totalLine = TOTAL.matcher(line.strip());
            if (totalLine.matches()) {
                total = Integer.parseInt(totalLine.group(1));
                break;
            }
            if (line.isBlank()) {
                continue;
            }
            entries.add(entry(column(line, 0, description), column(line, description, diagram),
                column(line, diagram, user), column(line, user, line.length())));
        }
        if (total == null) {
            throw unexpected("The import protocol has no Total line");
        }
        if (total != entries.size()) {
            throw unexpected("The import protocol lists " + entries.size() + " rows but Total: "
                + total);
        }
        return new ImportProtocol(entries, total);
    }

    private static Entry entry(String type, String description, String diagram, String user) {
        Matcher matcher = DESCRIPTION.matcher(description);
        if (!matcher.matches()) {
            return new Entry(type, description, diagram, user, Optional.empty(),
                Optional.empty(), Optional.empty());
        }
        return new Entry(type, description, diagram, user, Optional.of(
            matcher.group(1).equals("Module") ? Kind.MODULE : Kind.WORKFLOW),
            Optional.of(matcher.group(2)), Optional.of(matcher.group(3).equals("created")
                ? Action.CREATED : Action.MODIFIED));
    }

    private static String column(String line, int from, int to) {
        if (from >= line.length()) {
            return "";
        }
        return line.substring(from, Math.min(Math.max(to, from), line.length())).strip();
    }

    private static ToolErrorException unexpected(String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE, message,
            "StartCLI's import output differs from the recorded INUBIT 8.1 format",
            "Check the INUBIT client version (cliHome) and the INUBIT system log"));
    }
}
