package de.dadecker.inubit.mcp.live;

import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliTagRunner;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliTagAdminRunner;
import de.dadecker.inubit.mcp.adapter.os.OsPrivateFileSystem;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.ActiveTag;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Answer;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.FolderTags;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.GroupTags;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Inputs;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Outcome;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Plan;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Read;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Step;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.TagExport;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.Case;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.Run;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Feature 022 T055, live check L-4 (research D-18, "Live checks"): one owner-approved step list
 * ({@link Plan}: S1–S4 and W13–W18 from a tag on nothing, W15, X1, X2, W17, W18 from the tag
 * on the two test groups, or S1, W17, W18, X1 from a tag on nothing) against a real,
 * <b>non-production</b> INUBIT 8.1 development node whose profile enables writes (opt-in,
 * Constitution III), each step run once in that order, every read and command recorded
 * owner-only in {@code <INUBIT_LIVE_RECORD_DIR>/T055/} (a later run with
 * {@code INUBIT_LIVE_T055_RUN=<n>} in {@code T055-<n>/}).
 *
 * <p>The tool {@code manage_tags} still refuses 8.1 until the coverage rows are flipped (T066,
 * which waits for this check), so the commands run raw on the production runners:
 * {@code tag --tagMove} through {@link CliTagRunner#tag} (as {@code tag_artifacts}), the removals
 * and active-tag forms through {@link CliTagAdminRunner}, the reads through
 * {@link CliExportRunner#exportHistory} and {@link CliExportRunner#exportRelease} (as the
 * {@code manage_tags} preview) and {@link CliTagAdminRunner#activeTag}. Some forms have no
 * production runner: W13, W14 and W16 of the first list (group forms 8.1.17 refused, built by
 * {@code UnlistedTagCommand}) and the candidate folder read {@code export --exportRepositoryPath
 * --exportTag} (allow-listed form, record-only); they run on {@link CliRunner#run}.
 *
 * <p>Run with {@code INUBIT_MCP_PROFILE=<profile> INUBIT_LIVE_NODE=<group>/<node>
 * INUBIT_LIVE_T055_STEPS=S1,W17,W18,X1 INUBIT_LIVE_T055_RUN=4 INUBIT_LIVE_T055_TAG=<tag>
 * INUBIT_LIVE_T055_GROUPS=<g1>,<g2> INUBIT_LIVE_T055_REPOSITORY_PATH=derived|<folder>
 * INUBIT_LIVE_T055_USER=login|<user> INUBIT_LIVE_RECORD_DIR=<private dir> mvn verify -Plive
 * -Dtest=ManageTagsLiveTest}, only after the owner approved each command (docs/live-tests.md).
 * Prints one line per command with its exit code and byte counts, and one line per step with what
 * the reads derive about the test tag, and of a refused W17/W18 INUBIT's message lines with the
 * inputs masked; never other output.
 */
@Tag("live")
@Timeout(7200)
class ManageTagsLiveTest {

    @Test
    void theStepsOfL4RunOnceEachAndTheTestTagIsRemovedAfterwards() throws IOException {
        LiveTarget live = LiveTarget.resolve();
        Map<String, String> environment = System.getenv();
        Plan plan = ManageTagsLiveTestSupport.requireApproval(environment);
        Path recordDirectory = OperateServerReadsLiveTestSupport.recordDirectory(environment,
            Path.of("").toAbsolutePath());
        NodeId node = live.node();
        EffectiveNodeConfig server = live.loaded().config().effectiveNodes().stream()
            .filter(candidate -> candidate.id().equals(node)).findFirst().orElseThrow();
        ManageTagsLiveTestSupport.requireNode(node, server.effectiveWriteEnabled(),
            server.development().enabled());
        String taskDirectory = ManageTagsLiveTestSupport.taskDirectory(environment);
        // refuse an earlier recording before any contact; create the folder only after the 8.1 gate
        OperateServerReadsLiveTestSupport.requireNoTaskDirectory(recordDirectory, taskDirectory);
        ManageTagsLiveTestSupport.requireExportPath(recordDirectory.resolve(taskDirectory),
            live.windows());
        PrivateFileSystem files = OsPrivateFileSystem.forHost();
        Outcome outcome;
        try (OperateServerLiveConnection connection =
                OperateServerLiveConnection.open(live, server, environment, files)) {
            Inputs inputs = ManageTagsLiveTestSupport.inputs(environment,
                server.inventory().owner(), connection.username);
            InubitVersion version = connection.requireV81();
            Path directory = OperateServerReadsLiveTestSupport.taskDirectory(files,
                recordDirectory, taskDirectory);
            outcome = ManageTagsLiveTestSupport.run(new LiveNode(connection,
                connection.cli(version), new CliOutputClassifier(live.scrubber(),
                    server.secretLabels(), live.windows()), version, inputs, files, directory,
                live.windows()), inputs, plan);
        }
        ManageTagsLiveTestSupport.assertOutcome(outcome);
    }

    /** The node of the sequence: every call records its case files and prints its line. */
    private static final class LiveNode implements ManageTagsLiveTestSupport.Node {

        private final OperateServerLiveConnection connection;
        private final CliRunner cli;
        private final CliOutputClassifier classifier;
        private final CliTagRunner tags;
        private final CliTagAdminRunner admin;
        private final CliExportRunner exports;
        /** As {@code tag_artifacts}: a development write. */
        private final WritePermit tagPermit;
        /** As {@code manage_tags}. */
        private final WritePermit adminPermit;
        private final Inputs in;
        private final PrivateFileSystem files;
        private final Path directory;
        private final boolean windows;

        LiveNode(OperateServerLiveConnection connection, CliRunner cli,
            CliOutputClassifier classifier, InubitVersion version, Inputs in,
            PrivateFileSystem files, Path directory, boolean windows) {
            EffectiveNodeConfig server = connection.server;
            this.connection = connection;
            this.cli = cli;
            this.classifier = classifier;
            this.tags = new CliTagRunner(server, connection.credentials, connection.guard, cli,
                classifier);
            this.admin = new CliTagAdminRunner(server, connection.credentials, connection.guard,
                cli, classifier);
            this.exports = new CliExportRunner(server, connection.credentials, connection.guard,
                cli, classifier);
            this.tagPermit = new WritePermit(server.id(), Capability.DEVELOPMENT_WRITE, version);
            this.adminPermit = new WritePermit(server.id(), Capability.TAG_ADMINISTRATION,
                version);
            this.in = in;
            this.files = files;
            this.directory = directory;
            this.windows = windows;
        }

        @Override
        public Optional<GroupTags> group(String label, int which) throws IOException {
            Read read = Read.history(label, which);
            String group = which == 1 ? in.group1() : in.group2();
            Attempt<byte[]> attempt = attempt(read, () -> exports.exportHistory(in.owner(),
                ManageTagsLiveTestSupport.TECHNICAL, group));
            if (attempt.value().isEmpty()) {
                return Optional.empty();
            }
            OperateServerReadsLiveTestSupport.write(files,
                directory.resolve(read.stem() + ".versionHistory.xml"), attempt.value().get());
            return parsed(read, () -> ManageTagsLiveTestSupport.groupTags(connection.server.id(),
                attempt.value().get(), in.tag()));
        }

        @Override
        public Optional<TagExport> byTag(String label) throws IOException {
            Read read = Read.byTag(label);
            Attempt<byte[]> attempt = attempt(read, () -> exports.exportRelease(in.owner(),
                in.tag()));
            if (attempt.error().equals(Optional.of(ErrorCode.NOT_FOUND))) {
                return Optional.of(TagExport.NONE);
            }
            if (attempt.value().isEmpty()) {
                return Optional.empty();
            }
            // the archive holds whole workflows and module configurations: only derived values
            return parsed(read, () -> ManageTagsLiveTestSupport.tagExport(attempt.value().get(),
                in.tag()));
        }

        /**
         * The candidate form; StartCLI writes the archive into the owner-only task folder, and it
         * is deleted once its values are derived (also if that fails). Any failure, a recording
         * failure included, makes the read unknown and never stops the sequence.
         */
        @Override
        public Optional<FolderTags> folder(String label, String path) {
            Read read = Read.folder(label);
            Path file = directory.resolve(read.stem() + ".zip");
            try {
                Attempt<CliResult> attempt = attempt(read, () -> {
                    CliCommand command = CliCommand.command("export")
                        .repositoryPath("--exportRepositoryPath", path)
                        .quoted("--exportTag", in.tag())
                        .path("--exportFile", file, windows).build();
                    cli.checkAvailable(connection.server);
                    return cli.run(connection.server, connection.username, connection.password,
                        command, connection.server.cliExportTimeout(), "cliExportTimeout",
                        connection.guard);
                });
                if (attempt.value().filter(result -> result.exitCode() == 0).isEmpty()
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    return Optional.empty();
                }
                files.tightenOnPosix(file);
                byte[] archive = Files.readAllBytes(file);
                return parsed(read, () -> ManageTagsLiveTestSupport.folderTags(archive, in.tag()));
            } catch (IOException e) {
                print(ManageTagsLiveTestSupport.line(read.id(), "not readable: "
                    + e.getClass().getSimpleName()));
                return Optional.empty();
            } finally {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    print(ManageTagsLiveTestSupport.line(read.id(), "archive not deleted: "
                        + e.getClass().getSimpleName()));
                }
            }
        }

        @Override
        public Optional<ActiveTag> activeTag(String label) throws IOException {
            Read read = Read.user(label);
            Attempt<Optional<String>> attempt = attempt(read, () -> admin.activeTag(in.user()));
            if (attempt.error().equals(Optional.of(ErrorCode.UNEXPECTED_RESPONSE))) {
                // the real row form: its labels only, the answer itself is in .stdout
                record(read.stem(), "unrecognized answer, rows: " + ManageTagsLiveTestSupport
                    .rowLabels(attempt.failure().flatMap(ToolError::excerpt)));
                return Optional.of(ActiveTag.UNRECOGNIZED);
            }
            return attempt.value().map(tag -> ActiveTag.of(tag, in.tag()));
        }

        @Override
        public Answer run(Step step) throws IOException {
            if (step.action() instanceof ManageTagsLiveTestSupport.Unlisted unlisted) {
                Attempt<CliResult> attempt = attempt(step, () -> {
                    cli.checkAvailable(connection.server);
                    return cli.run(connection.server, connection.username, connection.password,
                        unlisted.command(), connection.server.cliExportTimeout(),
                        "cliExportTimeout", connection.guard, adminPermit);
                });
                return attempt.value().map(this::answer).orElseGet(() ->
                    new Answer(attempt.error().map(ErrorCode::name), false));
            }
            Attempt<Boolean> attempt = attempt(step, () -> {
                switch (step.action()) {
                    case ManageTagsLiveTestSupport.TagGroup tag ->
                        tags.tag(tag.tag(), tag.group(), tag.owner(), tagPermit);
                    case ManageTagsLiveTestSupport.Removal removal ->
                        admin.removeTag(removal.kind(), removal.tag(), removal.scope(),
                            adminPermit);
                    case ManageTagsLiveTestSupport.SetActive set ->
                        admin.setActiveTag(set.user(), set.tag(), adminPermit);
                    case ManageTagsLiveTestSupport.RemoveActive remove ->
                        admin.removeActiveTag(remove.user(), adminPermit);
                    default -> throw new IllegalStateException(step.id() + " is not runnable");
                }
                return Boolean.TRUE;
            });
            if (attempt.failure().isPresent()
                && (step.action() instanceof ManageTagsLiveTestSupport.SetActive
                    || step.action() instanceof ManageTagsLiveTestSupport.RemoveActive)) {
                Optional<String> refusal = ManageTagsLiveTestSupport.refusal(
                    attempt.failure().get(), attempt.runs(), in);
                if (refusal.isPresent()) {
                    return new Answer(refusal, true);
                }
            }
            return new Answer(attempt.error().map(ErrorCode::name), false);
        }

        /**
         * The answer of an unlisted form: a login failure or cut output is an error, a refusal
         * INUBIT's answer.
         */
        private Answer answer(CliResult result) {
            if (CliOutputClassifier.isLoginFailure(result)) {
                return new Answer(Optional.of(ErrorCode.AUTH_FAILED.name()), false);
            }
            if (result.truncated()) {
                return new Answer(Optional.of("TRUNCATED"), false);
            }
            if (result.exitCode() != 0) {
                return new Answer(Optional.of("exit " + result.exitCode()), true);
            }
            return classifier.parse(result).nokMessages().isEmpty() ? Answer.DONE
                : new Answer(Optional.of("n-NOK"), true);
        }

        @Override
        public void record(String stem, String text) throws IOException {
            print(ManageTagsLiveTestSupport.recordState(files, directory, stem, text));
        }

        /** One command that starts at most one StartCLI run, recorded as {@code recorded}. */
        private <T> Attempt<T> attempt(Case recorded, Supplier<T> call) throws IOException {
            int before = connection.recorder.runs().size();
            Optional<T> value = Optional.empty();
            Optional<ToolError> failure = Optional.empty();
            try {
                value = Optional.of(call.get());
            } catch (ToolErrorException e) {
                failure = Optional.of(e.error());
            }
            List<Run> all = connection.recorder.runs();
            List<Run> runs = List.copyOf(all.subList(before, all.size()));
            print(ManageTagsLiveTestSupport.recordCli(files, directory, recorded, runs,
                failure.map(ToolError::code)));
            return new Attempt<>(value, failure, runs);
        }

        /** A derived read; a parse failure makes it unknown, with its code in the line. */
        private static <T> Optional<T> parsed(Read read, Supplier<T> parse) {
            try {
                return Optional.of(parse.get());
            } catch (ToolErrorException e) {
                print(ManageTagsLiveTestSupport.line(read.id(), "not readable: "
                    + e.error().code()));
                return Optional.empty();
            } catch (IllegalArgumentException e) {
                print(ManageTagsLiveTestSupport.line(read.id(), "not readable: "
                    + e.getClass().getSimpleName()));
                return Optional.empty();
            }
        }

        private static void print(String line) {
            System.err.println(line);
        }
    }

    /** A command: its value, or the error that ended it; and the StartCLI runs it started. */
    private record Attempt<T>(Optional<T> value, Optional<ToolError> failure, List<Run> runs) {

        Optional<ErrorCode> error() {
            return failure.map(ToolError::code);
        }
    }
}
