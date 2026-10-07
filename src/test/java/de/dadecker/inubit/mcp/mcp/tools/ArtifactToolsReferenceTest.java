package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.Terminology;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T036: docs/tools.md describes {@code export_artifacts} and {@code check_artifacts} as the code
 * announces them, and names every finding code the checks can produce.
 */
class ArtifactToolsReferenceTest {

    private static final Path REFERENCE = Path.of("docs/tools.md");
    private static final Pattern CODE = Pattern.compile("\"([A-Z]{2,}(?:_[A-Z0-9]+)+)\"");

    /** The reference text with quote markers and line breaks folded into single spaces. */
    private static String reference() throws IOException {
        return Files.readString(REFERENCE).replaceAll("\\n> ?", " ").replaceAll("\\s+", " ");
    }

    @Test
    void theDescriptionsAreQuotedAsRendered() throws IOException {
        String reference = reference();

        for (String description : new String[] {ExportArtifactsTool.DESCRIPTION,
            CheckArtifactsTool.DESCRIPTION}) {
            assertThat(reference).contains("[acme] " + Terminology.DEFAULT.render(description));
        }
        assertThat(reference).contains("| [`export_artifacts`](#export_artifacts) |",
            "| [`check_artifacts`](#check_artifacts) |", "All fifteen tools");
    }

    @Test
    void everyFindingCodeIsDocumented() throws IOException {
        Set<String> codes = new TreeSet<>();
        for (String source : new String[] {
            "src/main/java/de/dadecker/inubit/mcp/application/ArtifactCheckService.java",
            "src/main/java/de/dadecker/inubit/mcp/adapter/xslt/SaxonXsltRunner.java",
            "src/main/java/de/dadecker/inubit/mcp/adapter/xslt/XsdValidator.java"}) {
            Matcher code = CODE.matcher(Files.readString(Path.of(source)));
            while (code.find()) {
                codes.add(code.group(1));
            }
        }
        assertThat(codes).as("the scan finds the codes").contains("EDGE_TARGET_MISSING",
            "XSLT_STANDIN_ASSUMED", "XSD_INVALID");

        String reference = reference();
        assertThat(codes).allSatisfy(code -> assertThat(reference).as(code)
            .contains("`" + code + "`"));
    }
}
