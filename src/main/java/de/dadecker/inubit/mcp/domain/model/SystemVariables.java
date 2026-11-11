package de.dadecker.inubit.mcp.domain.model;

import java.util.Set;

/** INUBIT's own workflow variables (feature 020, FR-010). */
public final class SystemVariables {

    private static final Set<String> NAMES = Set.of("ISFileSize", "ISCurrentTime",
        "ISWorkflowName", "ISModuleName", "ISModuleId", "ISProcessId", "ISUserName",
        "ISServerName", "ISNodeName", "ISGlobalProcessId", "ISOverwriteSubWorkflowPriority");

    private static final String HIDDEN_PREFIX = "_hidden.";

    private SystemVariables() {
    }

    /** True for the names of FR-010 and every name starting with {@code _hidden.}. */
    public static boolean isSystem(String name) {
        return NAMES.contains(name) || name.startsWith(HIDDEN_PREFIX);
    }
}
