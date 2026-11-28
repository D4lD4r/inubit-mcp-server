package de.dadecker.inubit.mcp.domain.model.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T004: a linked name with or without a found mark. */
class LinkedModuleTest {

    @Test
    void aLinkedModuleKeepsNameAndFoundMark() {
        assertThat(new LinkedModule("Demo-HTTP-Out-A", Optional.of(true)).found()).contains(true);
        assertThat(new LinkedModule("Demo-HTTP-Out-B", Optional.of(false)).found())
            .contains(false);
        assertThat(new LinkedModule("Demo-HTTP-Out-C", Optional.empty()).found()).isEmpty();
    }

    @Test
    void everyFieldIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new LinkedModule(null,
            Optional.empty())).withMessage("name");
        assertThatNullPointerException().isThrownBy(() -> new LinkedModule("Demo-HTTP-Out-A",
            null)).withMessage("found");
    }
}
