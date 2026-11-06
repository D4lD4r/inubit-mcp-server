package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** T003 (research R-8, FR-004, FR-009): archive file names of workflows and their comparison key. */
class ArchiveFileNamesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Order: In/Out?.|Order_ In_Out_.diagram.zip",
        "Workflow-0001|Workflow-0001.diagram.zip",
        "CON|_CON.diagram.zip",
        "con.backup|_con.backup.diagram.zip",
        "COM1|_COM1.diagram.zip",
        "lpt9.x|_lpt9.x.diagram.zip",
        "Aux|_Aux.diagram.zip",
        "nul.a.b|_nul.a.b.diagram.zip",
        "COM10|COM10.diagram.zip",
        "COM0|COM0.diagram.zip",
        "CONSOLE|CONSOLE.diagram.zip",
        "x.CON|x.CON.diagram.zip",
        "'name. . '|name.diagram.zip",
        "'CON. '|_CON.diagram.zip",
        "...|_.diagram.zip",
        "'  '|_.diagram.zip",
        ".hidden|_hidden.diagram.zip",
        "..x|_.x.diagram.zip",
    })
    void derivesTheFileNameInTheOrderOfTheSpec(String workflow, String fileName) {
        assertThat(ArchiveFileNames.fileName(workflow)).isEqualTo(fileName);
    }

    @ParameterizedTest
    @ValueSource(strings = {"<", ">", ":", "\"", "/", "\\", "|", "?", "*"})
    void replacesEachForbiddenCharacter(String character) {
        assertThat(ArchiveFileNames.fileName("a" + character + "b")).isEqualTo("a_b.diagram.zip");
    }

    @ParameterizedTest
    @ValueSource(chars = {'\u0000', '\u0001', '\t', '\n', '\r', '\u001F', '\u007F'})
    void replacesEachControlCharacter(char control) {
        assertThat(ArchiveFileNames.fileName("a" + control + "b")).isEqualTo("a_b.diagram.zip");
    }

    @Test
    void aControlCharacterAtTheEndIsReplacedNotRemoved() {
        assertThat(ArchiveFileNames.fileName("a.\n")).isEqualTo("a._.diagram.zip");
    }

    @Test
    void keepsLettersOutsideAscii() {
        assertThat(ArchiveFileNames.fileName("Straße Ä é"))
            .isEqualTo("Straße Ä é.diagram.zip");
    }

    @Test
    void acceptsAFileNameOf255Utf8Bytes() {
        String workflow = "ä".repeat(121) + "a"; // 243 bytes + 12 bytes of ".diagram.zip"
        String fileName = ArchiveFileNames.fileName(workflow);

        assertThat(fileName.getBytes(StandardCharsets.UTF_8)).hasSize(255);
        assertThat(fileName).isEqualTo(workflow + ".diagram.zip");
    }

    @Test
    void refusesAFileNameOf256Utf8BytesNamingTheWorkflow() {
        String workflow = "ä".repeat(122); // 244 bytes, only 122 characters
        assertThat((workflow + ".diagram.zip").length()).isLessThan(255);

        assertThatThrownBy(() -> ArchiveFileNames.fileName(workflow))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.error().message()).contains(workflow).contains("255");
            });
    }

    @Test
    void keyFoldsSharpSToDoubleS() {
        assertThat(ArchiveFileNames.key("Straße.diagram.zip"))
            .isEqualTo(ArchiveFileNames.key("STRASSE.diagram.zip"));
    }

    @Test
    void keyEqualsForNfdAndNfc() {
        String nfd = "é.diagram.zip";
        String nfc = "é.diagram.zip";

        assertThat(ArchiveFileNames.key(nfd)).isEqualTo(ArchiveFileNames.key(nfc));
    }

    @Test
    void keyIgnoresCaseAndKeepsDifferentNamesApart() {
        assertThat(ArchiveFileNames.key("Workflow-0001.diagram.zip"))
            .isEqualTo(ArchiveFileNames.key("WORKFLOW-0001.DIAGRAM.ZIP"));
        assertThat(ArchiveFileNames.key("Workflow-0001.diagram.zip"))
            .isNotEqualTo(ArchiveFileNames.key("Workflow-0002.diagram.zip"));
    }
}
