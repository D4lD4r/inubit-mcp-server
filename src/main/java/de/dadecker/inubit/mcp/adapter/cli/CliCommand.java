package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.config.CliPaths;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 *   <li>Feature 021 (FR-005, research D-1): {@code trace} only as the download
 *       {@code trace --download '<path>'} ({@link #traceDownload}): exactly one
 *       {@code --download}, given through {@link Builder#path} only, and nothing else, so the
 *       trace state is never switched or changed.
 *   <li>Feature 022 (research D-21), only the forms recorded in T039: {@code library} only as
 *       {@code library --list --type driver|plugin} ({@link #libraryList}, never
 *       {@code --upload}, {@code --delete} or {@code --deleteAll}); the owner export without
 *       groups with {@code --exportWorkflowGroupExclude}, which needs
 *       {@code --exportWorkflowType 'technical'} and the empty {@code --exportWorkflowGroup ''}
 *       and takes no option but the owner and {@code --exportFile}; the
 *       metadata export {@code export --exportMetadata} with the optional
 *       {@code --exportMetadataNames} and {@code --exportFile} only. The two lists are given
 *       through {@link Builder#quotedList} only. No {@code ps} and no {@code fetchXSLT1.0}.
 *   <li>Feature 024 (FR-007, research D-11): besides the download, {@code trace} takes exactly
 *       two more forms: {@code trace --nodelevel <level> --package '<logger>'}
 *       ({@link #traceNodeLevel}; the level only from {@link TemporaryLevel} through
 *       {@link Builder#nodeLevel}, the logger only as a {@link LoggerName} through
 *       {@link Builder#loggerPackage}) and {@code trace --reset} ({@link #traceReset}). Never
 *       {@code --level} or the short option names.
 *   <li>Feature 022 (research D-22), the operation forms, each only through its own method and
 *       never through the {@link Builder} nor combined with another option:
 *       {@code maintenance}, {@code maintenance -s}, {@code maintenance on|off} and
 *       {@code maintenance on -eq true|false} ({@link #maintenanceSwitch}; {@code -eq} only with
 *       {@code on}); {@code trace on|off} ({@link #traceSwitch}) and {@code trace -l <level>}
 *       ({@link #traceLevel}, a {@link TraceLevel}); {@code debug on|off};
 *       {@code triggerMidnightTask}; {@code processdelete error}. {@code OPTIONS} lists them as
 *       the coverage list writes them.
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
    private static final Map<String, Set<String>> OPTIONS = Map.ofEntries(
        Map.entry("processErrorStart", Set.of()),
        Map.entry("kill", Set.of()),
        Map.entry("export", Set.of(
            "--exportWorkflowUser", "--exportWorkflowType", "--exportWorkflowGroup",
            "--includeHistory", "--exportFile",
            "--exportModule", "--exportModuleGroup", "--exportModuleUser",
            // feature 005 (research D-1, D-4)
            "--exportTag", "--exportRepositoryPath",
            // feature 022 (research D-21)
            "--exportWorkflowGroupExclude", "--exportMetadata", "--exportMetadataNames")),
        // feature 004 (research D-8): no metadata; feature 005 (D-1): the repository mode
        Map.entry("import", Set.of("--importFile", "--importWorkflow", "--importWorkflowActive",
            "--importWorkflowInactive", "--importModule", "--importUser", "--returnProtocol",
            "--importRepositoryPath")),
        Map.entry("tag",
            Set.of("--tagMove", "--tagWorkflowGroup", "--tagWorkflowType", "--tagUser")),
        // feature 021 (research D-1), feature 024 (D-11), feature 022 (D-22: on|off and -l
        // through traceSwitch() and traceLevel() only); never --level, -nl, -p, -r
        Map.entry("trace",
            Set.of("--download", "--nodelevel", "--package", "--reset", "on|off", "-l")),
        // feature 022 (research D-21): built through libraryList() only
        Map.entry("library", Set.of("--list", "--type")),
        // feature 022 (research D-22): built through their factory methods only
        Map.entry("maintenance", Set.of("-s", "on|off", "-eq true|false")),
        Map.entry("debug", Set.of("on|off")),
        Map.entry("triggerMidnightTask", Set.of()),
        Map.entry("processdelete", Set.of("error")));
    /**
     * The commands of the operation forms (feature 022, research D-22): the {@link Builder}
     * refuses to build them, so they exist only as their factory methods.
     */
    private static final Set<String> OPERATION_COMMANDS =
        Set.of("maintenance", "debug", "triggerMidnightTask", "processdelete");
    /**
     * The entries of {@link #OPTIONS} that name an operation form the way the coverage list
     * writes it ({@code trace on|off}, {@code maintenance -eq true|false}); they are no options
     * the {@link Builder} takes.
     */
    private static final Set<String> OPERATION_OPTIONS =
        Set.of("-s", "on|off", "-eq true|false", "-l", "error");
    /** The options without value ({@link Builder#flag}); every other option takes one. */
    private static final Set<String> FLAGS = Set.of("--includeHistory", "--importWorkflow",
        "--importWorkflowActive", "--importWorkflowInactive", "--importModule",
        "--returnProtocol", "--exportMetadata", "--reset");
    /**
     * The options that take a level ({@link Builder#nodeLevel}) or a logger
     * ({@link Builder#loggerPackage}) only (feature 024, D-11).
     */
    private static final Set<String> LEVEL_OPTIONS = Set.of("--nodelevel", "--package");
    /** The options that take a repository path ({@link Builder#repositoryPath}) only. */
    private static final Set<String> REPOSITORY_OPTIONS =
        Set.of("--exportRepositoryPath", "--importRepositoryPath");
    /** The options that take a file path ({@link Builder#path}) only (feature 021, D-1). */
    private static final Set<String> PATH_OPTIONS = Set.of("--download");
    /**
     * The options that take a comma-separated list ({@link Builder#quotedList}) only, with the
     * most values each takes (feature 022, research D-21).
     */
    private static final Map<String, Integer> LIST_OPTIONS =
        Map.of("--exportWorkflowGroupExclude", ArtifactPort.MAX_EXCLUDED_GROUPS,
            "--exportMetadataNames", ArtifactPort.MAX_METADATA_NAMES);
    /** The only options of the metadata export (feature 022, research D-21). */
    private static final Set<String> METADATA_OPTIONS =
        Set.of("--exportMetadata", "--exportMetadataNames", "--exportFile");
    /** The only options of the owner export without groups (feature 022, research D-21). */
    private static final Set<String> EXCLUDE_OPTIONS = Set.of("--exportWorkflowUser",
        "--exportWorkflowType", "--exportWorkflowGroup", "--exportWorkflowGroupExclude",
        "--exportFile");
    /** The types of {@code library --list --type} (feature 022, research D-21). */
    private static final Set<String> LIBRARY_TYPES = Set.of("driver", "plugin");
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

    /**
     * {@code trace --download '<file>'}: download the server's trace log to {@code file} without
     * touching the trace state (feature 021, FR-005, research D-1).
     */
    public static CliCommand traceDownload(Path file, boolean windows) {
        return command("trace").path("--download", file, windows).build();
    }

    /**
     * {@code library --list --type <type>}: the file names of the uploaded libraries of
     * {@code type} ({@code driver} or {@code plugin}), in the form recorded in T039 (feature 022,
     * research D-21). The type is one of these two literals and therefore passed unquoted.
     */
    public static CliCommand libraryList(String type) {
        if (type == null || !LIBRARY_TYPES.contains(type)) {
            throw invalid("The library type is not supported",
                "Only driver and plugin libraries are listed");
        }
        return new CliCommand(List.of("library", "--list", "--type", type));
    }

    /**
     * {@code trace --nodelevel <level> --package '<logger>'}: set one logger's level on the node
     * StartCLI talks to (feature 024, FR-007, research D-11).
     */
    public static CliCommand traceNodeLevel(TemporaryLevel level, LoggerName logger) {
        return command("trace").nodeLevel(level).loggerPackage(logger).build();
    }

    /** {@code trace --reset}: reset the node's log levels (feature 024, FR-007, D-11). */
    public static CliCommand traceReset() {
        return command("trace").flag("--reset").build();
    }

    /** {@code maintenance}: the maintenance state as StartCLI shows it (feature 022, D-22). */
    public static CliCommand maintenance() {
        return new CliCommand(List.of("maintenance"));
    }

    /** {@code maintenance -s}: the maintenance state (feature 022, research D-22). */
    public static CliCommand maintenanceState() {
        return new CliCommand(List.of("maintenance", "-s"));
    }

    /**
     * {@code maintenance on|off}, and with {@code executeQueued} {@code maintenance on -eq
     * true|false} (feature 022, research D-22); {@code null} sends no {@code -eq}, which only
     * {@code on} takes.
     */
    public static CliCommand maintenanceSwitch(boolean on, Boolean executeQueued) {
        if (executeQueued == null) {
            return new CliCommand(List.of("maintenance", onOff(on)));
        }
        if (!on) {
            throw invalid("-eq is only allowed with maintenance on",
                "Whether queued processes are executed applies only when maintenance is switched"
                    + " on (research D-22)");
        }
        return new CliCommand(List.of("maintenance", "on", "-eq", executeQueued.toString()));
    }

    /** {@code trace on|off}: switch the server's trace (feature 022, research D-22). */
    public static CliCommand traceSwitch(boolean on) {
        return new CliCommand(List.of("trace", onOff(on)));
    }

    /**
     * {@code trace -l <level>}: the server's trace level, the enum name in lower case (feature
     * 022, research D-22).
     */
    public static CliCommand traceLevel(TraceLevel level) {
        if (level == null) {
            throw invalid("The trace level is missing", "Use one of the levels of trace -l");
        }
        return new CliCommand(List.of("trace", "-l", level.name().toLowerCase(Locale.ROOT)));
    }

    /** {@code debug on|off}: switch the server's debug mode (feature 022, research D-22). */
    public static CliCommand debugSwitch(boolean on) {
        return new CliCommand(List.of("debug", onOff(on)));
    }

    /** {@code triggerMidnightTask}: run the midnight task now (feature 022, research D-22). */
    public static CliCommand triggerMidnightTask() {
        return new CliCommand(List.of("triggerMidnightTask"));
    }

    /**
     * {@code processdelete error}: delete the process instances in state ERROR (feature 022,
     * research D-22); no other argument is ever sent.
     */
    public static CliCommand processDeleteError() {
        return new CliCommand(List.of("processdelete", "error"));
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
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

    /** {@code INVALID_INPUT}; also used by {@link CliSessionCommand.Builder}. */
    static ToolErrorException invalid(String message, String likelyCause) {
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
            String checked = textOption(option);
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
            String checked = textOption(option);
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

        /**
         * {@code <option> '<absolute path>'}, e.g. the export file in a private directory; on
         * Windows ({@code windows}) with {@code /} separators and the Windows rule (feature 013,
         * research R-3).
         */
        public Builder path(String option, Path value, boolean windows) {
            String checked = valueOption(option);
            if (!CliPaths.passable(value, windows)) {
                throw invalid("The path for " + checked + " cannot be passed to StartCLI safely",
                    "It must be " + (windows ? "a local drive path" : "absolute") + ", match "
                        + CliPaths.rule(windows).pattern() + " and contain no '.' or '..'"
                        + " segment");
            }
            tokens.add(checked);
            tokens.add("'" + CliPaths.render(value, windows) + "'");
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

        /**
         * {@code <option> '<v1>,<v2>,…'} for {@code --exportWorkflowGroupExclude} (1–20 groups)
         * and {@code --exportMetadataNames} (1–50 names), feature 022 (research D-21): every
         * value must match {@link #VALUE}, which has no comma, so the joined list is unambiguous.
         */
        public Builder quotedList(String option, List<String> values) {
            String checked = option(option);
            Integer max = LIST_OPTIONS.get(checked);
            if (max == null) {
                throw invalid("The StartCLI option " + checked + " takes no list",
                    "Only --exportWorkflowGroupExclude and --exportMetadataNames take one");
            }
            if (values == null || values.isEmpty() || values.size() > max) {
                throw invalid("The list for " + checked + " needs 1 to " + max + " values",
                    "An empty or longer list cannot be passed to StartCLI");
            }
            for (String value : values) {
                if (value == null || !VALUE.matcher(value).matches()) {
                    throw invalid("A value for " + checked + " cannot be passed to StartCLI"
                            + " safely",
                        "Each must match " + VALUE.pattern() + " (no quotes or commas, at most"
                            + " 200 chars)");
                }
            }
            tokens.add(checked);
            tokens.add("'" + String.join(",", values) + "'");
            return this;
        }

        /** {@code --nodelevel <level>}: the enum name, unquoted (feature 024, D-11). */
        public Builder nodeLevel(TemporaryLevel level) {
            Objects.requireNonNull(level, "level");
            tokens.add(option("--nodelevel"));
            tokens.add(level.name());
            return this;
        }

        /**
         * {@code --package '<logger>'}: a {@link LoggerName}, whose rule keeps quotes and
         * {@code $} out (feature 024, D-11).
         */
        public Builder loggerPackage(LoggerName logger) {
            Objects.requireNonNull(logger, "logger");
            tokens.add(option("--package"));
            tokens.add("'" + logger.value() + "'");
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
            if (OPERATION_COMMANDS.contains(name)) {
                throw invalid("The StartCLI command " + name + " is built by its own method only",
                    "Only the operation forms of research D-22 are sent");
            }
            if (name.equals("trace") && !traceForm()) {
                throw invalid("The StartCLI command trace takes exactly one of --download,"
                        + " --nodelevel with --package, or --reset",
                    "The trace switch and level are built by traceSwitch() and traceLevel()"
                        + " alone; every other form shows the trace state or sets more than one"
                        + " logger (research D-1, D-11, D-22)");
            }
            if (name.equals("library")) {
                throw invalid("The StartCLI command library is only the library list",
                    "Use the list form (library --list --type driver|plugin); uploading and"
                        + " deleting libraries is not offered (research D-21)");
            }
            boolean excluded = tokens.contains("--exportWorkflowGroupExclude");
            if (excluded) {
                checkOwnerExportWithoutGroups();
            }
            if (emptyGroupList && !tagged && !excluded) {
                throw invalid("The empty diagram group list is only allowed in a tag export or"
                        + " with --exportWorkflowGroupExclude",
                    "StartCLI exports every diagram group of the owner for --exportWorkflowGroup"
                        + " '' (research D-4); only --exportTag or the excluded groups narrow it"
                        + " (research D-21)");
            }
            checkMetadataExport();
            return new CliCommand(tokens);
        }

        /**
         * {@code --exportWorkflowGroupExclude} only in the recorded form: the type
         * {@code 'technical'} (owner decision 2026-11-14), the empty {@code --exportWorkflowGroup
         * ''}, no option but the owner and {@code --exportFile}, and each option once (feature
         * 022, research D-21).
         */
        private void checkOwnerExportWithoutGroups() {
            List<String> options = tokens.stream().filter(token -> token.startsWith("--"))
                .toList();
            if (!EXCLUDE_OPTIONS.containsAll(options)) {
                throw invalid("--exportWorkflowGroupExclude takes only --exportWorkflowUser,"
                        + " --exportWorkflowType, --exportWorkflowGroup and --exportFile",
                    "The owner export without groups cannot be combined with tag, module,"
                        + " history, repository or metadata options");
            }
            if (options.size() != Set.copyOf(options).size()) {
                throw invalid("--exportWorkflowGroupExclude takes each option once",
                    "A repeated option could change the type or the groups of the export");
            }
            int type = tokens.indexOf("--exportWorkflowType");
            int group = tokens.indexOf("--exportWorkflowGroup");
            if (type < 0 || group < 0 || !tokens.get(group + 1).equals("''")) {
                throw invalid("--exportWorkflowGroupExclude needs --exportWorkflowType and the"
                        + " empty --exportWorkflowGroup",
                    "Without them INUBIT answers 2-NOK: Missing export option. (research D-21)");
            }
            if (!tokens.get(type + 1).equals("'technical'")) {
                throw invalid("--exportWorkflowGroupExclude takes the workflow type technical only",
                    "The owner export without groups exports technical workflows (research D-21)");
            }
        }

        /**
         * {@code --exportMetadataNames} only with {@code --exportMetadata}, which takes no option
         * but the names and {@code --exportFile} (feature 022, research D-21).
         */
        private void checkMetadataExport() {
            boolean metadata = tokens.contains("--exportMetadata");
            if (!metadata && tokens.contains("--exportMetadataNames")) {
                throw invalid("--exportMetadataNames needs --exportMetadata",
                    "The names select entries of the metadata export only");
            }
            if (metadata && tokens.stream().filter(token -> token.startsWith("--"))
                .anyMatch(token -> !METADATA_OPTIONS.contains(token))) {
                throw invalid("The metadata export takes only --exportMetadataNames and"
                        + " --exportFile",
                    "export --exportMetadata cannot be combined with workflow or module options");
            }
        }

        /**
         * {@code trace --download '<file>'}, {@code trace --nodelevel <level> --package
         * '<logger>'} or {@code trace --reset}, nothing more (each value is one token).
         */
        private boolean traceForm() {
            return switch (tokens.size()) {
                case 2 -> tokens.get(1).equals("--reset");
                case 3 -> tokens.get(1).equals("--download");
                case 5 -> tokens.get(1).equals("--nodelevel") && tokens.get(3).equals("--package");
                default -> false;
            };
        }

        private String option(String option) {
            if (!OPTIONS.get(name).contains(option)) {
                throw invalid("The StartCLI option is not allow-listed",
                    "Only the documented options of " + name + " can be used");
            }
            if (OPERATION_OPTIONS.contains(option)) {
                throw invalid("The StartCLI operation form " + name + " " + option
                        + " is not built option by option",
                    "Use its own method (research D-22)");
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
            if (LEVEL_OPTIONS.contains(checked)) {
                throw invalid("The StartCLI option " + checked + " takes a log level or logger",
                    "Pass it with nodeLevel() or loggerPackage()");
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
            if (LIST_OPTIONS.containsKey(checked)) {
                throw invalid("The StartCLI option " + checked + " takes a list",
                    "Pass it with quotedList()");
            }
            return checked;
        }

        /** A value option that takes a quoted or empty value, not only a file path. */
        private String textOption(String option) {
            String checked = valueOption(option);
            if (PATH_OPTIONS.contains(checked)) {
                throw invalid("The StartCLI option " + checked + " takes a file path",
                    "Pass it with path()");
            }
            return checked;
        }
    }
}
