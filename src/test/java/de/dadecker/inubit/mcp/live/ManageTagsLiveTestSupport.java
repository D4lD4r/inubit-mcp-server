package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.fail;

import de.dadecker.inubit.mcp.adapter.archive.v81.V81ReleaseArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand.TagRemovalKind;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand.TagScope;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.UnlistedTagCommand;
import de.dadecker.inubit.mcp.adapter.cli.v81.VersionHistoryParser;
import de.dadecker.inubit.mcp.config.CliPaths;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.TaggedVersion;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionEntry;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.domain.port.ReleaseArchivePort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.Case;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.Run;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;

/**
 * The offline parts of {@link ManageTagsLiveTest} (feature 022 T055, live check L-4), tested by
 * {@link ManageTagsLiveTestSupportTest}: the inputs and the gate, the owner-approved steps in their
 * order and forms, the derived reads (version history of a group, export by the tag, the folder
 * export by the tag, the active tag), the sequence with its precondition, stop, W15 folder and
 * cleanup, and the lines. Lines and failure messages name steps, codes, byte counts and what the
 * reads derive about the test tag: the workflow and module names and the repository file paths
 * (relative to the owner's repository root, {@link #relative}) carrying it with their versions, and
 * counts; the groups only as {@code g1} and {@code g2}, never output, another tag, the owner or
 * the user.
 */
final class ManageTagsLiveTestSupport {

    /** The opt-in: the operator's statement that the owner approved every step of the list. */
    static final String STEPS_VARIABLE = "INUBIT_LIVE_T055_STEPS";
    static final String TAG_VARIABLE = "INUBIT_LIVE_T055_TAG";
    /** Exactly two technical diagram groups of the node's inventory owner, {@code g1,g2}. */
    static final String GROUPS_VARIABLE = "INUBIT_LIVE_T055_GROUPS";
    /** The folder of W15: {@link #DERIVED} or a repository path. */
    static final String REPOSITORY_PATH_VARIABLE = "INUBIT_LIVE_T055_REPOSITORY_PATH";
    /** The user of W17/W18: {@link #LOGIN} or a login name. */
    static final String USER_VARIABLE = "INUBIT_LIVE_T055_USER";
    static final String RUN_VARIABLE = "INUBIT_LIVE_T055_RUN";
    static final String TASK_DIRECTORY = "T055";
    /** W15 takes the folder of the first repository file carrying the tag before W15. */
    static final String DERIVED = "derived";
    /** The test user is the StartCLI login of the node's credentials. */
    static final String LOGIN = "login";
    static final String TECHNICAL = "technical";
    /** The owner-approved write steps, in the order they run. */
    static final List<String> STEP_IDS =
        List.of("S1", "S2", "W13", "S3", "W14", "S4", "W15", "W16", "W17", "W18");

    private static final Pattern RUN_NUMBER = Pattern.compile("[2-9]|[1-9][0-9]+");
    private static final ReleaseArchivePort RELEASES = new V81ReleaseArchives();

    private ManageTagsLiveTestSupport() {
    }

    // --- inputs and gate -------------------------------------------------------------------------

    /**
     * The inputs of a run: the test tag, the two groups, the W15 folder (empty: {@link #DERIVED}),
     * the test user (resolved) and the node's inventory owner.
     */
    record Inputs(String tag, String group1, String group2, Optional<String> repositoryPath,
        String user, String owner) {
    }

    /**
     * Skips without {@link #STEPS_VARIABLE}; fails unless it lists exactly {@link #STEP_IDS} in
     * their order, comma-separated.
     */
    static void requireApproval(Map<String, String> environment) {
        String value = environment.get(STEPS_VARIABLE);
        String steps = String.join(",", STEP_IDS);
        Assumptions.assumeTrue(value != null && !value.isBlank(), STEPS_VARIABLE + " is not set ("
            + steps + ", only after the owner approved each command); the T055 live check is"
            + " skipped");
        if (!Arrays.stream(value.split(",", -1)).map(String::strip).toList().equals(STEP_IDS)) {
            fail(STEPS_VARIABLE + " must list exactly " + steps + " (only after the owner approved"
                + " each command), or be unset to skip T055; nothing was run");
        }
    }

    /** Fails unless the profile enables writes and development on {@code node}. */
    static void requireNode(NodeId node, boolean writeEnabled, boolean developmentEnabled) {
        if (!writeEnabled || !developmentEnabled) {
            fail(node + " needs write.enabled and development.enabled in the profile (a development"
                + " stage, as for tag_artifacts); nothing was run");
        }
    }

    /**
     * The task folder: {@link #TASK_DIRECTORY}, or {@code T055-<n>} with {@link #RUN_VARIABLE}
     * {@code <n>} (a number from 2); any other value fails before contact.
     */
    static String taskDirectory(Map<String, String> environment) {
        String value = environment.get(RUN_VARIABLE);
        if (value == null || value.isBlank()) {
            return TASK_DIRECTORY;
        }
        if (!RUN_NUMBER.matcher(value.strip()).matches()) {
            fail(RUN_VARIABLE + " must be the number of the run, from 2, or be unset; nothing was"
                + " run");
        }
        return TASK_DIRECTORY + "-" + value.strip();
    }

