package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.config.CliPaths;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LogPresence;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.DownloadedLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Downloads {@code trace.log} of one server through StartCLI (feature 021, research D-1, D-7,
 * D-14): {@code trace --download '<private dir>/trace.log'} ({@link CliCommand#traceDownload})
 * through {@link CliRunner#run} with the server's {@link CredentialGuard}, the budget named
 * {@code cliExportTimeout}.
 *
 * <ul>
 *   <li>Before anything is created or launched: StartCLI must be available, the temporary root
 *       must be passable to StartCLI ({@link CliPaths}) and the credentials complete, as for the
 *       exports ({@code CliExportRunner}).
 *   <li>The private directory {@code inubit-mcp-log-<profile>-<pid>-<random>} below
 *       {@code java.io.tmpdir} is owner-only and registered with the {@link CliResources}, so
 *       that a shutdown deletes it as well; on any failure, timeout or interruption it is deleted
 *       before the call ends, after a success when the returned {@link DownloadedLog} is closed.
 *   <li>Success: no failure of {@link CliOutputClassifier#classifyTraceDownload}, exactly one
 *       stdout line {@value #CONFIRMATION_PATTERN} (StartCLI prints it without an {@code n-OK}
 *       prefix) and a regular file at the requested path. The path in StartCLI's message is
 *       never used. Anything else is {@code UNEXPECTED_RESPONSE}.
 * </ul>
 */
public final class CliTraceRunner {

    static final String FILE_NAME = "trace.log";
    static final String CONFIRMATION_PATTERN = "^Trace data \\(\\S+ MB\\) stored to: .+$";
    private static final Pattern CONFIRMATION = Pattern.compile(CONFIRMATION_PATTERN);

    private final EffectiveNodeConfig server;
    private final NodeCredentials credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final CliOutputClassifier classifier;
    private final Path tempRoot;

    /** Downloads into the system's temporary directory ({@code java.io.tmpdir}). */
    public CliTraceRunner(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliOutputClassifier classifier) {
        this(server, credentials, guard, runner, classifier,
            Path.of(System.getProperty("java.io.tmpdir")));
    }

    CliTraceRunner(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliOutputClassifier classifier, Path tempRoot) {
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        // on Windows its real path, 8.3 short names expanded (feature 013, research R-3)
        this.tempRoot = CliPaths.forCommand(Objects.requireNonNull(tempRoot, "tempRoot"),
            runner.windows());
    }

    /**
     * Downloads {@code trace.log} into a new private directory; StartCLI may run at most
     * {@code remaining}. The caller closes the result, which deletes the directory.
     *
     * @throws ToolErrorException with the server id: {@code CLI_UNAVAILABLE},
     *     {@code AUTH_FAILED} (no credentials, nothing launched), {@code TIMEOUT} naming
     *     {@code cliExportTimeout}, the classified StartCLI failure ({@code NOT_FOUND},
     *     {@code FORBIDDEN}, {@code AUTH_FAILED}, {@code TLS_ERROR}, …) or
     *     {@code UNEXPECTED_RESPONSE}; the private directory is deleted then
     */
    public DownloadedLog download(Duration remaining) {
        Objects.requireNonNull(remaining, "remaining");
        checkPreconditions();
        // announced before the directory exists, so that a concurrent shutdown deletes it
        CliResources.Reservation reservation;
        try {
            reservation = runner.resources().reserve();
        } catch (ToolErrorException e) {
            throw new ToolErrorException(e.error().withNode(server.id()));
        }
        Path directory;
        try {
            directory = createDirectory();
        } catch (RuntimeException e) {
            reservation.close();
            throw e;
        }
        CliResources.Registration registration;
        try {
            registration = reservation.directory(directory);
        } catch (ToolErrorException e) {
            // close() gave up waiting for this directory; it was deleted
            throw new ToolErrorException(e.error().withNode(server.id()));
        }
        boolean handedOver = false;
        try {
            Path file = directory.resolve(FILE_NAME);
            CliResult result = runner.run(server, credentials.username().orElseThrow().value(),
                credentials.password().orElseThrow().value(),
                CliCommand.traceDownload(file, runner.windows()), remaining, "cliExportTimeout",
                guard);
            if (classifier.classifyTraceDownload(result) instanceof CliOutcome.Failure failure) {
                ToolError error = failure.error();
                throw new ToolErrorException(error.node().isPresent() ? error
                    : error.withNode(server.id()));
            }
            if (result.stdout().lines().filter(line -> CONFIRMATION.matcher(line).matches())
                .count() != 1) {
                throw unconfirmed("StartCLI printed no single \"Trace data (… MB) stored to: …\""
                    + " line");
            }
            DownloadedLog log = new DownloadedLog(file, regularFileSize(file), Optional.empty(),
                LogPresence.PRESENT, registration::close);
            handedOver = true;
            return log;
        } finally {
            if (!handedOver) {
                registration.close();
            }
        }
    }

    private long regularFileSize(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            if (attributes.isRegularFile()) {
                return attributes.size();
            }
        } catch (IOException e) {
            // reported below
        }
        throw unconfirmed("StartCLI confirmed the download but left no file at the requested"
            + " path");
    }

    private void checkPreconditions() {
        runner.checkAvailable(server);
        if (!CliPaths.exportRootUsable(tempRoot, runner.windows())) {
            throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
                "The temporary directory " + tempRoot + " cannot be passed to StartCLI on "
                    + server.id(),
                "Downloaded logs are written below java.io.tmpdir, whose path is part of the"
                    + " StartCLI command and must match "
                    + CliPaths.rule(runner.windows()).pattern(),
                "Start the MCP server with -Djava.io.tmpdir=<"
                    + CliPaths.tmpdirHint(runner.windows()) + "> (e.g. via JAVA_TOOL_OPTIONS)")
                .withNode(server.id()));
        }
        if (!credentials.complete()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                "No INUBIT credentials are configured for " + server.id()
                    + ", so StartCLI cannot log in",
                "The username or password variable for " + server.id() + " is not set",
                guard.fixCredentials()).withNode(server.id()));
        }
    }

    private Path createDirectory() {
        try {
            // feature 013 (FR-007): owner-only on POSIX, this account and SYSTEM on Windows
            return runner.resources().files().createTempDirectory(tempRoot,
                runner.resources().logPrefix());
        } catch (IOException | UnsupportedOperationException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "No private temporary directory for the log download on " + server.id() + " ("
                    + e.getClass().getSimpleName() + ")",
                CliResources.NO_PRIVATE_ACCESS,
                CliResources.privateDirectoryStep(tempRoot, runner.windows()))
                .withNode(server.id()));
        }
    }

    private ToolErrorException unconfirmed(String likelyCause) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
            "download_server_log got an unexpected answer from " + server.id()
                + "; StartCLI did not confirm the trace download",
            likelyCause,
            "Download the log in the Workbench; report the INUBIT version")
            .withNode(server.id()));
    }
}
