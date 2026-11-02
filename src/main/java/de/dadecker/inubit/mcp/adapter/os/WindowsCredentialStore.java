package de.dadecker.inubit.mcp.adapter.os;

import de.dadecker.inubit.mcp.config.CredentialStore;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.StoreKind;
import de.dadecker.inubit.mcp.infra.Secret;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Windows Credential Manager (feature 011, research R-4): the generic credential with the
 * target name {@code inubit-mcp:<account>} (its user-name field is ignored), read with
 * {@code CredReadW}. Its blob is UTF-16LE, as {@code cmdkey} and the Credential Manager UI store
 * it; the byte copy is wiped after decoding. The read never prompts; it runs on its own platform
 * daemon thread and is abandoned when the timeout passes. A native library that cannot be loaded means the store is
 * not available.
 */
public final class WindowsCredentialStore implements CredentialStore {

    /** {@code ERROR_NOT_FOUND}. */
    static final int ERROR_NOT_FOUND = 1168;
    /** {@code ERROR_NO_SUCH_LOGON_SESSION}: no credential set for this session. */
    static final int ERROR_NO_SUCH_LOGON_SESSION = 1312;

    private final CredApi api;
    private final StoreGate gate;

    public WindowsCredentialStore(StoreGate gate) {
        this(new JnaCredApi(), gate);
    }

    WindowsCredentialStore(CredApi api, StoreGate gate) {
        this.api = Objects.requireNonNull(api, "api");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /** The target name of {@code account}. */
    static String targetName(String account) {
        return SERVICE + ":" + account;
    }

    @Override
    public StoreKind kind() {
        return StoreKind.CREDENTIAL_MANAGER;
    }

    @Override
    public Lookup read(String account, Duration timeout) {
        return gate.read(timeout, left -> readNow(account, left));
    }

    private Lookup readNow(String account, Duration timeout) {
        CompletableFuture<Lookup> lookup = new CompletableFuture<>();
        // a platform daemon thread: a native call that never returns must not pin a carrier thread
        // of the virtual threads
        Thread.ofPlatform().daemon().name("credential-manager-read").start(() -> {
            try {
                lookup.complete(classify(api.readGeneric(targetName(account))));
            } catch (LinkageError e) {
                lookup.complete(new Lookup.Failed(FailureKind.STORE_NOT_AVAILABLE,
                    "the Windows credential API could not be loaded ("
                        + e.getClass().getSimpleName() + ")"));
            } catch (RuntimeException e) {
                lookup.complete(new Lookup.Failed(FailureKind.ACCESS_DENIED,
                    "CredReadW failed (" + e.getClass().getSimpleName() + ")"));
            }
        });
        try {
            return lookup.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return new Lookup.Failed(FailureKind.TIMEOUT,
                "CredReadW did not return within " + timeout.toSeconds() + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Lookup.Failed(FailureKind.TIMEOUT, "interrupted");
        } catch (ExecutionException e) {
            return new Lookup.Failed(FailureKind.ACCESS_DENIED, "CredReadW failed");
        }
    }

    private static Lookup classify(CredApi.Result result) {
        return switch (result) {
            case CredApi.Result.Blob blob -> decode(blob.bytes());
            case CredApi.Result.Error error -> switch (error.code()) {
                case ERROR_NOT_FOUND -> new Lookup.NotFound();
                case ERROR_NO_SUCH_LOGON_SESSION -> new Lookup.Failed(
                    FailureKind.STORE_NOT_AVAILABLE, "CredReadW: error " + error.code()
                        + " (no credential set for this logon session)");
                default -> new Lookup.Failed(FailureKind.ACCESS_DENIED,
                    "CredReadW: error " + error.code());
            };
        };
    }

    /** Strict UTF-16LE; the bytes are wiped afterwards. */
    static Lookup decode(byte[] bytes) {
        try {
            if (bytes.length % 2 != 0) {
                return new Lookup.Failed(FailureKind.INVALID_VALUE,
                    "the value cannot be decoded as UTF-16 (odd length)");
            }
            return new Lookup.Found(Secret.of(StandardCharsets.UTF_16LE.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()));
        } catch (CharacterCodingException e) {
            return new Lookup.Failed(FailureKind.INVALID_VALUE,
                "the value cannot be decoded as UTF-16");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
