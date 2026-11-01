package de.dadecker.inubit.mcp.adapter.cli;

import java.util.Objects;

/**
 * One line of an interactive StartCLI session on INUBIT 6.1 (feature 011, research D-5, D-10),
 * from a fixed allowlist (Constitution I):
 *
 * <ul>
 *   <li>{@link #UNAME}, {@link #UPTIME}, {@link #MAINTENANCE}, {@link #FREE_MEMORY}
 *       ({@code free -m}) and {@link #EXIT};
 *   <li>a process listing {@code ps <-a|-e|-w|-q> -l <rows>} with 1 to {@link #MAX_ROWS} rows
 *       ({@link #psList});
 *   <li>a process count {@code ps -c <-a|-e|-w|-q>} ({@link #psCount}).
 * </ul>
 *
 * <p>No command is built from free text: the texts are constants or assembled from a
 * {@link Listing} and a bounded number.
 */
public final class CliSessionCommand {

    /** The largest row limit of a listing (FR-010). */
    public static final int MAX_ROWS = 10_000;

    public static final CliSessionCommand UNAME = new CliSessionCommand("uname");
    public static final CliSessionCommand UPTIME = new CliSessionCommand("uptime");
    public static final CliSessionCommand MAINTENANCE = new CliSessionCommand("maintenance");
    public static final CliSessionCommand FREE_MEMORY = new CliSessionCommand("free -m");
    public static final CliSessionCommand EXIT = new CliSessionCommand("exit");

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

    /** The line written to StartCLI's stdin (without line break). */
    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }
}
