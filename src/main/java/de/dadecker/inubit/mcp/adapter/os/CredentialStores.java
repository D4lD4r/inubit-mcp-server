package de.dadecker.inubit.mcp.adapter.os;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.config.CredentialStore;
import de.dadecker.inubit.mcp.config.StoreKind;
import java.nio.file.Files;
import java.nio.file.Path;
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
        return switch (StoreKind.forOs(osName)) {
            case KEYCHAIN -> new KeychainStore(launcher, GATE, environment, home);
            case CREDENTIAL_MANAGER -> new WindowsCredentialStore(GATE);
            case SECRET_SERVICE -> new SecretServiceStore(launcher, GATE, environment, home,
                Files::isExecutable);
            case NONE -> new UnavailableStore(osName);
        };
    }
}
