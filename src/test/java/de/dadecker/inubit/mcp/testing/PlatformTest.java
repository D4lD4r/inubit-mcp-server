package de.dadecker.inubit.mcp.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class PlatformTest {

    @Test
    void windowsIsTheHostOs() {
        assertThat(Platform.windows()).isEqualTo(OS.WINDOWS.isCurrentOs());
    }

    @Test
    void deadPidNamesAProcessThatHasExited() throws Exception {
        long pid = Platform.deadPid();

        assertThat(pid).isPositive();
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void commandPathIsThePathOnPosix() {
        assertThat(Platform.commandPath(Path.of("/tmp/a b/x.zip"))).isEqualTo("/tmp/a b/x.zip");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void commandPathUsesForwardSlashesOnWindows() {
        assertThat(Platform.commandPath(Path.of("C:\\Temp\\a b\\x.zip")))
            .isEqualTo("C:/Temp/a b/x.zip");
    }

    @Test
    void absolutePathIsAbsoluteOnTheHost() {
        assertThat(Platform.absolutePath("/home/test")).isAbsolute()
            .endsWithRaw(Path.of("home", "test"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void absolutePathIsThePathOnPosix() {
        assertThat(Platform.absolutePath("/home/test")).isEqualTo(Path.of("/home/test"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void absolutePathIsOnDriveCOnWindows() {
        assertThat(Platform.absolutePath("/home/test")).isEqualTo(Path.of("C:\\home\\test"));
    }

    @Test
    void aPosixPathKeepsItsSlashesOnEveryHost() {
        Path path = Platform.posixPath("/var/tmp/odd~dir");

        assertThat(path.isAbsolute()).isTrue();
        assertThat(path.resolve("export.zip")).hasToString("/var/tmp/odd~dir/export.zip");
    }
}
