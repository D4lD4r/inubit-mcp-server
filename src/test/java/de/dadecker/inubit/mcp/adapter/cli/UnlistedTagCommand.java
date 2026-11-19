package de.dadecker.inubit.mcp.adapter.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The StartCLI tag removal forms of the live check of feature 022 T055 (research "Live checks"
 * L-4, D-18) that {@link CliCommand} does not build: tag removal and deletion are excluded after
 * T055 (owner decision IMC-7), so {@code --tagRemove}, {@code --tagDelete} and
 * {@code --tagRepositoryPath} are no production options. {@link #removal} builds the scoped forms
 * the live check and its cleanup send ({@code --tagDelete} in a diagram group with its owner,
 * {@code --tagRemove} in a repository path), {@link #groupRemoval} the group forms 8.1.17 refused
 * in run 2 (W13, W14, W16). Test code only; built through the package-private
 * {@link CliCommand#unlisted}, so the live test runs them on the production StartCLI path
 * ({@link CliRunner#run}) without any tool being able to send them.
 */
public final class UnlistedTagCommand {

    private UnlistedTagCommand() {
    }

    /** The two removals of StartCLI's {@code tag} command. */
    public enum TagRemovalKind {
        /** {@code --tagRemove}: "Removes the tag only from repository". */
        REMOVE("--tagRemove"),
        /** {@code --tagDelete}: "Removes the tag from diagram, modules and repository". */
        DELETE("--tagDelete");

        private final String option;

        TagRemovalKind(String option) {
            this.option = option;
        }
    }

    /** The scope of a removal: one diagram group of its owner, or one repository path. */
    public sealed interface TagScope {

        /** {@code --tagWorkflowGroup '<group>' --tagWorkflowType '<type>' --tagUser '<owner>'}. */
        record DiagramGroup(String group, String type, String owner) implements TagScope {
        }

        /** {@code --tagRepositoryPath '<path>'}, a {@link CliCommand#REPOSITORY_PATH}. */
        record RepositoryPath(String path) implements TagScope {
        }
    }

    /**
     * {@code tag --tagRemove|--tagDelete '<tag>'} limited to {@code scope}; {@code --tagRemove}
     * takes only a repository path (8.1.17: "tagRepositoryPath is needed", T055 run 2), and a
     * repository path takes no {@code --tagUser}. Every value must be one StartCLI can carry
     * ({@code INVALID_INPUT} otherwise, the value not echoed).
     */
    public static CliCommand removal(TagRemovalKind kind, String tag, TagScope scope) {
        if (kind == TagRemovalKind.REMOVE && scope instanceof TagScope.DiagramGroup) {
            throw CliCommand.invalid("--tagRemove takes only a repository path, not a diagram"
                + " group", "INUBIT 8.1.17 answers it in a diagram group with 'tagRepositoryPath"
                + " is needed' (research D-18, T055)");
        }
        List<String> tokens = new ArrayList<>(List.of("tag", kind.option,
            quoted(kind.option, tag)));
        switch (scope) {
            case TagScope.DiagramGroup group -> tokens.addAll(List.of(
                "--tagWorkflowGroup", quoted("--tagWorkflowGroup", group.group()),
                "--tagWorkflowType", quoted("--tagWorkflowType", group.type()),
                "--tagUser", quoted("--tagUser", group.owner())));
            case TagScope.RepositoryPath path -> {
                if (!CliCommand.repositoryPathPassable(path.path())) {
                    throw CliCommand.invalid("The repository path for --tagRepositoryPath cannot"
                        + " be passed to StartCLI safely", "It must match "
                        + CliCommand.REPOSITORY_PATH.pattern() + " and contain no '.' or '..'"
                        + " segment");
                }
                tokens.addAll(List.of("--tagRepositoryPath", "'" + path.path() + "'"));
            }
        }
        return CliCommand.unlisted(tokens);
    }

    /**
     * {@code tag --tagRemove|--tagDelete '<tag>' --tagWorkflowGroup '<group>' --tagWorkflowType
     * '<type>'}, with {@code --tagUser '<owner>'} if {@code owner} is present; every value must
     * match {@link CliCommand#VALUE}, as for the production tag forms ({@code INVALID_INPUT}
     * otherwise, the value not echoed).
     */
    public static CliCommand groupRemoval(TagRemovalKind kind, String tag, String group,
        String type, Optional<String> owner) {
        List<String> tokens = new ArrayList<>(List.of("tag", kind.option, quoted(kind.option, tag),
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
