package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 002-T011: {@link Terminology#render} and {@link ProfileInfo#render} (002 data-model.md →
 * Terminology, research D-4).
 */
class TerminologyTest {

    private static final Terminology CUSTOM =
        new Terminology("Umgebung", "Umgebungen", "Knoten", "Knoten");

    @Test
    void plainPlaceholdersRenderTheConfiguredNamesExactly() {
        assertThat(CUSTOM.render("{group} {groups} {node} {nodes}"))
            .as("002 review U1/R1: no case change")
            .isEqualTo("Umgebung Umgebungen Knoten Knoten");
        assertThat(new Terminology("stage", "stages", "VM", "VMs")
            .render("one {node} of each {group}, all {nodes}, two {groups}"))
            .isEqualTo("one VM of each stage, all VMs, two stages");
    }

    @Test
    void theDefaultsAreLowerCaseEnglishNouns() {
        assertThat(Terminology.DEFAULT)
            .as("002 review R2: written as inside an English sentence")
            .isEqualTo(new Terminology("group", "groups", "node", "nodes"));
        assertThat(Terminology.DEFAULT.render("the {node} of one {group}"))
            .isEqualTo("the node of one group");
    }

    @Test
    void capitalisedPlaceholdersUpperCaseOnlyTheFirstCodePoint() {
        assertThat(CUSTOM.render("{Group}, {Groups}, {Node}, {Nodes}"))
            .isEqualTo("Umgebung, Umgebungen, Knoten, Knoten");
        Terminology english = new Terminology("stage", "stages", "inubit host", "inubit hosts");
        assertThat(english.render("{Group}/{Groups}/{Node}/{Nodes}"))
            .isEqualTo("Stage/Stages/Inubit host/Inubit hosts");
        assertThat(english.render("{node}")).isEqualTo("inubit host");
    }

    @Test
    void capitalisingKeepsTheRestOfTheConfiguredName() {
        Terminology mixed = new Terminology("eStage", "eStages", "VM", "VMs");

        assertThat(mixed.render("{Group} {Node} {nodes}")).isEqualTo("EStage VM VMs");
        assertThat(new Terminology("élément", "éléments", "nœud", "nœuds").render("{Node}"))
            .isEqualTo("Nœud");
    }

    @Test
    void textWithoutPlaceholdersIsUnchanged() {
        String text = "Find process instances; pattern ^[a-z0-9]{0,31}$ and {\"a\": 1} stay.";

        assertThat(CUSTOM.render(text)).isEqualTo(text);
        assertThat(CUSTOM.render("")).isEmpty();
    }

    @Test
    void placeholdersAreReplacedEverywhereInTheText() {
        assertThat(CUSTOM.render("{Nodes}: one {node} or all {nodes} of one {group}."))
            .isEqualTo("Knoten: one Knoten or all Knoten of one Umgebung.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{x}", "{server}", "{Stage}", "{GROUP}", "{groupss}", "{profile}"})
    void anUnknownPlaceholderIsAnError(String placeholder) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> CUSTOM.render("text " + placeholder + " text"))
            .withMessageContaining(placeholder);
    }

    @Test
    void theProfileRendersItsNameAndTheTerms() {
        ProfileInfo profile = new ProfileInfo("acme", Optional.of("ACME test"), CUSTOM,
            "INUBIT_ACME");

        assertThat(profile.render("{profile}: {Nodes} of {groups}"))
            .isEqualTo("acme: Knoten of Umgebungen");
    }

    @Test
    void theProfileRejectsUnknownPlaceholdersToo() {
        ProfileInfo profile = new ProfileInfo("acme", Optional.empty(), CUSTOM, "INUBIT_ACME");

        assertThatIllegalArgumentException().isThrownBy(() -> profile.render("{stage}"))
            .withMessageContaining("{stage}");
    }

    @Test
    void placeholdersFindsEveryPlaceholderName() {
        assertThat(Terminology.placeholders("{group}/{node} and {x} but not {0,31}"))
            .containsExactly("group", "node", "x");
    }
}
