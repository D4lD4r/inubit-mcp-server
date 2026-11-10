package de.dadecker.inubit.mcp.adapter.os;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import de.dadecker.inubit.mcp.adapter.os.AclApi.Principal;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Feature 013 (T011, FR-007, research R-8): the private files and directories of the server.
 * POSIX: today's owner-only permissions ({@code rwx------} directories, {@code rw-------} files,
 * set again after creation so the umask does not matter). Windows: the order and the failure
 * handling around the access list, through the {@link AclApi} seam, on every platform; the access
 * list itself is read back on Windows by {@code OsPrivateFileSystemAclTest}. A file system with
 * neither: nothing is created ({@link UnsupportedOperationException}, as today's
 * {@code posix:permissions} attribute), so the callers with a fallback keep it.
 */
class OsPrivateFileSystemTest {

    private static final String USER = "S-1-5-21-1-2-3-1001";
    private static final String SYSTEM = "S-1-5-18";

    @TempDir
    Path root;

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    private static void widen(Path path, String permissions) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }

    private static boolean empty(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return Files.size(path) == 0;
        }
        try (Stream<Path> entries = Files.list(path)) {
            return entries.findAny().isEmpty();
        }
    }

    // --- POSIX: today's permissions -----------------------------------------------------------

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aDirectoryIsOwnerOnly() throws IOException {
        Path directory = new OsPrivateFileSystem(false).createDirectory(root.resolve("d"));

        assertThat(directory).isEqualTo(root.resolve("d")).isEmptyDirectory();
        assertThat(permissions(directory)).isEqualTo("rwx------");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anExistingDirectoryIsNotCreatedAgain() throws IOException {
        Path existing = Files.createDirectory(root.resolve("d"));
        widen(existing, "rwxr-xr-x");

        assertThatThrownBy(() -> new OsPrivateFileSystem(false).createDirectory(existing))
            .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(permissions(existing)).isEqualTo("rwxr-xr-x");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void everyDirectoryCreatedOnTheWayIsOwnerOnlyAndExistingParentsAreNotChanged()
        throws IOException {
        widen(root, "rwxr-xr-x");
        Path directory = root.resolve("a").resolve("b").resolve("c");

        boolean created = new OsPrivateFileSystem(false).createDirectories(directory);

        assertThat(created).isTrue();
        assertThat(directory).isEmptyDirectory();
        assertThat(permissions(root.resolve("a"))).isEqualTo("rwx------");
        assertThat(permissions(root.resolve("a").resolve("b"))).isEqualTo("rwx------");
        assertThat(permissions(directory)).isEqualTo("rwx------");
        assertThat(permissions(root)).isEqualTo("rwxr-xr-x");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anExistingDirectoryIsLeftAsItIsByCreateDirectories() throws IOException {
        Path existing = Files.createDirectory(root.resolve("d"));
        widen(existing, "rwxr-xr-x");

        boolean created = new OsPrivateFileSystem(false).createDirectories(existing);

        assertThat(created).isFalse();
        assertThat(permissions(existing)).isEqualTo("rwxr-xr-x");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aTemporaryDirectoryIsOwnerOnlyBelowItsParentWithItsPrefix() throws IOException {
        Path directory = new OsPrivateFileSystem(false)
            .createTempDirectory(root, "inubit-mcp-export-");

        assertThat(directory.getParent()).isEqualTo(root);
        assertThat(directory.getFileName().toString()).startsWith("inubit-mcp-export-");
        assertThat(directory).isEmptyDirectory();
        assertThat(permissions(directory)).isEqualTo("rwx------");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFileIsEmptyAndOwnerOnlyAndNeverReplacesAnExistingOne() throws IOException {
        OsPrivateFileSystem files = new OsPrivateFileSystem(false);

        Path file = files.createFile(root.resolve("f"));

        assertThat(file).isEqualTo(root.resolve("f")).isEmptyFile();
        assertThat(permissions(file)).isEqualTo("rw-------");
        Files.writeString(file, "kept");
        assertThatThrownBy(() -> files.createFile(file))
            .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(file).hasContent("kept");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anExistingDirectoryOrFileIsTightenedOnPosix() throws IOException {
        Path directory = Files.createDirectory(root.resolve("d"));
        widen(directory, "rwxr-xr-x");
        Path file = Files.createFile(root.resolve("f"));
        widen(file, "rw-r--r--");
        OsPrivateFileSystem files = new OsPrivateFileSystem(false);

        files.tightenOnPosix(directory);
        files.tightenOnPosix(file);

        assertThat(permissions(directory)).isEqualTo("rwx------");
        assertThat(permissions(file)).isEqualTo("rw-------");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void checkReportsNothingOnPosixWhereExistingObjectsAreTightenedInstead() throws IOException {
        Path directory = Files.createDirectory(root.resolve("d"));
        widen(directory, "rwxrwxrwx");

        assertThat(new OsPrivateFileSystem(false).check(directory)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "rwx------ | ''",
        "rwx--x--x | ''",
        "rwxr-x--- | readable by group",
        "rwx----w- | writable by others",
        "rwxrw---- | readable and writable by group",
        "rwxr--r-- | readable by group and others",
        "rwxrwxrwx | readable and writable by group and others",
        "rwxr--rw- | readable by group, readable and writable by others",
    })
    @DisabledOnOs(OS.WINDOWS)
    void othersMayAccessReportsGroupAndOtherReadAndWriteBitsNotExecuteAlone(String bits,
        String finding) throws IOException {
        Path directory = Files.createDirectory(root.resolve("d"));
        widen(directory, bits);

        assertThat(new OsPrivateFileSystem(false).othersMayAccess(directory))
            .isEqualTo(finding.isEmpty() ? Optional.empty() : Optional.of(finding));
    }

    @Test
    void othersMayAccessIsEmptyForAMissingPathAndWithoutPermissionSupport() throws IOException {
        Path missing = root.resolve("missing");

        assertThat(new OsPrivateFileSystem(false).othersMayAccess(missing)).isEmpty();
        assertThat(windows().othersMayAccess(missing)).isEmpty();
        assertThat(new OsPrivateFileSystem(OsPrivateFileSystem.Mode.NONE, acl,
            Optional::empty).othersMayAccess(root)).isEmpty();
    }

    @Test
    void onWindowsOthersMayAccessJoinsThePrincipalsCheckReports() throws IOException {
        Path directory = Files.createDirectory(root.resolve("d"));
        assertThat(windows().othersMayAccess(directory)).isEmpty();

        acl.allowed = Optional.of(List.of(new Principal(USER, "HOST\\operator"),
            new Principal(SYSTEM, "NT AUTHORITY\\SYSTEM"),
            new Principal("S-1-5-32-545", "BUILTIN\\Users"),
            new Principal("S-1-1-0", "Everyone")));

        assertThat(windows().othersMayAccess(directory))
            .contains("accessible to BUILTIN\\Users, Everyone");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theOwnerIsComparedWithTheCurrentUser() throws IOException {
        assumeThat(System.getProperty("user.name")).isNotEqualTo("root");
        OsPrivateFileSystem files = new OsPrivateFileSystem(false);

        assertThat(files.ownedByCurrentUser(files.createDirectory(root.resolve("d")))).isTrue();
        assertThat(files.ownedByCurrentUser(Path.of("/"))).isFalse(); // owned by root
    }

    // --- Windows: order and failure handling (every platform, through the seam) ------------

    /** Records the calls; each protected object must still be empty when its list is applied. */
    private static class FakeAcl implements AclApi {

        record Call(Path path, String owner, boolean directory, boolean empty) {
        }

        final List<Call> calls = new ArrayList<>();
        IOException failure;
        String owner = USER;
        Optional<List<Principal>> allowed = Optional.of(List.of());

        @Override
        public void protect(Path path, String ownerSid, boolean directory) throws IOException {
            calls.add(new Call(path, ownerSid, directory, empty(path)));
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public String ownerSid(Path path) {
            return owner;
        }

        @Override
        public Optional<List<Principal>> allowed(Path path) {
            return allowed;
        }
    }

    private final FakeAcl acl = new FakeAcl();

    private OsPrivateFileSystem windows() {
        return new OsPrivateFileSystem(OsPrivateFileSystem.Mode.WINDOWS, acl,
            () -> Optional.of(USER));
    }

    @Test
    void theSidOfTheTokenUserIsKnownOnWindowsOnly() {
        assertThat(windows().currentUserSid()).contains(USER);
        assertThat(new OsPrivateFileSystem(OsPrivateFileSystem.Mode.POSIX, acl,
            () -> Optional.of(USER)).currentUserSid()).isEmpty();
        assertThat(new OsPrivateFileSystem(OsPrivateFileSystem.Mode.WINDOWS, acl,
            Optional::empty).currentUserSid()).isEmpty();
    }

    @Test
    void onWindowsTheEmptyDirectoryGetsTheInheritableListOfTheTokenUser() throws IOException {
        Path directory = windows().createDirectory(root.resolve("d"));

        assertThat(directory).isEmptyDirectory();
        assertThat(acl.calls).containsExactly(new FakeAcl.Call(directory, USER, true, true));
    }

    @Test
    void onWindowsATemporaryDirectoryGetsTheInheritableList() throws IOException {
        Path directory = windows().createTempDirectory(root, "inubit-mcp-export-");

        assertThat(directory.getParent()).isEqualTo(root);
        assertThat(directory.getFileName().toString()).startsWith("inubit-mcp-export-");
        assertThat(acl.calls).containsExactly(new FakeAcl.Call(directory, USER, true, true));
    }

    @Test
    void onWindowsTheEmptyFileGetsTheListWithoutInheritance() throws IOException {
        Path file = windows().createFile(root.resolve("f"));

        assertThat(file).isEmptyFile();
        assertThat(acl.calls).containsExactly(new FakeAcl.Call(file, USER, false, true));
    }

    @Test
    void onWindowsEveryDirectoryCreatedOnTheWayGetsTheListAndExistingOnesAreNotTouched()
        throws IOException {
        Path a = root.resolve("a");
        Path c = a.resolve("b").resolve("c");

        assertThat(windows().createDirectories(c)).isTrue();
        assertThat(windows().createDirectories(c)).isFalse();

        assertThat(acl.calls).extracting(FakeAcl.Call::path)
            .containsExactly(a, a.resolve("b"), c);
        assertThat(acl.calls).allMatch(call -> call.directory() && USER.equals(call.owner()));
    }

    @Test
    void createDirectoriesNormalizesThePathAndCreatesNoStrayDirectory() throws IOException {
        Path b = root.resolve("b");

        assertThat(windows().createDirectories(root.resolve("a").resolve("..").resolve("b")))
            .isTrue();

        assertThat(root.resolve("a")).doesNotExist();
        assertThat(acl.calls).extracting(FakeAcl.Call::path).containsExactly(b);
    }

    @Test
    void createDirectoriesFailsLikeTheJdkWhenAFileIsInTheWay() throws IOException {
        Path file = Files.createFile(root.resolve("f"));

        assertThatThrownBy(() -> windows().createDirectories(file))
            .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(acl.calls).isEmpty();
    }

    @Test
    void onWindowsADirectoryWhoseListCannotBeSetIsDeletedAndTheCallFails() {
        acl.failure = new FileSystemException("d", null, "SetNamedSecurityInfoW failed");

        assertThatThrownBy(() -> windows().createDirectory(root.resolve("d")))
            .isSameAs(acl.failure);
        assertThat(root.resolve("d")).doesNotExist();
    }

    @Test
    void onWindowsATemporaryDirectoryWhoseListCannotBeSetIsDeleted() throws IOException {
        acl.failure = new FileSystemException("d", null, "SetNamedSecurityInfoW failed");

        assertThatThrownBy(() -> windows().createTempDirectory(root, "inubit-mcp-export-"))
            .isSameAs(acl.failure);
        assertThat(root).isEmptyDirectory();
    }

    @Test
    void onWindowsAFileWhoseListCannotBeSetIsDeletedAndTheCallFails() {
        acl.failure = new FileSystemException("f", null, "SetNamedSecurityInfoW failed");

        assertThatThrownBy(() -> windows().createFile(root.resolve("f")))
            .isSameAs(acl.failure);
        assertThat(root.resolve("f")).doesNotExist();
    }

    @Test
    void onWindowsCreateDirectoriesStopsAtTheFirstDirectoryWhoseListCannotBeSet() {
        acl.failure = new FileSystemException("a", null, "SetNamedSecurityInfoW failed");

        assertThatThrownBy(() -> windows().createDirectories(root.resolve("a").resolve("b")))
            .isSameAs(acl.failure);
        assertThat(root).isEmptyDirectory();
    }

    @Test
    void onWindowsANativeLibraryThatCannotBeLoadedFailsTheCallAndDeletesTheObject() {
        OsPrivateFileSystem files = new OsPrivateFileSystem(OsPrivateFileSystem.Mode.WINDOWS,
            new FakeAcl() {
                @Override
                public void protect(Path path, String ownerSid, boolean directory) {
                    throw new UnsatisfiedLinkError("Advapi32");
                }
            }, () -> Optional.of(USER));

        assertThatThrownBy(() -> files.createDirectory(root.resolve("d")))
            .isInstanceOf(IOException.class).hasCauseInstanceOf(UnsatisfiedLinkError.class);
        assertThat(root.resolve("d")).doesNotExist();
    }

    @Test
    void onWindowsANativeLibraryThatCannotBeLoadedFailsCheckAndTheOwnerQueryWithAnIoException() {
        OsPrivateFileSystem files = new OsPrivateFileSystem(OsPrivateFileSystem.Mode.WINDOWS,
            new FakeAcl() {
                @Override
                public String ownerSid(Path path) {
                    throw new UnsatisfiedLinkError("Advapi32");
                }

                @Override
                public Optional<List<Principal>> allowed(Path path) {
                    throw new UnsatisfiedLinkError("Advapi32");
                }
            }, () -> Optional.of(USER));

        assertThatThrownBy(() -> files.check(root))
            .isInstanceOf(FileSystemException.class)
            .hasCauseInstanceOf(UnsatisfiedLinkError.class);
        assertThatThrownBy(() -> files.ownedByCurrentUser(root))
            .isInstanceOf(FileSystemException.class)
            .hasCauseInstanceOf(UnsatisfiedLinkError.class);
    }

    @Test
    void onWindowsAnUnreadableTokenUserFailsTheCallAndDeletesTheObject() {
        OsPrivateFileSystem files = new OsPrivateFileSystem(OsPrivateFileSystem.Mode.WINDOWS,
            acl, Optional::empty);

        assertThatThrownBy(() -> files.createDirectory(root.resolve("d")))
            .isInstanceOf(FileSystemException.class).hasMessageContaining("token");
        assertThat(root.resolve("d")).doesNotExist();
        assertThat(acl.calls).isEmpty();
    }

    @Test
    void onWindowsCheckReportsEveryPrincipalButTheTokenUserAndSystem() throws IOException {
        acl.allowed = Optional.of(List.of(new Principal(USER, "HOST\\operator"),
            new Principal(SYSTEM, "NT AUTHORITY\\SYSTEM"),
            new Principal("S-1-5-32-544", "BUILTIN\\Administrators"),
            new Principal("S-1-5-32-545", "BUILTIN\\Users"),
            new Principal("S-1-5-32-545", "BUILTIN\\Users")));

        assertThat(windows().check(root))
            .containsExactly("BUILTIN\\Administrators", "BUILTIN\\Users");
    }

    @Test
    void onWindowsCheckReportsNothingForThePrivateList() throws IOException {
        acl.allowed = Optional.of(List.of(new Principal(USER, "HOST\\operator"),
            new Principal(SYSTEM, "NT AUTHORITY\\SYSTEM")));

        assertThat(windows().check(root)).isEmpty();
    }

    @Test
    void onWindowsAnObjectWithoutAnAccessListIsReportedAsOpenToEveryone() throws IOException {
        acl.allowed = Optional.empty();

        assertThat(windows().check(root)).containsExactly("Everyone (no access list)");
    }

    @Test
    void onWindowsTheOwnerIsComparedWithTheTokenUser() throws IOException {
        assertThat(windows().ownedByCurrentUser(root)).isTrue();

        acl.owner = "S-1-5-32-544";
        assertThat(windows().ownedByCurrentUser(root)).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void onWindowsTightenOnPosixChangesNothing() throws IOException {
        Path file = Files.createFile(root.resolve("f"));
        widen(file, "rw-r--r--");

        windows().tightenOnPosix(file);

        assertThat(acl.calls).isEmpty();
        assertThat(permissions(file)).isEqualTo("rw-r--r--");
    }

    // --- neither POSIX permissions nor Windows access lists --------------------------------

    private OsPrivateFileSystem neither() {
        return new OsPrivateFileSystem(OsPrivateFileSystem.Mode.NONE, acl,
            () -> Optional.of(USER));
    }

    @Test
    void withoutPermissionSupportNothingIsCreatedOrChanged() throws IOException {
        OsPrivateFileSystem files = neither();

        assertThatThrownBy(() -> files.createDirectory(root.resolve("d")))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> files.createDirectories(root.resolve("a").resolve("b")))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> files.createTempDirectory(root, "inubit-mcp-export-"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> files.createFile(root.resolve("f")))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(root).isEmptyDirectory();
        assertThat(files.check(root)).isEmpty();
        files.tightenOnPosix(root);
        assertThat(acl.calls).isEmpty();
    }
}
