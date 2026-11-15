package de.dadecker.inubit.mcp.application.nodetools;

import de.dadecker.inubit.mcp.application.MiniJson;
import de.dadecker.inubit.mcp.application.OwnerOnlyFileLock;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.Lease;
import de.dadecker.inubit.mcp.domain.model.node.LeaseAttempt;
import de.dadecker.inubit.mcp.domain.model.node.LeaseOwner;
import de.dadecker.inubit.mcp.domain.model.node.LeaseReason;
import de.dadecker.inubit.mcp.domain.model.node.LeaseState;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.domain.port.LeaseView;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The temporary log level leases of a profile (feature 024, FR-043, FR-045, research D-12):
 * {@code ~/.inubit-mcp/<profile>/log-levels/leases.json}, format 1.
 *
 * <ul>
 *   <li>Every read-modify-write runs under the store lock, the {@link OwnerOnlyFileLock} on
 *       {@code log-levels/.lock}, so instances of the profile never lose an update. Callers that
 *       also hold a node lock ({@link NodeLogLevelLocks}) take it first.
 *   <li>Owner-only through the {@link PrivateFileSystem}; written to a private temporary file in
 *       the same directory and moved atomically into place. A link at the directory or the file is
 *       refused.
 *   <li>An unreadable or invalid file is {@link LeaseView.Snapshot.Unreadable} and never
 *       overwritten.
 *   <li>Ended leases ({@code RESET}, {@code FORGOTTEN}) are removed by the first update 7 days
 *       after their end.
 *   <li>Reading never creates anything; {@code leases.json} is written only when an update changes
 *       the leases, and an update that stores no lease while none is stored creates nothing, not
 *       even the directory or the lock file.
 * </ul>
 */
public final class LeaseStore implements LeaseView {

    /** The file name of the store in the log-levels directory. */
    public static final String FILE = "leases.json";
    /** The file name of the store lock in the log-levels directory. */
    public static final String LOCK_FILE = ".lock";
    /** How long ended leases are kept after {@code endedAt} (FR-045). */
    static final Duration RETENTION = Duration.ofDays(7);

    private static final long FORMAT = 1;
    private static final Pattern UUID_TEXT = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Set<String> STORE_FIELDS = Set.of("format", "leases");
    private static final Set<String> LEASE_FIELDS = Set.of("leaseId", "generation", "server",
        "inubitNode", "loggers", "setAt", "expiresAt", "owner", "state", "auditId", "lastAttempt",
        "endedAt");
    private static final Set<String> OWNER_FIELDS = Set.of("pid", "startedAt");
    private static final Set<String> ATTEMPT_FIELDS = Set.of("at", "outcome", "code", "reason");

    private final Path directory;
    private final PrivateFileSystem files;
    private final Clock clock;
    private final OwnerOnlyFileLock lock;

    /**
     * @param logLevels {@code ~/.inubit-mcp/<profile>/log-levels} ({@link #defaultDirectory})
     * @param files     creates the private directory and files
     * @param clock     the time of the retention rule
     */
    public LeaseStore(Path logLevels, PrivateFileSystem files, Clock clock) {
        this.directory = Objects.requireNonNull(logLevels, "logLevels");
        this.files = Objects.requireNonNull(files, "files");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lock = new OwnerOnlyFileLock(files, "the log-level leases",
            "the log-levels directory");
    }

    /** The log-levels directory of a profile: {@code <home>/.inubit-mcp/<profile>/log-levels}. */
    public static Path defaultDirectory(Path home, String profile) {
        return home.resolve(".inubit-mcp").resolve(profile).resolve("log-levels");
    }

