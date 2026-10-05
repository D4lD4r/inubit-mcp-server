package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NodeIdTest {

    private static final String NAME_32 = "a" + "b".repeat(31);
    private static final String NAME_33 = "a" + "b".repeat(32);

    @ParameterizedTest
    @ValueSource(strings = {"dev/node1", "qa/node2", "a/b", "0-x/y-0"})
    void acceptsValidNodeIds(String value) {
        NodeId id = NodeId.parse(value);

        assertThat(id.value()).isEqualTo(value);
        assertThat(id).hasToString(value);
    }

    @Test
    void exposesGroupAndNodeName() {
        NodeId id = NodeId.parse("qa/node2");

        assertThat(id.group()).isEqualTo(new GroupId("qa"));
        assertThat(id.name()).isEqualTo("node2");
        assertThat(NodeId.of("qa", "node2")).isEqualTo(id);
    }

    @Test
    void acceptsNamesOfExactly32Characters() {
        assertThat(NodeId.parse(NAME_32 + "/" + NAME_32).name()).isEqualTo(NAME_32);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "QA/x", "qa/X", "a//b", "a/b/c", "dev", "/dev", "dev/", "-a/b", "a/-b", "a_b/c",
        "a/b c", ""
    })
    void rejectsInvalidNodeIds(String value) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> NodeId.parse(value))
            .withMessageContaining("group/node");
    }

    @Test
    void rejectsNamesLongerThan32Characters() {
        assertThatIllegalArgumentException().isThrownBy(() -> NodeId.parse(NAME_33 + "/x"));
        assertThatIllegalArgumentException().isThrownBy(() -> NodeId.parse("x/" + NAME_33));
    }

    @Test
    void rejectsNullInput() {
        assertThatIllegalArgumentException().isThrownBy(() -> NodeId.parse(null));
    }

    @Test
    void groupIdUsesTheNamePattern() {
        assertThat(new GroupId("dev")).hasToString("dev");
        assertThat(new GroupId(NAME_32).value()).isEqualTo(NAME_32);
        assertThatIllegalArgumentException().isThrownBy(() -> new GroupId("DEV"));
        assertThatIllegalArgumentException().isThrownBy(() -> new GroupId(NAME_33));
        assertThatIllegalArgumentException().isThrownBy(() -> new GroupId("a/b"));
        assertThatIllegalArgumentException().isThrownBy(() -> new GroupId(null));
    }

    @Test
    void namePatternIsTheDocumentedOne() {
        assertThat(GroupId.NAME_PATTERN.pattern()).isEqualTo("^[a-z0-9][a-z0-9-]{0,31}$");
    }
}
