package de.dadecker.inubit.mcp.domain.model.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Feature 024 (T007, research D-8): one entry of the I/O log listing of a process run. */
class IoLogEntryTest {

    @Test
    void parsesAnInputEntry() {
        String raw = "4711@@@Orders(Module-0001)@@@Input.1";

        IoLogEntry entry = IoLogEntry.parse("4711", raw);

        assertThat(entry.entry()).isEqualTo(raw);
        assertThat(entry.moduleName()).isEqualTo("Orders");
        assertThat(entry.moduleId()).contains("Module-0001");
        assertThat(entry.moduleParsed()).isTrue();
        assertThat(entry.direction()).isEqualTo(IoDirection.INPUT);
        assertThat(entry.index()).isEqualTo(1);
        assertThat(entry.ref()).isEqualTo(IoMessageRef.of(raw));
    }

    @Test
    void parsesAnOutputEntryWithTheLargestIndex() {
        IoLogEntry entry = IoLogEntry.parse("A1b2_C3-d4",
            "A1b2_C3-d4@@@Orders(Module-0001)@@@Output.999999999");

        assertThat(entry.direction()).isEqualTo(IoDirection.OUTPUT);
        assertThat(entry.index()).isEqualTo(999_999_999);
    }

    @Test
    void splitsTheModulePartAtTheLastParenthesis() {
        IoLogEntry entry = IoLogEntry.parse("4711", "4711@@@Orders(acme)(Module-0003)@@@Input.2");

        assertThat(entry.moduleName()).isEqualTo("Orders(acme)");
        assertThat(entry.moduleId()).contains("Module-0003");
    }

    @Test
    void keepsSpacesAndNonAsciiInTheModuleName() {
        IoLogEntry entry = IoLogEntry.parse("4711",
            "4711@@@Bestellprüfung Größe 注文(Module-0004)@@@Output.12");

        assertThat(entry.moduleName()).isEqualTo("Bestellprüfung Größe 注文");
        assertThat(entry.moduleId()).contains("Module-0004");
    }

    @Test
    void keepsAModuleNameContainingTheSeparator() {
        IoLogEntry entry = IoLogEntry.parse("4711", "4711@@@Orders@@@acme(Module-0005)@@@Input.3");

        assertThat(entry.moduleName()).isEqualTo("Orders@@@acme");
        assertThat(entry.moduleId()).contains("Module-0005");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Orders", "Orders(Module-0001", "(Module-0001)", "Orders()",
        "Orders(Module-0001) ", "Orders)Module-0001("})
    void keepsAnUnparsableModulePartRaw(String modulePart) {
        String raw = "4711@@@" + modulePart + "@@@Input.4";

        IoLogEntry entry = IoLogEntry.parse("4711", raw);

        assertThat(entry.moduleName()).isEqualTo(modulePart);
        assertThat(entry.moduleId()).isEmpty();
        assertThat(entry.moduleParsed()).isFalse();
        assertThat(entry.direction()).isEqualTo(IoDirection.INPUT);
        assertThat(entry.index()).isEqualTo(4);
        assertThat(entry.ref()).isEqualTo(IoMessageRef.of(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4711@@@Orders(Module-0001)@@@Input.0",
        "4711@@@Orders(Module-0001)@@@Output.x", "4711@@@Orders(Module-0001)@@@Input.01",
        "4711@@@Orders(Module-0001)@@@Input.1234567890", "4711@@@Orders(Module-0001)@@@Input",
        "4711@@@Orders(Module-0001)@@@input.1", "4711@@@Orders(Module-0001)@@@Error.1",
        "4711@@@@@@Input.1", "4711@@@Orders(Module-0001)@@@Input.1 ", "4711", ""})
    void refusesAMalformedEntry(String raw) {
        assertThatIllegalArgumentException().isThrownBy(() -> IoLogEntry.parse("4711", raw))
            .withMessageContaining("I/O log entry");
    }

    @Test
    void refusesTheEntryOfAnotherProcess() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> IoLogEntry.parse("4711", "4712@@@Orders(Module-0001)@@@Input.1"))
            .withMessageContaining("another process");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> IoLogEntry.parse("471", "4711@@@Orders(Module-0001)@@@Input.1"))
            .withMessageContaining("another process");
    }
}
