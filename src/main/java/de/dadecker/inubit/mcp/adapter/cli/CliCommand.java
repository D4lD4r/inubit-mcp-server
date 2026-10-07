package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.config.CliPaths;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The text of StartCLI's {@code --execCommand} argument, assembled only from validated tokens
 * (Constitution I/V, research R-6, R-11).
 *
 * <ul>
 *   <li>Command names come from a fixed allowlist, and each command accepts only its own
 *       options: {@code processErrorStart} and {@code kill} take exactly one process id and no
 *       option, {@code export} takes the export options and no process id; feature 004 adds
 *       {@code import} and {@code tag} with their options (research D-8; never metadata,
 *       {@code --tagDiagram}, {@code --tagRemove} or a repository path for {@code tag}).
 *   <li>Feature 005 (research D-1, D-4): {@code --exportTag} (a {@link #VALUE}) and the
 *       repository paths of {@code --exportRepositoryPath} and {@code --importRepositoryPath}
 *       ({@link #REPOSITORY_PATH}, through {@link Builder#repositoryPath} only). The empty
 *       diagram group list {@code --exportWorkflowGroup ''} (all groups of the owner) is
 *       accepted only together with {@code --exportTag}.
 *   <li>Process ids are 1–19 decimal digits without leading zero ({@code ^[1-9][0-9]{0,18}$}).
 *   <li>Every other value (workflow, type, group, owner) must match
 *       {@link #VALUE} (no leading {@code -}) and is wrapped in single quotes; a value with
 *       {@code '} cannot match. The literal empty argument {@code ''} is available through
 *       {@link Builder#emptyQuoted}.
 *   <li>Export file paths must be absolute, consist of the same characters plus {@code /} and
 *       contain no {@code .}/{@code ..} segment.
 * </ul>
 *
 * <p>Violations throw {@code INVALID_INPUT} before anything is launched; the message names the
 * option but never echoes the value.
 */
public final class CliCommand {

    /** Value rule of research R-11 for anything inserted into {@code --execCommand}. */
    public static final Pattern VALUE =
        Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$");

    /**
     * Rule for repository paths (feature 005, research D-1): {@code /Root} and at least one
     * segment of {@link #VALUE}'s characters; {@code .} and {@code ..} segments are refused
     * separately.
     */
    public static final Pattern REPOSITORY_PATH =
        Pattern.compile("^/Root(/[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199})+$");

    private static final Pattern PROCESS_ID = Pattern.compile("^[1-9][0-9]{0,18}$");

    /** Commands and the options each of them accepts. */
    private static final Map<String, Set<String>> OPTIONS = Map.of(
        "processErrorStart", Set.of(),
        "kill", Set.of(),
        "export", Set.of(
            "--exportWorkflowUser", "--exportWorkflowType", "--exportWorkflowGroup",
            "--includeHistory", "--exportFile",
            "--exportModule", "--exportModuleGroup", "--exportModuleUser",
            // feature 005 (research D-1, D-4)
            "--exportTag", "--exportRepositoryPath"),
        // feature 004 (research D-8): no metadata; feature 005 (D-1): the repository mode
        "import", Set.of("--importFile", "--importWorkflow", "--importWorkflowActive",
            "--importWorkflowInactive", "--importModule", "--importUser", "--returnProtocol",
            "--importRepositoryPath"),
        "tag", Set.of("--tagMove", "--tagWorkflowGroup", "--tagWorkflowType", "--tagUser"));
    /** The options without value ({@link Builder#flag}); every other option takes one. */
    private static final Set<String> FLAGS = Set.of("--includeHistory", "--importWorkflow",
        "--importWorkflowActive", "--importWorkflowInactive", "--importModule",
        "--returnProtocol");
    /** The options that take a repository path ({@link Builder#repositoryPath}) only. */
    private static final Set<String> REPOSITORY_OPTIONS =
        Set.of("--exportRepositoryPath", "--importRepositoryPath");
    /** Commands that take exactly one process id and nothing else. */
    private static final Set<String> PROCESS_COMMANDS = Set.of("processErrorStart", "kill");

    private final List<String> tokens;

    private CliCommand(List<String> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    /** Starts a command; {@code name} must be allow-listed. */
    public static Builder command(String name) {
        if (name == null || !OPTIONS.containsKey(name)) {
            throw invalid("The StartCLI command is not allow-listed",
                "Only " + String.join(", ", OPTIONS.keySet().stream().sorted().toList())
                    + " are supported");
        }
        return new Builder(name);
    }

    /** {@code processErrorStart <pid>}: restart a process instance in ERROR. */
    public static CliCommand processErrorStart(String processId) {
        return command("processErrorStart").processId(processId).build();
    }

    /** {@code kill <pid>}: remove a process instance from the Queue Manager. */
    public static CliCommand kill(String processId) {
        return command("kill").processId(processId).build();
    }

    /** The command name, e.g. {@code export}. */
    public String name() {
        return tokens.get(0);
    }

    /** The value of {@code --execCommand}. */
    public String commandLine() {
        return String.join(" ", tokens);
    }

    /** The command line; it holds validated tokens only and never a credential. */
    @Override
    public String toString() {
        return commandLine();
    }

    /**
     * True if {@code path} is a repository path StartCLI can take ({@link #REPOSITORY_PATH}, no
     * {@code .} or {@code ..} segment).
     */
    public static boolean repositoryPathPassable(String path) {
        if (path == null || !REPOSITORY_PATH.matcher(path).matches()) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static ToolErrorException invalid(String message, String likelyCause) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message, likelyCause,
            "Use a value that matches the documented pattern"));
    }

    /** Collects the tokens of one command. */
    public static final class Builder {

        private final String name;
        private final List<String> tokens = new ArrayList<>();
        private int processIds;
        private boolean emptyGroupList;
        /** A validated value followed {@code --exportTag} ({@link #quoted}). */
        private boolean tagged;

        private Builder(String name) {
            this.name = name;
            tokens.add(name);
        }

        /**
         * An allow-listed option without value, e.g. {@code --includeHistory}; an option that
         * takes a value is refused (stage 1 review #2: {@code flag("--exportTag")} would bypass
         * the tag rule).
         */
        public Builder flag(String option) {
            String checked = plainOption(option);
            if (!FLAGS.contains(checked)) {
                throw invalid("The StartCLI option " + checked + " takes a value",
                    "Only " + String.join(", ", FLAGS.stream().sorted().toList())
                        + " are passed without value");
            }
            tokens.add(checked);
            return this;
        }

        /** {@code <option> '<value>'}; the value must match {@link #VALUE}. */
        public Builder quoted(String option, String value) {
            String checked = valueOption(option);
            if (value == null || !VALUE.matcher(value).matches()) {
                throw invalid("The value for " + checked + " cannot be passed to StartCLI"
                        + " safely",
                    "It must match " + VALUE.pattern() + " (no quotes, at most 200 chars)");
            }
            tokens.add(checked);
            tokens.add("'" + value + "'");
            if (checked.equals("--exportTag")) {
                tagged = true;
            }
            return this;
        }

        /**
         * {@code <option> ''}: the literal empty argument (research R-11). For
         * {@code --exportWorkflowGroup} ("all diagram groups") the command must also carry
         * {@code --exportTag} ({@link #build()}, feature 005); {@code --exportTag} itself never
         * takes it.
         */
        public Builder emptyQuoted(String option) {
            String checked = valueOption(option);
            if (checked.equals("--exportTag")) {
                throw invalid("The tag for --exportTag must not be empty",
                    "An empty tag cannot select a release");
            }
            if (checked.equals("--exportWorkflowGroup")) {
                emptyGroupList = true;
            }
            tokens.add(checked);
            tokens.add("''");
            return this;
        }

        /** {@code <option> '<absolute path>'}, e.g. the export file in a private directory. */
        public Builder path(String option, Path value) {
            String checked = valueOption(option);
            String text = value == null ? "" : value.toString();
            if (!CliPaths.passable(value)) {
                throw invalid("The path for " + checked + " cannot be passed to StartCLI safely",
                    "It must be absolute, match " + CliPaths.PATH_VALUE.pattern()
                        + " and contain no '.' or '..' segment");
            }
            tokens.add(checked);
            tokens.add("'" + text + "'");
            return this;
        }

        /**
         * {@code <option> '<repository path>'} for {@code --exportRepositoryPath} and
         * {@code --importRepositoryPath} (feature 005, research D-1): the path must match
         * {@link #REPOSITORY_PATH} and contain no {@code .} or {@code ..} segment. Only these
         * two options take a repository path, and they take nothing else.
         */
        public Builder repositoryPath(String option, String path) {
            String checked = option(option);
            if (!REPOSITORY_OPTIONS.contains(checked)) {
                throw invalid("The StartCLI option " + checked + " takes no repository path",
                    "Only --exportRepositoryPath and --importRepositoryPath take one");
            }
            if (!repositoryPathPassable(path)) {
                throw invalid("The repository path for " + checked + " cannot be passed to"
                        + " StartCLI safely",
                    "It must match " + REPOSITORY_PATH.pattern() + " and contain no '.' or"
                        + " '..' segment");
            }
            tokens.add(checked);
            tokens.add("'" + path + "'");
            return this;
        }

        /** A bare process id of 1–19 decimal digits (the Queue Manager id, research S-4). */
        public Builder processId(String processId) {
            if (!PROCESS_COMMANDS.contains(name) || processIds > 0) {
                throw invalid("The StartCLI command " + name + " takes "
                        + (PROCESS_COMMANDS.contains(name) ? "exactly one" : "no") + " process id",
                    "Only processErrorStart and kill take a process id, exactly one");
            }
            if (processId == null || !PROCESS_ID.matcher(processId).matches()) {
                throw invalid("The process id is not a Queue Manager id",
                    "It must be 1 to 19 decimal digits without leading zero (queueLog workflowId),"
                        + " not a UUID");
            }
            tokens.add(processId);
            processIds++;
            return this;
        }

        public CliCommand build() {
            if (PROCESS_COMMANDS.contains(name) && processIds != 1) {
                throw invalid("The StartCLI command " + name + " needs a process id",
                    "processErrorStart and kill take exactly one process id");
            }
            if (emptyGroupList && !tagged) {
                throw invalid("The empty diagram group list is only allowed in a tag export",
                    "StartCLI exports every diagram group of the owner for --exportWorkflowGroup"
                        + " '' (research D-4); only the release export narrows it by --exportTag");
            }
            return new CliCommand(tokens);
        }

        private String option(String option) {
            if (!OPTIONS.get(name).contains(option)) {
                throw invalid("The StartCLI option is not allow-listed",
                    "Only the documented options of " + name + " can be used");
            }
            return option;
        }

        /** An allow-listed option that takes a flag, a quoted value or a file path. */
        private String plainOption(String option) {
            String checked = option(option);
            if (REPOSITORY_OPTIONS.contains(checked)) {
                throw invalid("The StartCLI option " + checked + " takes a repository path",
                    "Repository paths are passed only through their own rule");
            }
            return checked;
        }

        /** An allow-listed option that takes a value (quoted, empty or a file path). */
        private String valueOption(String option) {
            String checked = plainOption(option);
            if (FLAGS.contains(checked)) {
                throw invalid("The StartCLI option " + checked + " takes no value",
                    "Pass it with flag()");
            }
            return checked;
        }
    }
}
