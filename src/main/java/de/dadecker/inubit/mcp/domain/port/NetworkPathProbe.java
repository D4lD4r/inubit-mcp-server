package de.dadecker.inubit.mcp.domain.port;

/**
 * Tells whether a Windows drive letter is a mapped network drive (feature 015, research R-10,
 * FR-008). The port takes the letter, not a path: the folder rules extract it from the raw
 * string ({@code <X>:\} or {@code <X>:/} at its start), which has no root on a non-Windows host.
 * UNC paths need no probe; the rules recognise them from the raw string.
 */
public interface NetworkPathProbe {

    /**
     * Returns {@code true} if the drive {@code letter} (upper case, {@code 'A'}–{@code 'Z'}) is a
     * remote drive as far as the operating system reports it; {@code false} for a local or
     * unknown drive and on every system other than Windows.
     */
    boolean remoteDrive(char letter);
}
