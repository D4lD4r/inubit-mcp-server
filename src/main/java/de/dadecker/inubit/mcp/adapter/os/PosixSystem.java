package de.dadecker.inubit.mcp.adapter.os;

import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * {@link CliSystem} on macOS and Linux (feature 013): StartCLI starts as {@code bin/startcli.sh}
 * without an interpreter, so there is no system directory, and its output is UTF-8 as today.
 */
public final class PosixSystem implements CliSystem {

    @Override
    public SystemDirectory systemDirectory() {
        return new Unreadable("macOS and Linux start StartCLI without an interpreter");
    }

    @Override
    public Charset oemCharset() {
        return StandardCharsets.UTF_8;
    }
}
