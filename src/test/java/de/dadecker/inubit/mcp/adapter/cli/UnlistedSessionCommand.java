package de.dadecker.inubit.mcp.adapter.cli;

import java.util.Map;
import java.util.Set;

/**
 * The session lines of the T054 debug clarification (feature 022, live check L-3): {@code debug},
 * {@code debug on} and {@code debug off} as lines of one interactive StartCLI session, which
 * {@link CliSessionCommand#SESSION_OPTIONS} deliberately does not admit. Test code only; built
 * through the package-private {@link CliSessionCommand#command(String, Map)} against an
 * allowlist of its own, so that the live test runs them on the production session path
 * ({@link CliRunner#runSession}) without any tool being able to send them.
 */
public enum UnlistedSessionCommand {
    DEBUG(null),
    DEBUG_ON("on"),
    DEBUG_OFF("off");

    private static final Map<String, Set<String>> ALLOWLIST =
        Map.of("debug", Set.of("on", "off"));

    private final String option;

    UnlistedSessionCommand(String option) {
        this.option = option;
    }

    /** The line for {@link CliRunner#runSession}. */
    public CliSessionCommand command() {
        CliSessionCommand.Builder builder = CliSessionCommand.command("debug", ALLOWLIST);
        return (option == null ? builder : builder.flag(option)).build();
    }
}
