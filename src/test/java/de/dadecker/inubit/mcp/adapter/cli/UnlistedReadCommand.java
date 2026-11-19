package de.dadecker.inubit.mcp.adapter.cli;

import java.util.List;

/**
 * The StartCLI reads of the live recording of feature 022 T054 (research "Live checks" L-2,
 * L-3) that {@link CliCommand} deliberately does not allow-list: the trace and debug state
 * without option and their {@code --help}. Test code only; built through the package-private
 * {@link CliCommand#unlisted} so that the live test runs them on the production StartCLI path
 * ({@link CliRunner#run}) without any tool being able to send them.
 */
public enum UnlistedReadCommand {
    TRACE("trace"),
    TRACE_HELP("trace", "--help"),
    DEBUG("debug"),
    DEBUG_HELP("debug", "--help");

    private final List<String> tokens;

    UnlistedReadCommand(String... tokens) {
        this.tokens = List.of(tokens);
    }

    /** The command for {@link CliRunner#run}. */
    public CliCommand command() {
        return CliCommand.unlisted(tokens);
    }
}
