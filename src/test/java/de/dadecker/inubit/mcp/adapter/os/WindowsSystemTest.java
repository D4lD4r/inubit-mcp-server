package de.dadecker.inubit.mcp.adapter.os;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Feature 013 (T008, research R-1, R-6, R-8): the Windows system queries. The code-page mapping is
 * pure and runs on every platform; the native queries run on Windows and are checked against the
 * system's own tools ({@code whoami}, {@code reg}).
 */
@Timeout(30)
class WindowsSystemTest {

    private final WindowsSystem system = new WindowsSystem();

    /** Output of a Windows tool from {@code System32}; the values read here are ASCII. */
    private static String tool(String executable, String... arguments)
        throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 1];
        command[0] = Path.of(System.getenv("SystemRoot"), "System32", executable).toString();
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.ISO_8859_1);
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }

    private static String group(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertThat(matcher.find()).as("%s in %s", regex, text).isTrue();
        return matcher.group(1);
    }

    // --- every platform -----------------------------------------------------------------------

    /** As the JDK names the console encoding on Windows: {@code ms<cp>} for 874–950, else cp. */
    @ParameterizedTest
    @CsvSource({"437, IBM437", "850, IBM850", "852, IBM852", "866, IBM866",
        "1252, windows-1252", "932, windows-31j", "65001, UTF-8"})
    void mapsTheOemCodePageToItsCharset(int codePage, String charset) {
        assertThat(WindowsSystem.charset(codePage)).isEqualTo(Charset.forName(charset));
    }

    @Test
    void aCodePageTheRuntimeDoesNotKnowDecodesEveryByteAsOneCharacter() {
        assertThat(WindowsSystem.charset(1)).isEqualTo(StandardCharsets.ISO_8859_1);
    }

    // --- Windows ------------------------------------------------------------------------------

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theSystemDirectoryIsSystem32AndHoldsTheInterpreter() throws IOException {
        Path directory = Path.of(((CliSystem.Read) system.systemDirectory()).path());

        assertThat(directory).isAbsolute();
        assertThat(directory.resolve("cmd.exe")).isRegularFile();
        assertThat(directory.toRealPath())
            .isEqualTo(Path.of(System.getenv("SystemRoot"), "System32").toRealPath());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theUserSidIsTheSidOfTheProcessTokensUser() throws Exception {
        String sid = system.userSid().orElseThrow();

        assertThat(sid).matches("S-1-5(-\\d+)+");
        assertThat(sid).isEqualTo(group(tool("whoami.exe", "/user", "/fo", "csv", "/nh"),
            "\"(S-1-[0-9-]+)\""));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theOemCharsetIsTheOneOfTheSystemsOemCodePage() throws Exception {
        int codePage = Integer.parseInt(group(tool("reg.exe", "query",
            "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Nls\\CodePage", "/v", "OEMCP"),
            "OEMCP\\s+REG_SZ\\s+(\\d+)"));

        assertThat(system.oemCharset()).isEqualTo(WindowsSystem.charset(codePage));
    }
}
