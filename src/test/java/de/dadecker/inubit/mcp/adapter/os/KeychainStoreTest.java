package de.dadecker.inubit.mcp.adapter.os;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.config.CredentialStore.Lookup;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.StoreKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Feature 011 T004 (research R-3, quickstart Q5): the macOS Keychain adapter against recorded
 * outputs of {@code security find-generic-password -g}, replayed by {@link FakeProcessLauncher}.
 */
class KeychainStoreTest {

    private static final String ACCOUNT = "INUBIT_ACME_DEV_PASSWORD";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Map<String, String> ENVIRONMENT = Map.of("HOME", "/Users/jdoe",
        "USER", "jdoe", "LANG", "en_US.UTF-8", "INUBIT_ACME_DEV_PASSWORD", "leftover-Pw-9",
        "PATH", "/usr/bin");
    /** What security prints to stderr before the password line. */
    private static final String ATTRIBUTES = """
        keychain: "/Users/jdoe/Library/Keychains/login.keychain-db"
        version: 512
        class: "genp"
        attributes:
            "acct"<blob>="INUBIT_ACME_DEV_PASSWORD"
            "svce"<blob>="inubit-mcp"
        """;

    private static KeychainStore store(FakeProcessLauncher launcher) {
        return new KeychainStore(launcher, new StoreGate(), ENVIRONMENT, Path.of("/tmp"));
    }

    @Test
    void readsAPrintableValueWithOnlyNamesAsArguments() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("",
            "password: \"test-Pw-1\"\n" + ATTRIBUTES, 0);

        Lookup lookup = store(launcher).read(ACCOUNT, TIMEOUT);

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Found.class,
            found -> assertThat(found.value().reveal()).isEqualTo("test-Pw-1"));
        assertThat(launcher.last().spec().command()).containsExactly("/usr/bin/security",
            "find-generic-password", "-s", "inubit-mcp", "-a", ACCOUNT, "-g");
        assertThat(launcher.last().stdinClosed()).isTrue();
    }

    @Test
    void passesOnlyHomeUserAndLang() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "password: \"test-Pw-1\"\n", 0);

        store(launcher).read(ACCOUNT, TIMEOUT);

        assertThat(launcher.last().spec().environment()).containsOnlyKeys("HOME", "USER", "LANG");
    }

    @Test
    void decodesTheMarkedHexFormAsUtf8() {
        String value = "Pässwort-€1";
        String hex = HexFormat.of().withUpperCase()
            .formatHex(value.getBytes(StandardCharsets.UTF_8));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("",
            "password: 0x" + hex + "  \"P\\303\\244sswort-\\342\\202\\2541\"\n", 0);

        Lookup lookup = store(launcher).read(ACCOUNT, TIMEOUT);

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Found.class,
            found -> assertThat(found.value().reveal()).isEqualTo(value));
    }

    @Test
    void aValueOfHexDigitsInQuotesIsTakenAsText() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "password: \"deadbeef\"\n", 0);

        assertThat(store(launcher).read(ACCOUNT, TIMEOUT)).isInstanceOfSatisfying(
            Lookup.Found.class, found -> assertThat(found.value().reveal()).isEqualTo("deadbeef"));
    }

    @Test
    void malformedUtf8IsAnInvalidValueWithoutTheBytes() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "password: 0xC328  \"\"\n", 0);

        Lookup lookup = store(launcher).read(ACCOUNT, TIMEOUT);

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class, failed -> {
            assertThat(failed.kind()).isEqualTo(FailureKind.INVALID_VALUE);
            assertThat(failed.detail()).doesNotContain("C328");
        });
    }

    @Test
    void anEmptyPasswordLineIsAnEmptyValue() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "password: \n" + ATTRIBUTES, 0);

        assertThat(store(launcher).read(ACCOUNT, TIMEOUT)).isInstanceOfSatisfying(
            Lookup.Found.class, found -> assertThat(found.value().reveal()).isEmpty());
    }

    @Test
    void exit44IsNotFound() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("",
            "security: SecKeychainSearchCopyNext: The specified item could not be found in the"
                + " keychain.\n", 44);

        assertThat(store(launcher).read(ACCOUNT, TIMEOUT)).isInstanceOf(Lookup.NotFound.class);
    }

    @ParameterizedTest
    @CsvSource({
        "128, ACCESS_DENIED",
        "51, ACCESS_DENIED",
        "36, STORE_NOT_AVAILABLE",
        "50, STORE_NOT_AVAILABLE",
        "1, ACCESS_DENIED"
    })
    void theExitCodeDecidesTheOutcome(int exit, String expected) {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("",
            "security: SecKeychainSearchCopyNext: some text\n", exit);

        Lookup lookup = store(launcher).read(ACCOUNT, TIMEOUT);

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class, failed -> {
            assertThat(failed.kind()).isEqualTo(FailureKind.valueOf(expected));
            assertThat(failed.detail()).contains("exit " + exit, "some text");
        });
    }

    @Test
    void aPasswordLineWithAnUnexpectedExitCodeNeverReachesTheDetail() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("",
            ATTRIBUTES + "password: \"secret-Pw-7\"\nsecurity: something odd\n", 3);

        Lookup lookup = store(launcher).read(ACCOUNT, TIMEOUT);

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class, failed -> {
            assertThat(failed.detail()).contains("something odd").doesNotContain("secret-Pw-7");
            assertThat(failed.toString()).doesNotContain("secret-Pw-7");
        });
    }

    @Test
    void exitZeroWithoutAPasswordLineIsNoValue() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", ATTRIBUTES, 0);

        assertThat(store(launcher).read(ACCOUNT, TIMEOUT)).isInstanceOfSatisfying(
            Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.ACCESS_DENIED));
    }

    @Test
    void aHangingReadTimesOutAndIsKilled() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).hanging();

        Lookup lookup = store(launcher).read(ACCOUNT, Duration.ofMillis(200));

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.TIMEOUT));
        assertThat(launcher.last().destroyed()).isTrue();
    }

    @Test
    void aToolThatIgnoresTheTerminationIsKilled() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).ignoringDestroy();

        Lookup lookup = store(launcher).read(ACCOUNT, Duration.ofMillis(200));

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.TIMEOUT));
        assertThat(launcher.last().destroyedForcibly()).isTrue();
    }

    @Test
    void aMissingToolIsNoStore() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0)
            .failingToLaunch(new IOException("No such file"));

        assertThat(store(launcher).read(ACCOUNT, TIMEOUT)).isInstanceOfSatisfying(
            Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.STORE_NOT_AVAILABLE));
    }

    @Test
    void isTheKeychain() {
        assertThat(store(FakeProcessLauncher.of("", "", 0)).kind()).isEqualTo(StoreKind.KEYCHAIN);
    }
}
