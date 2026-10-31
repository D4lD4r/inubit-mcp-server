package de.dadecker.inubit.mcp.adapter.os;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.config.CredentialStore;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.StoreKind;
import de.dadecker.inubit.mcp.infra.Secret;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The Linux Secret Service (feature 011, research R-5): {@code secret-tool lookup service inubit-mcp
 * account <account>}, which searches all collections of the session's Secret Service (GNOME
 * Keyring, KeePassXC, KWallet) and unlocks a locked one through the session's prompter.
 *
 * <ul>
 *   <li>The tool is the first existing of {@code /usr/bin/secret-tool},
 *       {@code /usr/local/bin/secret-tool}, then one found on {@code PATH}; without it the store
 *       is not available.
 *   <li>The child gets an allowlist of the server's environment (session bus, display, locale) and
 *       never the leftover {@code INUBIT_*} credential variables.
 *   <li>The value comes back on stdout (no line break is added on a pipe), decoded as strict UTF-8;
 *       stdout is never part of a log or failure detail, only stderr is.
 *   <li>A dismissed unlock prompt most likely looks like "not found" (exit 1, no output); to be
 *       confirmed by the Linux live check (research R-5 risk).
 * </ul>
 */
public final class SecretServiceStore implements CredentialStore {

    static final List<String> FIXED_LOCATIONS = List.of("/usr/bin/secret-tool",
        "/usr/local/bin/secret-tool");
    static final List<String> PASSED_VARIABLES = List.of("DBUS_SESSION_BUS_ADDRESS",
        "XDG_RUNTIME_DIR", "DISPLAY", "WAYLAND_DISPLAY", "HOME", "USER", "LANG", "PATH");

    private final ProcessLauncher launcher;
    private final StoreGate gate;
    private final Map<String, String> environment;
    private final Path workingDirectory;
    private final Optional<String> executable;

    /**
     * @param environment the server's environment; only {@link #PASSED_VARIABLES} are passed on
     * @param isExecutable whether a path is an executable file (the file system in production)
     */
    public SecretServiceStore(ProcessLauncher launcher, StoreGate gate,
        Map<String, String> environment, Path workingDirectory, Predicate<Path> isExecutable) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.environment = ToolRun.allowed(environment, PASSED_VARIABLES);
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.executable = locate(environment.get("PATH"), isExecutable);
    }

    @Override
    public StoreKind kind() {
        return StoreKind.SECRET_SERVICE;
    }

    @Override
    public Lookup read(String account, Duration timeout) {
        if (executable.isEmpty()) {
            return new Lookup.Failed(FailureKind.STORE_NOT_AVAILABLE,
                "secret-tool is not installed (package libsecret-tools or libsecret)");
        }
        return gate.read(timeout, left -> readNow(executable.get(), account, left));
    }

    private Lookup readNow(String tool, String account, Duration timeout) {
        ToolRun.Outcome outcome = ToolRun.run(launcher, List.of(tool, "lookup", "service",
            SERVICE, "account", account), environment, workingDirectory, timeout);
        return switch (outcome) {
            case ToolRun.Outcome.NotStarted notStarted -> new Lookup.Failed(
                FailureKind.STORE_NOT_AVAILABLE, tool + " could not be started ("
                    + notStarted.reason() + ")");
            case ToolRun.Outcome.TimedOut timedOut -> new Lookup.Failed(FailureKind.TIMEOUT,
                "secret-tool " + timedOut.reason());
            case ToolRun.Outcome.Exited exited -> classify(exited);
        };
    }

    private static Lookup classify(ToolRun.Outcome.Exited exited) {
        String stderr = new String(exited.stderr(), StandardCharsets.UTF_8).strip();
        if (exited.code() == 0) {
            // an empty entry prints nothing: an empty value, which the caller treats as not found
            Lookup lookup = decode(exited.stdout());
            ToolRun.wipe(exited.stdout());
            return lookup;
        }
        ToolRun.wipe(exited.stdout());
        if (exited.code() == 1 && stderr.isEmpty()) {
            return new Lookup.NotFound();
        }
        String detail = "exit " + exited.code() + ": " + stderr.replace('\n', ' ');
        if (stderr.contains("Cannot autolaunch D-Bus") || stderr.contains("org.freedesktop.secrets")
            || stderr.contains("Could not connect") || stderr.contains("DBUS_SESSION_BUS_ADDRESS")) {
            return new Lookup.Failed(FailureKind.STORE_NOT_AVAILABLE, detail);
        }
        return new Lookup.Failed(FailureKind.ACCESS_DENIED, detail);
    }

    /** The value printed on stdout, as strict UTF-8. */
    static Lookup decode(byte[] stdout) {
        try {
            return new Lookup.Found(Secret.of(StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(stdout)).toString()));
        } catch (CharacterCodingException e) {
            return new Lookup.Failed(FailureKind.INVALID_VALUE,
                "the value cannot be decoded as UTF-8");
        }
    }

    private static Optional<String> locate(String path, Predicate<Path> isExecutable) {
        for (String location : FIXED_LOCATIONS) {
            if (isExecutable.test(Path.of(location))) {
                return Optional.of(location);
            }
        }
        if (path == null) {
            return Optional.empty();
        }
        for (String directory : path.split(":")) {
            if (!directory.isEmpty()) {
                Path candidate = Path.of(directory, "secret-tool");
                if (candidate.isAbsolute() && isExecutable.test(candidate)) {
                    return Optional.of(candidate.toString());
                }
            }
        }
        return Optional.empty();
    }
}
