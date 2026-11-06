package de.dadecker.inubit.mcp.adapter.os;

import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;

/**
 * {@link ProcessContainment} on Windows (feature 013, FR-005, research R-7): binds the server
 * process to a new job object with {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}. Every process
 * started afterwards (the interpreter, Java and their descendants) is in the job, and Windows ends
 * them when the job's last handle closes, which is when the server ends, also on a forced
 * termination. The handle is never closed and cannot be inherited, so no child keeps the job
 * open. If the native library cannot be loaded or a call fails, the state is
 * {@link Unavailable} with the reason, and a job created on the way is closed.
 */
public final class WindowsJob implements ProcessContainment {

    private final State state;
    /** The open job handle; 0 if unavailable. */
    private final long handle;

    private WindowsJob(State state, long handle) {
        this.state = state;
        this.handle = handle;
    }

    /** Binds this process to a new kill-on-close job; called once per process. */
    public static WindowsJob bindCurrentProcess() {
        return bind(new JnaJobApi());
    }

    static WindowsJob bind(JobApi api) {
        try {
            long job = api.create();
            if (job == 0) {
                return unavailable("CreateJobObjectW", api.lastError());
            }
            if (!api.limitKillOnClose(job)) {
                return closed(api, job, "SetInformationJobObject");
            }
            // a process already in a job (e.g. of a CI runner) gets this one nested (Windows 8+)
            if (!api.assignCurrentProcess(job)) {
                return closed(api, job, "AssignProcessToJobObject");
            }
            return new WindowsJob(new Bound(), job);
        } catch (LinkageError e) {
            return new WindowsJob(new Unavailable("Kernel32 could not be loaded ("
                + e.getClass().getSimpleName() + ")"), 0);
        }
    }

    @Override
    public State state() {
        return state;
    }

    /** The open job handle, 0 if unavailable (for the test of its inheritance flag). */
    long handle() {
        return handle;
    }

    /** Closes {@code job} after reading the error of the failed {@code function}. */
    private static WindowsJob closed(JobApi api, long job, String function) {
        int error = api.lastError();
        api.close(job);
        return unavailable(function, error);
    }

    private static WindowsJob unavailable(String function, int error) {
        return new WindowsJob(new Unavailable(function + " failed (Windows error " + error + ")"),
            0);
    }
}
