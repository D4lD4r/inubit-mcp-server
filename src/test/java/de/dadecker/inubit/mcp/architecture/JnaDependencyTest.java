package de.dadecker.inubit.mcp.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * Feature 011 T001 (research R-4): JNA core is on the runtime classpath for the Windows Credential
 * Manager. The class is only loaded, not initialized, so its native library is never loaded here.
 */
class JnaDependencyTest {

    @Test
    void jnaLoadsWithoutItsNativeLibrary() {
        assertThatCode(() -> Class.forName("com.sun.jna.Native", false,
            JnaDependencyTest.class.getClassLoader())).doesNotThrowAnyException();
    }
}
