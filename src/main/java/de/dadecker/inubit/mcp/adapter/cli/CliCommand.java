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
 *       {@code import} and {@code tag} with their options (research D-8; never a repository
 *       path, metadata, {@code --tagDiagram} or {@code --tagRemove}).
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

    private static final Pattern PROCESS_ID = Pattern.compile("^[1-9][0-9]{0,18}$");

    /** Commands and the options each of them accepts. */
    private static final Map<String, Set<String>> OPTIONS = Map.of(
        "processErrorStart", Set.of(),
        "kill", Set.of(),
        "export", Set.of(
            "--exportWorkflowUser", "--exportWorkflowType", "--exportWorkflowGroup",
            "--includeHistory", "--exportFile",
            "--exportModule", "--exportModuleGroup", "--exportModuleUser"),
        // feature 004 (research D-8): no repository path, no metadata
        "import", Set.of("--importFile", "--importWorkflow", "--importWorkflowActive",
            "--importWorkflowInactive", "--importModule", "--importUser", "--importUserGroup",
            "--returnProtocol"),
        "tag", Set.of("--tagMove", "--tagDelete", "--tagWorkflowGroup", "--tagWorkflowType",
            "--tagUser"));
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

    private static ToolErrorException invalid(String message, String likelyCause) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message, likelyCause,
            "Use a value that matches the documented pattern"));
    }

    /** Collects the tokens of one command. */
    public static final class Builder {

        private final String name;
        private final List<String> tokens = new ArrayList<>();
        private int processIds;

        private Builder(String name) {
            this.name = name;
            tokens.add(name);
        }

        /** An allow-listed option without value, e.g. {@code --includeHistory}. */
        public Builder flag(String option) {
            tokens.add(option(option));
            return this;
        }

        /** {@code <option> '<value>'}; the value must match {@link #VALUE}. */
        public Builder quoted(String option, String value) {
            String checked = option(option);
            if (value == null || !VALUE.matcher(value).matches()) {
                throw invalid("The value for " + checked + " cannot be passed to StartCLI"
                        + " safely",
                    "It must match " + VALUE.pattern() + " (no quotes, at most 200 chars)");
            }
            tokens.add(checked);
            tokens.add("'" + value + "'");
            return this;
        }

        /** {@code <option> ''}: the literal empty argument (research R-11). */
        public Builder emptyQuoted(String option) {
            tokens.add(option(option));
            tokens.add("''");
            return this;
        }

        /** {@code <option> '<absolute path>'}, e.g. the export file in a private directory. */
        public Builder path(String option, Path value) {
            String checked = option(option);
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
            return new CliCommand(tokens);
        }

        private String option(String option) {
            if (!OPTIONS.get(name).contains(option)) {
                throw invalid("The StartCLI option is not allow-listed",
                    "Only the documented options of " + name + " can be used");
            }
            return option;
        }
    }
}
