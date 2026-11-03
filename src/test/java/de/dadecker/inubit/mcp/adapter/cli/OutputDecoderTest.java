package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Feature 013 (T010, FR-006, research R-6): how StartCLI's stdout and stderr bytes become text.
 * On Windows each line is decoded strictly as UTF-8 (the client's output, forced by
 * {@code JAVA_TOOL_OPTIONS}), else in the console OEM code page (a line the interpreter or the
 * batch file prints itself), and CRLF becomes LF; on macOS and Linux decoding is today's
 * {@code toString(UTF_8)}, byte for byte (FR-017). Pure: the OEM charset is passed explicitly, so
 * every case runs on every platform.
 */
class OutputDecoderTest {

    private static final Charset CP850 = Charset.forName("IBM850");
    private static final Charset CP1252 = Charset.forName("windows-1252");

    /** A client line with characters outside ASCII (UTF-8 on every platform). */
    private static final String CLIENT_LINE = "Module: Prüfung_Bestellungen, Größe 3 µs";
    /** An interpreter message with umlauts, as {@code cmd.exe} prints it in the OEM code page. */
    private static final String INTERPRETER_LINE =
        "Die Syntax für den Dateinamen ist falsch: Datenträgerbezeichnung";

    private static byte[] bytes(Object... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object part : parts) {
            if (part instanceof byte[] raw) {
                out.writeBytes(raw);
            } else {
                out.writeBytes(((String) part).getBytes(StandardCharsets.UTF_8));
            }
        }
        return out.toByteArray();
    }

    /** Today's decoding in {@code CliRunner} before feature 013. */
    private static String today(byte[] bytes) {
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        kept.writeBytes(bytes);
        return kept.toString(StandardCharsets.UTF_8);
    }

    // --- POSIX: exactly today --------------------------------------------------------------------

    @Test
    void posixDecodesExactlyAsToday() {
        List<byte[]> inputs = List.of(
            new byte[0],
            bytes(CLIENT_LINE, "\n", "second\n"),
            bytes("crlf\r\n", "lone\rcr\n", "no terminator"),
            bytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "after bom\n"),
            bytes("ok\n", INTERPRETER_LINE.getBytes(CP850), "\n", new byte[] {(byte) 0xC3}),
            bytes(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, "surrogate\n"));
        for (byte[] input : inputs) {
            assertThat(OutputDecoder.posix().decode(input)).isEqualTo(today(input));
        }
    }

    @Test
    void posixKeepsCarriageReturnsBomAndReplacesMalformedBytes() {
        byte[] input = bytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "a\r\nb\rc\n",
            new byte[] {(byte) 0x81}, "\n");

        assertThat(OutputDecoder.posix().decode(input)).isEqualTo("﻿a\r\nb\rc\n�\n");
    }

    // --- Windows: per line strict UTF-8, else OEM ------------------------------------------------

    @Test
    void windowsDecodesUtf8LinesAsUtf8WhateverTheOemCodePage() {
        byte[] input = bytes(CLIENT_LINE, "\r\n", "Ende\r\n");

        for (Charset oem : List.of(CP850, CP1252, StandardCharsets.UTF_8)) {
            assertThat(OutputDecoder.windows(oem).decode(input))
                .isEqualTo(CLIENT_LINE + "\nEnde\n");
        }
    }

    @Test
    void windowsDecodesALineThatIsNotUtf8InCodePage850() {
        byte[] input = bytes(INTERPRETER_LINE.getBytes(CP850), "\r\n");

        assertThat(OutputDecoder.windows(CP850).decode(input)).isEqualTo(INTERPRETER_LINE + "\n");
    }

    @Test
    void windowsDecodesALineThatIsNotUtf8InCodePage1252() {
        byte[] input = bytes(INTERPRETER_LINE.getBytes(CP1252), "\r\n");

        assertThat(OutputDecoder.windows(CP1252).decode(input)).isEqualTo(INTERPRETER_LINE + "\n");
    }

    @Test
    void windowsWithCodePage65001ReplacesMalformedBytesAsPosixDoes() {
        byte[] input = bytes("a\r\n", new byte[] {(byte) 0x81}, "b\r\n");

        assertThat(OutputDecoder.windows(StandardCharsets.UTF_8).decode(input))
            .isEqualTo("a\n�b\n");
    }

    @Test
    void windowsDecodesEachLineOfAMixedBufferOnItsOwn() {
        byte[] input = bytes(CLIENT_LINE, "\r\n", INTERPRETER_LINE.getBytes(CP850), "\r\n",
            "Größe: 3\r\n");

        assertThat(OutputDecoder.windows(CP850).decode(input))
            .isEqualTo(CLIENT_LINE + "\n" + INTERPRETER_LINE + "\nGröße: 3\n");
    }

    @Test
    void windowsNormalisesCrlfButKeepsALoneCarriageReturnAsPosixDoes() {
        byte[] input = bytes("one\r\n", "two\n", "three\rsame line\r\n", "\r\n", "last\r");

        assertThat(OutputDecoder.windows(CP850).decode(input))
            .isEqualTo("one\ntwo\nthree\rsame line\n\nlast\r");
    }

    @Test
    void windowsKeepsAFinalLineWithoutTerminatorAndAnEmptyBuffer() {
        assertThat(OutputDecoder.windows(CP850).decode(bytes("a\r\nb"))).isEqualTo("a\nb");
        assertThat(OutputDecoder.windows(CP850).decode(new byte[0])).isEmpty();
    }

    @Test
    void windowsDropsAUtf8ByteOrderMarkAtTheStartOnly() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] input = bytes(bom, CLIENT_LINE, "\r\n", "x", bom, "\r\n");

        assertThat(OutputDecoder.windows(CP850).decode(input))
            .isEqualTo(CLIENT_LINE + "\nx﻿\n");
    }

    @Test
    void windowsDropsTheByteOrderMarkAlsoBeforeAnOemLine() {
        byte[] input = bytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
            INTERPRETER_LINE.getBytes(CP850), "\r\n");

        assertThat(OutputDecoder.windows(CP850).decode(input)).isEqualTo(INTERPRETER_LINE + "\n");
    }

    @Test
    void windowsTreatsEncodedSurrogatesAsNotUtf8() {
        // CESU-style surrogate bytes are malformed in strict UTF-8; in 1252 each is a character
        byte[] surrogate = {(byte) 0xED, (byte) 0xA0, (byte) 0x80};

        assertThat(OutputDecoder.windows(CP1252).decode(bytes(surrogate, "\r\n")))
            .isEqualTo(new String(surrogate, CP1252) + "\n");
    }
}
