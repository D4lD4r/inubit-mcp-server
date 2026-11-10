package com.inubit.ibis.utils;

/**
 * Test fake (feature 020, research D-19) of the INUBIT client interface: only the login the
 * queue-entry helper uses. The 9.0 interface has no {@code logoutUser}.
 */
public interface I_IBISClientInterface {

    void loginUserCli(String user, String password) throws Exception;
}
