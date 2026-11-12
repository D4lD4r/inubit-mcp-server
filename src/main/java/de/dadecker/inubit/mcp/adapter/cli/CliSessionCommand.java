package de.dadecker.inubit.mcp.adapter.cli;

import static de.dadecker.inubit.mcp.adapter.cli.CliCommand.invalid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One line of an interactive StartCLI session (feature 012, research D-5, D-10; version-neutral
 * since feature 022, research D-7), from a fixed allowlist (Constitution I):
 *
 * <ul>
 *   <li>{@link #UNAME}, {@link #UPTIME}, {@link #MAINTENANCE}, {@link #FREE_MEMORY}
 *       ({@code free -m}) and {@link #EXIT};
 *   <li>a process listing {@code ps <-a|-e|-w|-q> -l <rows>} with 1 to {@link #MAX_ROWS} rows
 *       ({@link #psList});
 *   <li>a process count {@code ps -c <-a|-e|-w|-q>} ({@link #psCount});
 *   <li>INUBIT 8.1/9.0 (feature 022, research D-7): a command of {@link #SESSION_OPTIONS}
 *       built by {@link #command}, with the value rules of {@link CliCommand}: a value matches
 *       {@link CliCommand#VALUE} and a repository path {@link CliCommand#REPOSITORY_PATH}
 *       without {@code .}/{@code ..} segment, each wrapped in single quotes.
 * </ul>
 *
 * <p>No command is built from free text: the texts are constants, assembled from a
 * {@link Listing} and a bounded number, or from allow-listed names and validated values.
 * Violations throw {@code INVALID_INPUT}; the message never echoes the value.
 */
public final class CliSessionCommand {

    /** The largest row limit of a listing (FR-010). */
    public static final int MAX_ROWS = 10_000;

    public static final CliSessionCommand UNAME = new CliSessionCommand("uname");
    public static final CliSessionCommand UPTIME = new CliSessionCommand("uptime");
    public static final CliSessionCommand MAINTENANCE = new CliSessionCommand("maintenance");
    public static final CliSessionCommand FREE_MEMORY = new CliSessionCommand("free -m");
    public static final CliSessionCommand EXIT = new CliSessionCommand("exit");

    /**
     * The session commands {@link #command} admits and the options each accepts; empty until the
     * stories add the 8.1/9.0 commands (feature 022, research D-7).
     */
    static final Map<String, Set<String>> SESSION_OPTIONS = Map.of();

    /** The process states a {@code ps} command selects. */
    public enum Listing {
        ALL("-a"), ERROR("-e"), WAITING("-w"), QUEUED("-q");

        private final String option;

        Listing(String option) {
            this.option = option;
        }
    }

    private final String text;

    private CliSessionCommand(String text) {
        this.text = text;
    }

    /** {@code ps <listing> -l <rows>}; {@code rows} must be 1 to {@link #MAX_ROWS}. */
    public static CliSessionCommand psList(Listing listing, int rows) {
        Objects.requireNonNull(listing, "listing");
        if (rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException("row limit must be 1 to " + MAX_ROWS);
        }
        return new CliSessionCommand("ps " + listing.option + " -l " + rows);
    }

    /** {@code ps -c <listing>}: the number of instances in the selected states. */
    public static CliSessionCommand psCount(Listing listing) {
        Objects.requireNonNull(listing, "listing");
        return new CliSessionCommand("ps -c " + listing.option);
    }

    /** Starts a command of {@link #SESSION_OPTIONS}; {@code name} must be allow-listed. */
    public static Builder command(String name) {
        return command(name, SESSION_OPTIONS);
    }

    /** As above, against {@code allowlist} (the builder's rules are tested with their own). */
    static Builder command(String name, Map<String, Set<String>> allowlist) {
        if (name == null || !allowlist.containsKey(name)) {
            throw invalid("The StartCLI session command is not allow-listed",
                allowlist.isEmpty() ? "No session command is supported yet"
                    : "Only " + String.join(", ", allowlist.keySet().stream().sorted().toList())
                        + " are supported");
        }
        return new Builder(name, allowlist.get(name));
    }

    /** The line written to StartCLI's stdin (without line break). */
    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }

    /** Collects the tokens of one session command. */
    public static final class Builder {

        private final String name;
        private final Set<String> options;
        private final List<String> tokens = new ArrayList<>();

        private Builder(String name, Set<String> options) {
            this.name = name;
            this.options = options;
            tokens.add(name);
        }

        /** An allow-listed option or subcommand without value. */
        public Builder flag(String option) {
            tokens.add(option(option));
            return this;
        }

        /** {@code <option> '<value>'}; the value must match {@link CliCommand#VALUE}. */
        public Builder quoted(String option, String value) {
            String checked = option(option);
            if (value == null || !CliCommand.VALUE.matcher(value).matches()) {
                throw invalid("The value for " + checked + " cannot be passed to StartCLI safely",
                    "It must match " + CliCommand.VALUE.pattern()
                        + " (no quotes, at most 200 chars)");
            }
            tokens.add(checked);
            tokens.add("'" + value + "'");
            return this;
        }

        /**
         * {@code <option> '<repository path>'}; the path must match
         * {@link CliCommand#REPOSITORY_PATH} and contain no {@code .} or {@code ..} segment.
         */
        public Builder repositoryPath(String option, String path) {
            String checked = option(option);
            if (!CliCommand.repositoryPathPassable(path)) {
                throw invalid("The repository path for " + checked + " cannot be passed to"
                        + " StartCLI safely",
                    "It must match " + CliCommand.REPOSITORY_PATH.pattern() + " and contain no"
                        + " '.' or '..' segment");
            }
            tokens.add(checked);
            tokens.add("'" + path + "'");
            return this;
        }

        public CliSessionCommand build() {
            return new CliSessionCommand(String.join(" ", tokens));
        }

        private String option(String option) {
            if (option == null || !options.contains(option)) {
                throw invalid("The StartCLI session option is not allow-listed",
                    "Only the documented options of " + name + " can be used");
            }
            return option;
        }
    }
}
