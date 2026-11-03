package de.dadecker.inubit.mcp.adapter.cli;

import java.util.Objects;

/**
 * Whether the processes StartCLI starts end with the server, also when the server is terminated
 * forcibly (feature 013, FR-005, research R-7). A port: the implementations live in
 * {@code adapter.os} ({@code WindowsJob}: a kill-on-close job on Windows; {@code NoJob}: macOS
 * and Linux, which need none), which {@code adapter.cli} never references. {@code Launcher} sets
 * it up once per process, before the server starts any process.
 */
public interface ProcessContainment {

    /** The state, fixed once set up. */
    State state();

    /** {@link Bound} or {@link Unavailable}. */
    sealed interface State permits Bound, Unavailable {
    }

    /** The server is in a kill-on-close job (Windows), or no job is needed (macOS, Linux). */
    record Bound() implements State {
    }

    /**
     * Windows: the job could not be set up; StartCLI-backed capabilities report
     * {@code CLI_UNAVAILABLE} with {@code reason}.
     */
    record Unavailable(String reason) implements State {

        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
