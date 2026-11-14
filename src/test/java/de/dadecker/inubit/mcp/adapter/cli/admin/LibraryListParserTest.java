package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.LibraryEntry;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T040 (feature 022, US3): {@code library --list --type driver|plugin} against the 8.1.17
 * recordings of T039, which hold only the empty answer ({@code admin-model-library-*} in script
 * mode, the two {@code library} blocks of {@code admin-model-session}), and reconstructed
 * non-empty lines (RECONSTRUCTED: no listed library is recorded yet, L-21).
 */
class LibraryListParserTest {

    @Test
    void theRecordedEmptyScriptAnswersListNoLibrary() {
        for (String type : List.of("driver", "plugin")) {
            LibraryEntry.Listing listing = LibraryListParser.parse(type,
                AdminCliFixtures.answer("admin-model-library-" + type));

            assertThat(listing.entries()).as(type).isEmpty();
            assertThat(listing.rowFormatVerified()).as(type).isFalse();
        }
    }

    @Test
    void theRecordedEmptySessionBlocksListNoLibrary() {
        List<List<String>> blocks = AdminCliFixtures.sessionBlocks("admin-model-session", 4);

        assertThat(LibraryListParser.parse("driver", blocks.get(0)).entries()).isEmpty();
        assertThat(LibraryListParser.parse("plugin", blocks.get(1)).entries()).isEmpty();
    }

    @Test
    void blankAndCarriageReturnOnlyLinesAreSkipped() {
        LibraryEntry.Listing listing = LibraryListParser.parse("driver",
            List.of("", "\r", "  ", "\r"));

        assertThat(listing.entries()).isEmpty();
        assertThat(listing.rowFormatVerified()).isFalse();
    }

    /** RECONSTRUCTED: one file name per line, the row format is not recorded (L-21). */
    @Test
    void everyOtherLineIsOneFileNameOfTheListedType() {
        LibraryEntry.Listing listing = LibraryListParser.parse("plugin",
            List.of("postgresql-42.7.7.jar", "", "\r", "demo plugin (1).zip"));

        assertThat(listing.entries()).containsExactly(
            new LibraryEntry("postgresql-42.7.7.jar", "plugin"),
            new LibraryEntry("demo plugin (1).zip", "plugin"));
        assertThat(listing.rowFormatVerified()).isFalse();
    }

    @Test
    void aLineOf255CharactersIsAFileName() {
        String name = "a".repeat(251) + ".jar";

        assertThat(LibraryListParser.parse("driver", List.of(name)).entries())
            .containsExactly(new LibraryEntry(name, "driver"));
    }

    @Test
    void aLineOver255CharactersIsUnexpected() {
        assertUnknown(() -> LibraryListParser.parse("driver", List.of("a".repeat(252) + ".jar")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lib\u0000.jar", "lib\t.jar", "\u001b[31mlib.jar", "lib.jar\r",
        "lib\u007f.jar", "lib\u0085.jar"})
    void aLineWithAControlCharacterIsUnexpected(String line) {
        assertUnknown(() -> LibraryListParser.parse("driver", List.of("ok.jar", line)));
    }

    @Test
    void theExcerptOfAnUnknownAnswerHoldsNoSecret() {
        ToolError error = assertUnknown(() -> LibraryListParser.parse("driver",
            List.of("\u001b[0m password=synthetic-secret-3906")));

        assertThat(error.toString()).doesNotContain("synthetic-secret-3906");
        assertThat(error.message()).contains("library --list --type driver");
    }
}
