package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.adapter.os.OsPrivateFileSystem;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** {@link PrivateFileSystem}s for tests (feature 013, FR-007). */
public final class PrivateFiles {

    private PrivateFiles() {
    }

    /** The production file system of the host the tests run on. */
    public static PrivateFileSystem forHost() {
        return new OsPrivateFileSystem(Platform.windows());
    }

    /**
     * The host's file system, except that {@link PrivateFileSystem#createFile} fails as in
     * {@link #failing()}: the directory is created, the file in it is not.
     */
    public static PrivateFileSystem failingFiles() {
        PrivateFileSystem host = forHost();
        PrivateFileSystem failing = failing();
        return new PrivateFileSystem() {
            @Override
            public Path createDirectory(Path directory) throws IOException {
                return host.createDirectory(directory);
            }

            @Override
            public boolean createDirectories(Path directory) throws IOException {
                return host.createDirectories(directory);
            }

            @Override
            public Path createTempDirectory(Path parent, String prefix) throws IOException {
                return host.createTempDirectory(parent, prefix);
            }

            @Override
            public Path createFile(Path file) throws IOException {
                return failing.createFile(file);
            }

            @Override
            public void tightenOnPosix(Path path) throws IOException {
                host.tightenOnPosix(path);
            }

            @Override
            public List<String> check(Path path) throws IOException {
                return host.check(path);
            }

            @Override
            public boolean ownedByCurrentUser(Path path) throws IOException {
                return host.ownedByCurrentUser(path);
            }
        };
    }

    /**
     * A file system whose {@code create…} calls fail as when the access list cannot be set: the
     * object is not created and an {@link IOException} names it.
     */
    public static PrivateFileSystem failing() {
        return new PrivateFileSystem() {
            @Override
            public Path createDirectory(Path directory) throws IOException {
                throw refused(directory);
            }

            @Override
            public boolean createDirectories(Path directory) throws IOException {
                throw refused(directory);
            }

            @Override
            public Path createTempDirectory(Path parent, String prefix) throws IOException {
                throw refused(parent.resolve(prefix));
            }

            @Override
            public Path createFile(Path file) throws IOException {
                throw refused(file);
            }

            @Override
            public void tightenOnPosix(Path path) {
            }

            @Override
            public List<String> check(Path path) {
                return List.of();
            }

            @Override
            public boolean ownedByCurrentUser(Path path) {
                return true;
            }

            private IOException refused(Path path) {
                return new java.nio.file.FileSystemException(path.toString(), null,
                    "the access list could not be set (simulated)");
            }
        };
    }
}
