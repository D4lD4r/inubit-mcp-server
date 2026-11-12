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
 * The frames the client helper wrote for one call (research D-4, data-model "Helper
 * frames"): only stdout lines that start with {@code #QE1:<nonce> } of that call count, every
 * other line (vendor output, a frame of another nonce) is ignored; tokens are percent-decoded and
 * {@code -} is an empty token. Parsing stops at the first unknown frame type, malformed frame or
 * frame after {@code end} ({@link #problem()}); {@link #ended()} is false when no {@code end}
 * frame came, which the classifier judges against the exit code (a failed login or a missing
 * vendor API ends without one). No ignored line and no part of a problem frame is kept. Feature
 * 024 (research D-2) adds the frames of the operations {@code clusterNodes}, {@code nodeConfig}
 * and {@code ioLog}; whether a frame fits its operation is the classifier's judgement.
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

    /**
     * The part a {@code part} frame is about: a queue entry's part, the server log or an I/O
     * logging message.
     */
    public enum Part {
        DATA,
        VARIABLES,
        LOG,
        MESSAGE
    }

    /** One frame. */
    public sealed interface Frame permits Start, LoginOk, LoginError, NodeOk, NodeError, PartOk,
        PartError, ApiMissing, End, Member, MembersOk, MembersError, Detail, DetailsOk,
        DetailsError, DetailsUnknown, ConfigOk, ConfigAbsent, ConfigError, IoListOk, IoListError,
        IoMatch {
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

    /** {@code node ok <nodeName>}: the name of the node the server log comes from (D-5). */
    public record NodeOk(String name) implements Frame {
        public NodeOk {
            Objects.requireNonNull(name, "name");
        }
    }

    /** {@code node error <exceptionChain> <message>}, as {@link LoginError}. */
    public record NodeError(List<String> exceptionChain, String message) implements Frame {
        public NodeError {
            exceptionChain = List.copyOf(exceptionChain);
            Objects.requireNonNull(message, "message");
        }
    }

    /** {@code part <data|variables|log|message> ok <bytes>}: the part was written to its file. */
    public record PartOk(Part part, long bytes) implements Frame {
        public PartOk {
            Objects.requireNonNull(part, "part");
        }
    }

    /**
     * {@code part <data|variables|log|message> error <exceptionChain> <message>}, as
     * {@link LoginError}.
     */
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

    /**
     * {@code member <name> <status> <version> <zone> <nodeId> <uptimeMs>}: one element of the
     * cluster's node list; a text is empty for {@code -}, the uptime absent for {@code -}.
     */
    public record Member(String name, String status, String version, String zone, String nodeId,
        Optional<Long> uptimeMs) implements Frame {
        public Member {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(zone, "zone");
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(uptimeMs, "uptimeMs");
        }
    }

    /** {@code members ok <count>}: the node list had {@code count} elements in all. */
    public record MembersOk(int count) implements Frame {
    }

    /** {@code members error <exceptionChain> <message>}, as {@link LoginError}. */
    public record MembersError(List<String> exceptionChain, String message) implements Frame {
        public MembersError {
            exceptionChain = List.copyOf(exceptionChain);
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * {@code detail <key> <value> [cut]}: one node-information entry; {@code cut} when the helper
     * cut the key or the value. Key and value are empty for {@code -}.
     */
    public record Detail(String key, String value, boolean cut) implements Frame {
        public Detail {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * {@code details ok <count> <true|false>}: {@code count} entries were written; truncated when
     * the entry cap or the byte budget stopped them.
     */
    public record DetailsOk(int count, boolean truncated) implements Frame {
    }

    /** {@code details error <exceptionChain> <message>}, as {@link LoginError}. */
    public record DetailsError(List<String> exceptionChain, String message) implements Frame {
        public DetailsError {
            exceptionChain = List.copyOf(exceptionChain);
            Objects.requireNonNull(message, "message");
        }
    }

    /** {@code details unknown}: the requested node is not a listed member; nothing was asked. */
    public record DetailsUnknown() implements Frame {
    }

    /**
     * {@code config <name> ok <type> <true|false> <true|false> <displayName> <category>
     * <description> <value>}: one node configuration property; texts are empty for {@code -}.
     */
    public record ConfigOk(String name, String type, boolean editable, boolean deprecated,
        String displayName, String category, String description, String value) implements Frame {
        public ConfigOk {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(value, "value");
        }
    }

    /** {@code config <name> absent}: the node has no property of that name. */
    public record ConfigAbsent(String name) implements Frame {
        public ConfigAbsent {
            Objects.requireNonNull(name, "name");
        }
    }

    /** {@code config <name> error <exceptionChain> <message>}, as {@link LoginError}. */
    public record ConfigError(String name, List<String> exceptionChain, String message)
        implements Frame {
        public ConfigError {
            Objects.requireNonNull(name, "name");
            exceptionChain = List.copyOf(exceptionChain);
            Objects.requireNonNull(message, "message");
        }
    }

    /** {@code io list ok <count>}: the I/O logging listing of {@code count} entries was written. */
    public record IoListOk(int count) implements Frame {
    }

    /** {@code io list error <exceptionChain> <message>}, as {@link LoginError}. */
    public record IoListError(List<String> exceptionChain, String message) implements Frame {
        public IoListError {
            exceptionChain = List.copyOf(exceptionChain);
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * {@code io match <entry>} or {@code io match none} (empty): the listed entry whose reference
     * is the requested one. An entry is never empty and never {@code none} (it holds
     * {@code @@@}).
     */
    public record IoMatch(Optional<String> entry) implements Frame {
        public IoMatch {
            Objects.requireNonNull(entry, "entry");
        }
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
            case "node" -> Optional.of(node(rest));
            case "part" -> Optional.of(part(rest));
            case "api" -> Optional.of(apiMissing(rest));
            case "end" -> Optional.of(end(rest));
            case "member" -> Optional.of(member(rest));
            case "members" -> Optional.of(members(rest));
            case "detail" -> Optional.of(detail(rest));
            case "details" -> Optional.of(details(rest));
            case "config" -> Optional.of(config(rest));
            case "io" -> Optional.of(io(rest));
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

    private static Frame node(List<String> tokens) {
        if (tokens.size() == 2 && tokens.get(0).equals("ok")) {
            return new NodeOk(only(tokens, 2).get(1));
        }
        if (tokens.size() == 3 && tokens.get(0).equals("error")) {
            List<String> error = only(tokens, 3);
            return new NodeError(chain(error.get(1)), error.get(2));
        }
        throw new IllegalArgumentException("malformed node frame");
    }

    private static Frame part(List<String> tokens) {
        Part part = switch (tokens.isEmpty() ? "" : tokens.get(0)) {
            case "data" -> Part.DATA;
            case "variables" -> Part.VARIABLES;
            case "log" -> Part.LOG;
            case "message" -> Part.MESSAGE;
            default -> throw new IllegalArgumentException("unknown part");
        };
        String outcome = tokens.size() < 2 ? "" : tokens.get(1);
        if (outcome.equals("ok") && tokens.size() == 3) {
            return new PartOk(part, number(tokens.get(2)));
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

    private static Frame member(List<String> tokens) {
        List<String> member = only(tokens, 6);
        String uptime = tokens.get(5);
        Optional<Long> uptimeMs = uptime.equals(EMPTY_TOKEN) ? Optional.empty()
            : Optional.of(number(uptime));
        return new Member(member.get(0), member.get(1), member.get(2), member.get(3),
            member.get(4), uptimeMs);
    }

    private static Frame members(List<String> tokens) {
        if (tokens.size() == 2 && tokens.get(0).equals("ok")) {
            return new MembersOk(count(tokens.get(1)));
        }
        if (tokens.size() == 3 && tokens.get(0).equals("error")) {
            List<String> error = only(tokens, 3);
            return new MembersError(chain(error.get(1)), error.get(2));
        }
        throw new IllegalArgumentException("malformed members frame");
    }

    private static Frame detail(List<String> tokens) {
        if (tokens.size() == 2) {
            List<String> detail = only(tokens, 2);
            return new Detail(detail.get(0), detail.get(1), false);
        }
        if (tokens.size() == 3 && tokens.get(2).equals("cut")) {
            List<String> detail = only(tokens.subList(0, 2), 2);
            return new Detail(detail.get(0), detail.get(1), true);
        }
        throw new IllegalArgumentException("malformed detail frame");
    }

    private static Frame details(List<String> tokens) {
        if (tokens.size() == 3 && tokens.get(0).equals("ok")) {
            return new DetailsOk(count(tokens.get(1)), flag(tokens.get(2)));
        }
        if (tokens.size() == 3 && tokens.get(0).equals("error")) {
            List<String> error = only(tokens, 3);
            return new DetailsError(chain(error.get(1)), error.get(2));
        }
        if (tokens.equals(List.of("unknown"))) {
            return new DetailsUnknown();
        }
        throw new IllegalArgumentException("malformed details frame");
    }

    private static Frame config(List<String> tokens) {
        String outcome = tokens.size() < 2 ? "" : tokens.get(1);
        if (outcome.equals("ok") && tokens.size() == 9) {
            List<String> config = only(tokens, 9);
            return new ConfigOk(config.get(0), config.get(2), flag(tokens.get(3)),
                flag(tokens.get(4)), config.get(5), config.get(6), config.get(7), config.get(8));
        }
        if (outcome.equals("absent") && tokens.size() == 2) {
            return new ConfigAbsent(only(tokens, 2).get(0));
        }
        if (outcome.equals("error") && tokens.size() == 4) {
            List<String> error = only(tokens, 4);
            return new ConfigError(error.get(0), chain(error.get(2)), error.get(3));
        }
        throw new IllegalArgumentException("malformed config frame");
    }

    private static Frame io(List<String> tokens) {
        String subtype = tokens.isEmpty() ? "" : tokens.get(0);
        if (subtype.equals("list") && tokens.size() == 3 && tokens.get(1).equals("ok")) {
            return new IoListOk(count(tokens.get(2)));
        }
        if (subtype.equals("list") && tokens.size() == 4 && tokens.get(1).equals("error")) {
            List<String> error = only(tokens, 4);
            return new IoListError(chain(error.get(2)), error.get(3));
        }
        if (subtype.equals("match") && tokens.size() == 2) {
            if (tokens.get(1).equals("none")) {
                return new IoMatch(Optional.empty());
            }
            String entry = only(tokens, 2).get(1);
            if (entry.isEmpty()) {
                throw new IllegalArgumentException("empty matched entry");
            }
            return new IoMatch(Optional.of(entry));
        }
        throw new IllegalArgumentException("malformed io frame");
    }

    /** A non-negative count token (not {@code -}) that fits an {@code int}. */
    private static int count(String token) {
        if (!token.matches("[0-9]{1,9}")) {
            throw new IllegalArgumentException("malformed count");
        }
        return Integer.parseInt(token);
    }

    /** A non-negative number token (not {@code -}) that fits a {@code long}, as {@code part}. */
    private static long number(String token) {
        if (!token.matches("[0-9]{1,18}")) {
            throw new IllegalArgumentException("malformed number");
        }
        return Long.parseLong(token);
    }

    /** A {@code true} or {@code false} token (not {@code -}). */
    private static boolean flag(String token) {
        return switch (token) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("malformed flag");
        };
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
