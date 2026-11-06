package de.dadecker.inubit.mcp.domain.model;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * File names of workflow archives and the key they are compared by (research R-8, FR-004, FR-009).
 *
 * <p>{@link #fileName} derives {@code <file name>.diagram.zip} from a workflow name, in this
 * order: each of {@code < > : " / \ | ? *} and each control character ({@code U+0000–U+001F},
 * {@code U+007F}) becomes {@code _}; trailing dots and spaces are removed; an empty result becomes
 * {@code _}; a leading {@code .} becomes {@code _}; a name whose part before the first dot is a
 * Windows reserved name, in any case, gets the prefix {@code _}. A file name over 255 UTF-8
 * bytes is refused.
 *
 * <p>{@link #key} approximates Unicode case folding after NFC normalization; Java has no
 * {@code String.caseFold}, and upper- then lower-casing maps e.g. {@code ß} and {@code SS}
 * together.
 */
public final class ArchiveFileNames {

    private static final String SUFFIX = ".diagram.zip";
    private static final int MAX_BYTES = 255;
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private ArchiveFileNames() {
    }

    /**
     * The archive file name of {@code workflow}, ending in {@code .diagram.zip}.
     *
     * @throws ToolErrorException {@code INVALID_INPUT} if the file name has more than 255 bytes
     *     in UTF-8
     */
    public static String fileName(String workflow) {
        StringBuilder name = new StringBuilder(workflow.length());
        for (int i = 0; i < workflow.length(); i++) {
            char c = workflow.charAt(i);
            name.append(c < 0x20 || c == 0x7F || "<>:\"/\\|?*".indexOf(c) >= 0 ? '_' : c);
        }
        int end = name.length();
        while (end > 0 && (name.charAt(end - 1) == '.' || name.charAt(end - 1) == ' ')) {
            end--;
        }
        name.setLength(end);
        if (name.isEmpty()) {
            name.append('_');
        }
        if (name.charAt(0) == '.') {
            name.setCharAt(0, '_');
        }
        int dot = name.indexOf(".");
        String base = dot < 0 ? name.toString() : name.substring(0, dot);
        if (RESERVED.contains(base.toUpperCase(Locale.ROOT))) {
            name.insert(0, '_');
        }
        String fileName = name + SUFFIX;
        int bytes = fileName.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "The archive file name of workflow '" + workflow + "' would have " + bytes
                    + " bytes in UTF-8, more than the " + MAX_BYTES + " a file name may have",
                "The workflow name is too long for a file name",
                "Leave this workflow out of the selection or shorten its name in INUBIT"));
        }
        return fileName;
    }

    /** The key by which two file names are compared: NFC, then upper- and lower-cased. */
    public static String key(String fileName) {
        return Normalizer.normalize(fileName, Normalizer.Form.NFC)
            .toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }
}
