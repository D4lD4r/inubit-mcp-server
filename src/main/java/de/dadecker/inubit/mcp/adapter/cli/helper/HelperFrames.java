package de.dadecker.inubit.mcp.adapter.cli.helper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The frames the queue-entry helper wrote for one call (research D-4, data-model "Helper
 * frames"): only stdout lines that start with {@code #QE1:<nonce> } of that call count, every
 * other line (vendor output, a frame of another nonce) is ignored; tokens are percent-decoded and
 * {@code -} is an empty token. Parsing stops at the first unknown frame type, malformed frame or
 * frame after {@code end} ({@link #problem()}); {@link #ended()} is false when no {@code end}
 * frame came, which the classifier judges against the exit code (a failed login or a missing
 * vendor API ends without one). No ignored line and no part of a problem frame is kept.
 *
 * @param frames the frames up to the first problem, in order
 * @param problem the first problem, if any
 */
public record HelperFrames(List<Frame> frames, Optional<Problem> problem) {

    private static final String EMPTY_TOKEN = "-";

    /** Why the frames cannot be trusted; carries no text of the frame. */
    public enum Problem {
        UNKNOWN_FRAME_TYPE,
        MALFORMED_FRAME,
        FRAME_AFTER_END
    }

    /** The part a {@code part} frame is about. */
    public enum Part {
        DATA,
        VARIABLES
    }

    /** One frame. */
    public sealed interface Frame permits Start, LoginOk, LoginError, PartOk, PartError,
        ApiMissing, End {
    }

    /** {@code start <variant>}: the request was read and the vendor classes were found. */
    public record Start(String variant) implements Frame {
        public Start {
            Objects.requireNonNull(variant, "variant");
        }
    }

    /** {@code login ok}. */
    public record LoginOk() implements Frame {
    }

    /**
     * {@code login error <exceptionChain> <message>}.
     *
     * @param exceptionChain the exception classes, outermost first; empty for {@code -}
     * @param message the messages, one line per exception of the chain; empty for {@code -}
     */
    public record LoginError(List<String> exceptionChain, String message) implements Frame {
        public LoginError {
            exceptionChain = List.copyOf(exceptionChain);
            Objects.requireNonNull(message, "message");
        }
    }

    /** {@code part <data|variables> ok <bytes>}: the part was written to its file. */
    public record PartOk(Part part, long bytes) implements Frame {
        public PartOk {
            Objects.requireNonNull(part, "part");
        }
    }

    /** {@code part <data|variables> error <exceptionChain> <message>}, as {@link LoginError}. */
    public record PartError(Part part, List<String> exceptionChain, String message)
        implements Frame {
        public PartError {
            Objects.requireNonNull(part, "part");
            exceptionChain = List.copyOf(exceptionChain);
            Objects.requireNonNull(message, "message");
        }
    }

    /** {@code api missing <what>}: a vendor class or method was not found. */
    public record ApiMissing(String what) implements Frame {
        public ApiMissing {
            Objects.requireNonNull(what, "what");
        }
    }

    /** {@code end}: the helper finished normally. */
    public record End() implements Frame {
    }

    public HelperFrames {
        frames = List.copyOf(frames);
        Objects.requireNonNull(problem, "problem");
    }

    /** The frames of {@code nonce} in the helper's {@code stdout}. */
    public static HelperFrames parse(String nonce, String stdout) {
        String head = "#QE1:" + nonce;
        List<Frame> frames = new ArrayList<>();
        boolean ended = false;
        for (String line : stdout.split("\n", -1)) {
            if (!line.equals(head) && !line.startsWith(head + " ")) {
                continue;
            }
            if (ended) {
                return new HelperFrames(frames, Optional.of(Problem.FRAME_AFTER_END));
            }
            List<String> tokens = Arrays.asList(line.substring(head.length()).split(" ", -1));
            Optional<Frame> frame;
            try {
                frame = frame(tokens.subList(1, tokens.size()));
            } catch (IllegalArgumentException e) {
                return new HelperFrames(frames, Optional.of(Problem.MALFORMED_FRAME));
            }
            if (frame.isEmpty()) {
                return new HelperFrames(frames, Optional.of(Problem.UNKNOWN_FRAME_TYPE));
            }
            frames.add(frame.get());
            ended = frame.get() instanceof End;
        }
        return new HelperFrames(frames, Optional.empty());
    }

    /** True if an {@code end} frame came. */
    public boolean ended() {
        return !frames.isEmpty() && frames.get(frames.size() - 1) instanceof End;
    }

    /**
     * The frame of {@code tokens} (still encoded); empty for an unknown type, a malformed frame
     * throws {@link IllegalArgumentException}.
     */
    private static Optional<Frame> frame(List<String> tokens) {
        String type = tokens.isEmpty() ? "" : tokens.get(0);
        List<String> rest = tokens.isEmpty() ? List.of() : tokens.subList(1, tokens.size());
        return switch (type) {
            case "start" -> Optional.of(new Start(only(rest, 1).get(0)));
            case "login" -> Optional.of(login(rest));
            case "part" -> Optional.of(part(rest));
            case "api" -> Optional.of(apiMissing(rest));
            case "end" -> Optional.of(end(rest));
            case "" -> throw new IllegalArgumentException("frame without type");
            default -> Optional.empty();
        };
    }

    private static Frame login(List<String> tokens) {
        if (tokens.equals(List.of("ok"))) {
            return new LoginOk();
        }
        if (tokens.size() == 3 && tokens.get(0).equals("error")) {
            List<String> error = only(tokens, 3);
            return new LoginError(chain(error.get(1)), error.get(2));
        }
        throw new IllegalArgumentException("malformed login frame");
    }

    private static Frame part(List<String> tokens) {
        Part part = switch (tokens.isEmpty() ? "" : tokens.get(0)) {
            case "data" -> Part.DATA;
            case "variables" -> Part.VARIABLES;
            default -> throw new IllegalArgumentException("unknown part");
        };
        String outcome = tokens.size() < 2 ? "" : tokens.get(1);
        if (outcome.equals("ok") && tokens.size() == 3 && tokens.get(2).matches("[0-9]{1,18}")) {
            return new PartOk(part, Long.parseLong(tokens.get(2)));
        }
        if (outcome.equals("error") && tokens.size() == 4) {
            List<String> error = only(tokens, 4);
            return new PartError(part, chain(error.get(2)), error.get(3));
        }
        throw new IllegalArgumentException("malformed part frame");
    }

    private static Frame apiMissing(List<String> tokens) {
        if (tokens.size() == 2 && tokens.get(0).equals("missing")) {
            return new ApiMissing(only(tokens, 2).get(1));
        }
        throw new IllegalArgumentException("malformed api frame");
    }

    private static Frame end(List<String> tokens) {
        if (!tokens.isEmpty()) {
            throw new IllegalArgumentException("malformed end frame");
        }
        return new End();
    }

    /** The decoded {@code tokens}, which must be exactly {@code count}. */
    private static List<String> only(List<String> tokens, int count) {
        if (tokens.size() != count) {
            throw new IllegalArgumentException("wrong token count");
        }
        return tokens.stream().map(HelperFrames::decode).toList();
    }

    /** The classes of a decoded exception chain {@code class1>class2>…}; empty for none. */
    private static List<String> chain(String decoded) {
        if (decoded.isEmpty()) {
            return List.of();
        }
        List<String> classes = Arrays.asList(decoded.split(">", -1));
        if (classes.contains("")) {
            throw new IllegalArgumentException("empty class in exception chain");
        }
        return classes;
    }

    /**
     * Strict RFC 3986 percent-decoding of one token into UTF-8 text; {@code -} is the empty token.
     */
    private static String decode(String token) {
        if (token.isEmpty()) {
            throw new IllegalArgumentException("empty token");
        }
        if (token.equals(EMPTY_TOKEN)) {
            return "";
        }
        byte[] bytes = new byte[token.length()];
        int length = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '%') {
                int high = i + 2 < token.length() ? hex(token.charAt(i + 1)) : -1;
                int low = high < 0 ? -1 : hex(token.charAt(i + 2));
                if (low < 0) {
                    throw new IllegalArgumentException("invalid escape");
                }
                bytes[length++] = (byte) (high << 4 | low);
                i += 2;
            } else if (unreserved(c)) {
                bytes[length++] = (byte) c;
            } else {
                throw new IllegalArgumentException("character not percent-encoded");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("token not UTF-8");
        }
    }

    private static boolean unreserved(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
            || c == '-' || c == '.' || c == '_' || c == '~';
    }

    /** The value of an ASCII hex digit, -1 for any other character. */
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
