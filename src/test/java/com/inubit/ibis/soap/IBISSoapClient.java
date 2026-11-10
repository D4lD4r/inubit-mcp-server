package com.inubit.ibis.soap;

import com.inubit.ibis.utils.I_IBISClientInterface;
import de.dadecker.inubit.mcp.testing.FakeInubitScript;

/**
 * Test fake (feature 020, research "Spike facts", D-19) of the 8.1 SOAP client, driven by the
 * active {@link FakeInubitScript}, which records every call.
 */
public class IBISSoapClient implements I_IBISClientInterface {

    public IBISSoapClient(String url, String trustStoreFile, String trustStorePassword,
        String keyStoreFile, String keyStorePassword, boolean enableHostnameVerification) {
        FakeInubitScript.active().record("IBISSoapClient.<init>", url, trustStoreFile,
            trustStorePassword, keyStoreFile, keyStorePassword, enableHostnameVerification);
    }

    @Override
    public void loginUserCli(String user, String password) throws Exception {
        FakeInubitScript script = FakeInubitScript.active();
        script.record("IBISSoapClient.loginUserCli", user, password);
        script.login();
    }

    public void logoutUser(String user) {
        FakeInubitScript.active().record("IBISSoapClient.logoutUser", user);
    }
}
