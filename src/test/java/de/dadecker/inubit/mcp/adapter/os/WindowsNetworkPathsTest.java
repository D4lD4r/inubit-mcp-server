package de.dadecker.inubit.mcp.adapter.os;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Feature 015 T015 (research R-10, FR-008): the Windows drive-type probe through the
 * {@link DriveTypeApi} seam; the JNA call itself is covered by the manual Windows check in
 * {@code docs/live-tests.md} only. No test here loads the native library: that would set
 * {@code jna.loaded} for the rest of the test JVM (see {@code CredentialStoresTest}).
 */
class WindowsNetworkPathsTest {

    private final List<String> roots = new ArrayList<>();
    private final AtomicInteger loads = new AtomicInteger();

    private final Logger logger = (Logger) LoggerFactory.getLogger(WindowsNetworkPaths.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void captureLog() {
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
    }

    @AfterEach
    void releaseLog() {
        logger.setAdditive(true);
        logger.detachAppender(appender);
        appender.stop();
    }

    private Supplier<DriveTypeApi> api(int driveType) {
        return () -> {
            loads.incrementAndGet();
            return root -> {
                roots.add(root);
                return driveType;
            };
        };
    }

    @Test
    void aRemoteDriveIsRemote() {
        assertThat(new WindowsNetworkPaths(true, api(4)).remoteDrive('Z')).isTrue();
        assertThat(roots).containsExactly("Z:\\");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 5, 6})
    void everyOtherDriveTypeIsLocal(int driveType) {
        assertThat(new WindowsNetworkPaths(true, api(driveType)).remoteDrive('D')).isFalse();
        assertThat(roots).containsExactly("D:\\");
    }

    @Test
    void aFixedAndARemovableDriveAreLocal() {
        assertThat(new WindowsNetworkPaths(true, api(3)).remoteDrive('C')).isFalse();
        assertThat(new WindowsNetworkPaths(true, api(2)).remoteDrive('E')).isFalse();
        assertThat(roots).containsExactly("C:\\", "E:\\");
    }

    @Test
    void theApiIsAskedForTheUpperCaseDriveRoot() {
        assertThat(new WindowsNetworkPaths(true, api(4)).remoteDrive('z')).isTrue();
        assertThat(roots).containsExactly("Z:\\");
    }

    @ParameterizedTest
    @ValueSource(chars = {'1', ':', '\\', ' ', 'ä', 'Ä', 'ß', 'é', '\u0130', '\u212A'})
    void aCharacterThatIsNotAnAsciiLetterIsLocalWithoutLoadingTheApi(char letter) {
        assertThat(new WindowsNetworkPaths(true, api(4)).remoteDrive(letter)).isFalse();
        assertThat(loads).hasValue(0);
        assertThat(roots).isEmpty();
    }

    @Test
    void withoutTheWindowsFlagTheApiIsNeverLoadedAndTheAnswerIsFalse() {
        WindowsNetworkPaths probe = new WindowsNetworkPaths(false, api(4));

        assertThat(probe.remoteDrive('Z')).isFalse();
        assertThat(probe.remoteDrive('c')).isFalse();
        assertThat(loads).hasValue(0);
        assertThat(roots).isEmpty();
    }

    @Test
    void anApiThatCannotBeLoadedAnswersFalseAndWarnsOnce() {
        AtomicInteger calls = new AtomicInteger();
        WindowsNetworkPaths probe = new WindowsNetworkPaths(true, () -> {
            if (calls.getAndIncrement() == 0) {
                throw new UnsatisfiedLinkError("/opt/example/libkernel32.so: not found");
            }
            throw new NoClassDefFoundError("de/dadecker/inubit/mcp/adapter/os/Holder");
        });

        assertThat(probe.remoteDrive('Z')).isFalse();
        assertThat(probe.remoteDrive('Y')).isFalse();
        assertThat(calls).hasValue(2);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains(UnsatisfiedLinkError.class.getName())
                    .doesNotContain("/opt/example", "kernel32.so");
        });
    }

    @Test
    void aDriveTypeCallThatFailsToLinkAnswersFalse() {
        WindowsNetworkPaths probe = new WindowsNetworkPaths(true, () -> root -> {
            throw new UnsatisfiedLinkError("kernel32");
        });

        assertThat(probe.remoteDrive('Z')).isFalse();
        assertThat(appender.list).hasSize(1);
    }

    @Test
    void theJnaApiIsNotLoadedWithoutTheWindowsFlag() {
        WindowsNetworkPaths probe = new WindowsNetworkPaths(false, JnaDriveTypeApi::new);

        assertThat(probe.remoteDrive('Z')).isFalse();
        new JnaDriveTypeApi();

        // JNA sets jna.loaded when its native library is loaded (com.sun.jna.Native's
        // initializer); JnaDriveTypeApi loads it on its first call only (Holder)
        assertThat(System.getProperty("jna.loaded")).isNotEqualTo("true");
        assertThat(appender.list).isEmpty();
    }
}
