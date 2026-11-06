package de.dadecker.inubit.mcp.adapter.cli;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * The raw outcome of one StartCLI run, before classification ({@link CliOutputClassifier}).
 *
 * @param stdout at most {@code outputBound} bytes of standard output (UTF-8)
 * @param stderr at most {@code outputBound} bytes of standard error (UTF-8)
 * @param truncated true if stdout or stderr exceeded the bound and was cut
 * @param outputBound the bound in bytes per stream of this run: {@link CliRunner#MAX_OUTPUT_BYTES}
 *     for {@code --execCommand} runs and health sessions, larger for 6.1 process listings
 * @param clientMajor the INUBIT major the client was chosen for ({@code CliChoice.major},
 *     feature 017 research D-16), if known
 */
public record CliResult(int exitCode, String stdout, String stderr, Duration duration,
    boolean truncated, int outputBound, OptionalInt clientMajor) {

    public CliResult {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(clientMajor, "clientMajor");
    }

    /** A result whose client major is unknown. */
    public CliResult(int exitCode, String stdout, String stderr, Duration duration,
        boolean truncated, int outputBound) {
        this(exitCode, stdout, stderr, duration, truncated, outputBound, OptionalInt.empty());
    }

    /** This result with {@code major} as the client major. */
    public CliResult forClientMajor(OptionalInt major) {
        return new CliResult(exitCode, stdout, stderr, duration, truncated, outputBound, major);
    }
}
