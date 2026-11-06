package de.dadecker.inubit.mcp.adapter.os;

import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

/**
 * {@link DriveTypeApi} through JNA (feature 015, research R-10): {@code GetDriveTypeW} of
 * {@code kernel32}. The native library is loaded on the first call, never on construction, so
 * other systems never load it.
 */
public final class JnaDriveTypeApi implements DriveTypeApi {

    /** The {@code W} function of {@code kernel32}; it sets no last error. */
    interface Kernel32 extends StdCallLibrary {

        int GetDriveTypeW(WString rootPathName);
    }

    /** Loaded on first use (class initialization of the holder). */
    private static final class Holder {
        static final Kernel32 KERNEL32 = Native.load("kernel32", Kernel32.class);
    }

    @Override
    public int driveType(String rootPathName) {
        return Holder.KERNEL32.GetDriveTypeW(new WString(rootPathName));
    }
}
