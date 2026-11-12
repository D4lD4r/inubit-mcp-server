package de.dadecker.inubit.mcp.domain.model.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature 024 (T007, research D-8, contracts/helper-protocol-delta.md "Message reference"): the
 * reference of a listed I/O log entry, pinned by the vectors the helper test shares.
 */
class IoMessageRefTest {

    static final Path VECTORS =
        Path.of("src/test/resources/fixtures/node-tools/io-message-ref.vectors");

    @Test
    void everyVectorMatches() throws IOException {
        List<String> lines = Files.readAllLines(VECTORS, StandardCharsets.UTF_8);

        assertThat(lines).hasSizeGreaterThanOrEqualTo(5)
            .anyMatch(line -> line.chars().anyMatch(c -> c > 0x7F));
        for (String line : lines) {
            String[] parts = line.split("\t", -1);
            assertThat(parts).as("vector line %s", line).hasSize(2);

            assertThat(IoMessageRef.of(parts[0]).value()).as("reference of %s", parts[0])
                .isEqualTo(parts[1]);
        }
    }

    @Test
    void differentEntriesHaveDifferentReferences() {
        assertThat(IoMessageRef.of("4711@@@Orders(Module-0001)@@@Input.1"))
            .isNotEqualTo(IoMessageRef.of("4711@@@Orders(Module-0001)@@@Output.1"))
            .isEqualTo(IoMessageRef.of("4711@@@Orders(Module-0001)@@@Input.1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Uh6oih0R5HcbvXExtVml_w", "AAAAAAAAAAAAAAAAAAAAAA",
        "-_-_-_-_-_-_-_-_-_-_-_"})
    void acceptsTwentyTwoBase64urlCharacters(String value) {
        assertThat(new IoMessageRef(value).value()).isEqualTo(value);
        assertThat(IoMessageRef.isValid(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Uh6oih0R5HcbvXExtVml_", "Uh6oih0R5HcbvXExtVml_wA",
        "Uh6oih0R5HcbvXExtVml/w", "Uh6oih0R5HcbvXExtVml+w", "Uh6oih0R5HcbvXExtVml=w",
        "Uh6oih0R5HcbvXExtVml w"})
    void refusesOtherValues(String value) {
        assertThat(IoMessageRef.isValid(value)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new IoMessageRef(value))
            .withMessageContaining("22");
    }

    @Test
    void refusesNull() {
        assertThat(IoMessageRef.isValid(null)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new IoMessageRef(null));
    }
}
