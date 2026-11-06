package de.dadecker.inubit.mcp.infra;

import de.dadecker.inubit.mcp.application.UpdateCache;
import de.dadecker.inubit.mcp.domain.model.SemVer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@link UpdateCache} in {@code ~/.inubit-mcp/update-check.json} (feature 016, research R-12,
 * contracts/configuration-delta.md): {@code {"latestVersion", "checkedAt", "dismissedVersion"}}.
 * Unknown fields are ignored; a missing or invalid version or time, an oversized file or one that
 * is not JSON makes the whole file count as absent. Writes go through a temporary file and an
 * atomic move. No failure is thrown; it is logged at DEBUG.
 */
public final class FileUpdateCache implements UpdateCache {

    /** The file name below {@code ~/.inubit-mcp}. */
    public static final String FILE_NAME = "update-check.json";

    static final long MAX_BYTES = 64 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(FileUpdateCache.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path file;
    private final Clock clock;

    public FileUpdateCache(Path file, Clock clock) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The cache of the user whose home is {@code userHome}. */
    public static FileUpdateCache inHome(Path userHome, Clock clock) {
        return new FileUpdateCache(userHome.resolve(".inubit-mcp").resolve(FILE_NAME), clock);
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized Optional<CachedRelease> read() {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) {
                return Optional.empty();
            }
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            Optional<SemVer> latest = version(root.get("latestVersion"));
            Optional<Instant> checkedAt = instant(root.get("checkedAt"));
            JsonNode dismissedNode = root.get("dismissedVersion");
            Optional<SemVer> dismissed = version(dismissedNode);
            boolean dismissedValid = dismissedNode == null || dismissedNode.isNull()
                || dismissed.isPresent();
            if (latest.isEmpty() || checkedAt.isEmpty() || !dismissedValid) {
                return Optional.empty();
            }
            return Optional.of(new CachedRelease(latest.get(), checkedAt.get(), dismissed));
        } catch (IOException | RuntimeException e) {
            LOG.debug("Update check cache not readable: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public synchronized void write(SemVer latestVersion, Instant checkedAt) {
        Optional<SemVer> dismissed = read().flatMap(CachedRelease::dismissedVersion);
        store(new CachedRelease(latestVersion, checkedAt, dismissed));
    }

    @Override
    public synchronized void dismiss(SemVer version) {
        CachedRelease cached = read().orElseGet(() ->
            new CachedRelease(version, clock.instant(), Optional.empty()));
        store(new CachedRelease(cached.latestVersion(), cached.checkedAt(), Optional.of(version)));
    }

    private void store(CachedRelease release) {
        ObjectNode root = JSON.createObjectNode();
        root.put("latestVersion", release.latestVersion().toString());
        root.put("checkedAt", release.checkedAt().toString());
        release.dismissedVersion().ifPresent(v -> root.put("dismissedVersion", v.toString()));
        Path temporary = null;
        try {
            Path directory = file.toAbsolutePath().getParent();
            createDirectory(directory);
            temporary = Files.createTempFile(directory, FILE_NAME, ".tmp");
            Files.writeString(temporary, JSON.writeValueAsString(root) + "\n",
                StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("Update check cache not written: {}", e.getClass().getSimpleName());
            deleteQuietly(temporary);
        }
    }

    /** A missing directory is created owner-only, like the audit directory (Launcher). */
    private static void createDirectory(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            return;
        }
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(directory);
        }
    }

    private static void deleteQuietly(Path temporary) {
        if (temporary != null) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                LOG.debug("Temporary update check file not deleted: {}",
                    e.getClass().getSimpleName());
            }
        }
    }

    private static Optional<SemVer> version(JsonNode node) {
        return node != null && node.isString() ? SemVer.parse(node.asString()) : Optional.empty();
    }

    private static Optional<Instant> instant(JsonNode node) {
        if (node == null || !node.isString()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(node.asString()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
