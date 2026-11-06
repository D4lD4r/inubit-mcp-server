package de.dadecker.inubit.mcp.adapter.cli.v61;

import static de.dadecker.inubit.mcp.adapter.cli.v61.V61SessionParser.unexpected;

import de.dadecker.inubit.mcp.adapter.cli.CliDates;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code ps <-a|-e|-w|-q> -l <rows>} listings of a StartCLI 6.1 process session (FR-010,
 * research D-10), as reconstructed in {@code fixtures/v6_1/cli/README.md} until recorded:
 *
 * <ul>
 *   <li>a listing is a header line, one row per instance and a {@code Total: <n>} line;
 *   <li>the header names the columns {@code NODE UID PID PRIO STATE DATE WORKFLOW MODULE} (or
 *       their German names, {@link #LANGUAGES}); each column starts where its header name starts
 *       and ends where the next one starts, {@code MODULE} runs to the end of the line;
 *   <li>rows carry no trailing blanks, so a row without module ends after the workflow name.
 * </ul>
 *
 * <p>Mapping to {@link ProcessInstance}: {@code PID} → {@code processId}, {@code STATE} →
 * {@code rawState} as printed and {@code state} by {@link ProcessState#fromRaw} after a German
 * state name is translated to its English raw name (others → {@code OTHER}), {@code DATE} →
 * {@code since} ({@link CliDates}, Europe/Berlin), {@code WORKFLOW}, {@code MODULE} (the text
 * {@code Name(id)} kept as it is), {@code NODE} → {@code inubitNode} (none for
 * {@link #NOT_ASSIGNED}), {@code UID} → {@code owner}, {@code PRIO} → {@code priority}.
 *
 * <p>An unknown header, a row whose values do not fit the columns, lack a value other than the
 * module, carry a non-numeric {@code PID} or an unknown date, and a missing or unknown total line
 * are {@code UNEXPECTED_RESPONSE}, never an empty result (FR-017).
 */
public final class V61PsParser {

    /** The {@code NODE} of an instance that runs on no INUBIT node. */
    static final String NOT_ASSIGNED = "__NotAssigned__";

    /** The column indexes, in header order. */
    private static final int NODE = 0;
    private static final int UID = 1;
    private static final int PID = 2;
    private static final int PRIO = 3;
    private static final int STATE = 4;
    private static final int DATE = 5;
    private static final int WORKFLOW = 6;
    private static final int MODULE = 7;

    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /**
     * The texts of a listing in one language.
     *
     * @param states German state names → the English raw names {@link ProcessState} knows
     */
    private record Language(List<String> header, String total, Map<String, String> states) {
    }

    /** English and German texts (reconstructed; the recordings adjust them, T037+). */
    private static final List<Language> LANGUAGES = List.of(
        new Language(List.of("NODE", "UID", "PID", "PRIO", "STATE", "DATE", "WORKFLOW",
            "MODULE"), "Total", Map.of()),
        new Language(List.of("KNOTEN", "BENUTZER", "PID", "PRIO", "STATUS", "DATUM",
            "WORKFLOW", "MODUL"), "Gesamt", Map.of("Fehler", "Error", "Aktiv", "Processing",
            "Wartend", "Waiting", "Wiederholt", "Retry", "Eingereiht", "Queued")));

    /** The English column names, for messages. */
    private static final List<String> COLUMNS = LANGUAGES.getFirst().header();

    /**
     * One listing.
     *
     * @param instances the rows, in the order StartCLI printed them
     * @param total     the number of the total line; at least the number of rows (whether it
     *                  counts beyond the row limit is open until recorded, research D-10)
     */
    public record Listing(List<ProcessInstance> instances, long total) {

        public Listing {
            instances = List.copyOf(instances);
        }
    }

    private V61PsParser() {
    }

    /**
     * @param stdout    the output of a session that sent {@code commands}
     * @param commands  the {@code ps} listings of the session ({@link CliSessionCommand#psList})
     * @param node      the configured node the instances belong to
     * @param now       the reference time of {@code timeInState} and {@code hanging}
     * @param threshold the hanging threshold
     * @return one listing per command, in the order of {@code commands}
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} for a session or listing in an
     *     unknown format
     */
    public static List<Listing> parse(String stdout, List<CliSessionCommand> commands,
        NodeId node, Instant now, Duration threshold) {
        return V61SessionParser.blocks(stdout, commands).stream()
            .map(block -> listing(block, node, now, threshold))
            .toList();
    }

    private static Listing listing(List<String> block, NodeId node, Instant now,
        Duration threshold) {
        if (block.size() < 2) {
            throw unexpected("StartCLI answered ps without header and total line");
        }
        String header = block.getFirst();
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        Matcher word = WORD.matcher(header);
        while (word.find()) {
            names.add(word.group());
            starts.add(word.start());
        }
        Language language = LANGUAGES.stream().filter(l -> l.header().equals(names))
            .findFirst()
            .orElseThrow(() -> unexpected("StartCLI answered ps with an unknown header"));

        long total = total(block.getLast(), language);
        List<ProcessInstance> instances = new ArrayList<>();
        for (int row = 1; row < block.size() - 1; row++) {
            instances.add(instance(columns(block.get(row), starts, row), language, node, now,
                threshold, row));
        }
        if (total < instances.size()) {
            throw unexpected("StartCLI listed " + instances.size() + " process rows but a total of "
                + total);
        }
        return new Listing(instances, total);
    }

    /** {@code <label>: <n>}; at most 18 digits, so the value fits a long. */
    private static long total(String line, Language language) {
        Matcher matcher = Pattern.compile(Pattern.quote(language.total()) + ": (\\d{1,18})")
            .matcher(line.strip());
        if (!matcher.matches()) {
            throw unexpected("StartCLI ended a ps listing without a known total line");
        }
        return Long.parseLong(matcher.group(1));
    }

    /** The stripped values of a row; a value that runs into the next column is unexpected. */
    private static List<String> columns(String line, List<Integer> starts, int row) {
        List<String> values = new ArrayList<>();
        for (int column = 0; column < starts.size(); column++) {
            int start = starts.get(column);
            int end = column + 1 < starts.size() ? starts.get(column + 1) : line.length();
            if (start > 0 && start < line.length()
                && !Character.isWhitespace(line.charAt(start - 1))) {
                throw unexpected("StartCLI printed ps row " + row + " with a value that does not "
                    + "fit the " + COLUMNS.get(column - 1) + " column");
            }
            values.add(start < line.length()
                ? line.substring(start, Math.min(end, line.length())).strip() : "");
        }
        return values;
    }

    private static ProcessInstance instance(List<String> values, Language language, NodeId node,
        Instant now, Duration threshold, int row) {
        for (int column = 0; column < MODULE; column++) {
            if (values.get(column).isEmpty()) {
                throw unexpected("StartCLI printed ps row " + row + " without "
                    + COLUMNS.get(column));
            }
        }
        String processId = values.get(PID);
        if (!DIGITS.matcher(processId).matches()) {
            throw unexpected("StartCLI printed ps row " + row + " with a non-numeric PID");
        }
        Instant since = CliDates.parse(values.get(DATE)).orElseThrow(() -> unexpected(
            "StartCLI printed ps row " + row + " with a DATE in an unknown format"));
        String rawState = values.get(STATE);
        ProcessState state =
            ProcessState.fromRaw(language.states().getOrDefault(rawState, rawState));
        String inubitNode = values.get(NODE);
        return new ProcessInstance(node, processId, Optional.empty(), state, rawState, since,
            ProcessInstance.timeInState(since, now),
            ProcessInstance.hanging(state, since, now, threshold),
            Optional.of(values.get(WORKFLOW)),
            Optional.of(values.get(MODULE)).filter(module -> !module.isEmpty()),
            Optional.empty(), Optional.empty(),
            Optional.of(inubitNode).filter(name -> !name.equals(NOT_ASSIGNED)),
            Optional.of(values.get(UID)), Optional.of(values.get(PRIO)));
    }
}
