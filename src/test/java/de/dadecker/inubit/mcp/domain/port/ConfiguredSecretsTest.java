package de.dadecker.inubit.mcp.domain.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.SecretSpan;
import de.dadecker.inubit.mcp.domain.port.ConfiguredSecrets.Pending;
import de.dadecker.inubit.mcp.domain.port.ConfiguredSecrets.Readiness;
import de.dadecker.inubit.mcp.testing.FakeConfiguredSecrets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Feature 019 (T013, research D-7): the port's states and its test double. */
class ConfiguredSecretsTest {

    @Test
    void aNotReadyStateNamesItsPendingReads() {
        List<Pending> pending = new ArrayList<>(List.of(new Pending("node1", "Keychain",
            "waiting")));
        Readiness.NotReady notReady = new Readiness.NotReady(pending);
        pending.clear();

        assertThat(notReady.pending()).singleElement().isEqualTo(new Pending("node1", "Keychain",
            "waiting"));
        assertThatThrownBy(() -> new Readiness.NotReady(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFakeScriptsEachState() {
        FakeConfiguredSecrets failed = FakeConfiguredSecrets.failed("node2",
            "AWS Parameter Store", "unreachable");

        assertThat(FakeConfiguredSecrets.held("a").prepare(Duration.ZERO))
            .isInstanceOf(Readiness.Ready.class);
        assertThat(FakeConfiguredSecrets.nothingConfigured().prepare(Duration.ZERO))
            .isInstanceOf(Readiness.Ready.class);
        assertThat(FakeConfiguredSecrets.waiting("node1", "Keychain").prepare(Duration.ZERO))
            .isEqualTo(new Readiness.NotReady(List.of(new Pending("node1", "Keychain",
                "waiting"))));
        assertThat(failed.prepare(Duration.ofSeconds(10))).isEqualTo(new Readiness.NotReady(
            List.of(new Pending("node2", "AWS Parameter Store", "unreachable"))));
        assertThat(failed.prepared()).containsExactly(Duration.ofSeconds(10));

        failed.hold("pw");
        assertThat(failed.prepare(Duration.ZERO)).isInstanceOf(Readiness.Ready.class);
        assertThat(failed.occurrences("pw pw")).containsExactly(new SecretSpan(0, 2),
            new SecretSpan(3, 5));
    }
}
