package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.dadecker.inubit.mcp.domain.model.ClientVersion.Comparison;
import de.dadecker.inubit.mcp.domain.model.ClientVersion.Compatibility;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientVersionTest {

    /** The 8.1.17 client as its probe reports it: "supported 4.0.1 to 8.1.17". */
    private final ClientVersion client817 = new ClientVersion(known("8.1.17"),
        Optional.of(known("4.0.1")), Optional.of(known("8.1.17")));

    private final ClientVersion clientWithoutRange =
        new ClientVersion(known("8.1.17"), Optional.empty(), Optional.empty());

    @Test
    void sameServerVersionMatchesAndIsCompatible() {
        assertThat(client817.compare(version("8.1.17"))).isEqualTo(Comparison.MATCH);
        assertThat(client817.compatibleWith(version("8.1.17")))
            .isEqualTo(Compatibility.COMPATIBLE);
    }

    @Test
    void newerPatchIsAMismatchButCompatible() {
        assertThat(client817.compare(version("8.1.18"))).isEqualTo(Comparison.MISMATCH);
        assertThat(client817.compatibleWith(version("8.1.18")))
            .isEqualTo(Compatibility.COMPATIBLE);
    }

    @Test
    void suffixesAreIgnored() {
        assertThat(client817.compare(version("8.1.17-HF3"))).isEqualTo(Comparison.MATCH);
        assertThat(client817.compatibleWith(version("8.1.17-HF3")))
            .isEqualTo(Compatibility.COMPATIBLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.0.9", "7.3.1"})
    void olderPatchOrMinorIsAMismatch(String server) {
        assertThat(client817.compare(version(server))).isEqualTo(Comparison.MISMATCH);
    }

    @Test
    void server90IsIncompatible() {
        assertThat(client817.compare(version("9.0.7"))).isEqualTo(Comparison.MISMATCH);
        assertThat(client817.compatibleWith(version("9.0.7")))
            .isEqualTo(Compatibility.INCOMPATIBLE);
    }

    @Test
    void boundsAreInclusiveOnMajorMinor() {
        assertThat(client817.compatibleWith(version("4.0.0"))).isEqualTo(Compatibility.COMPATIBLE);
        assertThat(client817.compatibleWith(version("8.1"))).isEqualTo(Compatibility.COMPATIBLE);
        assertThat(client817.compatibleWith(version("3.9.9")))
            .isEqualTo(Compatibility.INCOMPATIBLE);
        assertThat(client817.compatibleWith(version("8.2.0")))
            .isEqualTo(Compatibility.INCOMPATIBLE);
    }

    @Test
    void majorOnlyLineV9xIsIncompatibleWithThe81Client() {
        assertThat(client817.compatibleWith(9, OptionalInt.empty()))
            .isEqualTo(Compatibility.INCOMPATIBLE);
    }

    @Test
    void majorOnlyLineIsCompatibleIfSomeMinorOfThatMajorIsInTheRange() {
        assertThat(client817.compatibleWith(8, OptionalInt.empty()))
            .isEqualTo(Compatibility.COMPATIBLE);
        assertThat(client817.compatibleWith(4, OptionalInt.empty()))
            .isEqualTo(Compatibility.COMPATIBLE);
        assertThat(client817.compatibleWith(3, OptionalInt.empty()))
            .isEqualTo(Compatibility.INCOMPATIBLE);
        ClientVersion client90 = new ClientVersion(known("9.0.7"),
            Optional.of(known("8.1.0")), Optional.of(known("9.0.7")));
        assertThat(client90.compatibleWith(9, OptionalInt.empty()))
            .isEqualTo(Compatibility.COMPATIBLE);
    }

    @Test
    void lineWithMinorIsCheckedLikeThatMajorMinor() {
        assertThat(client817.compatibleWith(8, OptionalInt.of(1)))
            .isEqualTo(Compatibility.COMPATIBLE);
        assertThat(client817.compatibleWith(9, OptionalInt.of(0)))
            .isEqualTo(Compatibility.INCOMPATIBLE);
    }

    @Test
    void noRangeIsNotEvaluable() {
        assertThat(clientWithoutRange.compatibleWith(version("8.1.17")))
            .isEqualTo(Compatibility.NOT_EVALUABLE);
        assertThat(clientWithoutRange.compatibleWith(9, OptionalInt.empty()))
            .isEqualTo(Compatibility.NOT_EVALUABLE);
        assertThat(clientWithoutRange.compare(version("8.1.17"))).isEqualTo(Comparison.MATCH);
    }

    @Test
    void unknownServerIsNotEvaluableAndNotComparable() {
        assertThat(client817.compatibleWith(Optional.empty()))
            .isEqualTo(Compatibility.NOT_EVALUABLE);
        assertThat(client817.compare(Optional.empty())).isEqualTo(Comparison.NOT_COMPARABLE);
    }

    @Test
    void aVersionWithoutPatchIsNotComparable() {
        assertThat(client817.compare(version("8.1"))).isEqualTo(Comparison.NOT_COMPARABLE);
        ClientVersion clientWithoutPatch =
            new ClientVersion(known("8.1"), Optional.empty(), Optional.empty());
        assertThat(clientWithoutPatch.compare(version("8.1.17")))
            .isEqualTo(Comparison.NOT_COMPARABLE);
    }

    @Test
    void rejectsAHalfRange() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new ClientVersion(known("8.1.17"), Optional.of(known("4.0.1")), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() ->
            new ClientVersion(known("8.1.17"), Optional.empty(), Optional.of(known("8.1.17"))));
    }

    @Test
    void rejectsARangeWhoseLowerBoundIsAboveItsUpperBound() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ClientVersion(known("8.1.17"),
            Optional.of(known("9.0.0")), Optional.of(known("8.1.17"))));
    }

    private static Optional<InubitVersion> version(String value) {
        return Optional.of(known(value));
    }

    private static InubitVersion known(String value) {
        Optional<InubitVersion> parsed = InubitVersion.parse(value);
        assertThat(parsed).as(value).isPresent();
        return parsed.orElseThrow();
    }
}
