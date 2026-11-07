package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Feature 017 (T009, research D-5): the identity of one StartCLI client of a node. */
class ClientKeyTest {

    @Test
    void equalByValue() {
        assertThat(new ClientKey("/opt/acme/client-9.0", "/opt/jdk-21"))
            .isEqualTo(new ClientKey("/opt/acme/client-9.0", "/opt/jdk-21"))
            .hasSameHashCodeAs(new ClientKey("/opt/acme/client-9.0", "/opt/jdk-21"))
            .isNotEqualTo(new ClientKey("/opt/acme/client-9.0", ""))
            .isNotEqualTo(new ClientKey("/opt/acme/client-8.1", "/opt/jdk-21"));
    }

    @Test
    void emptyMeansNone() {
        ClientKey none = new ClientKey("", "");
        assertThat(none).isEqualTo(ClientKey.NONE);
        assertThat(none.hasHome()).isFalse();
        assertThat(new ClientKey("/opt/acme/client", "").hasHome()).isTrue();
    }

    @Test
    void refusesNull() {
        assertThatNullPointerException().isThrownBy(() -> new ClientKey(null, ""));
        assertThatNullPointerException().isThrownBy(() -> new ClientKey("", null));
    }

    @Test
    void holdsOnlyTheClientDirectoryAndTheJdk() {
        assertThat(Arrays.stream(ClientKey.class.getRecordComponents())
            .map(RecordComponent::getName)).containsExactly("home", "javaHome");
    }

    @Test
    void probedClientPairsTheKeyWithTheCheckedVersion() {
        ClientKey key = new ClientKey("/opt/acme/client-9.0", "");
        ClientVersion version = new ClientVersion(
            new InubitVersion(9, 0, OptionalInt.of(7), "9.0.7"), Optional.empty(),
            Optional.empty());
        ProbedClient probed = new ProbedClient(key, version);
        assertThat(probed.key()).isEqualTo(key);
        assertThat(probed.version()).isEqualTo(version);
        assertThatNullPointerException().isThrownBy(() -> new ProbedClient(null, version));
        assertThatNullPointerException().isThrownBy(() -> new ProbedClient(key, null));
    }
}
