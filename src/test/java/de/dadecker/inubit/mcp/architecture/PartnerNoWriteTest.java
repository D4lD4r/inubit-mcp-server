package de.dadecker.inubit.mcp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T012 (feature 018, FR-003): the server only reads the Partner Management. Nothing in
 * {@code src/main} (code, comments, resources) names INUBIT's Partner Management write restlet or
 * its modify operation, so no request to them can be built.
 */
class PartnerNoWriteTest {

    private static final Path MAIN = Path.of("src/main");
    private static final List<String> FORBIDDEN =
        List.of("PartnerManagementRestlet", "modifyPartnerManagement");

    @Test
    void srcMainNeverReferencesThePartnerManagementWriteInterface() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        // guards against a vacuous pass: the scan must see the partner adapter
        assertThat(files).anyMatch(f -> f.endsWith("V81PartnerManagementAdapter.java"));

        for (Path file : files) {
            // ISO-8859-1 decodes every byte, so binary resources are scanned as well
            String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
            for (String name : FORBIDDEN) {
                assertThat(text).as("%s in %s", name, file)
                    .doesNotContain(name.toLowerCase(Locale.ROOT));
            }
        }
    }
}
