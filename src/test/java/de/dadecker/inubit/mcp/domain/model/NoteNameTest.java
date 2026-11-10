package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Note names of the local knowledge (feature 019, research D-4). */
class NoteNameTest {

    @ParameterizedTest
    @ValueSource(strings = {"acme", "partners/acme", "a/b/c/d", "Störung 2024", "v1.2-final_x",
        "日本語", "a.b", "CONSOLE", "COM10", "x/LPT"})
    void namesWithinTheRecordRuleAreWritable(String name) {
        assertThat(NoteName.writableProblem(name)).isEmpty();
        assertThat(NoteName.of(name).writable()).isTrue();
    }

    @Test
    void theRecordRuleNamesTheRuleAndTheSegmentButNeverTheName() {
        assertProblem("", "the name is empty");
        assertProblem("a/b/c/d/e", "the name has more than 4 segments");
        assertProblem("a//b", "segment 2 is empty");
        assertProblem("/a", "segment 1 is empty");
        assertProblem("a/" + "x".repeat(101), "segment 2 is longer than 100 characters");
        assertProblem("a/b:c", "segment 2 may contain only letters, digits, _ . - and spaces");
        assertProblem("a\\b", "segment 1 may contain only letters, digits, _ . - and spaces");
        assertProblem("tab\there", "segment 1 may contain only letters, digits, _ . - and spaces");
        assertProblem(".hidden", "segment 1 starts with . (it would be hidden)");
        assertProblem("a/..", "segment 2 starts with . (it would be hidden)");
        assertProblem("a/b.", "segment 2 ends with . or a space");
        assertProblem("a/b ", "segment 2 ends with . or a space");
        assertProblem("con", "segment 1 is a reserved Windows device name");
        assertProblem("x/Nul.txt", "segment 2 is a reserved Windows device name");
        assertProblem("COM¹", "segment 1 is a reserved Windows device name");
        assertProblem("lpt0", "segment 1 is a reserved Windows device name");
        assertProblem("aux .x", "segment 1 is a reserved Windows device name");
        assertProblem("notes.md", "the name must not end in .md");
        assertProblem("notes.MD", "the name must not end in .md");
        assertThat(NoteName.writableProblem("secret-xyz:1")).hasValueSatisfying(
            p -> assertThat(p).doesNotContain("secret-xyz"));
    }

    @Test
    void aSegmentOfExactlyOneHundredCodePointsIsAllowed() {
        assertThat(NoteName.writableProblem("𝔸".repeat(100))).isEmpty();
        assertThat(NoteName.writableProblem("𝔸".repeat(101))).isPresent();
    }

    @Test
    void namesAreNfcAndEqualByCodePoints() {
        String nfd = Normalizer.normalize("Störung", Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("Störung");
        assertThat(NoteName.of(nfd)).isEqualTo(NoteName.of("Störung"));
        assertThat(NoteName.of(nfd).value()).isEqualTo("Störung");
        assertThat(NoteName.of("Acme")).isNotEqualTo(NoteName.of("acme"));
    }

    @Test
    void namesComeFromRelativePathsWithoutTheSuffix() {
        assertThat(NoteName.ofRelativePath(Path.of("partners", "acme.md")).value())
            .isEqualTo("partners/acme");
        assertThat(NoteName.ofRelativePath(Path.of("x.md")).segments()).containsExactly("x");
        assertThatThrownBy(() -> NoteName.ofRelativePath(Path.of("x.txt")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(NoteName.of("a/b/c").segments()).containsExactly("a", "b", "c");
        assertThat(NoteName.of("a/b/c").lastSegment()).isEqualTo("c");
    }

    @Test
    void foldTreatsCaseVariantsAsOneName() {
        assertSameFold("Straße", "STRASSE", "strasse", "STRAẞE");
        assertSameFold("ΣΑΣ", "σας", "σασ");
        assertSameFold("ſ", "s", "S");
        assertSameFold("\u212A", "k", "K");
        assertSameFold("ı", "i", "I");
        assertSameFold("İ", "i\u0307");
        assertSameFold(Normalizer.normalize("Störung", Normalizer.Form.NFD), "STÖRUNG");
        assertThat(NoteName.of("a").fold()).isNotEqualTo(NoteName.of("b").fold());
    }

    /**
     * Every C and F mapping of Unicode 15.1's {@code CaseFolding.txt} for a letter is reached by
     * the fixpoint of upper/lower case (research D-4): the fold is at least as coarse as full case
     * folding, so a mismatch in a later JDK fails the build instead of going unnoticed.
     */
    @Test
    void theFoldIsAtLeastAsCoarseAsFullCaseFolding() throws IOException {
        List<String> mismatches = new ArrayList<>();
        int letters = 0;
        try (InputStream in = NoteNameTest.class.getResourceAsStream(
                "/unicode/CaseFolding-15.1.0-CF.txt");
             BufferedReader reader = new BufferedReader(
                 new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                String[] fields = line.split(";");
                int source = Integer.parseInt(fields[0].strip(), 16);
                if (!Character.isLetter(source)) {
                    continue;
                }
                StringBuilder target = new StringBuilder();
                Arrays.stream(fields[2].strip().split(" "))
                    .forEach(cp -> target.appendCodePoint(Integer.parseInt(cp, 16)));
                letters++;
                String from = NoteName.fold(Character.toString(source));
                String to = NoteName.fold(target.toString());
                if (!from.equals(to)) {
                    mismatches.add(fields[0].strip());
                }
            }
        }
        assertThat(letters).isGreaterThan(1400);
        assertThat(mismatches).isEmpty();
    }

    private static void assertSameFold(String... names) {
        String first = NoteName.of(names[0]).fold();
        for (String name : names) {
            assertThat(NoteName.of(name).fold()).as(name).isEqualTo(first);
        }
    }

    private static void assertProblem(String name, String problem) {
        Optional<String> actual = NoteName.writableProblem(name);
        assertThat(actual).as(name).contains(problem);
        if (!name.isBlank()) {
            assertThat(NoteName.of(name).writable()).isFalse();
        }
    }
}
