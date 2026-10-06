package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.config.CliPaths;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The inventory exports of one server through StartCLI (research R-11; Constitution I, II):
 * the version history of a diagram group and the module index.
 *
 * <ul>
 *   <li>History: {@code export --exportWorkflowUser '<owner>' --exportWorkflowType '<type>'
 *       --exportWorkflowGroup '<group>' --includeHistory --exportFile '<tmp>/history.zip'} →
 *       {@code versionHistory.xml}. Modules: {@code export --exportModule ''
 *       --exportModuleGroup '' --exportModuleUser '<owner>' --exportFile '<tmp>/modules.zip'} →
 *       {@code module/module.xml}. The literal {@code ''} is part of the single
 *       {@code --execCommand} argument.
 *   <li>Owner, type and group must match {@link CliCommand#VALUE} and are single-quoted; any
 *       other value (e.g. one containing {@code '}) is {@code UNEXPECTED_RESPONSE} ("not
 *       supported by CLI quoting") before anything is created or launched. So are a missing CLI
 *       ({@code CLI_UNAVAILABLE}) and missing credentials ({@code AUTH_FAILED}).
 *   <li>Each export runs in a fresh owner-only ({@code rwx------}) temporary directory
 *       {@code inubit-mcp-export-<profile>-<pid>-<random>} below {@code java.io.tmpdir}. It is
 *       registered with the {@link CliResources}, so that a shutdown deletes it as well, and
 *       deleted recursively when its registration is closed — also on failure, timeout or
 *       interruption — while it is still registered (Phase 7 review P1). The
 *       export may contain module configurations; they are never read. A temporary directory
 *       whose path StartCLI cannot take ({@link CliPaths}) is {@code CLI_UNAVAILABLE}.
 *   <li>A {@code TIMEOUT} names {@code cliExportTimeout} in its next step.
 *   <li>The timeout is the server's {@code cliExportTimeout}; every run goes through the
 *       server's {@link CredentialGuard} (shared with REST).
 *   <li>Success needs StartCLI's success classification and an {@code n-OK} message ending in
 *       {@value #SUCCESS_SUFFIX}; then only the requested allow-listed entry
 *       ({@link #ALLOWED_ENTRIES}) is read, at most {@link #MAX_ENTRY_BYTES} bytes.
 *   <li>Feature 003 (research D-8): {@link #exportWorkflowGroup} and {@link #exportModule} return
 *       the whole archive (at most {@link #MAX_ARCHIVE_BYTES}) for the workspace; their values
 *       are checked first ({@code INVALID_INPUT}, a blank diagram group included), and a missing
 *       group or module is {@code NOT_FOUND} ({@link CliOutputClassifier}).
 * </ul>
 */
public final class CliExportRunner {

    public static final String HISTORY_ENTRY = "versionHistory.xml";
    public static final String MODULE_INDEX_ENTRY = "module/module.xml";
    /** The only ZIP entries an export may be read for (research R-11). */
    static final Set<String> ALLOWED_ENTRIES =
        Set.of(HISTORY_ENTRY, MODULE_INDEX_ENTRY, "workflow/workflow.xml");
    /** Upper bound of an uncompressed entry. */
    public static final long MAX_ENTRY_BYTES = 64L << 20;
    /** Upper bound of a whole artifact export archive (research D-8). */
    public static final long MAX_ARCHIVE_BYTES = 128L << 20;
    static final String SUCCESS_SUFFIX = "exported successfully.";

    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    private final EffectiveNodeConfig server;
    private final NodeCredentials credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final CliOutputClassifier classifier;
    private final Path tempRoot;
    private final long maxArchiveBytes;

    /** Exports into the system's temporary directory ({@code java.io.tmpdir}). */
    public CliExportRunner(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliOutputClassifier classifier) {
        this(server, credentials, guard, runner, classifier,
            Path.of(System.getProperty("java.io.tmpdir")), MAX_ARCHIVE_BYTES);
    }

    CliExportRunner(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliOutputClassifier classifier, Path tempRoot,
        long maxArchiveBytes) {
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.tempRoot = Objects.requireNonNull(tempRoot, "tempRoot");
        this.maxArchiveBytes = maxArchiveBytes;
    }

    CliExportRunner(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliOutputClassifier classifier, Path tempRoot) {
        this(server, credentials, guard, runner, classifier, tempRoot, MAX_ARCHIVE_BYTES);
    }

    /**
     * {@code versionHistory.xml} of the workflows of {@code group} (type {@code type}) owned by
     * {@code owner}, and of the modules they use.
     *
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE}, {@code CLI_UNAVAILABLE},
     *     {@code AUTH_FAILED}, {@code TIMEOUT} or the classified StartCLI failure, with the
     *     server id
     */
    public byte[] exportHistory(String owner, String type, String group) {
        checkHistoryExport(owner, type, group);
        return export("history.zip", file -> readEntry(server.id(), file, HISTORY_ENTRY),
            file -> CliCommand.command("export")
            .quoted("--exportWorkflowUser", owner)
            .quoted("--exportWorkflowType", type)
            .quoted("--exportWorkflowGroup", group)
            .flag("--includeHistory")
            .path("--exportFile", file)
            .build());
    }

    /**
     * {@code module/module.xml} of all modules owned by {@code owner}.
     *
     * @throws ToolErrorException as {@link #exportHistory}
     */
    public byte[] exportModules(String owner) {
        checkModuleExport(owner);
        return export("modules.zip", file -> readEntry(server.id(), file, MODULE_INDEX_ENTRY),
            file -> CliCommand.command("export")
            .emptyQuoted("--exportModule")
            .emptyQuoted("--exportModuleGroup")
            .quoted("--exportModuleUser", owner)
            .path("--exportFile", file)
            .build());
    }

    /**
     * The whole export archive of the technical workflows of {@code diagramGroup} owned by
     * {@code owner} (feature 003, research D-8): {@code export --exportWorkflowUser '<owner>'
     * --exportWorkflowType 'technical' --exportWorkflowGroup '<group>' --exportFile
     * '<tmp>/export.zip'}. Only technical workflows, always (clarification 2).
     *
     * @throws ToolErrorException {@code INVALID_INPUT} for a blank group (StartCLI would export
     *     all groups) or a value StartCLI quoting cannot carry, before anything is launched;
     *     {@code NOT_FOUND}, {@code TIMEOUT} (naming {@code cliExportTimeout}),
     *     {@code UNEXPECTED_RESPONSE} (also above {@link #MAX_ARCHIVE_BYTES}), {@code AUTH_FAILED},
     *     {@code CLI_UNAVAILABLE}
     */
    public byte[] exportWorkflowGroup(String owner, String diagramGroup) {
        checkWorkflowGroupExport(owner, diagramGroup);
        return export("export.zip", this::readArchive, file -> CliCommand.command("export")
            .quoted("--exportWorkflowUser", owner)
            .quoted("--exportWorkflowType", "technical")
            .quoted("--exportWorkflowGroup", diagramGroup)
            .path("--exportFile", file)
            .build());
    }

    /**
     * The module-only export archive of module {@code name} of plugin type {@code pluginType}
     * owned by {@code owner}: {@code export --exportModule '<name>' --exportModuleGroup
     * '<plugin type>' --exportModuleUser '<owner>' --exportFile '<tmp>/module.zip'}.
     *
     * @throws ToolErrorException as {@link #exportWorkflowGroup}
     */
    public byte[] exportModule(String owner, String pluginType, String name) {
        checkModuleExport(owner, pluginType, name);
        return export("module.zip", this::readArchive, file -> CliCommand.command("export")
            .quoted("--exportModule", name)
            .quoted("--exportModuleGroup", pluginType)
            .quoted("--exportModuleUser", owner)
            .path("--exportFile", file)
            .build());
    }

    private void quotable(String label, String value) {
        if (value == null || !CliCommand.VALUE.matcher(value).matches()) {
            throw invalid(label + " " + Names.quote(value) + " is not supported by StartCLI"
                + " quoting", "StartCLI receives values inside single quotes; only values"
                + " matching " + CliCommand.VALUE.pattern() + " can be passed safely");
        }
    }

    private ToolErrorException invalid(String message, String likelyCause) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message,
            likelyCause, "Use the exact INUBIT name (letters, digits, _ . - and spaces); rename"
                + " the artifact in INUBIT if it should be exportable").withNode(server.id()));
    }

    /** The export file as a whole, at most {@code maxArchiveBytes}. */
    private byte[] readArchive(Path file) {
        try {
            if (Files.size(file) > maxArchiveBytes) {
                throw unexpected("The export of " + server.id() + " is too large (more than "
                    + maxArchiveBytes + " bytes)");
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw unexpected("The export file of " + server.id() + " is not readable ("
                + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Everything {@link #exportHistory} checks before it creates or launches anything: the CLI
     * (and the temporary directory) is usable, credentials exist, and the values pass the quoting
     * rule. Lets a caller do further preparation (e.g. a REST login) only for exports that can
     * run.
     *
     * @throws ToolErrorException {@code CLI_UNAVAILABLE}, {@code AUTH_FAILED} or
     *     {@code UNEXPECTED_RESPONSE}, with the server id
     */
    public void checkHistoryExport(String owner, String type, String group) {
        checkPreconditions();
        checkQuotable("inventory.owner", owner);
        checkQuotable("Diagram type", type);
        checkQuotable("Diagram group", group);
    }

    /** The checks of {@link #exportModules}; see {@link #checkHistoryExport}. */
    public void checkModuleExport(String owner) {
        checkPreconditions();
        checkQuotable("inventory.owner", owner);
    }

    /** The checks of {@link #exportWorkflowGroup}; see {@link #checkHistoryExport}. */
    public void checkWorkflowGroupExport(String owner, String diagramGroup) {
        if (diagramGroup == null || diagramGroup.isBlank()) {
            throw invalid("The diagram group must not be empty",
                "StartCLI treats an empty group as all diagram groups of the owner");
        }
        quotable("Owner", owner);
        quotable("Diagram group", diagramGroup);
        checkPreconditions();
    }

    /** The checks of {@link #exportModule}; see {@link #checkHistoryExport}. */
    public void checkModuleExport(String owner, String pluginType, String name) {
        if (name == null || name.isBlank() || pluginType == null || pluginType.isBlank()) {
            throw invalid("The module name and plugin type must not be empty",
                "StartCLI treats an empty module or module group as all");
        }
        quotable("Owner", owner);
        quotable("Plugin type", pluginType);
        quotable("Module", name);
        checkPreconditions();
    }

    private void checkPreconditions() {
        runner.checkAvailable(server);
        if (!CliPaths.exportRootUsable(tempRoot)) {
            throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
                "The temporary directory " + tempRoot + " cannot be passed to StartCLI on "
                    + server.id(),
                "Export files are written below java.io.tmpdir, whose path is part of the"
                    + " StartCLI command and must match " + CliPaths.PATH_VALUE.pattern(),
                "Start the MCP server with -Djava.io.tmpdir=<absolute path of letters, digits,"
                    + " _ . - / and spaces> (e.g. via JAVA_TOOL_OPTIONS)")
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

    private byte[] export(String fileName, Function<Path, byte[]> reader,
        Function<Path, CliCommand> command) {
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
            // close() gave up waiting for this directory; it was deleted (US4 R1)
            throw new ToolErrorException(e.error().withNode(server.id()));
        }
        // closing the registration deletes the directory while it is still registered (P1)
        try (registration) {
            Path file = directory.resolve(fileName);
            CliResult result = runner.run(server, credentials.username().orElseThrow().value(),
                credentials.password().orElseThrow().value(), command.apply(file),
                server.cliExportTimeout(), "cliExportTimeout", guard);
            if (classifier.classify(result) instanceof CliOutcome.Failure failure) {
                ToolError error = failure.error();
                throw new ToolErrorException(error.node().isPresent() ? error
                    : error.withNode(server.id()));
            }
            List<String> ok = classifier.parse(result).okMessages();
            if (ok.stream().noneMatch(text -> text.endsWith(SUCCESS_SUFFIX))) {
                throw unexpected("StartCLI did not confirm the export on " + server.id()
                    + " (" + String.join(" / ", ok) + ")");
            }
            if (!Files.isRegularFile(file)) {
                throw unexpected("StartCLI reported a successful export on " + server.id()
                    + " but wrote no export file");
            }
            return reader.apply(file);
        }
    }

    private Path createDirectory() {
        try {
            return Files.createTempDirectory(tempRoot, runner.resources().exportPrefix(),
                OWNER_ONLY);
        } catch (IOException | UnsupportedOperationException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "No private temporary directory for the export on " + server.id() + " ("
                    + e.getClass().getSimpleName() + ")",
                "The temporary directory is not writable or does not support owner-only"
                    + " permissions",
                "Check java.io.tmpdir of the MCP server").withNode(server.id()));
        }
    }

    /**
     * The bytes of the allow-listed {@code entry} of the ZIP {@code zip}.
     *
     * @throws IllegalArgumentException if {@code entry} is not allow-listed (programming error)
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} if the ZIP is unreadable, lacks the
     *     entry, or the entry exceeds {@link #MAX_ENTRY_BYTES}
     */
    static byte[] readEntry(NodeId server, Path zip, String entry) {
        return readEntry(server, zip, entry, MAX_ENTRY_BYTES);
    }

    /** As {@link #readEntry(NodeId, Path, String)} with the size cap {@code maxBytes}. */
    static byte[] readEntry(NodeId server, Path zip, String entry, long maxBytes) {
        if (!ALLOWED_ENTRIES.contains(entry)) {
            throw new IllegalArgumentException("ZIP entry " + entry + " is not allow-listed");
        }
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            ZipEntry found = archive.getEntry(entry);
            if (found == null || found.isDirectory()) {
                throw unexpected(server, "The export of " + server + " has no " + entry);
            }
            try (InputStream in = archive.getInputStream(found)) {
                return bounded(server, in, entry, maxBytes);
            }
        } catch (IOException e) {
            throw unexpected(server, "The export of " + server + " is not a readable ZIP"
                + " archive (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static byte[] bounded(NodeId server, InputStream in, String entry, long maxBytes)
        throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            total += n;
            if (total > maxBytes) {
                throw unexpected(server, entry + " of the export of " + server
                    + " is too large (more than " + maxBytes + " bytes)");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Owner, type and group are inserted into {@code --execCommand} (research R-11): only values
     * of {@link CliCommand#VALUE} are single-quoted and passed.
     */
    private void checkQuotable(String label, String value) {
        if (value == null || !CliCommand.VALUE.matcher(value).matches()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
                label + " " + Names.quote(value) + " is not supported by CLI quoting",
                "StartCLI receives values inside single quotes; only values matching "
                    + CliCommand.VALUE.pattern() + " can be passed safely",
                "Look the history up in the Workbench; rename the group if it should be"
                    + " exportable").withNode(server.id()));
        }
    }

    private ToolErrorException unexpected(String message) {
        return unexpected(server.id(), message);
    }

    private static ToolErrorException unexpected(NodeId server, String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE, message,
            "StartCLI or the export format behaved differently than recorded for INUBIT 8.1",
            "Check the INUBIT client version (cliHome) and retry; see the MCP server's log")
            .withNode(server));
    }

    @Override
    public String toString() {
        return "CliExportRunner[" + server.id() + ", timeout "
            + Durations.human(server.cliExportTimeout()) + "]";
    }
}
