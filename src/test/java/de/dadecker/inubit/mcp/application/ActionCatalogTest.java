package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Feature 022 (T010, research D-2): the registry of {@link ActionSpec}s by tool and action. */
class ActionCatalogTest {

    private static final ActionSpec STOP = spec("operate_server", "stop", "server -stop");
    private static final ActionSpec START = spec("operate_server", "start", "server -start");

    @Test
    void findsASpecByToolAndAction() {
        ActionCatalog catalog = new ActionCatalog(List.of(STOP, START));

        assertThat(catalog.spec("operate_server", "start")).containsSame(START);
        assertThat(catalog.spec("operate_server", "restart")).isEmpty();
        assertThat(catalog.spec("other_tool", "start")).isEmpty();
        assertThat(catalog.specs()).containsExactly(STOP, START);
    }

    @Test
    void refusesTwoSpecsForTheSameToolAndAction() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ActionCatalog(List.of(STOP,
                spec("operate_server", "stop", "server -halt"))))
            .withMessageContaining("operate_server/stop");
    }

    private static ActionSpec spec(String tool, String action, String entry) {
        CoverageKey key = new CoverageKey(Channel.CLI, entry);
        return new ActionSpec(tool, action, Capability.PROCESS_CONTROL,
            ActionSpec.Area.OPERATION, ActionSpec.Kind.CHANGE, Set.of(key), input -> Set.of(key),
            Set.of(), false, Optional.empty(), ActionSpec.RestartNotice.NONE);
    }
}
