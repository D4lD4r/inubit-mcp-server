package de.dadecker.inubit.mcp.application;

import java.io.IOException;
import java.nio.file.Path;

/**
 * T010 helper, run as a second JVM: holds the workspace lock of {@code args[0]}, prints
 * {@code locked} and keeps it until its stdin is closed.
 */
public final class LockHolder {

    private LockHolder() {
    }

    public static void main(String[] args) throws IOException {
        try (WorkspaceLock lock = WorkspaceLock.acquire(Path.of(args[0]))) {
            System.out.println("locked");
            System.out.flush();
            while (System.in.read() >= 0) {
                // wait for the test to close stdin
            }
        }
    }
}
