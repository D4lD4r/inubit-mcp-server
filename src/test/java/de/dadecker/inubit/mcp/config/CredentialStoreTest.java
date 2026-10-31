package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.infra.Secret;
import org.junit.jupiter.api.Test;

/** Feature 011 T002 (research R-2, data-model → CredentialStore port). */
class CredentialStoreTest {

    @Test
    void theServiceIsFixed() {
        assertThat(CredentialStore.SERVICE).isEqualTo("inubit-mcp");
    }

    @Test
    void aFoundValueIsNeverShown() {
        CredentialStore.Lookup found = new CredentialStore.Lookup.Found(Secret.of("hidden-Pw-1"));

        assertThat(found.toString()).doesNotContain("hidden-Pw-1");
    }

    @Test
    void aFailureShowsItsKindButNotItsDetail() {
        CredentialStore.Lookup failed = new CredentialStore.Lookup.Failed(
            FailureKind.ACCESS_DENIED, "raw tool text with account 123");

        assertThat(failed.toString()).contains("ACCESS_DENIED").doesNotContain("123");
    }

    @Test
    void lookupsRejectMissingParts() {
        assertThatThrownBy(() -> new CredentialStore.Lookup.Found(null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CredentialStore.Lookup.Failed(null, ""))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void everyStoreKindHasADisplayName() {
        assertThat(StoreKind.KEYCHAIN.displayName()).isEqualTo("macOS Keychain");
        assertThat(StoreKind.CREDENTIAL_MANAGER.displayName())
            .isEqualTo("Windows Credential Manager");
        assertThat(StoreKind.SECRET_SERVICE.displayName()).isEqualTo("Secret Service");
        assertThat(StoreKind.NONE.displayName()).isEqualTo("no credential store");
    }

    @Test
    void theNewFailureKindsExist() {
        assertThat(FailureKind.valueOf("STORE_NOT_AVAILABLE")).isNotNull();
        assertThat(FailureKind.valueOf("TIMEOUT")).isNotNull();
    }
}
