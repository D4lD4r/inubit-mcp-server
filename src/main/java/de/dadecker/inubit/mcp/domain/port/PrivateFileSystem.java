package de.dadecker.inubit.mcp.domain.port;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

/**
 * Creates the files and directories the server keeps private (feature 013, FR-007, research R-8):
 * temporary export and import data, backups, packages, the deployment lock and ledger, the
 * workspace, the empty git hooks directory, the audit directory and log.
 *
 * <ul>
 *   <li>POSIX: directories {@code rwx------}, files {@code rw-------}, given at creation and set
 *       again right after it, independent of the umask.
 *   <li>Windows: the object is created empty, then its owner is set to the user of the process
 *       token and a protected access list (no entries inherited from above) is applied that grants
 *       full control to that user and {@code SYSTEM} only, inherited by everything created below a
 *       directory. If that fails, the object is deleted and the call fails with an
 *       {@link IOException}.
 *   <li>A file system with neither (a POSIX system without permission support): every
 *       {@code create…} call throws {@link UnsupportedOperationException} and creates nothing, as
 *       the {@code posix:permissions} attribute did; callers that fall back today (the audit
 *       directory, the workspace) create the object without restricted access then.
 * </ul>
 */
public interface PrivateFileSystem {

    /**
     * Creates the private directory {@code directory}; its parent must exist.
     *
     * @throws java.nio.file.FileAlreadyExistsException if {@code directory} exists (it is not
     *     changed)
     */
    Path createDirectory(Path directory) throws IOException;

    /**
     * Creates {@code directory} and its missing parents, from the top, each one as by
     * {@link #createDirectory}. A directory that exists, or that another process creates
     * meanwhile, is left as it is.
     *
     * @return true if this call created {@code directory} itself
     * @throws java.nio.file.FileAlreadyExistsException if something other than a directory exists
     *     at {@code directory} (as {@code Files.createDirectories})
     */
    boolean createDirectories(Path directory) throws IOException;

    /** Creates a new private directory in {@code parent} whose name starts with {@code prefix}. */
    Path createTempDirectory(Path parent, String prefix) throws IOException;

    /**
     * Creates the empty private file {@code file}; its directory must exist.
     *
     * @throws java.nio.file.FileAlreadyExistsException if {@code file} exists (it is not changed)
     */
    Path createFile(Path file) throws IOException;

    /**
     * Sets an existing directory to {@code rwx------} or file to {@code rw-------} on POSIX, as
     * the server has always done with the private objects it re-uses. Changes nothing elsewhere:
     * on Windows the server does not rewrite access lists it did not set ({@link #check} reports
     * them).
     */
    void tightenOnPosix(Path path) throws IOException;

    /**
     * The principals other than the current user and {@code SYSTEM} that the access list of the
     * existing {@code path} grants access to (Windows); nothing is changed. Empty on POSIX, where
     * existing objects are tightened instead ({@link #tightenOnPosix}).
     */
    List<String> check(Path path) throws IOException;

    /**
     * Who else may read or write the existing {@code path} (feature 019, FR-003, research D-3);
     * nothing is changed. POSIX: the group and other read and write bits, e.g. {@code readable by
     * group and others} or {@code readable by group, readable and writable by others} (execute
     * alone is not reported). Windows: {@code accessible to <principals>} as {@link #check}
     * reports them. Empty if nobody else may, if {@code path} does not exist, or on a file system
     * with neither.
     */
    Optional<String> othersMayAccess(Path path) throws IOException;

    /**
     * True if the owner of {@code path} is the current user: on Windows the user of the process
     * token (also on an elevated account, whose new objects may be owned by
     * {@code BUILTIN\Administrators} otherwise), elsewhere the account {@code user.name}.
     */
    boolean ownedByCurrentUser(Path path) throws IOException;

    /**
     * The SID of the user of the process token on Windows, so that a remedy can name the user
     * unambiguously ({@code icacls … /grant:r "*<sid>:F"}; an account name may resolve to another
     * account, e.g. on an Entra ID joined machine); empty elsewhere or if it cannot be read.
     */
    default Optional<String> currentUserSid() {
        return Optional.empty();
    }

    /**
     * Opens an existing lock file for writing (feature 013 T025 r1). On Windows the file cannot
     * be deleted while it is open, so that another process whose private creation of the same
     * file failed cannot remove a lock a deployment holds; elsewhere a plain write channel.
     */
    default FileChannel openLockFile(Path file) throws IOException {
        return FileChannel.open(file, StandardOpenOption.WRITE);
    }

    /**
     * Opens an existing lock file of the local knowledge for writing without following a link
     * in its place (feature 019 D-11): a link is refused and its target left untouched; like
     * {@link #openLockFile} the file cannot be deleted on Windows while it is open.
     */
    default FileChannel openLockFileNoFollow(Path file) throws IOException {
        return FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
}
