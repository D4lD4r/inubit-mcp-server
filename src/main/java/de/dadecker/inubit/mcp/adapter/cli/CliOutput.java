package de.dadecker.inubit.mcp.adapter.cli;

import java.util.List;
import java.util.Objects;

/**
 * StartCLI output after preamble stripping and log separation ({@link CliOutputClassifier}).
 *
 * @param outputLines the command's own output (summary block, CSV, {@code n-OK} lines), without
 *     preamble, log records and blank lines
 * @param logLines the log records ({@code ERROR hh:mm:ss,SSS [...]}) with their exception and
 *     {@code \tat …} lines
 * @param markers the INUBIT markers {@code @Start@<code>@@@<text>@End@}, in order, without
 *     duplicates
 * @param messages the {@code <n>-OK: …} / {@code <n>-NOK: …} result lines
 */
public record CliOutput(List<String> outputLines, List<String> logLines, List<Marker> markers,
    List<Message> messages) {

    public CliOutput {
        outputLines = List.copyOf(outputLines);
        logLines = List.copyOf(logLines);
        markers = List.copyOf(markers);
        messages = List.copyOf(messages);
    }

    /** The texts of the {@code n-OK} messages. */
    public List<String> okMessages() {
        return messages.stream().filter(Message::ok).map(Message::text).toList();
    }

    /** The texts of the {@code n-NOK} messages. */
    public List<String> nokMessages() {
        return messages.stream().filter(m -> !m.ok()).map(Message::text).toList();
    }

    /** A locale-independent INUBIT marker; {@code text} may be empty. */
    public record Marker(String code, String text) {
        public Marker {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(text, "text");
        }
    }

    /** One {@code <index>-OK: <text>} or {@code <index>-NOK: <text>} line. */
    public record Message(int index, boolean ok, String text) {
        public Message {
            Objects.requireNonNull(text, "text");
        }
    }
}
