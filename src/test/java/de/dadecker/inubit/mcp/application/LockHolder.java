package de.dadecker.inubit.mcp.application;

import java.io.IOException;
import java.nio.file.Path;

/**
 * T010 helper, run as a second JVM: holds the workspace lock of {@code args[0]} — or, with
 * {@code deploy <deployments directory> <group>}, the deploy lock of that group (feature 005,
 * T015) — prints {@code locked} and keeps it until its stdin is closed.
 */
public final class LockHolder {

    private LockHolder() {
    }

    public static void main(String[] args) throws IOException {
        if (args[0].equals("deploy")) {
            try (AutoCloseable lock = DeployLock.groupLock(Path.of(args[1]),
                new de.dadecker.inubit.mcp.domain.model.GroupId(args[2]))) {
                System.out.println("locked");
                System.out.flush();
                while (System.in.read() >= 0) {
                    // wait for the test to close stdin
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return;
        }
        try (WorkspaceLock lock = WorkspaceLock.acquire(Path.of(args[0]))) {
            System.out.println("locked");
            System.out.flush();
            while (System.in.read() >= 0) {
                // wait for the test to close stdin
            }
        }
    }
}
