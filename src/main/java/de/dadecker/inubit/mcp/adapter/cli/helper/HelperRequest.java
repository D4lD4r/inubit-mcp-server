package de.dadecker.inubit.mcp.adapter.cli.helper;

import de.dadecker.inubit.mcp.infra.Secret;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The stdin request of the queue-entry helper (research D-4, data-model "Helper request"):
 * {@code qe=1}, one {@code key=value} line per field with the value percent-encoded (RFC 3986,
 * unreserved characters kept, UTF-8), {@code end}. The constructor refuses what the helper would
 * reject as malformed, naming the field but never its value; {@link #toString()} leaves out the
 * user and both passwords.
 *
 * @param nonce 32 lower-case hex characters, new per call ({@link #newNonce()})
 * @param variant {@code SOAP_81} or {@code REST_90}
 * @param url the {@code cli.url} of the selection
 * @param trustStore the call's trust store and its one-time password; empty for {@code http}
 * @param dataOut where the data part goes; empty if only the variables are read
 * @param variablesOut where the variables ({@code .wfsp}) go
 */
public record HelperRequest(String nonce, String variant, URI url,
    Optional<TrustStore> trustStore, boolean hostnameVerification, String objectType,
    String fileName, Optional<Path> dataOut, Path variablesOut, String user, Secret password) {

    private static final List<String> VARIANTS = List.of("SOAP_81", "REST_90");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] HEX = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] END = "end\n".getBytes(StandardCharsets.US_ASCII);

    /** A trust store file and its one-time password. */
    public record TrustStore(Path file, Secret password) {
        public TrustStore {
            Objects.requireNonNull(file, "trustStore");
            Objects.requireNonNull(password, "trustStorePassword");
            requireText("trustStore", file.toString());
            requireText("trustStorePassword", password.reveal());
        }
    }

    public HelperRequest {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(trustStore, "trustStore");
        Objects.requireNonNull(objectType, "objectType");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(dataOut, "dataOut");
        Objects.requireNonNull(variablesOut, "variablesOut");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(password, "password");
        if (!nonce.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("nonce must be 32 lower-case hex characters");
        }
        if (!VARIANTS.contains(variant)) {
            throw new IllegalArgumentException("variant must be one of " + VARIANTS);
        }
        requireText("url", url.toString());
        requireText("objectType", objectType);
        requireText("fileName", fileName);
        dataOut.ifPresent(path -> requireText("dataOut", path.toString()));
        requireText("variablesOut", variablesOut.toString());
        requireText("user", user);
        requireText("password", password.reveal());
    }

    /** A random nonce for one call: 16 bytes as 32 lower-case hex characters. */
    public static String newNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * The request as stdin bytes, a fresh buffer per call ({@code ProcessRun} zero-fills it); the
     * intermediate copies of the values are zero-filled here.
     */
    public byte[] encode() {
        List<Map.Entry<String, String>> fields = List.of(
            Map.entry("qe", "1"),
            Map.entry("nonce", nonce),
            Map.entry("variant", variant),
            Map.entry("url", url.toString()),
            Map.entry("trustStore", trustStore.map(store -> store.file().toString()).orElse("")),
            Map.entry("trustStorePassword",
                trustStore.map(store -> store.password().reveal()).orElse("")),
            Map.entry("hostnameVerification", Boolean.toString(hostnameVerification)),
            Map.entry("objectType", objectType),
            Map.entry("fileName", fileName),
            Map.entry("parts", dataOut.isPresent() ? "data,variables" : "variables"),
            Map.entry("dataOut", dataOut.map(Path::toString).orElse("")),
            Map.entry("variablesOut", variablesOut.toString()),
            Map.entry("user", user),
            Map.entry("password", password.reveal()));
        byte[][] values = new byte[fields.size()][];
        try {
            int size = END.length;
            for (int i = 0; i < values.length; i++) {
                values[i] = utf8(fields.get(i).getKey(), fields.get(i).getValue());
                size += fields.get(i).getKey().length() + 2 + encodedLength(values[i]);
            }
            byte[] request = new byte[size];
            int at = 0;
            for (int i = 0; i < values.length; i++) {
                at = put(request, at, (fields.get(i).getKey() + "=")
                    .getBytes(StandardCharsets.US_ASCII));
                at = percentEncode(values[i], request, at);
                request[at++] = '\n';
            }
            put(request, at, END);
            return request;
        } finally {
            for (byte[] value : values) {
                if (value != null) {
                    Arrays.fill(value, (byte) 0);
                }
            }
        }
    }

    @Override
    public String toString() {
        return "HelperRequest[nonce=" + nonce + ", variant=" + variant + ", url=" + url
            + ", trustStore=" + trustStore.map(store -> store.file().toString()).orElse("")
            + ", hostnameVerification=" + hostnameVerification + ", objectType=" + objectType
            + ", fileName=" + fileName + ", dataOut=" + dataOut.map(Path::toString).orElse("")
            + ", variablesOut=" + variablesOut + "]";
    }

    /**
     * Refuses an empty value and one that is not valid Unicode (the helper accepts only valid
     * UTF-8, and an unpaired surrogate would be sent changed); the message names only the key.
     */
    private static void requireText(String key, String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
        Arrays.fill(utf8(key, value), (byte) 0);
    }

    /** The UTF-8 bytes of {@code value}; refuses one that is not valid Unicode, naming the key. */
    private static byte[] utf8(String key, String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            if (encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(key + " is not valid Unicode");
        }
    }

    private static int encodedLength(byte[] value) {
        int length = 0;
        for (byte b : value) {
            length += unreserved(b) ? 1 : 3;
        }
        return length;
    }

    private static int percentEncode(byte[] value, byte[] target, int at) {
        int next = at;
        for (byte b : value) {
            if (unreserved(b)) {
                target[next++] = b;
            } else {
                target[next++] = '%';
                target[next++] = HEX[(b >> 4) & 0xF];
                target[next++] = HEX[b & 0xF];
            }
        }
        return next;
    }

    private static int put(byte[] target, int at, byte[] bytes) {
        System.arraycopy(bytes, 0, target, at, bytes.length);
        return at + bytes.length;
    }

    private static boolean unreserved(byte b) {
        return b >= 'A' && b <= 'Z' || b >= 'a' && b <= 'z' || b >= '0' && b <= '9'
            || b == '-' || b == '.' || b == '_' || b == '~';
    }
}
