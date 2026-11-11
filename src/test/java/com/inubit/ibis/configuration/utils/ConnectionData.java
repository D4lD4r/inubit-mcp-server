package com.inubit.ibis.configuration.utils;

import de.dadecker.inubit.mcp.testing.FakeInubitScript;
import java.io.File;

/**
 * Test fake (feature 020, research "Spike facts", D-19) of the 9.0 connection settings: the
 * setters only record their call in the active {@link FakeInubitScript}.
 */
public class ConnectionData {

    public void setServerURL(String serverUrl) {
        FakeInubitScript.active().record("ConnectionData.setServerURL", serverUrl);
    }

    public void setUserName(String userName) {
        FakeInubitScript.active().record("ConnectionData.setUserName", userName);
    }

    public void setPassword(String password) {
        FakeInubitScript.active().record("ConnectionData.setPassword", password);
    }

    public void setServerAuth(boolean serverAuth) {
        FakeInubitScript.active().record("ConnectionData.setServerAuth", serverAuth);
    }

    public void setServerTrustStoreFile(File trustStoreFile) {
        FakeInubitScript.active().record("ConnectionData.setServerTrustStoreFile",
            trustStoreFile);
    }

    public void setServerTrustStorePassword(String trustStorePassword) {
        FakeInubitScript.active().record("ConnectionData.setServerTrustStorePassword",
            trustStorePassword);
    }

    public void setSkipHostnameVerification(boolean skipHostnameVerification) {
        FakeInubitScript.active().record("ConnectionData.setSkipHostnameVerification",
            skipHostnameVerification);
    }
}
