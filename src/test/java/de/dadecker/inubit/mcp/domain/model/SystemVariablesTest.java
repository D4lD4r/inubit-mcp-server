package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Feature 020 (T005, FR-010): INUBIT's own workflow variables. */
class SystemVariablesTest {

    @Test
    void theFr010NamesAreSystemVariables() {
        for (String name : new String[] {"ISFileSize", "ISCurrentTime", "ISWorkflowName",
            "ISModuleName", "ISModuleId", "ISProcessId", "ISUserName", "ISServerName",
            "ISNodeName", "ISGlobalProcessId", "ISOverwriteSubWorkflowPriority"}) {
            assertThat(SystemVariables.isSystem(name)).as(name).isTrue();
        }
    }

    @Test
    void everyNameWithTheHiddenPrefixIsASystemVariable() {
        assertThat(SystemVariables.isSystem("_hidden.")).isTrue();
        assertThat(SystemVariables.isSystem("_hidden.ISRetryCount")).isTrue();
        assertThat(SystemVariables.isSystem("_hidden.orderId")).isTrue();
    }

    @Test
    void otherNamesAreNotSystemVariables() {
        for (String name : new String[] {"ISIN", "orderId", "isfilesize", "ISFileSize ",
            " ISFileSize", "ISFileSizeX", "_hidden", "_Hidden.x", "x_hidden.y", ""}) {
            assertThat(SystemVariables.isSystem(name)).as("'" + name + "'").isFalse();
        }
    }
}
