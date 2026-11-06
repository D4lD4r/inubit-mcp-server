package de.dadecker.inubit.mcp.adapter.os;

import de.dadecker.inubit.mcp.adapter.os.AclApi.Principal;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@link PrivateFileSystem} of the running system (feature 013, FR-007, research R-8), selected by
 * the {@code windows} flag the server passes around.
 *
 * <ul>
 *   <li>POSIX (the default file system supports {@code posix:permissions}): the permission set is
 *       given as creation attribute and set again right after creation, as the server's callers
 *       did so far.
 *   <li>Windows: the object is created without attributes (empty, with the access inherited from
 *       its parent), then {@link AclApi#protect} sets the token user as owner and the protected
 *       access list. Any failure there, also a native library that cannot be loaded or a token
 *       user that cannot be read, deletes the object and fails the call.
 *   <li>Otherwise every {@code create…} call throws {@link UnsupportedOperationException} before
 *       anything is created.
 * </ul>
 *
 * <p>{@link #ownedByCurrentUser} does not follow a link on POSIX; on Windows it reads the object a
 * link points to, so callers that must not follow links check for them first (as the sweep does).
 */
public final class OsPrivateFileSystem implements PrivateFileSystem {

    /** How the running system restricts access. */
    enum Mode { POSIX, WINDOWS, NONE }

    /** Reported by {@link #check} for an object without any access list. */
    static final String EVERYONE = "Everyone (no access list)";

    private static final Set<PosixFilePermission> DIRECTORY =
        PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE =
        PosixFilePermissions.fromString("rw-------");

    private final Mode mode;
    private final AclApi acl;
    private final Supplier<Optional<String>> userSid;

    /** For the running system ({@code os.name}). */
    public static OsPrivateFileSystem forHost() {
        return new OsPrivateFileSystem(
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
    }

    /** For the running system; on Windows the token user is read with {@link WindowsSystem}. */
    public OsPrivateFileSystem(boolean windows) {
        this(windows ? Mode.WINDOWS
                : FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                    ? Mode.POSIX : Mode.NONE,
            new JnaAclApi(), new WindowsSystem()::userSid);
    }

    OsPrivateFileSystem(Mode mode, AclApi acl, Supplier<Optional<String>> userSid) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.acl = Objects.requireNonNull(acl, "acl");
        this.userSid = Objects.requireNonNull(userSid, "userSid");
    }

    @Override
    public Path createDirectory(Path directory) throws IOException {
        return switch (mode) {
            case POSIX -> restrict(Files.createDirectory(directory,
                PosixFilePermissions.asFileAttribute(DIRECTORY)), DIRECTORY);
            case WINDOWS -> protect(Files.createDirectory(directory), true);
            case NONE -> throw unsupported();
        };
    }

    @Override
    public boolean createDirectories(Path directory) throws IOException {
        if (mode == Mode.NONE) {
            throw unsupported();
        }
        Path absolute = directory.toAbsolutePath().normalize();
        if (Files.exists(absolute) && !Files.isDirectory(absolute)) {
            throw new FileAlreadyExistsException(absolute.toString()); // as Files.createDirectories
        }
        List<Path> missing = new ArrayList<>();
        for (Path path = absolute; path != null && !Files.exists(path); path = path.getParent()) {
            missing.addFirst(path);
        }
        boolean created = false;
        for (Path path : missing) {
            try {
                createDirectory(path);
                created = path.equals(absolute);
            } catch (FileAlreadyExistsException e) {
                // created concurrently: not ours, not changed
            }
        }
        return created;
    }

    @Override
    public Path createTempDirectory(Path parent, String prefix) throws IOException {
        return switch (mode) {
            case POSIX -> restrict(Files.createTempDirectory(parent, prefix,
                PosixFilePermissions.asFileAttribute(DIRECTORY)), DIRECTORY);
            case WINDOWS -> protect(Files.createTempDirectory(parent, prefix), true);
            case NONE -> throw unsupported();
        };
    }

    @Override
    public Path createFile(Path file) throws IOException {
        return switch (mode) {
            case POSIX -> restrict(Files.createFile(file,
                PosixFilePermissions.asFileAttribute(FILE)), FILE);
            case WINDOWS -> protect(Files.createFile(file), false);
            case NONE -> throw unsupported();
        };
    }

    @Override
    public void tightenOnPosix(Path path) throws IOException {
        if (mode == Mode.POSIX) {
            restrict(path, Files.isDirectory(path) ? DIRECTORY : FILE);
        }
    }

    @Override
    public List<String> check(Path path) throws IOException {
        if (mode != Mode.WINDOWS) {
            return List.of();
        }
        String user = user(path);
        Optional<List<Principal>> allowed;
        try {
            allowed = acl.allowed(path);
        } catch (LinkageError e) {
            throw accessListFailure(path, e);
        }
        if (allowed.isEmpty()) {
            return List.of(EVERYONE);
        }
        return allowed.get().stream()
            .filter(p -> !p.sid().equals(user) && !p.sid().equals(JnaAclApi.SYSTEM_SID))
            .map(Principal::name)
            .distinct()
            .toList();
    }

    @Override
    public boolean ownedByCurrentUser(Path path) throws IOException {
        if (mode == Mode.WINDOWS) {
            try {
                return acl.ownerSid(path).equals(user(path));
            } catch (LinkageError e) {
                throw accessListFailure(path, e);
            }
        }
        return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(path.getFileSystem()
            .getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name")));
    }

    private static Path restrict(Path path, Set<PosixFilePermission> permissions)
        throws IOException {
        Files.setPosixFilePermissions(path, permissions); // independent of the umask
        return path;
    }

    /** Applies the access list to the new, empty {@code path}, else deletes it and fails. */
    private Path protect(Path path, boolean directory) throws IOException {
        try {
            acl.protect(path, user(path), directory);
            return path;
        } catch (IOException | RuntimeException | LinkageError e) {
            IOException failure = e instanceof IOException io ? io : accessListFailure(path, e);
            try {
                Files.deleteIfExists(path);
            } catch (IOException notDeleted) {
                failure.addSuppressed(notDeleted);
            }
            throw failure;
        }
    }

    /** A native library that cannot be loaded, or another unexpected failure of a native call. */
    private static FileSystemException accessListFailure(Path path, Throwable cause) {
        FileSystemException failure = new FileSystemException(path.toString(), null,
            "the access list cannot be read or set (" + cause + ")");
        failure.initCause(cause);
        return failure;
    }

    @Override
    public Optional<String> currentUserSid() {
        if (mode != Mode.WINDOWS) {
            return Optional.empty();
        }
        try {
            return userSid.get();
        } catch (LinkageError e) {
            return Optional.empty();
        }
    }

    private String user(Path path) throws FileSystemException {
        return userSid.get().orElseThrow(() -> new FileSystemException(path.toString(), null,
            "the user of the process token cannot be read"));
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(
            "the file system supports neither POSIX permissions nor Windows access lists");
    }
}
