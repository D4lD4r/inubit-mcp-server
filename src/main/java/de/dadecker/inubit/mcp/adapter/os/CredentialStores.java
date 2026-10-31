package de.dadecker.inubit.mcp.adapter.os;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.config.CredentialStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Picks the credential store of the running system once at startup (feature 011, research R-1):
 * macOS Keychain, Windows Credential Manager, Linux Secret Service, otherwise none. All stores share
 * one {@link StoreGate}, so the server reads one entry at a time.
 */
public final class CredentialStores {

    /** The one lock of the process (research R-8), shared by every store {@link #forOs} builds. */
    private static final StoreGate GATE = new StoreGate();

    private CredentialStores() {
    }

    /**
     * @param osName the value of the system property {@code os.name}
     * @param environment the server's environment (each adapter passes on only its allowlist)
     * @param home the working directory of the store tools
     */
    public static CredentialStore forOs(String osName, ProcessLauncher launcher,
        Map<String, String> environment, Path home) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("mac")) {
            return new KeychainStore(launcher, GATE, environment, home);
        }
        if (name.startsWith("windows")) {
            return new WindowsCredentialStore(GATE);
        }
        if (name.startsWith("linux")) {
            return new SecretServiceStore(launcher, GATE, environment, home, Files::isExecutable);
        }
        return new UnavailableStore(osName);
    }
}
