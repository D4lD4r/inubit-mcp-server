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
 *       removed automatically, nor is one whose audit id differs from its file name or that
 *       names a file other than {@code <auditId>-<n>.zip} (counted in {@link Sweep#skipped}).
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
        String outcome, Instant takenAt, List<String> zips, Kind kind, List<String> groups,
        List<String> repositoryPaths, Optional<String> tag, Optional<String> source) {

        /** What wrote the backup (feature 005, research D-13). */
        public enum Kind {
            /** A development tool of feature 004 (every manifest without {@code kind}). */
            IMPORT,
            /** A deployment of feature 005 into a node of a target group. */
            DEPLOYMENT
        }

        /** A backup of feature 004 ({@link Kind#IMPORT}). */
        public Manifest(String auditId, NodeId node, String owner, String scope,
            List<String> changeSet, List<String> created, Map<String, String> intendedState,
            String outcome, Instant takenAt, List<String> zips) {
            this(auditId, node, owner, scope, changeSet, created, intendedState, outcome,
                takenAt, zips, Kind.IMPORT, List.of(), List.of(), Optional.empty(),
                Optional.empty());
        }

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
            Objects.requireNonNull(kind, "kind");
            groups = List.copyOf(groups);
            repositoryPaths = List.copyOf(repositoryPaths);
            tag = tag == null ? Optional.empty() : tag;
            source = source == null ? Optional.empty() : source;
        }

        /** This manifest with another outcome and intended state. */
        public Manifest with(String newOutcome, Map<String, String> newState) {
            return new Manifest(auditId, node, owner, scope, changeSet, created, newState,
                newOutcome, takenAt, zips, kind, groups, repositoryPaths, tag, source);
        }

        private Manifest withZips(List<String> files) {
            return new Manifest(auditId, node, owner, scope, changeSet, created, intendedState,
                outcome, takenAt, files, kind, groups, repositoryPaths, tag, source);
        }

        private String retentionKey() {
            return node.value() + "\u0000" + owner + "\u0000" + scope;
        }
    }

    /**
     * The outcome of {@link #sweep}: the removed backups (oldest first) and the number of
     * manifests that were skipped because they cannot be trusted (count only).
     */
    public record Sweep(List<Removed> removed, int skipped) {
        public Sweep {
            removed = List.copyOf(removed);
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
        List<String> zips = new ArrayList<>();
        try {
            prepareDirectory();
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
            discard(zips);
            throw new IllegalStateException("A backup file of this audit id exists already");
        } catch (IOException | RuntimeException e) {
            discard(zips);
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new UncheckedIOException("The backup cannot be written to " + root,
                (IOException) e);
        }
    }

    /** Removes the ZIPs this call wrote before it failed (review M3); best effort. */
    private void discard(List<String> written) {
        for (String zip : written) {
            try {
                Files.deleteIfExists(root.resolve(zip));
            } catch (IOException e) {
                // the manifest was not written, so the backup is never found
            }
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
    public Sweep sweep() {
        if (!Files.isDirectory(root)) {
            return new Sweep(List.of(), 0);
        }
        List<Manifest> manifests = new ArrayList<>();
        int skipped = 0;
        List<Path> candidates;
        try (Stream<Path> files = Files.list(root)) {
            candidates = files.filter(file -> file.getFileName().toString().endsWith(MANIFEST))
                .filter(file -> AUDIT_ID.matcher(idOf(file)).matches()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("The backup directory cannot be read", e);
        }
        for (Path file : candidates) {
            Optional<Manifest> manifest = read(file);
            if (manifest.isPresent()) {
                manifests.add(manifest.get());
            } else {
                skipped++;
            }
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
        return new Sweep(removed, skipped);
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

    /**
     * The manifest of {@code file}, only if it can be trusted (review I2): its audit id is the
     * file's, and every ZIP it names is {@code <auditId>-<n>.zip} in this directory.
     */
    private Optional<Manifest> read(Path file) {
        try {
            Manifest manifest = ManifestJson.read(Files.readString(file, StandardCharsets.UTF_8));
            Pattern zip = Pattern.compile("^" + Pattern.quote(manifest.auditId())
                + "-[1-9][0-9]{0,5}\\.zip$");
            boolean trusted = manifest.auditId().equals(idOf(file))
                && manifest.zips().stream().allMatch(name -> zip.matcher(name).matches());
            return trusted ? Optional.of(manifest) : Optional.empty();
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
            field(json, "kind", string(manifest.kind().name()), false);
            if (manifest.kind() == Manifest.Kind.DEPLOYMENT) {
                field(json, "groups", list(manifest.groups()), false);
                field(json, "repositoryPaths", list(manifest.repositoryPaths()), false);
                field(json, "tag", string(manifest.tag().orElse("")), false);
                field(json, "source", string(manifest.source().orElse("")), false);
            }
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
            Map<String, Object> object = MiniJson.parse(text);
            if (!AUDIT_ID.matcher(text(object, "auditId")).matches()) {
                throw new IllegalArgumentException("Not a backup manifest");
            }
            try {
                Manifest.Kind kind = object.containsKey("kind")
                    ? Manifest.Kind.valueOf(text(object, "kind")) : Manifest.Kind.IMPORT;
                boolean deployment = kind == Manifest.Kind.DEPLOYMENT;
                return new Manifest(text(object, "auditId"), NodeId.parse(text(object, "node")),
                    text(object, "owner"), text(object, "scope"), texts(object, "changeSet"),
                    texts(object, "created"), map(object, "intendedState"),
                    text(object, "outcome"), Instant.parse(text(object, "takenAt")),
                    texts(object, "zips"), kind,
                    deployment ? texts(object, "groups") : List.of(),
                    deployment ? texts(object, "repositoryPaths") : List.of(),
                    deployment ? Optional.of(text(object, "tag")) : Optional.empty(),
                    deployment ? Optional.of(text(object, "source")) : Optional.empty());
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
            return MiniJson.quote(value);
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
    }
}
