package de.dadecker.inubit.mcp.adapter.os;

import de.dadecker.inubit.mcp.domain.port.NetworkPathProbe;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link NetworkPathProbe} of Windows (feature 015, research R-10, FR-008): a drive is remote if
 * {@code GetDriveTypeW("<X>:\")} answers {@code DRIVE_REMOTE}, which also covers drives mapped by
 * other means (a {@code subst} of a UNC path). Without the Windows flag, and for a character that
 * is not an ASCII letter, the answer is {@code false} and the native API is never loaded. A native
 * library that cannot be loaded makes the drive unknown, hence {@code false} (the port's contract
 * for an unknown drive); that is logged once as a warning (the server builds one probe, so once
 * per process), naming only the error class.
 */
public final class WindowsNetworkPaths implements NetworkPathProbe {

    private static final Logger LOG = LoggerFactory.getLogger(WindowsNetworkPaths.class);

    private final boolean windows;
    private final Supplier<DriveTypeApi> api;
    private final AtomicBoolean loadFailureLogged = new AtomicBoolean();

    /**
     * @param windows whether the server runs on Windows
     * @param api     the drive-type API, obtained only when a drive letter is asked on Windows
     */
    public WindowsNetworkPaths(boolean windows, Supplier<DriveTypeApi> api) {
        this.windows = windows;
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public boolean remoteDrive(char letter) {
        if (!windows || !isAsciiLetter(letter)) {
            return false;
        }
        String root = Character.toUpperCase(letter) + ":\\";
        try {
            return api.get().driveType(root) == DriveTypeApi.DRIVE_REMOTE;
        } catch (LinkageError e) {
            if (loadFailureLogged.compareAndSet(false, true)) {
                LOG.warn("The Windows drive-type API could not be loaded ({}); every drive letter"
                        + " counts as local for the network-path check", e.getClass().getName());
            }
            return false;
        }
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
