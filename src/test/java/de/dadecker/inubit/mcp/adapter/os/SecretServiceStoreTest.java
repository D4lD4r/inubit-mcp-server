package de.dadecker.inubit.mcp.adapter.os;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.config.CredentialStore.Lookup;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.StoreKind;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Feature 011 T005 (research R-5, quickstart Q7): the Secret Service adapter against recorded
 * outputs of {@code secret-tool lookup}, replayed by {@link FakeProcessLauncher}.
 */
class SecretServiceStoreTest {

    private static final String ACCOUNT = "INUBIT_ACME_DEV_PASSWORD";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Map<String, String> ENVIRONMENT = Map.of(
        "DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus",
        "XDG_RUNTIME_DIR", "/run/user/1000", "HOME", "/home/jdoe", "USER", "jdoe",
        "PATH", "/opt/tools/bin:/usr/bin", "INUBIT_ACME_DEV_PASSWORD", "leftover-Pw-9",
        "AWS_SECRET_ACCESS_KEY", "aws-key-1");
    private static final Predicate<Path> USR_BIN = Set.of(Path.of("/usr/bin/secret-tool"))::contains;

    private static SecretServiceStore store(FakeProcessLauncher launcher, Predicate<Path> exists) {
        return new SecretServiceStore(launcher, new StoreGate(), ENVIRONMENT, Path.of("/tmp"),
            exists);
    }

    @Test
    void readsTheValueFromStdoutWithOnlyNamesAsArguments() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("Pässwort-€1", "", 0);

        Lookup lookup = store(launcher, USR_BIN).read(ACCOUNT, TIMEOUT);

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Found.class,
            found -> assertThat(found.value().reveal()).isEqualTo("Pässwort-€1"));
        assertThat(launcher.last().spec().command()).containsExactly("/usr/bin/secret-tool",
            "lookup", "service", "inubit-mcp", "account", ACCOUNT);
        assertThat(launcher.last().stdinClosed()).isTrue();
    }

    @Test
    void passesOnlyTheAllowlistAndNoCredentialVariable() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("value-1", "", 0);

        store(launcher, USR_BIN).read(ACCOUNT, TIMEOUT);

        assertThat(launcher.last().spec().environment()).containsOnlyKeys(
            "DBUS_SESSION_BUS_ADDRESS", "XDG_RUNTIME_DIR", "HOME", "USER", "PATH");
    }

    @Test
    void exitOneWithoutOutputIsNotFound() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 1);

        assertThat(store(launcher, USR_BIN).read(ACCOUNT, TIMEOUT))
            .isInstanceOf(Lookup.NotFound.class);
    }

    @Test
    void anotherExitCodeWithoutOutputIsAccessDenied() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 2);

        assertThat(store(launcher, USR_BIN).read(ACCOUNT, TIMEOUT)).isInstanceOfSatisfying(
            Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.ACCESS_DENIED));
    }

    @Test
    void aToolThatLeavesADescendantIsKilledCompletely() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).leavingSurvivor();

        Lookup lookup = store(launcher, USR_BIN).read(ACCOUNT, Duration.ofMillis(200));

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.TIMEOUT));
        assertThat(launcher.last().destroyedForcibly()).isTrue();
    }

    @Test
    void noSessionBusIsNoStore() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("",
            "secret-tool: Cannot autolaunch D-Bus without X11 $DISPLAY\n", 1);

        assertThat(store(launcher, USR_BIN).read(ACCOUNT, TIMEOUT)).isInstanceOfSatisfying(
            Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.STORE_NOT_AVAILABLE));
    }

    @Test
    void noSecretServiceProviderIsNoStore() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("",
            "secret-tool: The name org.freedesktop.secrets was not provided by any .service files\n",
            1);

        assertThat(store(launcher, USR_BIN).read(ACCOUNT, TIMEOUT)).isInstanceOfSatisfying(
            Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.STORE_NOT_AVAILABLE));
    }

    @Test
    void otherErrorsAreAccessDeniedWithStderrOnly() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("partial-Secret-3",
            "secret-tool: Something else went wrong\n", 1);

        Lookup lookup = store(launcher, USR_BIN).read(ACCOUNT, TIMEOUT);

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class, failed -> {
            assertThat(failed.kind()).isEqualTo(FailureKind.ACCESS_DENIED);
            assertThat(failed.detail()).contains("Something else")
                .doesNotContain("partial-Secret-3");
        });
    }

    @Test
    void malformedUtf8IsAnInvalidValue() {
        Lookup lookup = SecretServiceStore.decode(new byte[] {(byte) 0xC3, (byte) 0x28});

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.INVALID_VALUE));
    }

    @Test
    void aHangingReadTimesOutAndIsKilled() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).hanging();

        Lookup lookup = store(launcher, USR_BIN).read(ACCOUNT, Duration.ofMillis(200));

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.TIMEOUT));
        assertThat(launcher.last().destroyed()).isTrue();
    }

    @Test
    void withoutTheToolNoProcessIsStarted() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("value-1", "", 0);

        Lookup lookup = store(launcher, path -> false).read(ACCOUNT, TIMEOUT);

        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class, failed -> {
            assertThat(failed.kind()).isEqualTo(FailureKind.STORE_NOT_AVAILABLE);
            assertThat(failed.detail()).contains("libsecret-tools");
        });
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void fallsBackToTheFirstExecutableOnPath() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("value-1", "", 0);

        store(launcher, Set.of(Path.of("/opt/tools/bin/secret-tool"))::contains)
            .read(ACCOUNT, TIMEOUT);

        assertThat(launcher.last().spec().command().get(0))
            .isEqualTo("/opt/tools/bin/secret-tool");
    }

    @Test
    void isTheSecretService() {
        assertThat(store(FakeProcessLauncher.of("", "", 0), USR_BIN).kind())
            .isEqualTo(StoreKind.SECRET_SERVICE);
    }
}
