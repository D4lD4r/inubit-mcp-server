package de.dadecker.inubit.mcp.helper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature 020 (research D-1, D-2, D-4): reads one queue manager entry through the INUBIT client
 * installation whose class path it runs on. The server starts it in a child JVM; it is compiled
 * for Java 17, uses JDK classes only and is a single class without nested or anonymous classes,
 * because it is shipped as one class-file resource.
 *
 * <p>Protocol: the request arrives on stdin (UTF-8, {@code qe=1} first, one {@code key=value} per
 * line with every value percent-encoded, {@code end} last); results are the stdout lines that
 * start with {@code #QE1:<nonce> }, followed by space-separated percent-encoded tokens, so output
 * of the vendor libraries cannot forge one. Exit codes: 0 after {@code end}, 1 anything else, 2
 * malformed request, 3 login failed, 4 vendor API missing. The request, the passwords and the
 * content are never printed.
 */
public final class QueueEntryHelper {

    static final int EXIT_FAILED = 1;
    static final int EXIT_MALFORMED = 2;

    private static final String END = "end";
    private static final String FRAME_PREFIX = "#QE1:";
    private static final List<String> KEYS = List.of("qe", "nonce", "variant", "url",
        "trustStore", "trustStorePassword", "hostnameVerification", "objectType", "fileName",
        "parts", "dataOut", "variablesOut", "user", "password");
    private static final List<String> NOT_EMPTY = List.of("url", "objectType", "fileName",
        "variablesOut", "user", "password");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private QueueEntryHelper() {
    }

    public static void main(String[] args) {
        System.exit(run(System.in, System.out, ClassLoader.getSystemClassLoader()));
    }

    /**
     * Reads the request from {@code in}, writes frames to {@code out} and resolves the vendor
     * classes through {@code vendor}; returns the exit code. Without the vendor flows every
     * well-formed request ends with exit 1.
     */
    static int run(InputStream in, PrintStream out, ClassLoader vendor) {
        try {
            readRequest(in);
        } catch (IllegalArgumentException e) {
            return EXIT_MALFORMED;
        } catch (IOException e) {
            return EXIT_FAILED;
        }
        return EXIT_FAILED;
    }

    /** The decoded request; malformed input throws {@link IllegalArgumentException}. */
    private static Map<String, String> readRequest(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(in, StandardCharsets.UTF_8));
        if (!"qe=1".equals(reader.readLine())) {
            throw new IllegalArgumentException("not a version 1 request");
        }
        Map<String, String> request = new HashMap<>();
        request.put("qe", "1");
        for (String line = reader.readLine(); !END.equals(line); line = reader.readLine()) {
            if (line == null) {
                throw new IllegalArgumentException("request without end");
            }
            int equals = line.indexOf('=');
            String key = equals < 0 ? "" : line.substring(0, equals);
            if (!KEYS.contains(key) || request.containsKey(key)) {
                throw new IllegalArgumentException("unknown or repeated key");
            }
            request.put(key, decode(line.substring(equals + 1)));
        }
        validate(request);
        return request;
    }

    private static void validate(Map<String, String> request) {
        if (request.size() != KEYS.size()) {
            throw new IllegalArgumentException("key missing");
        }
        for (String key : NOT_EMPTY) {
            if (request.get(key).isEmpty()) {
                throw new IllegalArgumentException("empty value");
            }
        }
        String parts = request.get("parts");
        if (!request.get("nonce").matches("[0-9a-fA-F]{32}")
            || !List.of("SOAP_81", "REST_90").contains(request.get("variant"))
            || !List.of("true", "false").contains(request.get("hostnameVerification"))
            || !List.of("data,variables", "variables").contains(parts)
            || parts.startsWith("data") && request.get("dataOut").isEmpty()) {
            throw new IllegalArgumentException("invalid value");
        }
    }

    /** Writes the frame {@code #QE1:<nonce> <token> …}, every token percent-encoded. */
    static void frame(PrintStream out, String nonce, String... tokens) {
        StringBuilder line = new StringBuilder(FRAME_PREFIX).append(nonce);
        for (String token : tokens) {
            line.append(' ').append(encode(token));
        }
        out.print(line.append('\n'));
        out.flush();
    }

    private static String encode(String token) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : token.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (unreserved(c)) {
                encoded.append(c);
            } else {
                encoded.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
        }
        return encoded.toString();
    }

    /** RFC 3986 percent-decoding; any character outside the unreserved set must be escaped. */
    private static String decode(String value) {
        byte[] bytes = new byte[value.length()];
        int length = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                int high = i + 2 < value.length() ? hex(value.charAt(i + 1)) : -1;
                int low = high < 0 ? -1 : hex(value.charAt(i + 2));
                if (low < 0) {
                    throw new IllegalArgumentException("invalid escape");
                }
                bytes[length++] = (byte) (high << 4 | low);
                i += 2;
            } else if (unreserved(c)) {
                bytes[length++] = (byte) c;
            } else {
                throw new IllegalArgumentException("value not percent-encoded");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("value not UTF-8");
        }
    }

    private static boolean unreserved(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
            || c == '-' || c == '.' || c == '_' || c == '~';
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return -1;
    }
}
