package de.dadecker.inubit.mcp.adapter.os;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * {@link CliSystem} on Windows, plus the token user (feature 013, research R-1, R-6, R-8), mapped
 * here with JNA core: {@code GetSystemDirectoryW} and {@code GetOEMCP} of {@code Kernel32}, and the
 * user SID of the process token ({@code OpenProcessToken}, {@code GetTokenInformation} with
 * {@code TokenUser}, {@code ConvertSidToStringSidW} of {@code Advapi32}). The native libraries are
 * loaded on the first query, never on construction. A query that fails, also because a library
 * cannot be loaded, is empty; the OEM charset then falls back as for an unknown code page.
 */
public final class WindowsSystem implements CliSystem {

    /** {@code MAX_PATH}; the buffer for {@code GetSystemDirectoryW}. */
    private static final int MAX_PATH = 260;
    /** {@code TOKEN_QUERY}. */
    private static final int TOKEN_QUERY = 0x0008;
    /** {@code TOKEN_INFORMATION_CLASS.TokenUser}. */
    private static final int TOKEN_USER = 1;
    /** Holds {@code TOKEN_USER} (a pointer and a DWORD) plus {@code SECURITY_MAX_SID_SIZE} (68). */
    private static final int TOKEN_USER_BUFFER = 256;
    /** {@code CP_UTF8}. */
    private static final int CP_UTF8 = 65001;

    /**
     * The {@code Kernel32} functions. No {@code LastErrorException} is declared (as in
     * {@link JnaCredApi}): the result decides.
     */
    interface Kernel32 extends StdCallLibrary {

        int GetSystemDirectoryW(char[] buffer, int size);

        int GetOEMCP();

        Pointer GetCurrentProcess();

        boolean CloseHandle(Pointer handle);

        Pointer LocalFree(Pointer memory);
    }

    /** The {@code Advapi32} functions for the token user. */
    interface Advapi32 extends StdCallLibrary {

        boolean OpenProcessToken(Pointer process, int desiredAccess, PointerByReference token);

        boolean GetTokenInformation(Pointer token, int informationClass, Pointer information,
            int length, IntByReference returnLength);

        boolean ConvertSidToStringSidW(Pointer sid, PointerByReference text);
    }

    /** Loaded on first use (class initialization of the holder). */
    private static final class Holder {
        static final Kernel32 KERNEL32 = Native.load("Kernel32", Kernel32.class);
        static final Advapi32 ADVAPI32 = Native.load("Advapi32", Advapi32.class);
    }

    @Override
    public SystemDirectory systemDirectory() {
        try {
            char[] buffer = new char[MAX_PATH];
            int length = Holder.KERNEL32.GetSystemDirectoryW(buffer, buffer.length);
            if (length == 0) {
                return new Unreadable("GetSystemDirectoryW failed (Windows error "
                    + Native.getLastError() + ")");
            }
            // the buffer is too small
            return length < buffer.length ? new Read(new String(buffer, 0, length))
                : new Unreadable("the directory is longer than " + (MAX_PATH - 1)
                    + " characters");
        } catch (LinkageError e) {
            return new Unreadable("the Windows API could not be loaded ("
                + e.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public Charset oemCharset() {
        try {
            return charset(Holder.KERNEL32.GetOEMCP());
        } catch (LinkageError e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /**
     * The user SID of this process's token as {@code S-1-…} (research R-8: owner and first entry
     * of the server's private objects, also on an elevated account, where new objects would
     * otherwise be owned by {@code BUILTIN\Administrators}); empty if it cannot be read.
     */
    public Optional<String> userSid() {
        try {
            PointerByReference token = new PointerByReference();
            if (!Holder.ADVAPI32.OpenProcessToken(Holder.KERNEL32.GetCurrentProcess(),
                TOKEN_QUERY, token)) {
                return Optional.empty();
            }
            try (Memory information = new Memory(TOKEN_USER_BUFFER)) {
                if (!Holder.ADVAPI32.GetTokenInformation(token.getValue(), TOKEN_USER,
                    information, TOKEN_USER_BUFFER, new IntByReference())) {
                    return Optional.empty();
                }
                // TOKEN_USER starts with SID_AND_ATTRIBUTES, whose first field points to the SID
                // inside the buffer
                PointerByReference text = new PointerByReference();
                if (!Holder.ADVAPI32.ConvertSidToStringSidW(information.getPointer(0), text)) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(text.getValue().getWideString(0));
                } finally {
                    Holder.KERNEL32.LocalFree(text.getValue());
                }
            } finally {
                Holder.KERNEL32.CloseHandle(token.getValue());
            }
        } catch (LinkageError e) {
            return Optional.empty();
        }
    }

    /**
     * The charset of a Windows code page, named as the JDK names the console encoding:
     * {@code ms<cp>} for 874 to 950, UTF-8 for 65001, else {@code cp<cp>}. A code page the runtime
     * does not know gives ISO-8859-1, which decodes every byte as one character.
     */
    static Charset charset(int codePage) {
        if (codePage == CP_UTF8) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName((codePage >= 874 && codePage <= 950 ? "ms" : "cp") + codePage);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }
}
