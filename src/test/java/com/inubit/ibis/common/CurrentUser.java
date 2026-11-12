package com.inubit.ibis.common;

import de.dadecker.inubit.mcp.testing.FakeInubitScript;

/**
 * Test fake (feature 021, research "Spike findings", D-22) of the vendor's client identity: a
 * fixed client UID and no additional user group, as the 8.1 spike observed in a plain JVM. Local
 * values: its calls are not recorded.
 */
public class CurrentUser {

    static {
        FakeInubitScript.initializing("CurrentUser");
    }

    private CurrentUser() {
    }

    public static synchronized String getClientUID() {
        return "fake-client-uid-0001";
    }

    public static String getUsedAdditionalUserGroup() {
        return null;
    }
}
