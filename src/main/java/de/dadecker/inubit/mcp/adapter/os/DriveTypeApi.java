package de.dadecker.inubit.mcp.adapter.os;

/**
 * The one Windows API call the network-path probe needs (feature 015, research R-10):
 * {@code GetDriveTypeW}. A seam, so that the mapping of the answer is tested without Windows;
 * production uses {@link JnaDriveTypeApi}.
 */
public interface DriveTypeApi {

    /** {@code DRIVE_REMOTE}: a remote (network) drive. */
    int DRIVE_REMOTE = 4;

    /**
     * Returns the drive type of {@code rootPathName} ({@code <X>:\}) as {@code GetDriveTypeW}
     * reports it.
     *
     * @throws LinkageError if the native library cannot be loaded
     */
    int driveType(String rootPathName);
}