    /**
     * The inputs from the environment, the node's inventory {@code owner} and the StartCLI
     * {@code login} of its credentials; each is required and checked, and every command of the
     * sequence and the cleanup is built once, before anything is contacted. Messages name the
     * variable, never its value.
     */
    static Inputs inputs(Map<String, String> environment, Optional<String> owner, String login) {
        String tag = required(environment, TAG_VARIABLE);
        List<String> groups = Arrays.stream(required(environment, GROUPS_VARIABLE).split(",", -1))
            .map(String::strip).toList();
        if (groups.size() != 2 || groups.get(0).equals(groups.get(1))) {
            fail(GROUPS_VARIABLE + " must name exactly two different technical diagram groups of"
                + " the inventory owner, comma-separated; nothing was run");
        }
        String path = required(environment, REPOSITORY_PATH_VARIABLE);
        String userValue = required(environment, USER_VARIABLE);
        if (owner.isEmpty()) {
            fail("the node has no inventory.owner in the profile (the owner the groups belong to);"
                + " nothing was run");
        }
        if (!path.equals(DERIVED) && (!CliCommand.repositoryPathPassable(path)
            || !belowOwnerRoot(path, owner.get()))) {
            fail(REPOSITORY_PATH_VARIABLE + " must be " + DERIVED + " or a folder StartCLI can take"
                + " strictly below the owner's repository root /Root/<owner>/ (never the root"
                + " itself or a shared folder); nothing was run");
        }
        Inputs inputs = new Inputs(tag, groups.get(0), groups.get(1),
            path.equals(DERIVED) ? Optional.empty() : Optional.of(path),
            userValue.equals(LOGIN) ? login : userValue, owner.get());
        try {
            steps(inputs).forEach(step -> step.action().form());
            cleanupSteps(inputs, inputs.repositoryPath()).forEach(step -> step.action().form());
            CliSessionCommand.command("user").login(inputs.user()).build();
        } catch (ToolErrorException e) {
            fail("a value of " + String.join(", ", TAG_VARIABLE, GROUPS_VARIABLE,
                REPOSITORY_PATH_VARIABLE, USER_VARIABLE) + " or the inventory owner cannot be"
                + " passed to StartCLI (" + e.error().message() + "); nothing was run");
        }
        return inputs;
    }

    private static String required(Map<String, String> environment, String variable) {
        String value = environment.get(variable);
        if (value == null || value.isBlank()) {
            fail(variable + " is not set (docs/live-tests.md, T055); nothing was run");
        }
        return value.strip();
    }

    // --- steps -----------------------------------------------------------------------------------

    /** What a step sends; {@link #form} is its command line. */
    sealed interface Action permits TagGroup, Removal, FolderRemoval, SetActive, RemoveActive,
        Unlisted {

        String form();
    }

    /** {@code tag --tagMove} of one group through the production {@code CliTagRunner.tag}. */
    record TagGroup(String tag, String group, String owner) implements Action {

        /** The command {@code CliTagRunner.tag} builds. */
        @Override
        public String form() {
            return CliCommand.command("tag").quoted("--tagMove", tag)
                .quoted("--tagWorkflowGroup", group).quoted("--tagWorkflowType", TECHNICAL)
                .quoted("--tagUser", owner).build().commandLine();
        }
    }

    /** {@code tag --tagRemove|--tagDelete} through the production {@code CliTagAdminRunner}. */
    record Removal(TagRemovalKind kind, String tag, TagScope scope) implements Action {

        @Override
        public String form() {
            return CliCommand.tagRemoval(kind, tag, scope).commandLine();
        }
    }

    /**
     * W15 before its folder is known: {@code tag --tagRemove '<tag>' --tagRepositoryPath
     * '<folder>'}, the folder from the export by the tag right before it ({@link #w15Folder}).
     */
    record FolderRemoval(String tag, Optional<String> path) implements Action {

        @Override
        public String form() {
            return path.map(folder -> new Removal(TagRemovalKind.REMOVE, tag,
                new TagScope.RepositoryPath(folder)).form())
                .orElse("tag --tagRemove '" + tag + "' --tagRepositoryPath '<derived folder>'");
        }
    }

    /** {@code tag --tagSetActive} through the production {@code CliTagAdminRunner}. */
    record SetActive(String user, String tag) implements Action {

        @Override
        public String form() {
            return CliCommand.tagSetActive(tag, user).commandLine();
        }
    }

    /** {@code tag --tagRemoveActive} through the production {@code CliTagAdminRunner}. */
    record RemoveActive(String user) implements Action {

        @Override
        public String form() {
            return CliCommand.tagRemoveActive(user).commandLine();
        }
    }

    /** A form outside the production forms, on the production {@code CliRunner.run} (W16). */
    record Unlisted(CliCommand command) implements Action {

        @Override
        public String form() {
            return command.commandLine();
        }
    }

    /**
     * One step: its action runs once. With {@code expectTagged} ({@code g1} or {@code g2}) that
     * group must carry the tag in the read after it.
     */
    record Step(String id, String slug, Action action, Optional<String> expectTagged)
        implements Case {

        @Override
        public String form() {
            return action.form();
        }
    }

