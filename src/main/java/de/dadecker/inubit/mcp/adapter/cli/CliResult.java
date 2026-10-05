package de.dadecker.inubit.mcp.adapter.cli;

import java.time.Duration;
import java.util.Objects;

/**
 * The raw outcome of one StartCLI run, before classification ({@link CliOutputClassifier}).
 *
 * @param stdout at most {@link CliRunner#MAX_OUTPUT_BYTES} of standard output (UTF-8)
 * @param stderr at most {@link CliRunner#MAX_OUTPUT_BYTES} of standard error (UTF-8)
 * @param truncated true if stdout or stderr exceeded the bound and was cut
 */
public record CliResult(int exitCode, String stdout, String stderr, Duration duration,
    boolean truncated) {

    public CliResult {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(duration, "duration");
    }
}
