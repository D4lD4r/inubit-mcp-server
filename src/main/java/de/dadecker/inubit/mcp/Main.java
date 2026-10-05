package de.dadecker.inubit.mcp;

import de.dadecker.inubit.mcp.infra.StdoutGuard;
import java.io.PrintStream;
import java.util.Locale;

/**
 * Entry point of the MCP stdio server.
 *
 * <p>This class deliberately has no logger and no static state: the English locale (SDK
 * validation messages, research R-16) and the stdout guard (the real stdout is kept for the
 * protocol, {@code System.out} goes to stderr) are set up <em>before</em> any class that
 * initializes Logback is loaded, so that not even Logback's own status output can reach the
 * protocol stream. Everything else happens in {@link Launcher}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ENGLISH);
        PrintStream protocolOut = StdoutGuard.install();
        int exit = new Launcher(Launcher.Context.system(protocolOut)).run(args);
        protocolOut.flush();
        // The SDK's transport threads are not daemons; exit explicitly once stdin is closed.
        System.exit(exit);
    }
}
