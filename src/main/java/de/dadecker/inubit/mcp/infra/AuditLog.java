package de.dadecker.inubit.mcp.infra;

import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The audit log of the write tools (FR-023, research R-14, data-model.md → AuditRecord).
 *
 * <ul>
 *   <li>JSON Lines in {@code <auditDirectory>/audit-YYYY-MM.jsonl} (month of the record's
 *       timestamp, UTC); one compact JSON object per line, fields in the order of the data
 *       model ({@code auditId, timestamp, profile, node, group, capability, step, inputs,
 *       account, outcome, reason, mcpClient}; 002 contracts/mcp-tools-delta.md), absent optional
 *       fields omitted. Line breaks inside values are escaped by JSON, so
 *       a value can never start a new record.
 *   <li>Append-only: each record is written through a {@link FileChannel} opened with
 *       {@code CREATE, WRITE, APPEND}, then {@code force(true)}d before {@link #append}
 *       returns, so that it survives a crash or restart. Nothing is ever rewritten.
 *   <li>Owner-only: the directory is {@code rwx------} and the file {@code rw-------}; both are
 *       created so and tightened if they exist with wider permissions (POSIX only).
 *   <li>Every string value is scrubbed of registered secrets (Constitution II). The inputs
 *       {@value AuditRecord#CONFIRMATION_CODE} and {@value AuditRecord#ISSUED_CONFIRMATION_CODE}
 *       are stored only as {@code sha256:} plus the first 16 hex chars of their SHA-256, which
 *       is enough to correlate a preview with its execution.
 *   <li>Any failure throws {@link UncheckedIOException}, so that the caller fails closed.
 * </ul>
 *
 * <p>Appends are serialized within this process; the file is opened per record, so a month
 * change needs no rollover logic.
 */
public final class AuditLog implements AuditPort {

    /** Opens the channel of an audit file; replaceable in tests. */
    @FunctionalInterface
    interface ChannelOpener {
        FileChannel open(Path file, Set<? extends OpenOption> options,
            FileAttribute<?>... attributes) throws IOException;
    }

    static final int HASH_HEX_CHARS = 16;
    private static final DateTimeFormatter MONTH =
        DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);
    private static final Set<StandardOpenOption> APPEND = Set.of(StandardOpenOption.CREATE,
        StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
        PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
        PosixFilePermissions.fromString("rwx------");
    private static final Set<String> HASHED_INPUTS =
        Set.of(AuditRecord.CONFIRMATION_CODE, AuditRecord.ISSUED_CONFIRMATION_CODE);

    private final Path directory;
    private final SecretScrubber scrubber;
    private final ChannelOpener opener;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ReentrantLock lock = new ReentrantLock();
    private final boolean posix =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    public AuditLog(Path directory, SecretScrubber scrubber) {
        this(directory, scrubber, FileChannel::open);
    }

    AuditLog(Path directory, SecretScrubber scrubber, ChannelOpener opener) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    /**
     * The default {@code auditDirectory} of a profile: {@code <home>/.inubit-mcp/<profile>/audit}
     * (002 research D-7). The 001 directory {@code <home>/.inubit-mcp/audit} is never used.
     *
     * @throws IllegalArgumentException if {@code profile} is no valid profile name, so that no
     *     name can lead out of {@code <home>/.inubit-mcp}
     */
    public static Path defaultDirectory(Path home, String profile) {
        if (!ProfileInfo.isValidName(profile)) {
            throw new IllegalArgumentException("Invalid profile name for the audit directory");
        }
        return home.resolve(".inubit-mcp").resolve(profile).resolve("audit");
    }

    /** The file that records of {@code record}'s month go to. */
    public Path fileOf(AuditRecord record) {
        return directory.resolve("audit-" + MONTH.format(record.timestamp()) + ".jsonl");
    }

    /**
     * Appends {@code record} and forces it to disk.
     *
     * @throws UncheckedIOException if the directory, the file or the write fails
     */
    @Override
    public void append(AuditRecord record) {
        byte[] line = (json.writeValueAsString(toJson(record)) + "\n")
            .getBytes(StandardCharsets.UTF_8);
        Path file = fileOf(record);
        lock.lock();
        try {
            ensureDirectory();
            try (FileChannel channel = posix
                ? opener.open(file, APPEND, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
                : opener.open(file, APPEND)) {
                if (posix) {
                    Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
                }
                ByteBuffer buffer = ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The audit record could not be written to " + file
                + " (" + e.getClass().getSimpleName() + ")", e);
        } finally {
            lock.unlock();
        }
    }

    private void ensureDirectory() throws IOException {
        if (Files.isDirectory(directory)) {
            if (posix) {
                Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
            }
            return;
        }
        if (posix) {
            Files.createDirectories(directory,
                PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        } else {
            Files.createDirectories(directory);
        }
    }

    private ObjectNode toJson(AuditRecord record) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("auditId", record.auditId().toString());
        node.put("timestamp", record.timestamp().toString());
        node.put("profile", scrubber.scrub(record.profile()));
        node.put("node", scrubber.scrub(record.node()));
        put(node, "group", record.group());
        node.put("capability", scrubber.scrub(record.capability()));
        node.put("step", record.step().name());
        ObjectNode inputs = node.putObject("inputs");
        for (Map.Entry<String, String> input : record.inputs().entrySet()) {
            String value = input.getValue() == null ? "" : input.getValue();
            inputs.put(scrubber.scrub(input.getKey()), HASHED_INPUTS.contains(input.getKey())
                ? hash(value) : scrubber.scrub(value));
        }
        put(node, "account", record.account());
        node.put("outcome", record.outcome().name());
        put(node, "reason", record.reason());
        put(node, "mcpClient", record.mcpClient());
        return node;
    }

    private void put(ObjectNode node, String name, Optional<String> value) {
        value.ifPresent(text -> node.put(name, scrubber.scrub(text)));
    }

    /** {@code sha256:} plus the first {@value #HASH_HEX_CHARS} hex chars of the SHA-256. */
    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest).substring(0, HASH_HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
