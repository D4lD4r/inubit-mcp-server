package de.dadecker.inubit.mcp.adapter.os;

import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * {@link CliSystem} on macOS and Linux (feature 013): StartCLI starts as {@code bin/startcli.sh}
 * without an interpreter, so there is no system directory, and its output is UTF-8 as today.
 */
public final class PosixSystem implements CliSystem {

    @Override
    public Optional<String> systemDirectory() {
        return Optional.empty();
    }

    @Override
    public Charset oemCharset() {
        return StandardCharsets.UTF_8;
    }
}
