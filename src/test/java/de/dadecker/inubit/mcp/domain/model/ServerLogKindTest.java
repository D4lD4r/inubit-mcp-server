package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ServerLogKindTest {

    @Test
    void wireNamesAndFileNames() {
        assertThat(ServerLogKind.TRACE.wireName()).isEqualTo("trace");
        assertThat(ServerLogKind.TRACE.fileName()).isEqualTo("trace.log");
        assertThat(ServerLogKind.WEBSERVICE.wireName()).isEqualTo("webservice");
        assertThat(ServerLogKind.WEBSERVICE.fileName()).isEqualTo("webservice.log");
    }

    @Test
    void fromWireAcceptsTheWireNames() {
        assertThat(ServerLogKind.fromWire("trace")).isEqualTo(ServerLogKind.TRACE);
        assertThat(ServerLogKind.fromWire("webservice")).isEqualTo(ServerLogKind.WEBSERVICE);
    }

    @Test
    void fromWireRejectsAnythingElse() {
        assertThatIllegalArgumentException().isThrownBy(() -> ServerLogKind.fromWire("TRACE"));
        assertThatIllegalArgumentException().isThrownBy(() -> ServerLogKind.fromWire("trace.log"));
        assertThatIllegalArgumentException().isThrownBy(() -> ServerLogKind.fromWire("server"));
        assertThatIllegalArgumentException().isThrownBy(() -> ServerLogKind.fromWire(""));
        assertThatIllegalArgumentException().isThrownBy(() -> ServerLogKind.fromWire(null));
    }
}
