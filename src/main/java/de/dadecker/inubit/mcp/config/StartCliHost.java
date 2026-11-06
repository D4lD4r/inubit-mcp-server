package de.dadecker.inubit.mcp.config;

import java.util.Objects;
import java.util.Optional;

/**
 * What the Windows host reports before StartCLI can start (feature 013, FR-005, FR-011,
 * contracts/configuration-delta.md), as {@code Launcher} found it for {@code --check-config}: why
 * the system directory with the command interpreter could not be read, and why StartCLI's
 * processes could not be bound to the server. Plain reasons, so that {@code config} does not
 * depend on the adapters that query them.
 *
 * @param systemDirectoryProblem why {@code GetSystemDirectoryW} failed; empty if it succeeded
 * @param containmentProblem why the kill-on-close job is unavailable; empty if it is bound
 */
public record StartCliHost(Optional<String> systemDirectoryProblem,
    Optional<String> containmentProblem) {

    private static final StartCliHost UNCHECKED = new StartCliHost(Optional.empty(),
        Optional.empty());

    public StartCliHost {
        Objects.requireNonNull(systemDirectoryProblem, "systemDirectoryProblem");
        Objects.requireNonNull(containmentProblem, "containmentProblem");
    }

    /** No problem known: macOS and Linux, and validations that do not query the host. */
    public static StartCliHost unchecked() {
        return UNCHECKED;
    }
}
