package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.DeploymentLedger;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.infra.AuditLog;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T024, T025 (feature 013, FR-007, US3 AS4): the default audit directory, the audit log and the
 * deployment ledgers and locks with broader access give a warning naming them and the
 * {@code icacls} command that restricts them; nothing is changed. Every platform, with a
 * stubbed {@link PrivateFileSystem}; the Windows read-back is in {@code MainTest}.
 */
class PrivateObjectCheckTest {

    @TempDir
    Path home;

    private final Map<Path, List<String>> granted = new HashMap<>();
    private final List<Path> checked = new ArrayList<>();
    private final Map<Path, IOException> unreadable = new HashMap<>();
    private Optional<String> userSid = Optional.empty();

    @Test
    void theDefaultAuditDirectoryAndItsLogWithOtherPrincipalsAreNamedWithTheRemedy()
        throws IOException {
        Path audit = Files.createDirectories(AuditLog.defaultDirectory(home, "acme"));
        Path log = Files.createFile(audit.resolve("audit-2026-10.jsonl"));
        Files.createFile(audit.resolve("notes.txt"));
        granted.put(audit, List.of("BUILTIN\\Users", "S-1-5-21-1-2-3-1001"));
        granted.put(log, List.of("NT AUTHORITY\\Authenticated Users"));

        List<String> warnings = check().warnings(audit, home, "acme");

        assertThat(warnings).containsExactly(
            "the audit directory " + audit + " grants access to BUILTIN\\Users,"
                + " S-1-5-21-1-2-3-1001; the server does not change it. Restrict it to jdoe and"
                + " SYSTEM with: icacls \"" + audit + "\" /inheritance:r /grant:r"
                + " \"jdoe:(OI)(CI)F\" \"*S-1-5-18:(OI)(CI)F\" /remove \"BUILTIN\\Users\""
                + " \"*S-1-5-21-1-2-3-1001\"",
            "the audit log " + log + " grants access to NT AUTHORITY\\Authenticated Users; the"
                + " server does not change it. Restrict it to jdoe and SYSTEM with: icacls \""
                + log + "\" /inheritance:r /grant:r \"jdoe:F\" \"*S-1-5-18:F\" /remove"
                + " \"NT AUTHORITY\\Authenticated Users\"");
        assertThat(checked).as("other files are not the audit log").containsExactly(audit, log);
    }

    @Test
    void anObjectWithoutAnAccessListIsNamedAndTheRemedyRemovesNothing() throws IOException {
        Path audit = Files.createDirectories(AuditLog.defaultDirectory(home, "acme"));
        granted.put(audit, List.of("Everyone (no access list)"));

        assertThat(check().warnings(audit, home, "acme")).singleElement().asString()
            .contains("grants access to Everyone (no access list);")
            .endsWith("\"*S-1-5-18:(OI)(CI)F\"");
    }

    @Test
    void privateObjectsGiveNoWarning() throws IOException {
        Path audit = Files.createDirectories(AuditLog.defaultDirectory(home, "acme"));
        Files.createFile(audit.resolve("audit-2026-10.jsonl"));

        assertThat(check().warnings(audit, home, "acme")).isEmpty();
        assertThat(checked).hasSize(2);
    }

    @Test
    void aConfiguredAuditDirectoryIsUsedAsItIs() throws IOException {
        Path audit = Files.createDirectories(home.resolve("chosen"));
        Files.createDirectories(AuditLog.defaultDirectory(home, "acme"));
        granted.put(audit, List.of("BUILTIN\\Users"));

        assertThat(check().warnings(audit, home, "acme")).isEmpty();
        assertThat(checked).isEmpty();
    }

    @Test
    void aMissingDefaultAuditDirectoryAndAnInvalidProfileNameAreNotChecked() {
        Path audit = AuditLog.defaultDirectory(home, "acme");

        assertThat(check().warnings(audit, home, "acme")).isEmpty();
        assertThat(check().warnings(audit, home, "../acme")).isEmpty();
        assertThat(checked).isEmpty();
    }

    @Test
    void anAccessListThatCannotBeReadIsAWarning() throws IOException {
        Path audit = Files.createDirectories(AuditLog.defaultDirectory(home, "acme"));
        unreadable.put(audit, new AccessDeniedException(audit.toString()));

        assertThat(check().warnings(audit, home, "acme")).containsExactly(
            "the access of the audit directory " + audit + " could not be checked"
                + " (AccessDeniedException: " + audit + ")");
    }

