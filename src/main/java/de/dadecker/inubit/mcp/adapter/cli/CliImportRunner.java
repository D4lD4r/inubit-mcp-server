package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.config.CliPaths;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ImportPort.Mode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The imports of one server through StartCLI (feature 004, research D-8; Constitution I, II):
 * {@code import --importFile '<tmp>/import.zip' --importWorkflow [--importWorkflowActive |
 * --importWorkflowInactive] | --importModule --importUser '<owner>' --returnProtocol} — the
 * owner is a user or a user group alike: INUBIT 8.1 refuses {@code --importUserGroup} with
 * "Missing user or group!" and takes a user-group owner with {@code --importUser} (research
 * D-26).
 *
 * <ul>
 *   <li>The owner must match {@link CliCommand#VALUE}; the CLI, the temporary directory and the
 *       credentials are checked before anything is written ({@code INVALID_INPUT},
 *       {@code CLI_UNAVAILABLE}, {@code AUTH_FAILED}).
 *   <li>The archive holds secret values. It is written {@code rw-------} into a fresh owner-only
 *       temporary directory below {@code java.io.tmpdir} (the rules of the exports, registered
 *       with {@link CliResources}) that is deleted on every path.
 *   <li>The node's {@code cliExportTimeout} applies; a {@code TIMEOUT} names it (the import may
 *       nevertheless have happened). Every run goes through the node's {@link CredentialGuard}.
 *   <li>A failure reported by StartCLI ({@code n-NOK}, exit code, unreadable protocol) is
 *       {@code IMPORT_FAILED} with the scrubbed reason; success is exit code 0 with a protocol
 *       ({@link ImportProtocolParser}).
 *   <li>Feature 005 (research D-1): {@link #importRepository} imports repository files into
 *       {@code /Root/<owner>} ({@code --importRepositoryPath}); it has no protocol, success is
 *       {@code n-OK: Imported successfully}.
 * </ul>
 */
public final class CliImportRunner {

    static final String FILE = "import.zip";
    /** The result line of a repository import (research D-1; there is no protocol). */
    static final String REPOSITORY_SUCCESS = "Imported successfully";
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_DIRECTORY =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_FILE =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private final EffectiveNodeConfig server;
    private final NodeCredentials credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final CliOutputClassifier classifier;
    private final Path tempRoot;

    /** Imports through the system's temporary directory ({@code java.io.tmpdir}). */
    public CliImportRunner(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliOutputClassifier classifier) {
        this(server, credentials, guard, runner, classifier,
            Path.of(System.getProperty("java.io.tmpdir")));
    }

    CliImportRunner(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliOutputClassifier classifier, Path tempRoot) {
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.tempRoot = Objects.requireNonNull(tempRoot, "tempRoot");
    }

    /**
     * The checks before an import: CLI, temporary directory and credentials.
     *
     * @throws ToolErrorException {@code CLI_UNAVAILABLE} or {@code AUTH_FAILED}
     */
    public void checkAvailable() {
        runner.checkAvailable(server);
        if (!CliPaths.exportRootUsable(tempRoot)) {
            throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
                "The temporary directory " + tempRoot + " cannot be passed to StartCLI on "
                    + server.id(),
                "Import files are written below java.io.tmpdir, whose path is part of the"
                    + " StartCLI command and must match " + CliPaths.PATH_VALUE.pattern(),
                "Start the MCP server with -Djava.io.tmpdir=<absolute path of letters, digits,"
                    + " _ . - / and spaces>").withNode(server.id()));
        }
        if (!credentials.complete()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                "No INUBIT credentials are configured for " + server.id()
                    + ", so StartCLI cannot log in",
                "The username or password variable for " + server.id() + " is not set",
                guard.fixCredentials()).withNode(server.id()));
        }
    }

    /**
     * Imports {@code archive}; see the class description.
     *
     * @throws ToolErrorException {@code INVALID_INPUT}, {@code CLI_UNAVAILABLE},
     *     {@code AUTH_FAILED} before anything is written; {@code IMPORT_FAILED},
     *     {@code TIMEOUT} after StartCLI ran
     */
    public ImportProtocol importArchive(byte[] archive, Mode mode, String owner) {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(mode, "mode");
        checkOwner(owner);
        checkAvailable();
        return protocol(run(archive, file -> command(file, mode, owner)));
    }

    /**
     * Imports the repository files of {@code archive} into {@code /Root/<owner>} (feature 005,
     * research D-1): {@code import --importFile '<tmp>/import.zip' --importRepositoryPath
     * '/Root/<owner>'}. The archive's entries are relative to that path (built by the archive
     * adapter). StartCLI prints no protocol: success is exit code 0 with
     * {@code n-OK: Imported successfully} and no {@code n-NOK}; the caller verifies the result
     * by a repository export.
     *
     * @throws ToolErrorException {@code INVALID_INPUT}, {@code CLI_UNAVAILABLE},
     *     {@code AUTH_FAILED} before anything is written; {@code IMPORT_FAILED},
     *     {@code TIMEOUT} after StartCLI ran
     */
    public void importRepository(byte[] archive, String owner) {
        Objects.requireNonNull(archive, "archive");
        checkRepositoryImport(owner);
        String root = "/Root/" + owner;
        CliResult result = run(archive, file -> CliCommand.command("import")
            .path("--importFile", file).repositoryPath("--importRepositoryPath", root).build());
        CliOutput output = classifier.parse(result);
        if (result.exitCode() != 0 || !output.nokMessages().isEmpty() || result.truncated()
            || output.okMessages().stream().noneMatch(REPOSITORY_SUCCESS::equals)) {
            throw failed("StartCLI did not confirm the repository import on " + server.id()
                + ": " + reason(result, output));
        }
    }

    /**
     * Everything {@link #importRepository} checks before it writes or launches anything: the
     * owner and its repository root pass the quoting rules, CLI and credentials are usable.
     *
     * @throws ToolErrorException {@code INVALID_INPUT}, {@code CLI_UNAVAILABLE} or
     *     {@code AUTH_FAILED}
     */
    public void checkRepositoryImport(String owner) {
        checkOwner(owner);
        if (!CliCommand.repositoryPathPassable("/Root/" + owner)) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "The owner's repository root cannot be passed to StartCLI safely; nothing was"
                    + " sent",
                "It must match " + CliCommand.REPOSITORY_PATH.pattern(),
                "Use the exact INUBIT owner name").withNode(server.id()));
        }
        checkAvailable();
    }

    private void checkOwner(String owner) {
        if (owner == null || !CliCommand.VALUE.matcher(owner).matches()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "The owner cannot be passed to StartCLI safely; nothing was sent",
                "It must match " + CliCommand.VALUE.pattern(),
                "Use the exact INUBIT owner name").withNode(server.id()));
        }
    }

    /**
     * Writes {@code archive} into a fresh private temporary directory, runs the command for it
     * and deletes the directory on every path.
     */
    private CliResult run(byte[] archive, Function<Path, CliCommand> command) {
        CliResources.Reservation reservation;
        try {
            reservation = runner.resources().reserve();
        } catch (ToolErrorException e) {
            throw new ToolErrorException(e.error().withNode(server.id()));
        }
        Path directory;
        try {
            directory = Files.createTempDirectory(tempRoot, runner.resources().exportPrefix(),
                OWNER_ONLY_DIRECTORY);
        } catch (IOException | UnsupportedOperationException e) {
            reservation.close();
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "No private temporary directory for the import on " + server.id() + " ("
                    + e.getClass().getSimpleName() + "); nothing was sent",
                "The temporary directory is not writable or does not support owner-only"
                    + " permissions",
                "Check java.io.tmpdir of the MCP server").withNode(server.id()));
        } catch (RuntimeException e) {
            reservation.close();
            throw e;
        }
        CliResources.Registration registration;
        try {
            registration = reservation.directory(directory);
        } catch (ToolErrorException e) {
            throw new ToolErrorException(e.error().withNode(server.id()));
        }
        // closing the registration deletes the directory and the archive in it
        try (registration) {
            Path file = directory.resolve(FILE);
            try {
                Files.write(Files.createFile(file, OWNER_ONLY_FILE), archive,
                    StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                    "The import archive cannot be written for " + server.id() + " ("
                        + e.getClass().getSimpleName() + "); nothing was sent",
                    "The temporary directory is not writable", "Check java.io.tmpdir")
                    .withNode(server.id()));
            }
            return runner.run(server, credentials.username().orElseThrow().value(),
                credentials.password().orElseThrow().value(), command.apply(file),
                server.cliExportTimeout(), "cliExportTimeout", guard);
        }
    }

    private static CliCommand command(Path file, Mode mode, String owner) {
        CliCommand.Builder command = CliCommand.command("import").path("--importFile", file);
        switch (mode) {
            case WORKFLOW -> command.flag("--importWorkflow");
            case WORKFLOW_ACTIVE -> command.flag("--importWorkflow")
                .flag("--importWorkflowActive");
            case WORKFLOW_INACTIVE -> command.flag("--importWorkflow")
                .flag("--importWorkflowInactive");
            case MODULE -> command.flag("--importModule");
        }
        command.quoted("--importUser", owner);
        return command.flag("--returnProtocol").build();
    }

    private ImportProtocol protocol(CliResult result) {
        CliOutput output = classifier.parse(result);
        if (result.exitCode() != 0 || !output.nokMessages().isEmpty() || result.truncated()) {
            throw failed("StartCLI reported a failed import on " + server.id() + ": "
                + reason(result, output));
        }
        try {
            return ImportProtocolParser.parse(result.stdout());
        } catch (ToolErrorException e) {
            throw failed(e.error().message() + " (import on " + server.id() + ")");
        }
    }

    private String reason(CliResult result, CliOutput output) {
        if (!output.nokMessages().isEmpty()) {
            return String.join(" / ", output.nokMessages());
        }
        if (classifier.classify(result) instanceof CliOutcome.Failure failure) {
            return failure.error().code() + ": " + failure.error().message();
        }
        return "exit code " + result.exitCode() + ", "
            + (output.okMessages().isEmpty() ? "no result line"
                : String.join(" / ", output.okMessages()));
    }

    private ToolErrorException failed(String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.IMPORT_FAILED, message,
            "INUBIT refused the archive or StartCLI failed; part of it may have been imported",
            "The development tools re-export the scope and roll back from the backup")
            .withNode(server.id()));
    }

    @Override
    public String toString() {
        return "CliImportRunner[" + server.id() + ", timeout "
            + Durations.human(server.cliExportTimeout()) + "]";
    }
}
