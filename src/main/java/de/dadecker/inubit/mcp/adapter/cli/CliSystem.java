package de.dadecker.inubit.mcp.adapter.cli;

import java.nio.charset.Charset;
import java.util.Optional;

/**
 * The operating-system queries a StartCLI launch needs (feature 013, research R-1, R-6). A port:
 * the implementations live in {@code adapter.os} ({@code WindowsSystem}, {@code PosixSystem}),
 * which {@code adapter.cli} never references.
 */
public interface CliSystem {

    /**
     * The Windows system directory as {@code GetSystemDirectoryW} reports it (e.g.
     * {@code C:\Windows\system32}), which holds the interpreter {@code cmd.exe}; empty if it cannot
     * be read (StartCLI is then unavailable, no directory is guessed) and on macOS and Linux, where
     * none is needed.
     */
    Optional<String> systemDirectory();

    /**
     * The charset of the console OEM code page ({@code GetOEMCP}), in which the interpreter prints
     * its own messages on Windows; UTF-8 on macOS and Linux.
     */
    Charset oemCharset();
}