    /** The owner-approved steps {@link #STEP_IDS} for {@code in}. */
    static List<Step> steps(Inputs in) {
        TagScope.DiagramGroup g1 = new TagScope.DiagramGroup(in.group1(), TECHNICAL);
        return List.of(
            tagStep("S1", "tag-move-g1", in, in.group1(), "g1"),
            tagStep("S2", "tag-move-g2", in, in.group2(), "g2"),
            new Step("W13", "tag-remove-g1", new Removal(TagRemovalKind.REMOVE, in.tag(), g1),
                Optional.empty()),
            tagStep("S3", "tag-move-g1", in, in.group1(), "g1"),
            new Step("W14", "tag-delete-g1", new Removal(TagRemovalKind.DELETE, in.tag(), g1),
                Optional.empty()),
            tagStep("S4", "tag-move-g1", in, in.group1(), "g1"),
            new Step("W15", "tag-remove-folder", new FolderRemoval(in.tag(), in.repositoryPath()),
                Optional.empty()),
            new Step("W16", "tag-remove-g1-owner", new Unlisted(UnlistedTagCommand.removeWithOwner(
                in.tag(), in.group1(), TECHNICAL, in.owner())), Optional.empty()),
            new Step("W17", "tag-set-active", new SetActive(in.user(), in.tag()),
                Optional.empty()),
            new Step("W18", "tag-remove-active", new RemoveActive(in.user()), Optional.empty()));
    }

    private static Step tagStep(String id, String slug, Inputs in, String group, String label) {
        return new Step(id, slug, new TagGroup(in.tag(), group, in.owner()), Optional.of(label));
    }

