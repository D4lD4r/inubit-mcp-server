package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TargetTest {

    @Test
    void patternIsTheDocumentedOne() {
        assertThat(Target.PATTERN.pattern())
            .isEqualTo("^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$");
    }

    @Test
    void parsesAGroupTarget() {
        assertThat(Target.parse("qa")).isEqualTo(new Target.Group(new GroupId("qa")));
    }

    @Test
    void parsesANodeTarget() {
        assertThat(Target.parse("qa/node2"))
            .isEqualTo(new Target.Node(NodeId.parse("qa/node2")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"QA", "QA/x", "a//b", "a/b/c", "", "a/", "/a", "a b"})
    void rejectsInvalidTargets(String value) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> Target.parse(value))
            .withMessageContaining("group id")
            .withMessageContaining("group/node");
    }

    @Test
    void rejectsNull() {
        assertThatIllegalArgumentException().isThrownBy(() -> Target.parse(null));
    }
}
