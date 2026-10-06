package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The backups of the development tools (feature 004, research D-13, D-25, FR-010), outside the
 * workspace history in {@code ~/.inubit-mcp/<profile>/backups}:
 *
 * <ul>
 *   <li>one backup = the raw StartCLI exports of the scope taken right before a write, as
 *       {@code <auditId>-<n>.zip} ({@code n} from 1), plus the manifest {@code <auditId>.json}
 *       with node, owner, scope, change-set names, created artifacts, the intended state (hashes
 *       per artifact), outcome and time — names and hashes only, never content or secrets. The
 *       ZIPs hold the server's raw values, secrets included, so the directory is
 *       {@code rwx------} (tightened if it exists) and every file {@code rw-------};
 *   <li>the ZIPs are written first and the manifest last, so a manifest names a complete backup;
 *       an audit id is written once; {@link #update} rewrites only the manifest (atomically);
 *   <li>{@link #sweep} removes backups taken more than {@link #RETENTION} ago, except the newest
 *       of each {@code (node, owner, scope)} (clarification 3), and returns what it removed for
 *       the audit log ({@code backup_retention}). A manifest that cannot be read is never
 *       removed automatically.
 * </ul>
 *
 * <p>Not thread-safe across processes; the workspace lock serializes the writing calls.
 */
public final class BackupStore {

    /** How long a backup is kept unless it is the newest of its node, owner and scope. */
    public static final Duration RETENTION = Duration.ofDays(30);

    private static final Pattern AUDIT_ID = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final String MANIFEST = ".json";
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
        PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
        PosixFilePermissions.fromString("rw-------");

    /**
     * The manifest of one backup (data-model.md → Backup).
     *
     * @param auditId       the audit id of the writing call (a lower-case UUID), the backup
     *                      reference
     * @param scope         the diagram group or module set of the call (the retention key)
     * @param changeSet     the names of the artifacts the call changes
     * @param created       the names of the artifacts the call creates
     * @param intendedState hash per artifact name of the state the call intends (after it), or
     *                      that the verification saw for a failed call
     * @param outcome       e.g. {@code PENDING}, {@code EXECUTED}, {@code FAILED}
     * @param takenAt       when the backup was taken
     * @param zips          the file names of the raw exports, in export order (set by
     *                      {@link #write})
     */
    public record Manifest(String auditId, NodeId node, String owner, String scope,
        List<String> changeSet, List<String> created, Map<String, String> intendedState,
        String outcome, Instant takenAt, List<String> zips) {

        public Manifest {
            Objects.requireNonNull(auditId, "auditId");
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(scope, "scope");
            changeSet = List.copyOf(changeSet);
            created = List.copyOf(created);
            intendedState = Collections.unmodifiableMap(new TreeMap<>(intendedState));
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(takenAt, "takenAt");
            zips = List.copyOf(zips);
        }

        private Manifest withZips(List<String> files) {
            return new Manifest(auditId, node, owner, scope, changeSet, created, intendedState,
                outcome, takenAt, files);
        }

        private String retentionKey() {
            return node.value() + "\u0000" + owner + "\u0000" + scope;
        }
    }

    /** A backup removed by {@link #sweep}, for its audit record. */
    public record Removed(String auditId, NodeId node, String owner, String scope,
        Instant takenAt) {
    }

    private final Path root;
    private final Clock clock;

    /**
     * @param root  the backup directory ({@link #defaultRoot} unless a test injects another)
     * @param clock the time of the retention sweep
     */
    public BackupStore(Path root, Clock clock) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** {@code <home>/.inubit-mcp/<profile>/backups}. */
    public static Path defaultRoot(Path home, String profile) {
        return home.resolve(".inubit-mcp").resolve(profile).resolve("backups");
    }

    /** The backup directory. */
    public Path root() {
        return root;
    }

    /**
     * Writes the raw {@code exports} and then the manifest.
     *
     * @return the manifest with the file names of the ZIPs
     * @throws IllegalArgumentException if the audit id is not a lower-case UUID
     * @throws IllegalStateException if a backup with this audit id exists
     * @throws UncheckedIOException if a file cannot be written (the caller does not write to
     *     INUBIT without a backup)
     */
    public Manifest write(Manifest manifest, List<byte[]> exports) {
        String id = checkId(manifest.auditId());
        if (Files.exists(manifestFile(id))) {
            throw new IllegalStateException("A backup with this audit id exists already");
        }
        try {
            prepareDirectory();
            List<String> zips = new ArrayList<>();
            for (int i = 0; i < exports.size(); i++) {
                String name = id + "-" + (i + 1) + ".zip";
                writeNew(root.resolve(name), exports.get(i));
                zips.add(name);
            }
            Manifest complete = manifest.withZips(zips);
            writeNew(manifestFile(id), ManifestJson.write(complete)
                .getBytes(StandardCharsets.UTF_8));
            return complete;
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("A backup file of this audit id exists already");
        } catch (IOException e) {
            throw new UncheckedIOException("The backup cannot be written to " + root, e);
        }
    }

    /**
     * Rewrites the manifest of an existing backup (e.g. its outcome and intended state); the
     * ZIPs stay as they are.
     *
     * @throws IllegalStateException if there is no such backup
     */
    public void update(Manifest manifest) {
        String id = checkId(manifest.auditId());
        Manifest existing = find(id).orElseThrow(() ->
            new IllegalStateException("There is no backup with this audit id"));
        Manifest updated = manifest.withZips(existing.zips());
        Path target = manifestFile(id);
        try {
            Path temporary = Files.createTempFile(root, "." + id, ".tmp", owner());
            try {
                Files.writeString(temporary, ManifestJson.write(updated), StandardCharsets.UTF_8);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The backup manifest cannot be written", e);
        }
    }

    /** The manifest of {@code auditId}; empty if it is unknown, removed, malformed or unreadable. */
    public Optional<Manifest> find(String auditId) {
        if (auditId == null || !AUDIT_ID.matcher(auditId).matches()) {
            return Optional.empty();
        }
        Path file = manifestFile(auditId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return read(file).filter(manifest -> manifest.auditId().equals(auditId));
    }

    /**
     * The raw exports of {@code auditId}, in export order.
     *
     * @throws IllegalStateException if there is no such backup or a ZIP is missing
     */
    public List<byte[]> exports(String auditId) {
        Manifest manifest = find(auditId).orElseThrow(() ->
            new IllegalStateException("There is no backup with this audit id"));
        List<byte[]> exports = new ArrayList<>();
        for (String zip : manifest.zips()) {
            try {
                exports.add(Files.readAllBytes(root.resolve(zip)));
            } catch (IOException e) {
                throw new IllegalStateException("A file of the backup is missing or unreadable");
            }
        }
        return List.copyOf(exports);
    }

    /**
     * Removes every backup taken more than {@link #RETENTION} ago that is not the newest of its
     * {@code (node, owner, scope)}.
     *
     * @return the removed backups, oldest first
     */
    public List<Removed> sweep() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Manifest> manifests = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            files.filter(file -> file.getFileName().toString().endsWith(MANIFEST))
                .filter(file -> AUDIT_ID.matcher(idOf(file)).matches())
                .forEach(file -> read(file).ifPresent(manifests::add));
        } catch (IOException e) {
            throw new UncheckedIOException("The backup directory cannot be read", e);
        }
        Comparator<Manifest> newestFirst = Comparator.comparing(Manifest::takenAt)
            .thenComparing(Manifest::auditId).reversed();
        Map<String, Manifest> newest = new HashMap<>();
        for (Manifest manifest : manifests) {
            newest.merge(manifest.retentionKey(), manifest,
                (a, b) -> newestFirst.compare(a, b) <= 0 ? a : b);
        }
        Instant limit = clock.instant().minus(RETENTION);
        List<Removed> removed = new ArrayList<>();
        manifests.stream()
            .filter(manifest -> manifest.takenAt().isBefore(limit))
            .filter(manifest -> newest.get(manifest.retentionKey()) != manifest)
            .sorted(newestFirst.reversed())
            .forEach(manifest -> {
                remove(manifest);
                removed.add(new Removed(manifest.auditId(), manifest.node(), manifest.owner(),
                    manifest.scope(), manifest.takenAt()));
            });
        return List.copyOf(removed);
    }

    /** The manifest first, so that a half-removed backup is never found. */
    private void remove(Manifest manifest) {
        try {
            Files.deleteIfExists(manifestFile(manifest.auditId()));
            for (String zip : manifest.zips()) {
                Files.deleteIfExists(root.resolve(zip));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("A backup cannot be removed", e);
        }
    }

    private Optional<Manifest> read(Path file) {
        try {
            return Optional.of(ManifestJson.read(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private void prepareDirectory() throws IOException {
        if (!Files.isDirectory(root)) {
            Files.createDirectories(root, owner(DIRECTORY_PERMISSIONS));
        }
        Files.setPosixFilePermissions(root, DIRECTORY_PERMISSIONS);
    }

    private static void writeNew(Path file, byte[] content) throws IOException {
        Files.createFile(file, owner());
        Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        Files.write(file, content, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static FileAttribute<Set<PosixFilePermission>> owner() {
        return owner(FILE_PERMISSIONS);
    }

    private static FileAttribute<Set<PosixFilePermission>> owner(
        Set<PosixFilePermission> permissions) {
        return PosixFilePermissions.asFileAttribute(permissions);
    }

    private Path manifestFile(String auditId) {
        return root.resolve(auditId + MANIFEST);
    }

    private static String idOf(Path manifest) {
        String name = manifest.getFileName().toString();
        return name.substring(0, name.length() - MANIFEST.length());
    }

    private static String checkId(String auditId) {
        if (!AUDIT_ID.matcher(auditId).matches()) {
            throw new IllegalArgumentException("An audit id is a lower-case UUID");
        }
        return auditId;
    }

    /**
     * The manifest as JSON: one object of strings, string lists and a string map. Written and
     * read here, without a JSON library, like the check reports (the application layer depends
     * only on the JDK).
     */
    static final class ManifestJson {

        private ManifestJson() {
        }

        static String write(Manifest manifest) {
            StringBuilder json = new StringBuilder("{\n");
            field(json, "auditId", string(manifest.auditId()), false);
            field(json, "node", string(manifest.node().value()), false);
            field(json, "owner", string(manifest.owner()), false);
            field(json, "scope", string(manifest.scope()), false);
            field(json, "takenAt", string(manifest.takenAt().toString()), false);
            field(json, "outcome", string(manifest.outcome()), false);
            field(json, "changeSet", list(manifest.changeSet()), false);
            field(json, "created", list(manifest.created()), false);
            StringBuilder state = new StringBuilder("{");
            int i = 0;
            for (Map.Entry<String, String> entry : manifest.intendedState().entrySet()) {
                state.append(i++ == 0 ? "" : ", ").append(string(entry.getKey())).append(": ")
                    .append(string(entry.getValue()));
            }
            field(json, "intendedState", state.append('}').toString(), false);
            field(json, "zips", list(manifest.zips()), true);
            return json.append("}\n").toString();
        }

        static Manifest read(String text) {
            Parser parser = new Parser(text);
            Map<String, Object> object = parser.object();
            parser.end();
            try {
                return new Manifest(text(object, "auditId"), NodeId.parse(text(object, "node")),
                    text(object, "owner"), text(object, "scope"), texts(object, "changeSet"),
                    texts(object, "created"), map(object, "intendedState"),
                    text(object, "outcome"), Instant.parse(text(object, "takenAt")),
                    texts(object, "zips"));
            } catch (DateTimeException | ClassCastException | NullPointerException e) {
                throw new IllegalArgumentException("Not a backup manifest", e);
            }
        }

        private static void field(StringBuilder json, String name, String value, boolean last) {
            json.append("  ").append(string(name)).append(": ").append(value)
                .append(last ? "\n" : ",\n");
        }

        private static String list(List<String> values) {
            StringBuilder list = new StringBuilder("[");
            for (int i = 0; i < values.size(); i++) {
                list.append(i == 0 ? "" : ", ").append(string(values.get(i)));
            }
            return list.append(']').toString();
        }

        private static String string(String value) {
            StringBuilder out = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            return out.append('"').toString();
        }

        private static String text(Map<String, Object> object, String name) {
            return (String) Objects.requireNonNull(object.get(name), name);
        }

        @SuppressWarnings("unchecked")
        private static List<String> texts(Map<String, Object> object, String name) {
            List<Object> values = (List<Object>) Objects.requireNonNull(object.get(name), name);
            return values.stream().map(String.class::cast).toList();
        }

        @SuppressWarnings("unchecked")
        private static Map<String, String> map(Map<String, Object> object, String name) {
            Map<String, Object> values = (Map<String, Object>) Objects.requireNonNull(
                object.get(name), name);
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> result.put(key, (String) value));
            return result;
        }

        /** Objects, arrays and strings: the subset {@link #write} produces. */
        private static final class Parser {
            private final String text;
            private int position;

            Parser(String text) {
                this.text = text;
            }

            Map<String, Object> object() {
                expect('{');
                Map<String, Object> object = new LinkedHashMap<>();
                if (peek() == '}') {
                    position++;
                    return object;
                }
                do {
                    String name = string();
                    expect(':');
                    object.put(name, value());
                } while (comma());
                expect('}');
                return object;
            }

            void end() {
                skipWhitespace();
                if (position != text.length()) {
                    throw new IllegalArgumentException("Trailing content in a backup manifest");
                }
            }

            private Object value() {
                return switch (peek()) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    default -> throw new IllegalArgumentException("Unexpected value");
                };
            }

            private List<Object> array() {
                expect('[');
                List<Object> array = new ArrayList<>();
                if (peek() == ']') {
                    position++;
                    return array;
                }
                do {
                    array.add(value());
                } while (comma());
                expect(']');
                return array;
            }

            private String string() {
                expect('"');
                StringBuilder value = new StringBuilder();
                while (true) {
                    char c = next();
                    if (c == '"') {
                        return value.toString();
                    }
                    if (c != '\\') {
                        value.append(c);
                        continue;
                    }
                    char escaped = next();
                    switch (escaped) {
                        case '"', '\\', '/' -> value.append(escaped);
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'u' -> {
                            if (position + 4 > text.length()) {
                                throw new IllegalArgumentException("Truncated escape");
                            }
                            value.append((char) Integer.parseInt(
                                text.substring(position, position + 4), 16));
                            position += 4;
                        }
                        default -> throw new IllegalArgumentException("Invalid escape");
                    }
                }
            }

            private boolean comma() {
                if (peek() == ',') {
                    position++;
                    return true;
                }
                return false;
            }

            private void expect(char expected) {
                if (peek() != expected) {
                    throw new IllegalArgumentException("Malformed backup manifest");
                }
                position++;
            }

            private char peek() {
                skipWhitespace();
                if (position >= text.length()) {
                    throw new IllegalArgumentException("Truncated backup manifest");
                }
                return text.charAt(position);
            }

            private char next() {
                if (position >= text.length()) {
                    throw new IllegalArgumentException("Truncated backup manifest");
                }
                return text.charAt(position++);
            }

            private void skipWhitespace() {
                while (position < text.length() && " \n\r\t".indexOf(text.charAt(position)) >= 0) {
                    position++;
                }
            }
        }
    }
}
