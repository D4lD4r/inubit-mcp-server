package de.dadecker.inubit.mcp.adapter.os;

/**
 * The {@code Kernel32} job-object calls of {@link WindowsJob} (feature 013, research R-7). A seam,
 * so that their order and the failure handling around them are tested without Windows;
 * production uses {@link JnaJobApi}. Every method may throw {@link LinkageError} if the native
 * library cannot be loaded.
 */
interface JobApi {

    /**
     * {@code CreateJobObjectW} without security attributes, so the handle cannot be inherited.
     *
     * @return the job handle, 0 if the call failed
     */
    long create();

    /**
     * {@code SetInformationJobObject}: {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}, no other
     * limit.
     */
    boolean limitKillOnClose(long job);

    /** {@code AssignProcessToJobObject} with {@code GetCurrentProcess()}. */
    boolean assignCurrentProcess(long job);

    /** {@code CloseHandle}. */
    void close(long job);

    /** {@code GetLastError()} after a failed call on this thread. */
    int lastError();
}
