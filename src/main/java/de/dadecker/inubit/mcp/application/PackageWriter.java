package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The package of a node of a package-only group (feature 005, US4, FR-023, research D-10):
 * {@code <root>/<auditId>/<group>-<node>/} with the import archives of D-7 step 4 in their order
 * (they carry the node's own secret values — the reason for the private location),
 * {@code diff.txt}, {@code warnings.txt} and a {@code README.md} with the StartCLI commands in
 * that order. Directories are {@code rwx------}, files {@code rw-------}. Packages follow the
 * backup retention: older than {@link #RETENTION} and not the newest of their target group →
 * removed (by {@link #sweep}, which every {@link #write} runs afterwards).
 *
 * <p>Nothing here sends anything to INUBIT.
 */
public final class PackageWriter {

    private static final Logger LOG = LoggerFactory.getLogger(PackageWriter.class);

    /** As the backups (research D-10). */
    public static final Duration RETENTION = BackupStore.RETENTION;

    private static final String MARKER = "package.properties";
    private static final Pattern AUDIT_ID = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Set<PosixFilePermission> DIRECTORY =
        PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE =
        PosixFilePermissions.fromString("rw-------");

    /**
     * One import of D-7 step 4.
     *
     * @param mode         empty: the repository files ({@code --importRepositoryPath})
     * @param diagramGroup the diagram group of a workflow archive
     */
    public record Archive(Optional<ImportPort.Mode> mode, Optional<String> diagramGroup,
        byte[] zip) {
        public Archive {
            mode = mode == null ? Optional.empty() : mode;
            diagramGroup = diagramGroup == null ? Optional.empty() : diagramGroup;
            Objects.requireNonNull(zip, "zip");
        }
    }

    /**
     * The package of one node.
     *
     * @param diff     the difference report of the node's plan (never secret values)
     * @param warnings the plan's warnings, one per line
     * @param excluded the artifacts the release leaves out on this node, one per line
     */
    public record Content(UUID auditId, NodeId node, String tag, GroupId source, String owner,
        List<Archive> archives, String diff, List<String> warnings, List<String> excluded) {
        public Content {
            Objects.requireNonNull(auditId, "auditId");
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(tag, "tag");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(owner, "owner");
            archives = List.copyOf(archives);
            Objects.requireNonNull(diff, "diff");
            warnings = List.copyOf(warnings);
            excluded = List.copyOf(excluded);
        }
    }

    /** Writes one new owner-only file of a package. */
    @FunctionalInterface
    interface FileSink {
        void write(Path file, byte[] content) throws IOException;
    }

    private final Path root;
    private final Clock clock;
    private final FileSink files;

    /** @param root {@code ~/.inubit-mcp/<profile>/packages} */
    public PackageWriter(Path root, Clock clock) {
        this(root, clock, PackageWriter::writeOwnerOnly);
    }

    /** As above, writing the files through {@code files} (tests). */
    PackageWriter(Path root, Clock clock, FileSink files) {
        this.root = Objects.requireNonNull(root, "root");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.files = Objects.requireNonNull(files, "files");
    }

    /**
     * Writes the package of {@code content.node()}, then runs the {@link #sweep} (whose failure
     * is only logged).
     *
     * @return the package directory of the node
     * @throws UncheckedIOException if the package cannot be written completely; what was
     *     written of the node's package is removed first (it may hold secret values)
     * @throws IllegalStateException if the node's package of this call exists already
     */
    public Path write(Content content) {
        Path call = root.resolve(content.auditId().toString());
        Path dir = call.resolve(content.node().group() + "-" + content.node().name());
        boolean created = false;
        try {
            directory(root);
            directory(call);
            Path marker = call.resolve(MARKER);
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                Properties properties = new Properties();
                properties.setProperty("target", content.node().group().value());
                properties.setProperty("takenAt", clock.instant().toString());
                java.io.StringWriter text = new java.io.StringWriter();
                properties.store(text, null);
                files.write(marker, text.toString().getBytes(StandardCharsets.ISO_8859_1));
            }
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("The package of " + content.node()
                    + " exists already for this call");
            }
            directory(dir);
            created = true;
            List<String> commands = new ArrayList<>();
            int index = 0;
            for (Archive archive : content.archives()) {
                Path zip = dir.resolve(++index + "-" + name(archive) + ".zip");
                files.write(zip, archive.zip());
                commands.add("import --importFile '" + zip + "' "
                    + importOptions(archive.mode(), content.owner()));
            }
            files.write(dir.resolve("diff.txt"), content.diff().getBytes(StandardCharsets.UTF_8));
            List<String> warnings = new ArrayList<>(content.warnings());
            if (!content.excluded().isEmpty()) {
                warnings.add("");
                warnings.add("Left out by the release on this node:");
                warnings.addAll(content.excluded());
            }
            files.write(dir.resolve("warnings.txt"), (String.join("\n", warnings) + "\n")
                .getBytes(StandardCharsets.UTF_8));
            files.write(dir.resolve("README.md"), readme(content, commands)
                .getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            if (created) {
                removeQuietly(dir);
            }
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new UncheckedIOException("The package of " + content.node()
                + " cannot be written below " + root, (IOException) e);
        }
        try {
            sweep();
        } catch (RuntimeException e) {
            LOG.warn("The package retention could not run ({})", e.getClass().getSimpleName());
        }
        return dir;
    }

    /**
     * The options of the StartCLI import of an archive, as the deployment sends them (D-7).
     */
    static String importOptions(Optional<ImportPort.Mode> mode, String owner) {
        if (mode.isEmpty()) {
            return "--importRepositoryPath '/Root/" + owner + "'";
        }
        String user = "--importUser '" + owner + "' --returnProtocol";
        return switch (mode.get()) {
            case MODULE -> "--importModule " + user;
            case WORKFLOW -> "--importWorkflow " + user;
            case WORKFLOW_ACTIVE -> "--importWorkflow --importWorkflowActive " + user;
            case WORKFLOW_INACTIVE -> "--importWorkflow --importWorkflowInactive " + user;
        };
    }

    private static String name(Archive archive) {
        if (archive.mode().isEmpty()) {
            return "repository";
        }
        return switch (archive.mode().get()) {
            case MODULE -> "modules";
            case WORKFLOW -> safe(archive.diagramGroup().orElse("workflows"));
            case WORKFLOW_ACTIVE -> safe(archive.diagramGroup().orElse("workflows")) + "-active";
            case WORKFLOW_INACTIVE -> safe(archive.diagramGroup().orElse("workflows"))
                + "-inactive";
        };
    }

    private static String safe(String text) {
        return text.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private String readme(Content content, List<String> commands) {
        StringBuilder text = new StringBuilder();
        text.append("# Package of ").append(content.tag()).append(" for ")
            .append(content.node()).append("\n\n");
        text.append("- Release: tag `").append(content.tag()).append("` from `")
            .append(content.source()).append("`, owner `").append(content.owner())
            .append("`\n");
        text.append("- Target node: `").append(content.node()).append("` (package only:"
            + " this server never imports into it)\n");
        text.append("- Audit id: `").append(content.auditId()).append("`\n");
        text.append("- Written: ").append(clock.instant()).append("\n\n");
        text.append("The archives hold only the new, changed and layout-only artifacts of the"
            + " release, with the node's own secret values: keep this directory private and"
            + " remove it after the import. Review `diff.txt` and `warnings.txt` first.\n\n");
        if (commands.isEmpty()) {
            text.append("Nothing to import: the release is on the node already.\n");
            return text.toString();
        }
        text.append("## Import, in this order\n\n");
        text.append("Run each command with StartCLI against ").append(content.node())
            .append(", with the user, StartCLI URL, trust store and host name verification"
                + " options as configured for this node (replace `<options>`); stop at the"
                + " first one that does not report success. If you move this directory, adjust"
                + " the paths.\n\n");
        int step = 0;
        for (String command : commands) {
            text.append(++step).append(". ```\n   startcli.sh <options> --execCommand \"")
                .append(command).append("\" <StartCLI URL>\n   ```\n");
        }
        text.append("\nAfterwards: check the workflows' active flags, then tag the diagram"
            + " groups with `").append(content.tag()).append("` if your process asks for it.\n");
        return text.toString();
    }

    /**
     * Removes every package older than {@link #RETENTION} that is not the newest of its target
     * group; directories that are not packages are left alone.
     *
     * @return the removed packages' audit ids, oldest first
     */
    public List<UUID> sweep() {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        record Taken(UUID auditId, Path dir, String target, Instant at) {
        }
        List<Taken> packages = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> AUDIT_ID.matcher(p.getFileName().toString()).matches()).toList()) {
                Path marker = dir.resolve(MARKER);
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Properties properties = new Properties();
                try (java.io.Reader reader = Files.newBufferedReader(marker,
                    StandardCharsets.ISO_8859_1)) {
                    properties.load(reader);
                }
                try {
                    packages.add(new Taken(UUID.fromString(dir.getFileName().toString()), dir,
                        properties.getProperty("target", ""), Instant.parse(
                            properties.getProperty("takenAt", ""))));
                } catch (RuntimeException e) {
                    LOG.warn("The package {} has an unreadable marker; it is kept",
                        dir.getFileName());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The package directory cannot be read", e);
        }
        Comparator<Taken> newestFirst = Comparator.comparing(Taken::at)
            .thenComparing(taken -> taken.auditId().toString()).reversed();
        Map<String, Taken> newest = new HashMap<>();
        packages.forEach(taken -> newest.merge(taken.target(), taken,
            (a, b) -> newestFirst.compare(a, b) <= 0 ? a : b));
        Instant limit = clock.instant().minus(RETENTION);
        List<UUID> removed = new ArrayList<>();
        packages.stream().filter(taken -> taken.at().isBefore(limit))
            .filter(taken -> newest.get(taken.target()) != taken)
            .sorted(newestFirst.reversed())
            .forEach(taken -> {
                remove(taken.dir());
                removed.add(taken.auditId());
            });
        return removed;
    }

    /** The marker first, so that a half-removed package is never swept as a package again. */
    private static void remove(Path dir) {
        try {
            Files.deleteIfExists(dir.resolve(MARKER));
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("A package cannot be removed", e);
        }
    }

    private static void directory(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIRECTORY));
        }
        Files.setPosixFilePermissions(dir, DIRECTORY);
    }

    /** Removes a half-written package directory; a failure is only logged. */
    private static void removeQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException e) {
            LOG.error("The incomplete package {} could not be removed ({}); remove it by hand,"
                + " it may hold secret values", dir, e.getClass().getSimpleName());
        }
    }

    static void writeOwnerOnly(Path file, byte[] content) throws IOException {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE));
        Files.setPosixFilePermissions(file, FILE);
        Files.write(file, content, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
