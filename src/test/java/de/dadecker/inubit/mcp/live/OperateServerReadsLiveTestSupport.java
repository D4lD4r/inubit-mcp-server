package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.fail;

import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.cli.UnlistedReadCommand;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.MediaRange;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.Run;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assumptions;

/**
 * The offline parts of {@link OperateServerReadsLiveTest} (feature 022 T054, read set of the live
 * checks L-2 and L-3), tested by {@link OperateServerReadsLiveTestSupportTest}: the nine reads
 * R1–R9 in their order, the opt-in and record directory variables, the owner-only task directory,
 * the 8.1 gate, the case files of each read and the summary lines. Failure messages and summary
 * lines name reads, codes and byte counts, never output.
 */
final class OperateServerReadsLiveTestSupport {

    /** The opt-in of the read set; the owner sets it after approving this run. */
    static final String APPROVAL_VARIABLE = "INUBIT_LIVE_T054_READS";
    /** The only value of {@link #APPROVAL_VARIABLE} that runs the reads. */
    static final String APPROVED = "approved";
    /** The directory below {@code INUBIT_LIVE_RECORD_DIR} that receives the case files. */
    static final String TASK_DIRECTORY = "T054";

    /** Every status but 2xx: a REST read records whatever INUBIT answers, never maps it. */
    private static final int[] ANY_STATUS =
        IntStream.range(100, 600).filter(status -> status < 200 || status >= 300).toArray();

    /** One read of the set; its case files are {@code <id>-<slug>.*}. */
    sealed interface Read permits CliRead, RestRead {

        String id();

        String slug();

        /** The command line or {@code GET <path>}: fixed text, never output. */
        String form();

        default String stem() {
            return id() + "-" + slug();
        }
    }

    /** A StartCLI run of {@code command} through the production {@code CliRunner.run}. */
    record CliRead(String id, String slug, CliCommand command) implements Read {

        @Override
        public String form() {
            return command.commandLine();
        }
    }

    /**
     * A {@code GET} of {@code endpoint} through the production REST client, with the node's
     * credentials or (as the health read of {@code /ready}) without, asking for {@code range} as
     * the production reader of the endpoint does.
     */
    record RestRead(String id, String slug, RestEndpoint endpoint, boolean authenticated,
        MediaRange range) implements Read {

        @Override
        public String form() {
            return "GET " + endpoint.template();
        }

        RestResponse get(InubitHttpClient client) {
            return authenticated
                ? client.get(endpoint, List.of(), Map.of(), range, ANY_STATUS)
                : client.getWithoutCredentials(endpoint, List.of(), Map.of(), range, ANY_STATUS);
        }
    }

    /**
     * The owner-approved read set, in the order it runs: R1 and R2 through the production
     * factories, R4–R7 through {@link UnlistedReadCommand} (deliberately outside the allowlist).
     */
    static final List<Read> READS = List.of(
        new CliRead("R1", "maintenance-s", CliCommand.maintenanceState()),
        new CliRead("R2", "maintenance", CliCommand.maintenance()),
        new RestRead("R3", "get-ready", RestEndpoint.READY, false, MediaRange.JSON),
        new CliRead("R4", "trace", UnlistedReadCommand.TRACE.command()),
        new CliRead("R5", "trace-help", UnlistedReadCommand.TRACE_HELP.command()),
        new CliRead("R6", "debug", UnlistedReadCommand.DEBUG.command()),
        new CliRead("R7", "debug-help", UnlistedReadCommand.DEBUG_HELP.command()),
        new RestRead("R8", "get-system-info", RestEndpoint.SYSTEM_INFO, true, MediaRange.XML),
        new RestRead("R9", "get-configurations", RestEndpoint.CONFIGURATIONS, true,
            MediaRange.JSON));

    private OperateServerReadsLiveTestSupport() {
    }

    /** Skips without {@link #APPROVAL_VARIABLE}; fails unless it is exactly {@link #APPROVED}. */
    static void requireApproval(Map<String, String> environment) {
        String value = environment.get(APPROVAL_VARIABLE);
        Assumptions.assumeTrue(value != null && !value.isBlank(), APPROVAL_VARIABLE + " is not set"
            + " (" + APPROVED + ", only after the owner approved this run); the T054 read set is"
            + " skipped");
        if (!value.strip().equals(APPROVED)) {
            fail(APPROVAL_VARIABLE + " must be exactly " + APPROVED + " (only after the owner"
                + " approved this run), or unset to skip the T054 read set");
        }
    }

    /**
     * {@code INUBIT_LIVE_RECORD_DIR} as a real path; skips without it. Refused as
     * {@link QueueEntryLiveTestSupport#privateDirectory}: relative, with {@code ..} segments,
     * inside {@code repository} or inside any git working tree.
     */
    static Path recordDirectory(Map<String, String> environment, Path repository)
        throws IOException {
        String variable = QueueEntryLiveTestSupport.RECORD_DIR_VARIABLE;
        String value = environment.get(variable);
        Assumptions.assumeTrue(value != null && !value.isBlank(), variable + " is not set (a"
            + " private directory outside every repository); the T054 read set is skipped");
        return QueueEntryLiveTestSupport.privateDirectory(variable, value, repository,
            "the recording holds raw StartCLI output and REST bodies, configuration values"
                + " included");
    }

