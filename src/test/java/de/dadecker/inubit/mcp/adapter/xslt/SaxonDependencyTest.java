package de.dadecker.inubit.mcp.adapter.xslt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * T001 (research D-11): the XSLT engine is Saxon-HE of INUBIT 8.1's Saxon 10 line, pinned to
 * 10.9. Looked up reflectively so that a missing dependency fails as an assertion.
 */
class SaxonDependencyTest {

    @Test
    void saxonHomeEdition109IsOnTheClasspath() throws ReflectiveOperationException {
        Class<?> version;
        try {
            version = Class.forName("net.sf.saxon.Version");
        } catch (ClassNotFoundException e) {
            fail("Saxon-HE (net.sf.saxon:Saxon-HE) is not on the classpath");
            return;
        }

        assertThat(version.getMethod("getProductVersion").invoke(null)).isEqualTo("10.9");
        assertThat((String) version.getMethod("getProductTitle").invoke(null))
            .as("the Home Edition, not PE/EE").contains("SAXON-HE");
    }
}
