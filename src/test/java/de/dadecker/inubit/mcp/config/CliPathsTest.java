package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T018 (feature 013, FR-009, research R-3): the rule for paths inside the StartCLI command on
 * Windows. The Windows cases use the path's text only, so they run on every platform.
 */
class CliPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {"C:/Users/svc/AppData/Local/Temp/x/history.zip",
        "C:\\Users\\svc\\AppData\\Local\\Temp\\x\\history.zip", "d:/mcp temp/a_b-c.1/out.zip",
        "C:\\mcp-temp\\v1..2\\x.zip"})
    void onWindowsADriveLetterPathIsPassedWithForwardSlashes(String path) {
        assertThat(CliPaths.passable(Path.of(path), true)).isTrue();
        assertThat(CliPaths.render(Path.of(path), true)).doesNotContain("\\")
            .isEqualTo(path.replace('\\', '/'));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\\\files.example.test\\share\\x.zip",
        "//files.example.test/share/x.zip",
        "C:x.zip", "C:/", "/tmp/x.zip", "relative\\x.zip", "C:\\Users\\RUNNER~1\\x.zip",
        "C:\\Users\\j\u00f6rg\\x.zip", "C:\\a\\..\\x.zip", "C:\\a\\.\\x.zip", "C:\\a;b\\x.zip",
        "C:\\a'b\\x.zip", "C:\\a(b)\\x.zip"})
    void onWindowsAnyOtherPathIsRefused(String path) {
        assertThat(CliPaths.passable(Path.of(path), true)).isFalse();
    }

    @Test
    void onWindowsAPathHasAtMost259Characters() {
        String longest = "C:/" + "a".repeat(252) + ".zip";

        assertThat(longest).hasSize(259);
        assertThat(CliPaths.passable(Path.of(longest), true)).isTrue();
        assertThat(CliPaths.passable(Path.of("C:/" + "a".repeat(253) + ".zip"), true)).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void thePosixRuleIsUnchanged() {
        assertThat(CliPaths.passable(Path.of("/tmp/inubit-mcp-1/history.zip"), false)).isTrue();
        assertThat(CliPaths.render(Path.of("/tmp/x.zip"), false)).isEqualTo("/tmp/x.zip");
        assertThat(CliPaths.passable(Path.of("/tmp/../x.zip"), false)).isFalse();
        assertThat(CliPaths.passable(Path.of("C:/x.zip"), false)).isFalse();
        assertThat(CliPaths.rule(false)).isSameAs(CliPaths.PATH_VALUE);
        assertThat(CliPaths.rule(true)).isSameAs(CliPaths.WINDOWS_PATH_VALUE);
        assertThat(CliPaths.exportRootUsable(Path.of("/var/tmp"), false)).isTrue();
    }

    @Test
    void onWindowsTheExportRootMustLeaveRoomForTheLongestExportFile() {
        assertThat(CliPaths.exportRootUsable(Path.of("C:/" + "t".repeat(149)), true)).isTrue();
        assertThat(CliPaths.exportRootUsable(Path.of("C:/" + "t".repeat(150)), true)).isFalse();
        assertThat(CliPaths.exportRootUsable(Path.of("C:\\Users\\svc\\Temp"), true)).isTrue();
    }

    // --- the directory StartCLI is given: 8.3 short names expanded on Windows only ---------

    @Test
    void onWindowsTheTemporaryDirectoryIsItsRealPathWithShortNamesExpanded() {
        Path shortName = Path.of("C:\\Users\\RUNNER~1\\AppData\\Local\\Temp");
        Path longName = Path.of("C:\\Users\\runneradmin\\AppData\\Local\\Temp");

        Path forCommand = CliPaths.forCommand(shortName, true,
            path -> path.equals(shortName) ? longName : path);

        assertThat(forCommand).isEqualTo(longName);
        assertThat(CliPaths.exportRootUsable(forCommand, true)).isTrue();
        assertThat(CliPaths.exportRootUsable(shortName, true)).isFalse();
    }

    @Test
    void aDirectoryThatCannotBeResolvedIsKeptAsItIs() {
        Path directory = Path.of("C:\\missing");

        assertThat(CliPaths.forCommand(directory, true, path -> {
            throw new IOException("not found");
        })).isEqualTo(directory);
    }

    @Test
    void onPosixAndForANetworkPathNothingIsResolved() {
        List<Path> resolved = new ArrayList<>();
        CliPaths.RealPath recording = path -> {
            resolved.add(path);
            return Path.of("/elsewhere");
        };

        assertThat(CliPaths.forCommand(Path.of("/var/tmp"), false, recording))
            .isEqualTo(Path.of("/var/tmp"));
        Path unc = Path.of("\\\\files.example.test\\share\\tmp");
        assertThat(CliPaths.forCommand(unc, true, recording)).isEqualTo(unc);
        assertThat(resolved).isEmpty();
    }
}