    /**
     * Creates {@code recordDirectory} (owner-only where missing) and below it the new owner-only
     * {@link #TASK_DIRECTORY}; an existing one is refused, so no earlier recording is
     * overwritten. Without POSIX permissions or Windows access lists nothing is created.
     */
    /** Refuses a run whose task folder exists already, before anything is contacted. */
    static void requireNoTaskDirectory(Path recordDirectory) {
        if (Files.exists(recordDirectory.resolve(TASK_DIRECTORY), LinkOption.NOFOLLOW_LINKS)) {
            throw new AssertionError(TASK_DIRECTORY + " already exists below "
                + QueueEntryLiveTestSupport.RECORD_DIR_VARIABLE + ": move the earlier recording"
                + " away; nothing was run");
        }
    }

    static Path taskDirectory(PrivateFileSystem files, Path recordDirectory) throws IOException {
        Path directory = recordDirectory.resolve(TASK_DIRECTORY);
        try {
            files.createDirectories(recordDirectory);
            return files.createDirectory(directory);
        } catch (FileAlreadyExistsException e) {
            throw new AssertionError(TASK_DIRECTORY + " already exists below "
                + QueueEntryLiveTestSupport.RECORD_DIR_VARIABLE + ": move the earlier recording"
                + " away; nothing was run", e);
        } catch (UnsupportedOperationException e) {
            throw new AssertionError(QueueEntryLiveTestSupport.RECORD_DIR_VARIABLE + " needs"
                + " owner-only files (POSIX permissions or a Windows access list), which this file"
                + " system does not offer; nothing was run", e);
        }
    }

    /** The version {@code node} reports, if it is INUBIT 8.1; else the test fails before R1. */
    static InubitVersion requireV81(NodeId node, String reported) {
        Optional<InubitVersion> version = InubitVersion.parse(reported);
        if (version.isEmpty() || version.get().major() != 8 || version.get().minor() != 1) {
            fail(node + " reports INUBIT " + reported + "; the T054 read set runs on 8.1 only;"
                + " nothing was run");
        }
        return version.get();
    }

    /**
     * Writes the StartCLI run of {@code read} as {@code <stem>.stdout}, {@code .stderr} and,
     * once it exited, {@code .exit}, and {@code error} as {@code <stem>.error}; each owner-only.
     * The stdin, which carries the password, is never recorded (the {@code ProcessRecorder}
     * does not keep it).
     *
     * @param runs the StartCLI runs the read started: none (refused before the launch) or one
     * @return the summary line
     */
    static String recordCli(PrivateFileSystem files, Path directory, NodeId node, CliRead read,
        List<Run> runs, Optional<ErrorCode> error) throws IOException {
        if (runs.size() > 1) {
            fail(read.id() + " started " + runs.size() + " StartCLI runs, expected one");
        }
        String outcome = "not started";
        if (runs.size() == 1) {
            Run run = runs.get(0);
            byte[] stdout = run.stdout().toByteArray();
            byte[] stderr = run.stderr().toByteArray();
            write(files, directory.resolve(read.stem() + ".stdout"), stdout);
            write(files, directory.resolve(read.stem() + ".stderr"), stderr);
            if (run.exit().isPresent()) {
                write(files, directory.resolve(read.stem() + ".exit"), line(run.exit().get()));
            }
            outcome = "exit " + run.exit().map(String::valueOf).orElse("none") + ", stdout "
                + stdout.length + " bytes, stderr " + stderr.length + " bytes";
        }
        if (error.isPresent()) {
            write(files, directory.resolve(read.stem() + ".error"), line(error.get()));
            outcome += ", error " + error.get();
        }
        return summary(node, read, outcome);
    }

    /** Writes the answer to {@code read} as {@code <stem>.status} and {@code .body}, owner-only. */
    static String recordRest(PrivateFileSystem files, Path directory, NodeId node, RestRead read,
        RestResponse response) throws IOException {
        write(files, directory.resolve(read.stem() + ".status"), line(response.status()));
        write(files, directory.resolve(read.stem() + ".body"), response.body());
        return summary(node, read, "status " + response.status() + ", body "
            + response.body().length + " bytes");
    }

    /** Writes {@code error} of a REST read without answer as {@code <stem>.error}, owner-only. */
    static String recordRestFailure(PrivateFileSystem files, Path directory, NodeId node,
        RestRead read, ErrorCode error) throws IOException {
        write(files, directory.resolve(read.stem() + ".error"), line(error));
        return summary(node, read, "error " + error);
    }

    /** Fails naming the reads that ended in an error and their codes; the reads ran once each. */
    static void assertNoFailure(Map<String, ErrorCode> failed) {
        if (!failed.isEmpty()) {
            fail("T054 reads that ended in an error (each ran once, not retried): "
                + failed.entrySet().stream().map(entry -> entry.getKey() + " " + entry.getValue())
                    .collect(Collectors.joining(", ")));
        }
    }

    private static String summary(NodeId node, Read read, String outcome) {
        return "[live] T054 " + read.id() + " " + read.form() + " on " + node + ": " + outcome;
    }

    private static byte[] line(Object value) {
        return (value + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static void write(PrivateFileSystem files, Path file, byte[] content)
        throws IOException {
        files.createFile(file);
        Files.write(file, content);
    }
}
