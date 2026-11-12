package com.inubit.ibis.utils;

/**
 * Test fake (feature 020, research D-19) of the INUBIT client interface: the login the
 * queue-entry helper uses and (feature 024, research D-9) {@code getObject}. The 9.0 interface
 * has no {@code logoutUser}.
 */
public interface I_IBISClientInterface {

    void loginUserCli(String user, String password) throws Exception;

    String getObject(String objectName, String user, String objectType) throws Exception;
}