    @Test
    void theDeploymentLedgerAndLockWithOtherPrincipalsAreNamedWithTheRemedy()
        throws IOException {
        Path deployments = Files.createDirectories(DeploymentLedger.defaultDirectory(home,
            "acme"));
        Path ledger = Files.createFile(deployments.resolve("int.ledger.json"));
        Path lock = Files.createFile(deployments.resolve("int.lock"));
        Path qaLock = Files.createFile(deployments.resolve("qa.lock"));
        Files.createFile(deployments.resolve(".ledger-1.tmp"));
        Files.createFile(deployments.resolve("notes.txt"));
        granted.put(ledger, List.of("BUILTIN\\Users"));
        granted.put(lock, List.of("S-1-5-21-1-2-3-1001"));
        Path audit = AuditLog.defaultDirectory(home, "acme");

        List<String> warnings = check().warnings(audit, home, "acme");

        assertThat(warnings).containsExactly(
            "the deployment ledger " + ledger + " grants access to BUILTIN\\Users; the server"
                + " does not change it. Restrict it to jdoe and SYSTEM with: icacls \"" + ledger
                + "\" /inheritance:r /grant:r \"jdoe:F\" \"*S-1-5-18:F\" /remove"
                + " \"BUILTIN\\Users\"",
            "the deployment lock " + lock + " grants access to S-1-5-21-1-2-3-1001; the server"
                + " does not change it. Restrict it to jdoe and SYSTEM with: icacls \"" + lock
                + "\" /inheritance:r /grant:r \"jdoe:F\" \"*S-1-5-18:F\" /remove"
                + " \"*S-1-5-21-1-2-3-1001\"");
        assertThat(checked).as("only ledgers and locks, not the directory")
            .containsExactly(ledger, lock, qaLock);
    }

    @Test
    void theDeploymentFilesAreCheckedAfterTheAuditObjectsAlsoWithAConfiguredAuditDirectory()
        throws IOException {
        Path audit = Files.createDirectories(AuditLog.defaultDirectory(home, "acme"));
        Path deployments = Files.createDirectories(DeploymentLedger.defaultDirectory(home,
            "acme"));
        Path ledger = Files.createFile(deployments.resolve("int.ledger.json"));
        granted.put(ledger, List.of("BUILTIN\\Users"));

        assertThat(check().warnings(audit, home, "acme")).hasSize(1);
        assertThat(checked).containsExactly(audit, ledger);
        checked.clear();
        assertThat(check().warnings(home.resolve("chosen"), home, "acme")).singleElement()
            .asString().startsWith("the deployment ledger " + ledger + " grants access to");
        assertThat(checked).containsExactly(ledger);
    }

    @Test
    void privateOrMissingDeploymentFilesGiveNoWarning() throws IOException {
        Path audit = AuditLog.defaultDirectory(home, "acme");
        assertThat(check().warnings(audit, home, "acme")).isEmpty();
        assertThat(checked).isEmpty();

        Path deployments = Files.createDirectories(DeploymentLedger.defaultDirectory(home,
            "acme"));
        assertThat(check().warnings(audit, home, "acme")).isEmpty();
        assertThat(checked).isEmpty();

        Files.createFile(deployments.resolve("int.ledger.json"));
        Files.createFile(deployments.resolve("int.lock"));
        assertThat(check().warnings(audit, home, "acme")).isEmpty();
        assertThat(check().warnings(audit, home, "../acme")).isEmpty();
        assertThat(checked).hasSize(2);
    }

    @Test
    void aDeploymentFileWhoseAccessListCannotBeReadIsAWarning() throws IOException {
        Path deployments = Files.createDirectories(DeploymentLedger.defaultDirectory(home,
            "acme"));
        Path lock = Files.createFile(deployments.resolve("int.lock"));
        unreadable.put(lock, new AccessDeniedException(lock.toString()));

        assertThat(check().warnings(AuditLog.defaultDirectory(home, "acme"), home, "acme"))
            .containsExactly("the access of the deployment lock " + lock + " could not be"
                + " checked (AccessDeniedException: " + lock + ")");
    }

    @Test
    void theRemedyGrantsToTheSidOfTheTokenUserWhenItIsKnown() throws IOException {
        Path audit = Files.createDirectories(AuditLog.defaultDirectory(home, "acme"));
        granted.put(audit, List.of("BUILTIN\\Users"));
        userSid = Optional.of("S-1-12-1-1-2-3-4");

        assertThat(check().warnings(audit, home, "acme")).singleElement().asString()
            .contains("Restrict it to jdoe and SYSTEM with: icacls \"" + audit + "\""
                + " /inheritance:r /grant:r \"*S-1-12-1-1-2-3-4:(OI)(CI)F\""
                + " \"*S-1-5-18:(OI)(CI)F\" /remove \"BUILTIN\\Users\"");
    }

    private PrivateObjectCheck check() {
        return new PrivateObjectCheck(new StubFileSystem(), "jdoe");
    }

    /** Only {@link #check} is used. */
    private final class StubFileSystem implements PrivateFileSystem {

        @Override
        public List<String> check(Path path) throws IOException {
            checked.add(path);
            if (unreadable.containsKey(path)) {
                throw unreadable.get(path);
            }
            return granted.getOrDefault(path, List.of());
        }

        @Override
        public Path createDirectory(Path directory) {
            throw new AssertionError("nothing is created");
        }

        @Override
        public boolean createDirectories(Path directory) {
            throw new AssertionError("nothing is created");
        }

        @Override
        public Path createTempDirectory(Path parent, String prefix) {
            throw new AssertionError("nothing is created");
        }

        @Override
        public Path createFile(Path file) {
            throw new AssertionError("nothing is created");
        }

        @Override
        public void tightenOnPosix(Path path) {
            throw new AssertionError("nothing is changed");
        }

        @Override
        public boolean ownedByCurrentUser(Path path) {
            throw new AssertionError("not asked");
        }

        @Override
        public Optional<String> othersMayAccess(Path path) {
            throw new AssertionError("not asked");
        }

        @Override
        public Optional<String> currentUserSid() {
            return userSid;
        }
    }
}
