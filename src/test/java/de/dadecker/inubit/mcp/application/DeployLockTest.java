package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T015 (feature 005, FR-021, research D-11): one deployment per target group at a time, also
 * across server processes of the profile; the deploy lock is taken first, then the workspace
 * lock.
 */
class DeployLockTest {

    private static final GroupId INT = new GroupId("int");

    @TempDir
    Path temp;

    private Path deployments() {
        return temp.resolve("profile").resolve("deployments");
    }

    private Path workspace() throws IOException {
        return Files.createDirectories(temp.resolve("workspace"));
    }

    @Test
    void holdsTheGroupLockAndTheWorkspaceLock() throws IOException {
        Path workspace = workspace();
        try (DeployLock lock = DeployLock.acquire(deployments(), INT, workspace)) {
            assertThat(deployments().resolve("int.lock")).isRegularFile();
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(
                deployments()))).isEqualTo("rwx------");
            assertThatThrownBy(() -> WorkspaceLock.acquire(workspace))
                .isInstanceOf(ToolErrorException.class);
            assertThatThrownBy(() -> DeployLock.acquire(deployments(), INT, workspace))
                .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                    assertThat(e.error().code()).isEqualTo(ErrorCode.DEPLOY_LOCKED);
                    assertThat(e.error().message()).contains("int");
                });
        }
        DeployLock.acquire(deployments(), INT, workspace).close();
    }

    @Test
    void aBusyWorkspaceReleasesTheGroupLock() throws IOException {
        Path workspace = workspace();
        try (WorkspaceLock busy = WorkspaceLock.acquire(workspace)) {
            assertThatThrownBy(() -> DeployLock.acquire(deployments(), INT, workspace))
                .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                    e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED));
        }
        // the group lock was given back
        DeployLock.acquire(deployments(), INT, workspace).close();
    }

    @Test
    void anotherGroupIsNotBlockedByTheGroupLockButByTheWorkspace() throws IOException {
        Path workspace = workspace();
        Path other = Files.createDirectories(temp.resolve("other-workspace"));
        try (DeployLock lock = DeployLock.acquire(deployments(), INT, workspace)) {
            DeployLock.acquire(deployments(), new GroupId("qa"), other).close();
            assertThatThrownBy(() -> DeployLock.acquire(deployments(), new GroupId("qa"),
                workspace)).isInstanceOfSatisfying(ToolErrorException.class, e ->
                    assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED));
        }
    }

    @Test
    void aDeploymentOfAnotherProcessIsRefusedUntilItEnds() throws Exception {
        Path workspace = workspace();
        Files.createDirectories(deployments());
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process holder = new ProcessBuilder(List.of(java.toString(), "-cp",
            System.getProperty("java.class.path"), LockHolder.class.getName(), "deploy",
            deployments().toString(), "int"))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(
                holder.getInputStream(), StandardCharsets.UTF_8));
            assertThat(out.readLine()).isEqualTo("locked");

            assertThatThrownBy(() -> DeployLock.acquire(deployments(), INT, workspace))
                .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                    e.error().code()).isEqualTo(ErrorCode.DEPLOY_LOCKED));

            holder.getOutputStream().close();
            assertThat(holder.waitFor(30, TimeUnit.SECONDS)).isTrue();
            DeployLock.acquire(deployments(), INT, workspace).close();
        } finally {
            holder.destroyForcibly();
        }
    }
}
