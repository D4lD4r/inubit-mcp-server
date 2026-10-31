package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.os.CredentialStores;
import de.dadecker.inubit.mcp.config.CredentialStore;
import de.dadecker.inubit.mcp.config.CredentialStore.Lookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Feature 011 T019 (FR-017, research R-14): reads entries of the fictitious profile
 * {@code livetest} from the real credential store of this system (macOS Keychain, Windows
 * Credential Manager, Linux Secret Service), through the adapter the server uses. Opt-in twice:
 * the tag {@code live-store} ({@code mvn verify -Plive-store}) and {@code INUBIT_LIVE_STORE=1};
 * without the variable the test is skipped. It never contacts INUBIT and never prints a value.
 *
 * <p>The person creates the entries beforehand as {@code docs/live-tests.md} describes:
 * {@value #USERNAME_ENTRY} and {@value #PASSWORD_ENTRY} (a password with {@code ä€}, to cover
 * the encoding), and passes the expectation: {@value #EXPECTED_USERNAME} (the username) and
 * {@value #EXPECTED_PASSWORD_SHA256} (the SHA-256 of the password's UTF-8 bytes, hex). The entry
 * {@value #MISSING_ENTRY} must not exist. The access-denied case is a manual step
 * (docs/live-tests.md); the Windows Credential Manager has none.
 */
@Tag("live-store")
@Timeout(180)
class CredentialStoreLiveTest {

    static final String ENABLE = "INUBIT_LIVE_STORE";
    static final String USERNAME_ENTRY = "INUBIT_LIVETEST_DEV_USERNAME";
    static final String PASSWORD_ENTRY = "INUBIT_LIVETEST_DEV_PASSWORD";
    static final String MISSING_ENTRY = "INUBIT_LIVETEST_DEV_NODE9_PASSWORD";
    static final String EXPECTED_USERNAME = "INUBIT_LIVE_STORE_USERNAME";
    static final String EXPECTED_PASSWORD_SHA256 = "INUBIT_LIVE_STORE_PASSWORD_SHA256";
    /** The bound of one read: an access dialog may be open. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    /** Skips unless {@value #ENABLE} is {@code 1}. */
    static void requireEnabled(Map<String, String> environment) {
        Assumptions.assumeTrue("1".equals(environment.get(ENABLE)), ENABLE + " is not 1; the"
            + " credential store live check is skipped (docs/live-tests.md)");
    }

    @Test
    void readsThePersonCreatedEntriesOfThisSystem() {
        Map<String, String> environment = System.getenv();
        requireEnabled(environment);
        assertThat(environment).as("the expectation (docs/live-tests.md)")
            .containsKeys(EXPECTED_USERNAME, EXPECTED_PASSWORD_SHA256);
        CredentialStore store = CredentialStores.forOs(System.getProperty("os.name"),
            new SystemProcessLauncher(), environment, Path.of(System.getProperty("user.home")));

        Lookup username = store.read(USERNAME_ENTRY, READ_TIMEOUT);
        Lookup password = store.read(PASSWORD_ENTRY, READ_TIMEOUT);
        Lookup missing = store.read(MISSING_ENTRY, READ_TIMEOUT);

        // a secret-free summary for the person running the check (kinds only, never a value)
        System.err.println("[live-store] " + store.kind().displayName() + ": "
            + USERNAME_ENTRY + " " + kind(username) + ", " + PASSWORD_ENTRY + " "
            + kind(password) + ", " + MISSING_ENTRY + " " + kind(missing));
        assertThat(username).as(USERNAME_ENTRY).isInstanceOfSatisfying(Lookup.Found.class,
            found -> assertThat(found.value().reveal()).as("the username")
                .isEqualTo(environment.get(EXPECTED_USERNAME)));
        assertThat(password).as(PASSWORD_ENTRY).isInstanceOfSatisfying(Lookup.Found.class,
            found -> {
                String value = found.value().reveal();
                assertThat(sha256(value)).as("SHA-256 of the password read")
                    .isEqualToIgnoringCase(environment.get(EXPECTED_PASSWORD_SHA256).strip());
                assertThat(value.chars().anyMatch(c -> c > 127))
                    .as("the password contains a non-ASCII character (encoding case)").isTrue();
            });
        assertThat(missing).as(MISSING_ENTRY).isInstanceOf(Lookup.NotFound.class);
    }

    private static String kind(Lookup lookup) {
        return switch (lookup) {
            case Lookup.Found found -> "found";
            case Lookup.NotFound notFound -> "not found";
            case Lookup.Failed failed -> "failed (" + failed.kind() + ")";
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
