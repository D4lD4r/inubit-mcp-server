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
}
