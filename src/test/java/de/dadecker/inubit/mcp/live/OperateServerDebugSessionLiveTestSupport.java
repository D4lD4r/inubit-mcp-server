package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.fail;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionParser;
import de.dadecker.inubit.mcp.adapter.cli.UnlistedReadCommand;
import de.dadecker.inubit.mcp.adapter.cli.UnlistedSessionCommand;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.Case;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.CliRead;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.Property;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;

/**
 * The offline parts of {@link OperateServerDebugSessionLiveTest} (feature 022 T054, debug
 * clarification of live check L-3), tested by {@link OperateServerDebugSessionLiveTestSupportTest}:
 * the opt-in, the task folder, the lines of the one session, the session run on the production
 * path, the debug states derived from its transcript and the states line. Lines name states,
 * codes and byte counts, never output.
 */
final class OperateServerDebugSessionLiveTestSupport {

    /** The opt-in of the debug session; the owner sets it after approving this run. */
    static final String APPROVAL_VARIABLE = "INUBIT_LIVE_T054_DEBUG_SESSION";
    /** The only value of {@link #APPROVAL_VARIABLE} that runs the session. */
    static final String APPROVED = "approved";
    /** The directory below {@code INUBIT_LIVE_RECORD_DIR} that receives the case files. */
    static final String TASK_DIRECTORY = "T054-debug-session";

    /** A debug state that no recorded answer shows. */
    static final String UNKNOWN = "unknown";
    /** The state after the session when the read after it was not sent. */
    static final String NOT_READ = "not read";

    /**
     * The lines of the one session, in the order they are sent; {@link CliRunner#runSession}
     * appends {@code exit}.
     */
    static final List<CliSessionCommand> SESSION = Stream.of(UnlistedSessionCommand.DEBUG,
        UnlistedSessionCommand.DEBUG_ON, UnlistedSessionCommand.DEBUG,
        UnlistedSessionCommand.DEBUG_OFF, UnlistedSessionCommand.DEBUG)
        .map(UnlistedSessionCommand::command).toList();
    /** The positions of the three {@code debug} reads in {@link #SESSION}. */
    private static final List<Integer> SESSION_READS = List.of(0, 2, 4);

    /** The session as one recorded case: {@code S1-debug-session.*}. */
    static final Case SESSION_CASE = new SessionCase("S1", "debug-session");
    /** The read after the session: R6 of the read set, in a new StartCLI process. */
    static final CliRead AFTER = new CliRead("R6", "debug", UnlistedReadCommand.DEBUG.command());

    /** As the production 8.1/9.0 sessions ({@code CliSettingsRunner}): the client's options. */
    private static final CliClientOptions OPTIONS =
        new CliClientOptions(true, true, true, Optional.empty());

    private OperateServerDebugSessionLiveTestSupport() {
    }

    /** Skips without {@link #APPROVAL_VARIABLE}; fails unless it is exactly {@link #APPROVED}. */
    static void requireApproval(Map<String, String> environment) {
        String value = environment.get(APPROVAL_VARIABLE);
        Assumptions.assumeTrue(value != null && !value.isBlank(), APPROVAL_VARIABLE + " is not set"
            + " (" + APPROVED + ", only after the owner approved this run); the T054 debug session"
            + " is skipped");
        if (!value.strip().equals(APPROVED)) {
            fail(APPROVAL_VARIABLE + " must be exactly " + APPROVED + " (only after the owner"
                + " approved this run), or unset to skip the T054 debug session");
        }
    }

    /**
     * Runs {@link #SESSION} as one interactive StartCLI session on the production path, as the
     * 8.1 session readers do ({@code CliSettingsRunner}): the client chosen for the node, its
     * availability and pin checked first, the password on stdin, the node's {@code cliTimeout}.
     *
     * @throws ToolErrorException as {@link CliRunner#runSession}; nothing is retried
     */
    static CliResult runSession(CliRunner cli, EffectiveNodeConfig server, String username,
        Secret password, CredentialGuard guard) {
        CliRunner.Selection selection = cli.select(server);
        cli.checkAvailable(selection);
        cli.checkPin(selection);
        return cli.runSession(selection, username, password, OPTIONS, SESSION,
            server.cliTimeout(), CliRunner.MAX_OUTPUT_BYTES, guard);
    }

    /**
     * The debug states the three {@code debug} reads of the session show, in order; each
     * {@link #UNKNOWN} unless its block is exactly one of the answers R6 recorded. All three are
     * unknown if the transcript does not split into one block per line
     * ({@link CliSessionParser#STANDARD}).
     */
    static List<String> sessionStates(String stdout) {
        List<List<String>> blocks;
        try {
            blocks = CliSessionParser.STANDARD.blocks(stdout, SESSION);
        } catch (ToolErrorException e) {
            return List.of(UNKNOWN, UNKNOWN, UNKNOWN);
        }
        return SESSION_READS.stream().map(index -> state(blocks.get(index))).toList();
    }

    /** The debug state of one {@code debug} answer: {@code on}, {@code off} or unknown. */
    static String state(List<String> outputLines) {
        return OperateServerWritesLiveTestSupport.parseCli(AFTER.id(), outputLines).values()
            .getOrDefault(Property.DEBUG, UNKNOWN);
    }

    /**
     * Writes the derived states as {@code debug-states.state}, owner-only.
     *
     * @return its line: the derived states only
     */
    static String recordStates(PrivateFileSystem files, Path directory, NodeId node,
        List<String> inSession, String after) throws IOException {
        String text = "in session: " + String.join(" → ", inSession) + "; after session: "
            + after;
        OperateServerReadsLiveTestSupport.write(files, directory.resolve("debug-states.state"),
            (text + "\n").getBytes(StandardCharsets.UTF_8));
        return "[live] T054 debug states on " + node + ": " + text;
    }

    /** The session as a case of {@link OperateServerReadsLiveTestSupport#recordCli}. */
    private record SessionCase(String id, String slug) implements Case {

        @Override
        public String form() {
            return "session " + Stream.concat(SESSION.stream(), Stream.of(CliSessionCommand.EXIT))
                .map(CliSessionCommand::text).collect(Collectors.joining("; "));
        }
    }
}
