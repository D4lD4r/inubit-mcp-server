package de.dadecker.inubit.mcp.adapter.rest.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** Feature 009 T001: the synthetic 9.0 fixture has the 8.1 shape and its version is read. */
class SystemInfoXmlParserTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");

    @Test
    void theVersionOfTheSyntheticNineZeroFixtureIsRead() throws IOException {
        SystemInfo info;
        try (InputStream in = getClass().getResourceAsStream(
                "/fixtures/v9_0/rest/system_info.xml")) {
            assertThat(in).as("fixtures/v9_0/rest/system_info.xml").isNotNull();
            info = SystemInfoXmlParser.parse(DEV, in.readAllBytes());
        }

        assertThat(info.version()).contains("9.0.7");
    }
}
