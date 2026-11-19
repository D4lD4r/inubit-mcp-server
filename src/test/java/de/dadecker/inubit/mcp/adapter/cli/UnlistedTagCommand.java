package de.dadecker.inubit.mcp.adapter.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The StartCLI tag forms of the first step list of the live check of feature 022 T055 (research
 * "Live checks" L-4, W13, W14, W16) that {@link CliCommand#tagRemoval} no longer builds since
 * 8.1.17 refused them (T055 run 2, research D-18): {@code --tagRemove} in a diagram group, with or
 * without {@code --tagUser}, and {@code --tagDelete} in a diagram group without it. Test code
 * only; built through the package-private {@link CliCommand#unlisted}, so the live test runs it on
 * the production StartCLI path ({@link CliRunner#run}) without any tool being able to send it.
 */
public final class UnlistedTagCommand {

    private UnlistedTagCommand() {
    }

    /**
     * {@code tag --tagRemove|--tagDelete '<tag>' --tagWorkflowGroup '<group>' --tagWorkflowType
     * '<type>'}, with {@code --tagUser '<owner>'} if {@code owner} is present; every value must
     * match {@link CliCommand#VALUE}, as for the production tag forms ({@code INVALID_INPUT}
     * otherwise, the value not echoed).
     */
    public static CliCommand groupRemoval(CliCommand.TagRemovalKind kind, String tag, String group,
        String type, Optional<String> owner) {
        String option = switch (kind) {
            case REMOVE -> "--tagRemove";
            case DELETE -> "--tagDelete";
        };
        List<String> tokens = new ArrayList<>(List.of("tag", option, quoted(option, tag),
            "--tagWorkflowGroup", quoted("--tagWorkflowGroup", group),
            "--tagWorkflowType", quoted("--tagWorkflowType", type)));
        owner.ifPresent(user -> tokens.addAll(List.of("--tagUser", quoted("--tagUser", user))));
        return CliCommand.unlisted(tokens);
    }

    private static String quoted(String option, String value) {
        if (value == null || !CliCommand.VALUE.matcher(value).matches()) {
            throw CliCommand.invalid("The value for " + option + " cannot be passed to StartCLI"
                + " safely", "It must match " + CliCommand.VALUE.pattern());
        }
        return "'" + value + "'";
    }
}
