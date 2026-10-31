package de.dadecker.inubit.mcp.adapter.os;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.config.CredentialStore;
import de.dadecker.inubit.mcp.config.CredentialStore.Lookup;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.StoreKind;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Feature 011 T007 (research R-1): the store of the running system. */
class CredentialStoresTest {

    private static CredentialStore forOs(String osName) {
        return CredentialStores.forOs(osName, FakeProcessLauncher.of("", "", 0), Map.of(),
            Path.of("/tmp"));
    }

    @ParameterizedTest
    @CsvSource({
        "Mac OS X, KEYCHAIN",
        "Windows 11, CREDENTIAL_MANAGER",
        "Windows Server 2022, CREDENTIAL_MANAGER",
        "Linux, SECRET_SERVICE",
        "FreeBSD, NONE",
        "SunOS, NONE"
    })
    void picksTheStoreBySystem(String osName, StoreKind expected) {
        assertThat(forOs(osName).kind()).isEqualTo(expected);
    }

    @Test
    void anUnknownSystemHasNoStore() {
        Lookup lookup = forOs("FreeBSD").read("INUBIT_ACME_DEV_PASSWORD", Duration.ofSeconds(1));

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class, failed -> {
            assertThat(failed.kind()).isEqualTo(FailureKind.STORE_NOT_AVAILABLE);
            assertThat(failed.detail()).contains("FreeBSD");
        });
    }

    @Test
    void buildingTheStoresLoadsNoNativeCode() {
        for (String osName : new String[] {"Mac OS X", "Windows 11", "Linux", "FreeBSD"}) {
            forOs(osName).kind();
        }

        // JNA sets jna.loaded when its native library is loaded (com.sun.jna.Native's
        // initializer); it is loaded on the first Windows read only (JnaCredApi.Holder)
        assertThat(System.getProperty("jna.loaded")).isNull();
    }
}
