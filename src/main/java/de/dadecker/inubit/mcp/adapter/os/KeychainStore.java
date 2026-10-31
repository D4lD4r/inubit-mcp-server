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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The macOS Keychain (feature 011, research R-3): {@code /usr/bin/security find-generic-password -s
 * inubit-mcp -a <account> -g}. Reading through {@code security} keeps the trust that entries
 * created with {@code security add-generic-password} (and an earlier "Always Allow" for the start
 * script) already grant to that program.
 *
 * <ul>
 *   <li>Only service and account are arguments; the value comes back on stderr ({@code -g}) as
 *       {@code password: "<text>"} or, for non-printable values, {@code password: 0x<HEX>  "…"}
 *       (UTF-8). Unlike {@code -w}, the hex form is marked, so a password of hex digits is not
 *       ambiguous.
 *   <li>The {@code password:} line is removed from stderr before anything of it may be logged:
 *       the value is not registered with the scrubber when the outcome is unexpected.
 *   <li>The outcome is decided by the exit code (the low byte of the OSStatus) first.
 *   <li>The child gets {@code HOME}, {@code USER} and {@code LANG} only.
 * </ul>
 */
public final class KeychainStore implements CredentialStore {

    static final String SECURITY = "/usr/bin/security";

    /** {@code errSecItemNotFound}. */
    static final int NOT_FOUND = 44;
    /** {@code errSecUserCanceled}: the person chose "Deny". */
    static final int USER_CANCELED = 128;
    /** {@code errSecAuthFailed}. */
    static final int AUTH_FAILED = 51;
    /** {@code errSecInteractionNotAllowed}: no GUI session (e.g. SSH) or locked without a prompt. */
    static final int INTERACTION_NOT_ALLOWED = 36;
    /** {@code errSecNoSuchKeychain}. */
    static final int NO_SUCH_KEYCHAIN = 50;

    private static final List<String> PASSED_VARIABLES = List.of("HOME", "USER", "LANG");
    private static final String PASSWORD_PREFIX = "password:";

    private final ProcessLauncher launcher;
    private final StoreGate gate;
    private final Map<String, String> environment;
    private final Path workingDirectory;

    /**
     * @param environment the server's environment; only {@code HOME}, {@code USER} and
     *     {@code LANG} are passed on
     */
    public KeychainStore(ProcessLauncher launcher, StoreGate gate,
        Map<String, String> environment, Path workingDirectory) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.environment = ToolRun.allowed(environment, PASSED_VARIABLES);
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
    }

    @Override
    public StoreKind kind() {
        return StoreKind.KEYCHAIN;
    }

    @Override
    public Lookup read(String account, Duration timeout) {
        return gate.read(timeout, left -> readNow(account, left));
    }

    private Lookup readNow(String account, Duration timeout) {
        ToolRun.Outcome outcome = ToolRun.run(launcher, List.of(SECURITY, "find-generic-password",
            "-s", SERVICE, "-a", account, "-g"), environment, workingDirectory, timeout);
        return switch (outcome) {
            case ToolRun.Outcome.NotStarted notStarted -> new Lookup.Failed(
                FailureKind.STORE_NOT_AVAILABLE, SECURITY + " could not be started ("
                    + notStarted.reason() + ")");
            case ToolRun.Outcome.TimedOut timedOut -> new Lookup.Failed(FailureKind.TIMEOUT,
                "security did not exit within " + timeout.toSeconds() + " s");
            case ToolRun.Outcome.Exited exited -> classify(exited);
        };
    }

    private static Lookup classify(ToolRun.Outcome.Exited exited) {
        List<String> lines = new String(exited.stderr(), StandardCharsets.UTF_8).lines().toList();
        ToolRun.wipe(exited.stderr());
        ToolRun.wipe(exited.stdout());
        Optional<String> passwordLine = lines.stream()
            .filter(line -> line.startsWith(PASSWORD_PREFIX)).findFirst();
        String detail = "exit " + exited.code() + ": " + lines.stream()
            .filter(line -> !line.startsWith(PASSWORD_PREFIX))
            .filter(line -> !line.isBlank())
            .collect(Collectors.joining(" | "));
        return switch (exited.code()) {
            case 0 -> passwordLine.map(KeychainStore::parse)
                .orElseGet(() -> new Lookup.Failed(FailureKind.ACCESS_DENIED,
                    detail + " (no password line)"));
            case NOT_FOUND -> new Lookup.NotFound();
            case USER_CANCELED, AUTH_FAILED -> new Lookup.Failed(FailureKind.ACCESS_DENIED, detail);
            case INTERACTION_NOT_ALLOWED, NO_SUCH_KEYCHAIN -> new Lookup.Failed(
                FailureKind.STORE_NOT_AVAILABLE, detail);
            default -> new Lookup.Failed(FailureKind.ACCESS_DENIED, detail);
        };
    }

    /** The value of a {@code password:} line. */
    static Lookup parse(String line) {
        String rest = line.substring(PASSWORD_PREFIX.length()).strip();
        if (rest.isEmpty()) {
            return new Lookup.Found(Secret.of(""));
        }
        if (rest.startsWith("0x")) {
            int end = rest.indexOf(' ');
            String hex = rest.substring(2, end < 0 ? rest.length() : end);
            try {
                return new Lookup.Found(Secret.of(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(HexFormat.of().parseHex(hex))).toString()));
            } catch (IllegalArgumentException | CharacterCodingException e) {
                return new Lookup.Failed(FailureKind.INVALID_VALUE,
                    "the value cannot be decoded as UTF-8");
            }
        }
        if (rest.length() >= 2 && rest.startsWith("\"") && rest.endsWith("\"")) {
            return new Lookup.Found(Secret.of(rest.substring(1, rest.length() - 1)));
        }
        return new Lookup.Failed(FailureKind.INVALID_VALUE, "unexpected output format of security");
    }
}