    /**
     * The cleanup commands, each a scoped form of the catalogue: C1/C2 {@code --tagDelete} in g1/g2,
     * C3 {@code --tagRemove} in the W15 {@code folder} (none without one), C4
     * {@code --tagRemoveActive} of the test user.
     */
    static List<Step> cleanupSteps(Inputs in, Optional<String> folder) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("C1", "tag-delete-g1", new Removal(TagRemovalKind.DELETE, in.tag(),
            new TagScope.DiagramGroup(in.group1(), TECHNICAL)), Optional.empty()));
        steps.add(new Step("C2", "tag-delete-g2", new Removal(TagRemovalKind.DELETE, in.tag(),
            new TagScope.DiagramGroup(in.group2(), TECHNICAL)), Optional.empty()));
        folder.ifPresent(path -> steps.add(new Step("C3", "tag-remove-folder",
            new Removal(TagRemovalKind.REMOVE, in.tag(), new TagScope.RepositoryPath(path)),
            Optional.empty())));
        steps.add(new Step("C4", "tag-remove-active", new RemoveActive(in.user()),
            Optional.empty()));
        return List.copyOf(steps);
    }

    // --- reads -----------------------------------------------------------------------------------

    /** One read of a round, recorded as {@code <label>-<part>-<slug>.*}. */
    record Read(String id, String slug, String form) implements Case {

        /** The history export of group {@code which} (1 or 2), as {@code manage_tags} reads it. */
        static Read history(String label, int which) {
            return new Read(label + "-H" + which, "history-g" + which,
                "history export of g" + which);
        }

        static Read byTag(String label) {
            return new Read(label + "-T", "export-by-tag", "owner export by the tag");
        }

        /** The candidate folder export; StartCLI writes its archive to {@code <stem>.zip}. */
        static Read folder(String label) {
            return new Read(label + "-P", "folder-by-tag", "folder export by the tag");
        }

        static Read user(String label) {
            return new Read(label + "-A", "user", "user read of the test user");
        }
    }

    /**
     * Fails before contact unless StartCLI can take the archive path of a folder read in
     * {@code taskDirectory} ({@code --exportFile}, {@link CliPaths#passable}).
     */
    static void requireExportPath(Path taskDirectory, boolean windows) {
        if (!CliPaths.passable(taskDirectory.resolve(Read.folder("W15-before").stem() + ".zip"),
            windows)) {
            fail(QueueEntryLiveTestSupport.RECORD_DIR_VARIABLE + " cannot be passed to StartCLI as"
                + " an export file folder (" + CliPaths.rule(windows).pattern() + "); nothing was"
                + " run");
        }
    }

    /** A group's version history: its workflow count and the versions carrying the test tag. */
    record GroupTags(int workflows, List<TaggedVersion> tagged) {
    }

    /**
     * The owner's export by the tag: the groups it holds, the number of its tagged workflow and
     * module versions, and its repository files carrying the tag (path → version). {@link #NONE}
     * if no group carries the tag ({@code NOT_FOUND}).
     */
    record TagExport(SortedSet<String> groups, int versions, SortedMap<String, String> files) {

        static final TagExport NONE = new TagExport(new TreeSet<>(), 0, new TreeMap<>());
    }

    /** The folder export by the tag: its file count and the files carrying the tag. */
    record FolderTags(int files, SortedMap<String, String> tagged) {
    }

    /**
     * One read round; an empty part is unknown (its read ended in an error). {@code folderPath}
     * is the folder read, if any.
     */
    record Round(Optional<GroupTags> group1, Optional<GroupTags> group2,
        Optional<TagExport> byTag, Optional<String> folderPath, Optional<FolderTags> folder) {
    }

    /** What the {@code user} read shows of the test user's active tag. */
    enum ActiveTag {
        NONE("none"),
        TEST_TAG("the test tag"),
        OTHER("another tag"),
        /** The answer is not in the form {@code ActiveTagParser} reads. */
        UNRECOGNIZED("unrecognized answer");

        private final String text;

        ActiveTag(String text) {
            this.text = text;
        }

        static ActiveTag of(Optional<String> parsed, String tag) {
            return parsed.map(value -> value.equals(tag) ? TEST_TAG : OTHER).orElse(NONE);
        }
    }

    /**
     * The versions of {@code versionHistory.xml} carrying {@code tag}, parsed as
     * {@code manage_tags} reads them ({@code VersionHistoryParser}), sorted by kind, name and
     * version.
     */
    static GroupTags groupTags(NodeId node, byte[] versionHistory, String tag) {
        TagPort.History history = VersionHistoryParser.parseHistory(node, versionHistory);
        List<TaggedVersion> tagged = new ArrayList<>();
        history.diagrams().forEach((name, diagram) ->
            add(tagged, TaggedVersion.Kind.WORKFLOW, name, diagram.versions(), tag));
        history.modules().forEach((name, versions) ->
            add(tagged, TaggedVersion.Kind.MODULE, name, versions, tag));
        tagged.sort(Comparator.comparing(TaggedVersion::kind).thenComparing(TaggedVersion::name)
            .thenComparingInt(TaggedVersion::version));
        return new GroupTags(history.diagrams().size(), List.copyOf(tagged));
    }

    private static void add(List<TaggedVersion> tagged, TaggedVersion.Kind kind, String name,
        List<VersionEntry> versions, String tag) {
        versions.stream().filter(version -> version.tags().contains(tag))
            .forEach(version -> tagged.add(new TaggedVersion(kind, name, version.version())));
    }

    /**
     * The export by the tag, normalized as {@code manage_tags} and a deployment read it
     * ({@code ReleaseArchivePort.normalize}); its {@code Repository.zip}, if any, gives the
     * repository files with their {@code tagName} and {@code version}.
     */
    static TagExport tagExport(byte[] archive, String tag) {
        ReleaseArchivePort.ReleaseExport release = RELEASES.normalize(archive);
        SortedMap<String, String> files = RELEASES.repositoryExport(archive) ? new TreeMap<>()
            : taggedFiles(RELEASES.repositoryMetadata(archive), tag);
        return new TagExport(release.diagramGroups(), release.taggedVersions().size(), files);
    }

    /** The files of a repository export and those whose metadata carries {@code tag}. */
    static FolderTags folderTags(byte[] archive, String tag) {
        Map<String, String> metadata = RELEASES.repositoryMetadata(archive);
        return new FolderTags(metadata.size(), taggedFiles(metadata, tag));
    }

    private static SortedMap<String, String> taggedFiles(Map<String, String> metadata,
        String tag) {
        SortedMap<String, String> tagged = new TreeMap<>();
        metadata.forEach((path, xml) -> {
            XmlTree.Element root = XmlTree.parse(xml.getBytes(StandardCharsets.UTF_8)).root();
            if (root.attribute("tagName").filter(tag::equals).isPresent()) {
                tagged.put(path, root.attribute("version").orElse("?"));
            }
        });
        return tagged;
    }

    /**
     * The folder of W15 from the export by the tag right before it: only a folder strictly below
     * the owner's repository root {@code /Root/<owner>/} (never the root itself or another owner's
     * folder, which other objects may share). With {@link #DERIVED} the deepest such folder of a
     * file carrying the tag (the first in path order among equally deep ones); a stated
     * {@code path} must be one of them, else W15 stops ({@link Folder#stop}). With no such folder
     * W15 is not applicable. The note names the folder relative to the owner root.
     */
    static Folder w15Folder(Optional<String> path, TagExport byTag, String owner) {
        SortedSet<String> folders = byTag.files().keySet().stream()
            .map(ManageTagsLiveTestSupport::parent)
            .collect(Collectors.toCollection(TreeSet::new));
        if (folders.isEmpty()) {
            return new Folder(Optional.empty(), "not applicable: the tag reached no repository"
                + " file", false);
        }
        List<String> below = folders.stream().filter(folder -> belowOwnerRoot(folder, owner))
            .toList();
        if (path.isPresent()) {
            String stated = relative(path.get(), owner);
            if (!belowOwnerRoot(path.get(), owner)) {
                return new Folder(Optional.empty(), "the stated folder " + stated + " is not below"
                    + " the owner's repository root", true);
            }
            if (!folders.contains(path.get())) {
                return new Folder(Optional.empty(), "the stated folder " + stated + " holds none"
                    + " of the " + byTag.files().size() + " files carrying the tag", true);
            }
            return new Folder(path, "folder " + stated + " (stated)", false);
        }
        if (below.isEmpty()) {
            return new Folder(Optional.empty(), "not applicable: none of the " + folders.size()
                + " folders of files carrying the tag lies below the owner's repository root",
                false);
        }
        String deepest = below.stream().max(Comparator.comparingInt(
            (String folder) -> folder.split("/").length)
            .thenComparing(Comparator.<String>reverseOrder())).orElseThrow();
        return new Folder(Optional.of(deepest), "folder " + relative(deepest, owner) + " (derived:"
            + " the deepest of " + below.size() + " folders below the owner's repository root"
            + " holding files carrying the tag)", false);
    }

    /** True if {@code folder} lies strictly below {@code /Root/<owner>/}. */
    static boolean belowOwnerRoot(String folder, String owner) {
        String root = "/Root/" + owner + "/";
        return folder.startsWith(root) && folder.length() > root.length();
    }

    private static String parent(String file) {
        return file.substring(0, file.lastIndexOf('/'));
    }

    /**
     * {@code path} for a line: below the owner root as {@code ~/…}, below another owner's root as
     * {@code /Root/<other>/…}; never an owner name.
     */
    static String relative(String path, String owner) {
        String root = "/Root/" + owner;
        if (path.equals(root) || path.startsWith(root + "/")) {
            return "~" + path.substring(root.length());
        }
        String[] segments = path.split("/", 4);
        if (segments.length >= 3 && segments[1].equals("Root")) {
            return "/Root/<other>" + (segments.length == 4 ? "/" + segments[3] : "");
        }
        return path;
    }

    /** The folders of the files carrying the tag that {@code round} shows. */
    static SortedSet<String> taggedFolders(Round round) {
        SortedSet<String> folders = new TreeSet<>();
        round.byTag().ifPresent(export -> export.files().keySet()
            .forEach(file -> folders.add(parent(file))));
        round.folder().ifPresent(tags -> tags.tagged().keySet()
            .forEach(file -> folders.add(parent(file))));
        return folders;
    }

    /** The W15 folder, if any; its note; and whether W15 stops the sequence. */
    record Folder(Optional<String> path, String note, boolean stop) {
    }

    // --- the sequence ----------------------------------------------------------------------------

    /** The node as the sequence sees it; each call runs one StartCLI command once and records it. */
    interface Node {

        /** The history export of group {@code which} (1 or 2); empty if it ended in an error. */
        Optional<GroupTags> group(String label, int which) throws IOException;

        /** The owner's export by the tag; {@link TagExport#NONE} for NOT_FOUND, empty on error. */
        Optional<TagExport> byTag(String label) throws IOException;

        /** The candidate folder export by the tag; empty if it ended in an error or unreadable. */
        Optional<FolderTags> folder(String label, String path) throws IOException;

        /** The {@code user} read of the test user; empty if it ended in an error. */
        Optional<ActiveTag> activeTag(String label) throws IOException;

        /** Runs {@code step} once, never retried. */
        Answer run(Step step) throws IOException;

        /** Writes {@code <stem>.state} with {@code text} and prints its line. */
        void record(String stem, String text) throws IOException;
    }

    /**
     * A command's failure, if any (an error code, {@code exit <n>} or {@code n-NOK}); with
     * {@code answered} StartCLI ran to its end and INUBIT refused (only for {@link Unlisted}).
     */
    record Answer(Optional<String> failure, boolean answered) {

        static final Answer DONE = new Answer(Optional.empty(), false);
    }

    /** Where and why the sequence stopped. */
    record Stop(String step, String code) {
    }

    /**
     * The end of a run: where it stopped, the cleanup commands with their outcome, what still
     * carries the tag after the cleanup, and notes (not a failure).
     */
    record Outcome(Optional<Stop> stop, List<String> cleanup, List<String> remains,
        List<String> notes) {
    }

    /** What the sequence knows while it runs. */
    private static final class Progress {
        Round latest;
        Optional<String> folder = Optional.empty();
        boolean activeSet;
        boolean activeRemoved;
        /** The folders of every repository file a read showed carrying the tag. */
        final SortedSet<String> taggedFolders = new TreeSet<>();
        final List<String> notes = new ArrayList<>();

        Progress(Round latest) {
            this.latest = latest;
        }

        void saw(Round round) {
            latest = round;
            taggedFolders.addAll(taggedFolders(round));
        }
    }

    /** One read round: g1, g2, the export by the tag and, with {@code folder}, the folder. */
    static Round round(Node node, String label, Optional<String> folder) throws IOException {
        Optional<GroupTags> group1 = node.group(label, 1);
        Optional<GroupTags> group2 = node.group(label, 2);
        Optional<TagExport> byTag = node.byTag(label);
        Optional<FolderTags> files = folder.isPresent() ? node.folder(label, folder.get())
            : Optional.empty();
        return new Round(group1, group2, byTag, folder, files);
    }

    /**
     * Runs the T055 sequence on {@code node}: the read B0 must show the tag on nothing and no
     * active tag of the test user (else nothing is written); then each step of {@link #steps}
     * once, each followed by its read; at the first error, unknown read or deviation it stops.
     * Then the {@link #cleanupPlan} runs once and the read V checks that nothing carries the tag:
     * the groups, the export by the tag, and with the folder export by the tag every folder in
     * which a read showed a file carrying it (W15's folder included).
     */
    static Outcome run(Node node, Inputs in) throws IOException {
        Round b0 = round(node, "B0", Optional.empty());
        Optional<ActiveTag> a0 = node.activeTag("B0");
        node.record("B0", describe(b0, in) + "; active tag " + describe(a0));
        List<String> unclean = unclean(b0, a0);
        if (!unclean.isEmpty()) {
            return new Outcome(Optional.of(new Stop("B0", "PRECONDITION (" + String.join("; ",
                unclean) + "); nothing was written")), List.of(), List.of(), List.of());
        }
        Progress progress = new Progress(b0);
        Optional<Stop> stop = Optional.empty();
        for (Step step : steps(in)) {
            try {
                stop = runStep(node, in, step, progress).map(code -> new Stop(step.id(), code));
            } catch (IOException | RuntimeException | AssertionError e) {
                stop = Optional.of(new Stop(step.id(), e.getClass().getSimpleName()));
                // the step may have been sent: everything counts as unknown, so the cleanup runs
                progress.latest = new Round(Optional.empty(), Optional.empty(), Optional.empty(),
                    progress.folder, Optional.empty());
            }
            if (stop.isPresent()) {
                break;
            }
        }
        List<String> cleanup = new ArrayList<>();
        for (Step step : cleanupPlan(in, progress.latest, progress.folder, progress.activeSet,
            progress.activeRemoved)) {
            Optional<String> failure;
            try {
                failure = node.run(step).failure();
            } catch (IOException | RuntimeException | AssertionError e) {
                failure = Optional.of(e.getClass().getSimpleName());
            }
            cleanup.add(step.id() + " " + failure.orElse("done"));
        }
        List<String> remains;
        try {
            Round end = round(node, "V", Optional.empty());
            Optional<ActiveTag> active = progress.activeSet ? node.activeTag("V")
                : Optional.of(ActiveTag.NONE);
            node.record("V", describe(end, in) + "; active tag " + describe(active));
            remains = new ArrayList<>(remains(end, active, in));
            SortedSet<String> folders = new TreeSet<>(progress.taggedFolders);
            folders.addAll(taggedFolders(end));
            progress.folder.ifPresent(folders::add);
            int index = 0;
            for (String folder : folders) {
                String label = "V-F" + ++index;
                Optional<FolderTags> tags = node.folder(label, folder);
                String shown = "folder " + relative(folder, in.owner()) + " "
                    + tags.map(found -> describe(found, in)).orElse("unknown");
                node.record(label, shown);
                if (tags.isEmpty()) {
                    progress.notes.add(shown + ": not verified");
                } else if (!tags.get().tagged().isEmpty()) {
                    remains.add(shown);
                }
            }
        } catch (IOException | RuntimeException | AssertionError e) {
            remains = List.of("unknown (the read V ended in " + e.getClass().getSimpleName() + ")");
        }
        return new Outcome(stop, cleanup, remains, progress.notes);
    }

    private static Optional<String> runStep(Node node, Inputs in, Step step, Progress progress)
        throws IOException {
        Step sent = step;
        if (step.action() instanceof FolderRemoval removal) {
            TagExport byTag = progress.latest.byTag().orElseThrow();
            Folder folder = w15Folder(removal.path(), byTag, in.owner());
            node.record(step.id() + "-folder", folder.note());
            if (folder.stop()) {
                return Optional.of("FOLDER NOT TAGGED");
            }
            if (folder.path().isEmpty()) {
                progress.notes.add("W15 " + folder.note());
                return Optional.empty();
            }
            progress.folder = folder.path();
            Optional<FolderTags> before = node.folder("W15-before", folder.path().get());
            progress.saw(new Round(progress.latest.group1(), progress.latest.group2(),
                progress.latest.byTag(), folder.path(), before));
            sent = new Step(step.id(), step.slug(), new Removal(TagRemovalKind.REMOVE, in.tag(),
                new TagScope.RepositoryPath(folder.path().get())), Optional.empty());
        }
        if (sent.action() instanceof SetActive) {
            progress.activeSet = true;
        }
        Answer answer = node.run(sent);
        Optional<String> failure = answer.failure();
        String outcome = failure.map(code -> answer.answered() ? "refused (" + code + ")" : code)
            .orElse("done");
        if (answer.answered() && failure.isPresent()) {
            progress.notes.add(step.id() + " refused by INUBIT (" + failure.get() + ")");
            failure = Optional.empty();
        }
        if (sent.action() instanceof SetActive || sent.action() instanceof RemoveActive) {
            Optional<ActiveTag> after = node.activeTag(step.id() + "-after");
            progress.activeRemoved = sent.action() instanceof RemoveActive && failure.isEmpty();
            node.record(step.stem(), "answer " + outcome + "; active tag " + describe(after));
            return failure.or(() -> after.isEmpty() ? Optional.of("READ active tag")
                : Optional.empty());
        }
        Round before = progress.latest;
        Round after = round(node, step.id() + "-after", progress.folder);
        progress.saw(after);
        node.record(step.stem(), "answer " + outcome + "; before: " + describe(before, in)
            + "; after: " + describe(after, in));
        if (failure.isPresent()) {
            return failure;
        }
        List<String> unknown = unknown(after);
        if (!unknown.isEmpty()) {
            return Optional.of("READ " + String.join(", ", unknown) + " unknown");
        }
        if (step.expectTagged().isPresent()) {
            String group = step.expectTagged().get();
            GroupTags tags = (group.equals("g1") ? after.group1() : after.group2()).orElseThrow();
            if (tags.tagged().isEmpty()) {
                return Optional.of("DEVIATION (" + group + " carries no version with the tag)");
            }
        }
        return Optional.empty();
    }

    /** The production reads of {@code round} that are unknown: g1, g2, by tag. */
    private static List<String> unknown(Round round) {
        List<String> unknown = new ArrayList<>();
        if (round.group1().isEmpty()) {
            unknown.add("g1");
        }
        if (round.group2().isEmpty()) {
            unknown.add("g2");
        }
        if (round.byTag().isEmpty()) {
            unknown.add("by tag");
        }
        return unknown;
    }

    /**
     * Why B0 refuses to write: a read unknown, a group without workflow, anything carrying the
     * tag, or a test user whose active tag is set or unknown.
     */
    static List<String> unclean(Round b0, Optional<ActiveTag> active) {
        List<String> reasons = new ArrayList<>();
        unknown(b0).forEach(part -> reasons.add(part + " unknown"));
        b0.group1().filter(tags -> tags.workflows() == 0).ifPresent(tags ->
            reasons.add("g1 holds no workflow"));
        b0.group2().filter(tags -> tags.workflows() == 0).ifPresent(tags ->
            reasons.add("g2 holds no workflow"));
        if (b0.group1().filter(tags -> !tags.tagged().isEmpty()).isPresent()
            || b0.group2().filter(tags -> !tags.tagged().isEmpty()).isPresent()
            || b0.byTag().filter(export -> !export.equals(TagExport.NONE)).isPresent()) {
            reasons.add("the test tag is in use: choose a new tag");
        }
        if (active.isEmpty() || active.get() != ActiveTag.NONE) {
            reasons.add("the test user's active tag is " + describe(active) + ", not none");
        }
        return reasons;
    }

    /**
     * The cleanup after the steps or a stop, from the latest read: C1/C2 for a group that carries
     * the tag, is named by the export by the tag or is unknown; C3 for the W15 folder if it, or
     * the export by the tag, shows a file there carrying the tag, or is unknown; C4 if W17 was
     * sent and W18 was not done. Each runs once.
     */
    static List<Step> cleanupPlan(Inputs in, Round latest, Optional<String> folder,
        boolean activeSet, boolean activeRemoved) {
        List<Step> plan = new ArrayList<>();
        for (Step step : cleanupSteps(in, folder)) {
            boolean needed = switch (step.id()) {
                case "C1" -> carries(latest.group1(), latest.byTag(), in.group1());
                case "C2" -> carries(latest.group2(), latest.byTag(), in.group2());
                case "C3" -> latest.folder().map(tags -> !tags.tagged().isEmpty()).orElse(true)
                    || latest.byTag().map(export -> export.files().keySet().stream()
                        .anyMatch(file -> file.startsWith(folder.get() + "/"))).orElse(true);
                case "C4" -> activeSet && !activeRemoved;
                default -> throw new IllegalArgumentException(step.id());
            };
            if (needed) {
                plan.add(step);
            }
        }
        return plan;
    }

    private static boolean carries(Optional<GroupTags> group, Optional<TagExport> byTag,
        String name) {
        return group.map(tags -> !tags.tagged().isEmpty()).orElse(true)
            || byTag.map(export -> export.groups().contains(name)).orElse(true);
    }

    /**
     * What still carries the tag (or is unknown) after the cleanup in the groups, the export by
     * the tag and the active tag; {@link #run} adds the folders.
     */
    static List<String> remains(Round end, Optional<ActiveTag> active, Inputs in) {
        List<String> remains = new ArrayList<>();
        unknown(end).forEach(part -> remains.add(part + " unknown"));
        end.group1().filter(tags -> !tags.tagged().isEmpty())
            .ifPresent(tags -> remains.add("g1 " + describe(tags)));
        end.group2().filter(tags -> !tags.tagged().isEmpty())
            .ifPresent(tags -> remains.add("g2 " + describe(tags)));
        end.byTag().filter(export -> !export.equals(TagExport.NONE))
            .ifPresent(export -> remains.add("by tag " + describe(export, in)));
        if (active.isEmpty() || active.get() != ActiveTag.NONE) {
            remains.add("active tag " + describe(active));
        }
        return remains;
    }

    /** Fails naming the stop, the cleanup and what remains; notes are only shown. */
    static void assertOutcome(Outcome outcome) {
        if (outcome.stop().isPresent() || !outcome.remains().isEmpty()) {
            fail("T055 " + outcome.stop().map(stop -> "stopped at " + stop.step() + ": "
                    + stop.code()).orElse("ran all steps") + "; cleanup "
                + (outcome.cleanup().isEmpty() ? "none" : String.join(", ", outcome.cleanup()))
                + "; " + (outcome.remains().isEmpty() ? "nothing carries the test tag"
                    : "still carrying the test tag or unknown, the owner must remove it: "
                        + String.join("; ", outcome.remains()))
                + (outcome.notes().isEmpty() ? "" : "; notes: "
                    + String.join("; ", outcome.notes())));
        }
    }

    // --- lines -----------------------------------------------------------------------------------

    /** E.g. {@code g1 1 workflow, 2 tagged: workflow W v3, module M v2; g2 …; by tag …}. */
    static String describe(Round round, Inputs in) {
        return "g1 " + round.group1().map(ManageTagsLiveTestSupport::describe).orElse("unknown")
            + "; g2 " + round.group2().map(ManageTagsLiveTestSupport::describe).orElse("unknown")
            + "; by tag " + round.byTag().map(export -> describe(export, in)).orElse("unknown")
            + round.folderPath().map(path -> "; folder " + round.folder()
                .map(tags -> describe(tags, in)).orElse("unknown")).orElse("");
    }

    static String describe(GroupTags tags) {
        return tags.workflows() + " workflow" + (tags.workflows() == 1 ? "" : "s") + ", "
            + (tags.tagged().isEmpty() ? "none tagged" : tags.tagged().size() + " tagged: "
                + tags.tagged().stream().map(version -> version.kind().name()
                    .toLowerCase(Locale.ROOT) + " " + version.name() + " v"
                    + version.version()).collect(Collectors.joining(", ")));
    }

    /** The groups as {@code g1}/{@code g2} and a count of others; never a group name. */
    static String describe(TagExport export, Inputs in) {
        if (export.equals(TagExport.NONE)) {
            return "none (no group carries the tag)";
        }
        List<String> groups = new ArrayList<>();
        long others = export.groups().size();
        if (export.groups().contains(in.group1())) {
            groups.add("g1");
            others--;
        }
        if (export.groups().contains(in.group2())) {
            groups.add("g2");
            others--;
        }
        if (others > 0) {
            groups.add(others + " other");
        }
        return "groups " + String.join(", ", groups) + ", " + export.versions() + " versions, "
            + files(export.files(), in.owner());
    }

    static String describe(FolderTags tags, Inputs in) {
        return tags.files() + " files, " + files(tags.tagged(), in.owner());
    }

    /** The files with their versions, relative to the owner root ({@link #relative}). */
    private static String files(SortedMap<String, String> files, String owner) {
        return files.size() + " repository files tagged" + (files.isEmpty() ? ""
            : ": " + files.entrySet().stream().map(file -> relative(file.getKey(), owner) + " v"
                + file.getValue()).collect(Collectors.joining(", ")));
    }

    static String describe(Optional<ActiveTag> active) {
        return active.map(tag -> tag.text).orElse("unknown");
    }

    // --- recording -------------------------------------------------------------------------------

    /**
     * Writes the StartCLI run of {@code read} as {@code <stem>.stdout}, {@code .stderr}, once it
     * exited {@code .exit}, and {@code error} as {@code .error}, each owner-only (the stdin with
     * the password is never recorded).
     *
     * @param runs the runs the command started: none (refused before the launch) or one
     * @return its line: id, exit code, byte counts and error code only
     */
    static String recordCli(PrivateFileSystem files, Path directory, Case read, List<Run> runs,
        Optional<ErrorCode> error) throws IOException {
        if (runs.size() > 1) {
            fail(read.id() + " started " + runs.size() + " StartCLI runs, expected one");
        }
        String outcome = "not started";
        if (runs.size() == 1) {
            Run run = runs.get(0);
            byte[] stdout = run.stdout().toByteArray();
            byte[] stderr = run.stderr().toByteArray();
            OperateServerReadsLiveTestSupport.write(files,
                directory.resolve(read.stem() + ".stdout"), stdout);
            OperateServerReadsLiveTestSupport.write(files,
                directory.resolve(read.stem() + ".stderr"), stderr);
            if (run.exit().isPresent()) {
                OperateServerReadsLiveTestSupport.write(files,
                    directory.resolve(read.stem() + ".exit"),
                    OperateServerReadsLiveTestSupport.line(run.exit().get()));
            }
            outcome = "exit " + run.exit().map(String::valueOf).orElse("none") + ", stdout "
                + stdout.length + " bytes, stderr " + stderr.length + " bytes";
        }
        if (error.isPresent()) {
            OperateServerReadsLiveTestSupport.write(files, directory.resolve(read.stem() + ".error"),
                OperateServerReadsLiveTestSupport.line(error.get()));
            outcome += ", error " + error.get();
        }
        return line(read.id(), outcome);
    }

    /** Writes {@code <stem>.state} owner-only with {@code text}; its line. */
    static String recordState(PrivateFileSystem files, Path directory, String stem, String text)
        throws IOException {
        OperateServerReadsLiveTestSupport.write(files, directory.resolve(stem + ".state"),
            (text + "\n").getBytes(StandardCharsets.UTF_8));
        return line(stem, text);
    }

    static String line(String id, String text) {
        return "[live] T055 " + id + ": " + text;
    }
}
