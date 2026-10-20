package de.dadecker.inubit.mcp.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T001 (research R-1): the AWS SDK is on the runtime classpath with {@code url-connection-client}
 * as its only HTTP client; Netty and both Apache HTTP clients are excluded from every SDK
 * artifact. Classes are only loaded, not initialized.
 */
class AwsSdkDependencyTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient",
        "software.amazon.awssdk.services.ssm.SsmClient"
    })
    void requiredSdkClassesLoad(String className) {
        assertThatCode(() -> load(className)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "software.amazon.awssdk.http.apache.ApacheHttpClient",
        "software.amazon.awssdk.http.apache5.Apache5HttpClient",
        "software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient"
    })
    void excludedHttpClientsAreAbsent(String className) {
        assertThatThrownBy(() -> load(className)).isInstanceOf(ClassNotFoundException.class);
    }

    private static Class<?> load(String className) throws ClassNotFoundException {
        return Class.forName(className, false, AwsSdkDependencyTest.class.getClassLoader());
    }
}
