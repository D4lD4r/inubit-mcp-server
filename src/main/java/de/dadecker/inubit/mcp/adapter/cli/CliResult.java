package de.dadecker.inubit.mcp.adapter.cli;

import java.time.Duration;
import java.util.Objects;

/**
 * The raw outcome of one StartCLI run, before classification ({@link CliOutputClassifier}).
 *
 * @param stdout at most {@code outputBound} bytes of standard output (UTF-8)
 * @param stderr at most {@code outputBound} bytes of standard error (UTF-8)
 * @param truncated true if stdout or stderr exceeded the bound and was cut
 * @param outputBound the bound in bytes per stream of this run: {@link CliRunner#MAX_OUTPUT_BYTES}
 *     for {@code --execCommand} runs and health sessions, larger for 6.1 process listings
 */
public record CliResult(int exitCode, String stdout, String stderr, Duration duration,
    boolean truncated, int outputBound) {

    public CliResult {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(duration, "duration");
    }
}
