package de.dadecker.inubit.mcp.adapter.cli;

import java.util.List;

/**
 * The StartCLI writes of the live recording of feature 022 T054 (research "Live checks" L-3) that
 * {@link CliCommand} no longer allow-lists: {@code debug on} and {@code debug off}, excluded after
 * T054 found no observable server effect. Test code only; built through the package-private
 * {@link CliCommand#unlisted} so that the live test runs them on the production StartCLI path
 * ({@link CliRunner#run}) without any tool being able to send them.
 */
public enum UnlistedWriteCommand {
    DEBUG_ON("debug", "on"),
    DEBUG_OFF("debug", "off");

    private final List<String> tokens;

    UnlistedWriteCommand(String... tokens) {
        this.tokens = List.of(tokens);
    }

    /** The command for {@link CliRunner#run}. */
    public CliCommand command() {
        return CliCommand.unlisted(tokens);
    }
}
