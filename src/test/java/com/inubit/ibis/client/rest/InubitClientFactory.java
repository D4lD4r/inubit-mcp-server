package com.inubit.ibis.client.rest;

import com.inubit.ibis.configuration.utils.ConnectionData;
import com.inubit.ibis.utils.I_IBISClientInterface;
import de.dadecker.inubit.mcp.testing.FakeInubitScript;

/**
 * Test fake (feature 020, research "Spike facts", D-19) of the 9.0 client factory; its client
 * has no {@code logoutUser}, as the 9.0 interface has none.
 */
public class InubitClientFactory {

    static {
        FakeInubitScript.initializing("InubitClientFactory");
    }

    public I_IBISClientInterface getRestInstance(ConnectionData connectionData) {
        FakeInubitScript.active().record("InubitClientFactory.getRestInstance");
        return new RestClient();
    }

    private static final class RestClient implements I_IBISClientInterface {

        @Override
        public void loginUserCli(String user, String password) throws Exception {
            FakeInubitScript script = FakeInubitScript.active();
            script.record("RestClient.loginUserCli", user, password);
            script.login();
        }

        @Override
        public String getObject(String objectName, String user, String objectType)
            throws Exception {
            FakeInubitScript script = FakeInubitScript.active();
            script.record("RestClient.getObject", objectName, user, objectType);
            return script.object(objectName, objectType);
        }
    }
}
