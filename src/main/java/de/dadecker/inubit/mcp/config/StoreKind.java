package de.dadecker.inubit.mcp.config;

/**
 * The credential store of the operating system the server runs on (feature 011, data-model →
 * CredentialStore port); its display name appears in messages and {@code --check-config}.
 */
public enum StoreKind {
    KEYCHAIN("macOS Keychain"),
    CREDENTIAL_MANAGER("Windows Credential Manager"),
    SECRET_SERVICE("Secret Service"),
    /** No supported store on this system: every read is {@code STORE_NOT_AVAILABLE}. */
    NONE("no credential store");

    private final String displayName;

    StoreKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** The store of the system whose {@code os.name} is {@code osName} (research R-1). */
    public static StoreKind forOs(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("mac")) {
            return KEYCHAIN;
        }
        if (name.startsWith("windows")) {
            return CREDENTIAL_MANAGER;
        }
        if (name.startsWith("linux")) {
            return SECRET_SERVICE;
        }
        return NONE;
    }
}
