package de.dadecker.inubit.mcp.adapter.cli;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * The operating-system queries a StartCLI launch needs (feature 013, research R-1, R-6). A port:
 * the implementations live in {@code adapter.os} ({@code WindowsSystem}, {@code PosixSystem}),
 * which {@code adapter.cli} never references.
 */
public interface CliSystem {

    /** The outcome of {@link #systemDirectory}. */
    sealed interface SystemDirectory {
    }

    /** The directory as {@code GetSystemDirectoryW} reports it. */
    record Read(String path) implements SystemDirectory {

        public Read {
            Objects.requireNonNull(path, "path");
        }
    }

    /** The directory is unknown: {@code reason} says why, without a guessed directory. */
    record Unreadable(String reason) implements SystemDirectory {

        public Unreadable {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The Windows system directory as {@code GetSystemDirectoryW} reports it (e.g.
     * {@code C:\Windows\system32}), which holds the interpreter {@code cmd.exe}; {@link Unreadable}
     * with the reason if it cannot be read (StartCLI is then unavailable, no directory is guessed)
     * and on macOS and Linux, where none is needed.
     */
    SystemDirectory systemDirectory();

    /**
     * The charset of the console OEM code page ({@code GetOEMCP}), in which the interpreter prints
     * its own messages on Windows; UTF-8 on macOS and Linux.
     */
    Charset oemCharset();
}