    /** The stored leases without the store lock (the file is replaced atomically). */
    @Override
    public Snapshot leases() {
        if (Files.isSymbolicLink(directory)) {
            return new Snapshot.Unreadable("the log-levels directory is a symbolic link");
        }
        Path file = file();
        if (Files.isSymbolicLink(file)) {
            return new Snapshot.Unreadable(FILE + " is a symbolic link");
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new Snapshot.Leases(List.of());
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return new Snapshot.Unreadable(FILE + " is not a regular file");
        }
        String text;
        try (InputStream in = Channels.newInputStream(Files.newByteChannel(file,
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Snapshot.Unreadable(FILE + " cannot be opened ("
                + e.getClass().getSimpleName() + ")");
        }
        try {
            return new Snapshot.Leases(parse(text));
        } catch (Invalid e) {
            return new Snapshot.Unreadable(e.getMessage());
        }
    }

    /**
     * The stored leases, as {@link #leases()}.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} if the store cannot be read
     */
    public List<Lease> read() {
        return switch (leases()) {
            case Snapshot.Leases stored -> stored.leases();
            case Snapshot.Unreadable unreadable -> throw unreadable(unreadable.reason());
        };
    }

    /**
     * Replaces the leases with {@code change} applied to the stored ones, under the store lock;
     * ended leases past their retention are then removed. Writes only if that changes the leases.
     * While nothing is stored, {@code change} is first applied to no leases without the lock; if
     * that leaves no lease, nothing is created at all - not even the directory or the lock file
     * (research D-12: the files are created by the first set).
     *
     * @param change applied once; a second time, on the stored leases, only if another instance
     *               stored the first leases meanwhile (the last result counts); must not take a
     *               node lock
     * @param wait   how long to wait for the store lock
     * @return the leases now stored
     * @throws ToolErrorException {@code PRECONDITION_FAILED} if the store lock stays busy beyond
     *     {@code wait} or the store cannot be read (nothing written); {@code INTERNAL} if it cannot
     *     be written (the stored file is unchanged)
     * @throws IllegalArgumentException if {@code change} returns two leases with one id
     */
    public List<Lease> update(Function<List<Lease>, List<Lease>> change, Duration wait) {
        if (Files.isSymbolicLink(directory)) {
            throw unreadable("the log-levels directory is a symbolic link");
        }
        List<Lease> fromNothing = null;
        if (!Files.exists(file(), LinkOption.NOFOLLOW_LINKS)) {
            fromNothing = retained(change.apply(List.of()));
            if (fromNothing.isEmpty()) {
                return fromNothing;
            }
        }
        try {
            files.createDirectories(directory);
        } catch (IOException | UnsupportedOperationException e) {
            throw unwritable(e);
        }
        try (OwnerOnlyFileLock.Held held =
                 lock.acquire(directory.resolve(LOCK_FILE), wait, LeaseStore::busy)) {
            List<Lease> before = read();
            List<Lease> after = fromNothing != null && before.isEmpty()
                ? fromNothing : retained(change.apply(before));
            Set<UUID> ids = new HashSet<>();
            for (Lease lease : after) {
                if (!ids.add(lease.leaseId())) {
                    throw new IllegalArgumentException("Two leases with id " + lease.leaseId());
                }
            }
            if (!after.equals(before)) {
                write(after);
            }
            return after;
        }
    }

    private Path file() {
        return directory.resolve(FILE);
    }

    private List<Lease> retained(List<Lease> leases) {
        Instant now = clock.instant();
        return leases.stream()
            .filter(lease -> lease.state().open()
                || now.isBefore(lease.endedAt().orElseThrow().plus(RETENTION)))
            .toList();
    }

    private void write(List<Lease> leases) {
        byte[] json = json(leases).getBytes(StandardCharsets.UTF_8);
        try {
            Path temporary = files.createFile(directory.resolve("." + FILE + "."
                + UUID.randomUUID() + ".tmp"));
            try {
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                    ByteBuffer buffer = ByteBuffer.wrap(json);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                Files.move(temporary, file(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | UnsupportedOperationException e) {
            throw unwritable(e);
        }
    }

    // ---- format 1 -------------------------------------------------------------------------

    private static String json(List<Lease> leases) {
        StringBuilder json = new StringBuilder("{\n  \"format\": ").append(FORMAT)
            .append(",\n  \"leases\": [");
        for (int i = 0; i < leases.size(); i++) {
            Lease lease = leases.get(i);
            json.append(i == 0 ? "\n" : ",\n").append("    {\n");
            member(json, "leaseId", quote(lease.leaseId().toString()));
            member(json, "generation", Long.toString(lease.generation()));
            member(json, "server", quote(lease.server().value()));
            member(json, "inubitNode", quote(lease.inubitNode()));
            List<String> loggers = new ArrayList<>();
            lease.loggers().forEach((name, level) ->
                loggers.add(quote(name.value()) + ": " + quote(level.name())));
            member(json, "loggers", "{ " + String.join(", ", loggers) + " }");
            member(json, "setAt", quote(lease.setAt().toString()));
            member(json, "expiresAt", quote(lease.expiresAt().toString()));
            member(json, "owner", "{ \"pid\": " + lease.owner().pid() + ", \"startedAt\": "
                + quote(lease.owner().startedAt().map(Instant::toString)) + " }");
            member(json, "state", quote(lease.state().name()));
            member(json, "auditId", quote(lease.auditId().toString()));
            member(json, "lastAttempt", lease.lastAttempt().map(attempt ->
                "{ \"at\": " + quote(attempt.at().toString())
                    + ", \"outcome\": " + quote(attempt.outcome().name())
                    + ", \"code\": " + quote(attempt.code().map(Enum::name))
                    + ", \"reason\": " + quote(attempt.reason().map(Enum::name)) + " }")
                .orElse("null"));
            json.append("      \"endedAt\": ")
                .append(quote(lease.endedAt().map(Instant::toString))).append("\n    }");
        }
        return json.append(leases.isEmpty() ? "]\n}\n" : "\n  ]\n}\n").toString();
    }

    private static void member(StringBuilder json, String name, String value) {
        json.append("      ").append(quote(name)).append(": ").append(value).append(",\n");
    }

    private static String quote(String value) {
        return MiniJson.quote(value);
    }

    private static String quote(Optional<String> value) {
        return value.map(MiniJson::quote).orElse("null");
    }

    private static List<Lease> parse(String text) throws Invalid {
        Map<String, Object> store;
        try {
            store = MiniJson.parse(text);
        } catch (IllegalArgumentException e) {
            throw new Invalid("not valid JSON");
        }
        fields(store, STORE_FIELDS, "");
        if (!Long.valueOf(FORMAT).equals(store.get("format"))) {
            throw new Invalid("unsupported format, expected " + FORMAT);
        }
        if (!(store.get("leases") instanceof List<?> items)) {
            throw new Invalid("leases is not a list");
        }
        List<Lease> leases = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String where = "lease " + (i + 1);
            Lease lease = lease(items.get(i), where);
            if (!ids.add(lease.leaseId())) {
                throw new Invalid(where + ": duplicate leaseId");
            }
            leases.add(lease);
        }
        return leases;
    }

    private static Lease lease(Object item, String where) throws Invalid {
        if (!(item instanceof Map<?, ?>)) {
            throw new Invalid(where + ": not an object");
        }
        Map<?, ?> lease = (Map<?, ?>) item;
        fields(lease, LEASE_FIELDS, where + ": ");
        UUID leaseId = field(lease, "leaseId", where, LeaseStore::uuid);
        long generation = field(lease, "generation", where, value -> (long) (Long) value);
        NodeId server = field(lease, "server", where, value -> NodeId.parse((String) value));
        String inubitNode = field(lease, "inubitNode", where, LeaseStore::text);
        SortedMap<LoggerName, TemporaryLevel> loggers =
            field(lease, "loggers", where, LeaseStore::loggers);
        Instant setAt = field(lease, "setAt", where, LeaseStore::instant);
        Instant expiresAt = field(lease, "expiresAt", where, LeaseStore::instant);
        LeaseOwner owner = field(lease, "owner", where, LeaseStore::owner);
        LeaseState state = field(lease, "state", where,
            value -> LeaseState.valueOf(text(value)));
        UUID auditId = field(lease, "auditId", where, LeaseStore::uuid);
        Optional<LeaseAttempt> lastAttempt =
            field(lease, "lastAttempt", where, LeaseStore::attempt);
        Optional<Instant> endedAt = field(lease, "endedAt", where, LeaseStore::optionalInstant);
        try {
            return new Lease(leaseId, generation, server, inubitNode, loggers, setAt, expiresAt,
                owner, state, auditId, lastAttempt, endedAt);
        } catch (IllegalArgumentException e) {
            throw new Invalid(where + ": " + e.getMessage());
        }
    }

    /** {@code object} has exactly {@code expected} as keys. */
    private static void fields(Map<?, ?> object, Set<String> expected, String where)
        throws Invalid {
        try {
            requireFields(object, expected);
        } catch (IllegalArgumentException e) {
            throw new Invalid(where + e.getMessage());
        }
    }

    private static void requireFields(Map<?, ?> object, Set<String> expected) {
        for (Object name : object.keySet()) {
            if (!expected.contains(name)) {
                throw new IllegalArgumentException("unknown field " + name);
            }
        }
        for (String name : expected) {
            if (!object.containsKey(name)) {
                throw new IllegalArgumentException("missing field " + name);
            }
        }
    }

    /** Reads one field; any failure names only the field, never its value. */
    private static <T> T field(Map<?, ?> object, String name, String where,
        Function<Object, T> reader) throws Invalid {
        try {
            return reader.apply(object.get(name));
        } catch (RuntimeException e) {
            throw new Invalid(where + ": invalid " + name);
        }
    }

    private static String text(Object value) {
        return (String) Objects.requireNonNull(value);
    }

    /** A UUID as {@link UUID#toString()} writes it ({@link UUID#fromString} also takes less). */
    private static UUID uuid(Object value) {
        String text = text(value);
        if (!UUID_TEXT.matcher(text).matches()) {
            throw new IllegalArgumentException("not a UUID");
        }
        return UUID.fromString(text);
    }

    private static Instant instant(Object value) {
        return Instant.parse(text(value));
    }

    private static Optional<Instant> optionalInstant(Object value) {
        return value == null ? Optional.empty() : Optional.of(instant(value));
    }

    private static SortedMap<LoggerName, TemporaryLevel> loggers(Object value) {
        SortedMap<LoggerName, TemporaryLevel> loggers =
            new TreeMap<>(Comparator.comparing(LoggerName::value));
        ((Map<?, ?>) value).forEach((name, level) ->
            loggers.put(new LoggerName((String) name), TemporaryLevel.valueOf(text(level))));
        return loggers;
    }

    private static LeaseOwner owner(Object value) {
        Map<?, ?> owner = (Map<?, ?>) value;
        requireFields(owner, OWNER_FIELDS);
        return new LeaseOwner((Long) owner.get("pid"), optionalInstant(owner.get("startedAt")));
    }

    private static Optional<LeaseAttempt> attempt(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        Map<?, ?> attempt = (Map<?, ?>) value;
        requireFields(attempt, ATTEMPT_FIELDS);
        Object code = attempt.get("code");
        Object reason = attempt.get("reason");
        return Optional.of(new LeaseAttempt(instant(attempt.get("at")),
            AuditOutcome.valueOf(text(attempt.get("outcome"))),
            code == null ? Optional.empty() : Optional.of(ErrorCode.valueOf(text(code))),
            reason == null ? Optional.empty() : Optional.of(LeaseReason.valueOf(text(reason)))));
    }

    // ---- errors ---------------------------------------------------------------------------

    private ToolErrorException unreadable(String reason) {
        return new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
            "The lease store " + file() + " cannot be read (" + reason + ")",
            "The file was edited by hand, damaged or replaced by a link; it is never overwritten",
            "Check the file, then fix or move it; after a move, reset the nodes it names with"
                + " reset_temporary_log_level"));
    }

    private ToolErrorException unwritable(Exception e) {
        return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
            "The lease store " + file() + " cannot be written (" + e.getClass().getSimpleName()
                + ")",
            "The directory is not writable or does not support owner-only permissions",
            "Check ~/.inubit-mcp/<profile>/log-levels, then retry"));
    }

    private static ToolErrorException busy() {
        return new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
            "Another change of the temporary log level leases is in progress; nothing was changed",
            "A set, a reset or the lease scheduler (in this or another MCP server process of the"
                + " profile) holds the lease store lock beyond the wait",
            "Try again in a moment"));
    }

    /** Why the stored text is not format 1; names fields, never their values. */
    private static final class Invalid extends Exception {
        Invalid(String reason) {
            super(reason, null, false, false);
        }
    }
}
