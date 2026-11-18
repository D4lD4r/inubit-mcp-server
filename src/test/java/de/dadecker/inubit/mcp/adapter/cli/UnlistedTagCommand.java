package de.dadecker.inubit.mcp.adapter.cli;

import java.util.List;

/**
 * The StartCLI tag form of the live check of feature 022 T055 (research "Live checks" L-4, W16)
 * that {@link CliCommand} does not build: a group-scoped {@code tag --tagRemove} with
 * {@code --tagUser}. {@link CliCommand#tagRemoval} sends no {@code --tagUser} (research D-18,
 * "Owner"); T055 checks whether INUBIT accepts it. Test code only; built through the
 * package-private {@link CliCommand#unlisted}, so the live test runs it on the production StartCLI
 * path ({@link CliRunner#run}) without any tool being able to send it.
 */
public final class UnlistedTagCommand {

    private UnlistedTagCommand() {
    }

    /**
     * {@code tag --tagRemove '<tag>' --tagWorkflowGroup '<group>' --tagWorkflowType '<type>'
     * --tagUser '<owner>'}; every value must match {@link CliCommand#VALUE}, as for the production
     * tag forms ({@code INVALID_INPUT} otherwise, the value not echoed).
     */
    public static CliCommand removeWithOwner(String tag, String group, String type,
        String owner) {
        return CliCommand.unlisted(List.of("tag", "--tagRemove", quoted("--tagRemove", tag),
            "--tagWorkflowGroup", quoted("--tagWorkflowGroup", group),
            "--tagWorkflowType", quoted("--tagWorkflowType", type),
            "--tagUser", quoted("--tagUser", owner)));
    }

    private static String quoted(String option, String value) {
        if (value == null || !CliCommand.VALUE.matcher(value).matches()) {
            throw CliCommand.invalid("The value for " + option + " cannot be passed to StartCLI"
                + " safely", "It must match " + CliCommand.VALUE.pattern());
        }
        return "'" + value + "'";
    }
}
