package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.StageChain.ChainLink;
import de.dadecker.inubit.mcp.domain.model.StageChain.Exclusion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * T013 (feature 005, research D-2, data-model.md → StageChain): the chain derived from the
 * {@code deploy} records — links per target, one rendered line per chain, acyclic.
 */
class StageChainTest {

    private static GroupId g(String name) {
        return new GroupId(name);
    }

    private static ChainLink link(String source, DeployMode mode) {
        return new ChainLink(g(source), mode, List.of());
    }

    @Test
    void rendersOneLinePerChainFromItsFirstGroup() {
        Map<GroupId, ChainLink> targets = new LinkedHashMap<>();
        targets.put(g("int"), link("dev", DeployMode.EXECUTE));
        targets.put(g("qa"), link("int", DeployMode.EXECUTE));
        targets.put(g("prod"), link("qa", DeployMode.PACKAGE_ONLY));
        targets.put(g("test"), link("dev", DeployMode.EXECUTE));
        targets.put(g("b"), link("a", DeployMode.EXECUTE));

        StageChain chain = new StageChain(targets);

        assertThat(chain.render()).containsExactly("dev → int → qa → prod (package only)",
            "dev → test", "a → b");
        assertThat(chain.link(g("prod"))).contains(link("qa", DeployMode.PACKAGE_ONLY));
        assertThat(chain.link(g("dev"))).isEmpty();
        assertThat(chain.isEmpty()).isFalse();
    }

    @Test
    void anEmptyChainRendersNothing() {
        assertThat(new StageChain(Map.of()).render()).isEmpty();
        assertThat(new StageChain(Map.of()).isEmpty()).isTrue();
    }

    @Test
    void aCycleOrASelfLinkIsRefused() {
        Map<GroupId, ChainLink> cycle = new LinkedHashMap<>();
        cycle.put(g("dev"), link("int", DeployMode.EXECUTE));
        cycle.put(g("int"), link("dev", DeployMode.EXECUTE));

        assertThatThrownBy(() -> new StageChain(cycle))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle");
        assertThatThrownBy(() -> new StageChain(Map.of(g("dev"), link("dev",
            DeployMode.EXECUTE)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exclusionsHaveOneKindAndANonBlankPattern() {
        Exclusion exclusion = new Exclusion(Exclusion.Kind.REPOSITORY_PATH, "/Root/*/stage/**");

        assertThat(exclusion).hasToString("repositoryPath /Root/*/stage/**");
        assertThat(new Exclusion(Exclusion.Kind.DIAGRAM_GROUP, "GRP-SYS"))
            .hasToString("diagramGroup GRP-SYS");
        assertThat(new Exclusion(Exclusion.Kind.NAME, "CFG_*")).hasToString("name CFG_*");
        assertThatThrownBy(() -> new Exclusion(Exclusion.Kind.NAME, " "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
