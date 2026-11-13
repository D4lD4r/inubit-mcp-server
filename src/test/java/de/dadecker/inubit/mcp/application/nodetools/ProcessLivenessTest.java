package de.dadecker.inubit.mcp.application.nodetools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLiveness;
import de.dadecker.inubit.mcp.domain.model.node.LeaseOwner;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * T046 (research D-13): the lease owner of this instance and the alive check, on the real
 * {@link SystemProcessLiveness}. Alive means the pid is alive and, when both start times are known,
 * they are equal within one second; when the recorded one is unknown, the pid alone decides.
 */
class ProcessLivenessTest {

    private final ProcessLiveness liveness = new SystemProcessLiveness();

    @Test
    void selfIsThisProcessAndAlive() {
        LeaseOwner self = liveness.self();

        assertThat(self.pid()).isEqualTo(ProcessHandle.current().pid());
        assertThat(liveness.alive(self)).isTrue();
    }

    @Test
    void aFinishedChildProcessIsNotAlive() throws Exception {
        Process child = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Optional<Instant> started = child.info().startInstant();
        assertThat(child.waitFor(30, TimeUnit.SECONDS)).as("child finished").isTrue();

        assertThat(liveness.alive(new LeaseOwner(child.pid(), started))).isFalse();
        assertThat(liveness.alive(new LeaseOwner(child.pid(), Optional.empty()))).isFalse();
    }

    @Test
    void theSamePidWithAnotherStartTimeIsNotAlive() {
        Instant started = startOfThisProcess();
        long pid = ProcessHandle.current().pid();

        assertThat(liveness.alive(new LeaseOwner(pid, Optional.of(started.minusSeconds(60)))))
            .isFalse();
        assertThat(liveness.alive(new LeaseOwner(pid, Optional.of(started.plusSeconds(2)))))
            .isFalse();
        assertThat(liveness.alive(new LeaseOwner(pid, Optional.of(started.plusMillis(900)))))
            .as("within one second").isTrue();
        assertThat(liveness.alive(new LeaseOwner(pid,
            Optional.of(started.minus(Duration.ofMillis(900)))))).as("within one second").isTrue();
    }

    @Test
    void anUnknownStartTimeLetsThePidDecide() {
        assertThat(liveness.alive(new LeaseOwner(ProcessHandle.current().pid(), Optional.empty())))
            .isTrue();
    }

    private static Instant startOfThisProcess() {
        Optional<Instant> started = ProcessHandle.current().info().startInstant();
        assertThat(started).as("the OS reports the start time of this process").isPresent();
        return started.orElseThrow();
    }
}
