package de.dadecker.inubit.mcp.adapter.os;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

/**
 * {@link JobApi} through JNA core (feature 013, research R-7): {@code CreateJobObjectW},
 * {@code SetInformationJobObject} with {@code JOBOBJECT_EXTENDED_LIMIT_INFORMATION},
 * {@code AssignProcessToJobObject}, {@code GetCurrentProcess} and {@code CloseHandle} of
 * {@code Kernel32}. The native library is loaded on the first call, never on construction.
 */
final class JnaJobApi implements JobApi {

    /** {@code JOBOBJECTINFOCLASS.JobObjectExtendedLimitInformation}. */
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
    /**
     * Offset of {@code BasicLimitInformation.LimitFlags} in
     * {@code JOBOBJECT_EXTENDED_LIMIT_INFORMATION}: after two 8-byte time limits.
     */
    private static final int LIMIT_FLAGS_OFFSET = 16;

    /**
     * No {@code LastErrorException} is declared (as in {@link JnaCredApi}): the result decides,
     * {@link Native#getLastError()} is read only after a failed call.
     */
    interface Kernel32 extends StdCallLibrary {

        Pointer CreateJobObjectW(Pointer attributes, WString name);

        boolean SetInformationJobObject(Pointer job, int informationClass, Pointer information,
            int length);

        boolean AssignProcessToJobObject(Pointer job, Pointer process);

        Pointer GetCurrentProcess();

        boolean CloseHandle(Pointer handle);
    }

    /** Loaded on first use (class initialization of the holder). */
    private static final class Holder {
        static final Kernel32 KERNEL32 = Native.load("Kernel32", Kernel32.class);
    }

    @Override
    public long create() {
        return Pointer.nativeValue(Holder.KERNEL32.CreateJobObjectW(null, null));
    }

    @Override
    public boolean limitKillOnClose(long job) {
        // sizeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION): 144 bytes on 64 bit, 112 on 32 bit
        int size = Native.POINTER_SIZE == 8 ? 144 : 112;
        try (Memory limits = new Memory(size)) {
            limits.clear();
            limits.setInt(LIMIT_FLAGS_OFFSET, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
            return Holder.KERNEL32.SetInformationJobObject(new Pointer(job),
                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, limits, size);
        }
    }

    @Override
    public boolean assignCurrentProcess(long job) {
        return Holder.KERNEL32.AssignProcessToJobObject(new Pointer(job),
            Holder.KERNEL32.GetCurrentProcess());
    }

    @Override
    public void close(long job) {
        Holder.KERNEL32.CloseHandle(new Pointer(job));
    }

    @Override
    public int lastError() {
        return Native.getLastError();
    }
}
