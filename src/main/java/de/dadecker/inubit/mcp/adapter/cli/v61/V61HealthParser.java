package de.dadecker.inubit.mcp.adapter.cli.v61;

import static de.dadecker.inubit.mcp.adapter.cli.v61.V61SessionParser.unexpected;

import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand.Listing;
import de.dadecker.inubit.mcp.domain.model.MemoryFigures;
import de.dadecker.inubit.mcp.domain.model.ProcessCounts;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The health session of StartCLI 6.1 (research D-5, D-6), as reconstructed in
 * {@code fixtures/v6_1/cli/README.md} until recorded:
 *
 * <ul>
 *   <li>{@code uname}: {@code INUBIT Process Engine <version>};
 *   <li>{@code uptime}: the alive check, {@code Process Engine is alive (up …)} or
 *       {@code Process Engine is not alive};
 *   <li>{@code maintenance}: {@code Maintenance mode: on|off};
 *   <li>{@code free -m}: {@code Used memory: <n> MB}, {@code Free memory: <n> MB},
 *       {@code Max memory: <n> MB}, in this order;
 *   <li>{@code ps -c <state>}: {@code Total: <n>}.
 * </ul>
 *
 * <p>The labels exist in English and German ({@link #LANGUAGES}); each block may use either.
 * Anything else is {@code UNEXPECTED_RESPONSE}, never an empty result (FR-017).
 */
public final class V61HealthParser {

    /** The commands of the health session, in the order their blocks are read. */
    public static final List<CliSessionCommand> COMMANDS = List.of(CliSessionCommand.UNAME,
        CliSessionCommand.UPTIME, CliSessionCommand.MAINTENANCE, CliSessionCommand.FREE_MEMORY,
        CliSessionCommand.psCount(Listing.ERROR), CliSessionCommand.psCount(Listing.WAITING),
        CliSessionCommand.psCount(Listing.QUEUED), CliSessionCommand.psCount(Listing.ALL));

    private static final Pattern VERSION =
        Pattern.compile("^INUBIT Process Engine (\\d+(?:\\.\\d+)+)$");

    /** The texts StartCLI prints in one language. */
    private record Labels(String alive, String notAlive, String maintenance, String on,
        String off, String usedMemory, String freeMemory, String maxMemory, String total) {
    }

    /** English and German labels (reconstructed; the recordings adjust them, T037+). */
    private static final List<Labels> LANGUAGES = List.of(
        new Labels("Process Engine is alive", "Process Engine is not alive", "Maintenance mode",
            "on", "off", "Used memory", "Free memory", "Max memory", "Total"),
        new Labels("Process Engine ist aktiv", "Process Engine ist nicht aktiv",
            "Wartungsmodus", "ein", "aus", "Belegter Speicher", "Freier Speicher",
            "Maximaler Speicher", "Gesamt"));

    /**
     * What the health session read.
     *
     * @param version the version {@code uname} reported
     * @param alive   the alive check ({@code uptime}) succeeded
     */
    public record Health(String version, boolean alive, boolean maintenance,
        MemoryFigures memory, ProcessCounts counts) {
    }

    private V61HealthParser() {
    }

    /**
     * @param stdout the output of a session that sent {@link #COMMANDS}
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} if a block is missing, has more or
     *     fewer lines than expected, or carries an unknown label or a non-numeric value
     */
    public static Health parse(String stdout) {
        Iterator<List<String>> blocks = V61SessionParser.blocks(stdout, COMMANDS).iterator();
        // in the order of COMMANDS
        String version = version(single(blocks.next(), "uname"));
        boolean alive = alive(single(blocks.next(), "uptime"));
        boolean maintenance = maintenance(single(blocks.next(), "maintenance"));
        MemoryFigures memory = memory(blocks.next());
        long error = count(single(blocks.next(), "ps -c -e"));
        long waiting = count(single(blocks.next(), "ps -c -w"));
        long queued = count(single(blocks.next(), "ps -c -q"));
        long all = count(single(blocks.next(), "ps -c -a"));
        return new Health(version, alive, maintenance, memory, new ProcessCounts(error,
            processing(all, error, waiting, queued), waiting, queued));
    }

    /**
     * The Processing count. Reconstruction assumption, to be confirmed by the recordings
     * (T037+): {@code ps -c -a} counts every instance and the other states are Error, Waiting
     * and Queued, so Processing = all − Error − Waiting − Queued. A negative result means the
     * assumption does not hold.
     */
    private static long processing(long all, long error, long waiting, long queued) {
        long processing = all - error - waiting - queued;
        if (processing < 0) {
            throw unexpected("StartCLI counted " + all + " instances in all states but "
                + (error + waiting + queued) + " in Error, Waiting and Queued");
        }
        return processing;
    }

    private static String single(List<String> block, String command) {
        if (block.size() != 1) {
            throw unexpected("StartCLI answered " + command + " with " + block.size()
                + " lines instead of one");
        }
        return block.getFirst().strip();
    }

    private static String version(String line) {
        Matcher matcher = VERSION.matcher(line);
        if (!matcher.matches()) {
            throw unexpected("StartCLI answered uname in an unknown format");
        }
        return matcher.group(1);
    }

    private static boolean alive(String line) {
        for (Labels labels : LANGUAGES) {
            if (line.equals(labels.notAlive())) {
                return false;
            }
            if (line.equals(labels.alive()) || line.startsWith(labels.alive() + " (")) {
                return true;
            }
        }
        throw unexpected("StartCLI answered uptime in an unknown format");
    }

    private static boolean maintenance(String line) {
        for (Labels labels : LANGUAGES) {
            if (line.equals(labels.maintenance() + ": " + labels.on())) {
                return true;
            }
            if (line.equals(labels.maintenance() + ": " + labels.off())) {
                return false;
            }
        }
        throw unexpected("StartCLI answered maintenance in an unknown format");
    }

    private static MemoryFigures memory(List<String> block) {
        if (block.size() == 3) {
            for (Labels labels : LANGUAGES) {
                OptionalLong used = number(block.get(0), labels.usedMemory(), " MB");
                OptionalLong free = number(block.get(1), labels.freeMemory(), " MB");
                OptionalLong max = number(block.get(2), labels.maxMemory(), " MB");
                if (used.isPresent() && free.isPresent() && max.isPresent()) {
                    return new MemoryFigures(used.getAsLong(), free.getAsLong(),
                        max.getAsLong());
                }
            }
        }
        throw unexpected("StartCLI answered free -m in an unknown format");
    }

    private static long count(String line) {
        for (Labels labels : LANGUAGES) {
            OptionalLong total = number(line, labels.total(), "");
            if (total.isPresent()) {
                return total.getAsLong();
            }
        }
        throw unexpected("StartCLI answered a process count in an unknown format");
    }

    /** {@code <label>: <n><suffix>}; at most 18 digits, so the value fits a long. */
    private static OptionalLong number(String line, String label, String suffix) {
        Matcher matcher = Pattern.compile(Pattern.quote(label) + ": (\\d{1,18})"
            + Pattern.quote(suffix)).matcher(line.strip());
        return matcher.matches() ? OptionalLong.of(Long.parseLong(matcher.group(1)))
            : OptionalLong.empty();
    }
}
