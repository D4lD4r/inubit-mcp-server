package de.dadecker.inubit.mcp.infra;

import java.io.PrintStream;

/**
 * Keeps stdout free for the MCP stdio transport (research R-16): captures the real
 * {@code System.out} for the transport and redirects {@code System.out} to {@code System.err}, so
 * that stray library output cannot corrupt the protocol stream.
 */
public final class StdoutGuard {

    private static PrintStream original;

    private StdoutGuard() {
    }

    /**
     * Installs the guard and returns the original stdout for the transport. Calling it again
     * returns the same stream.
     */
    public static synchronized PrintStream install() {
        if (original == null) {
            original = System.out;
            System.out.flush();
            System.setOut(System.err);
        }
        return original;
    }

    /** Puts the original stdout back (tests and shutdown). */
    public static synchronized void restore() {
        if (original != null) {
            System.out.flush();
            System.setOut(original);
            original = null;
        }
    }
}
