package de.dadecker.inubit.mcp.domain.model;

import java.util.List;

/** A known binary format, recognised by its magic bytes at offset 0 (feature 020, FR-009, D-10). */
public enum BinarySignature {
    ZIP(magic(0x50, 0x4B, 0x03, 0x04), magic(0x50, 0x4B, 0x05, 0x06),
        magic(0x50, 0x4B, 0x07, 0x08)),
    GZIP(magic(0x1F, 0x8B)),
    PDF(magic(0x25, 0x50, 0x44, 0x46, 0x2D)),
    PNG(magic(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
    JPEG(magic(0xFF, 0xD8, 0xFF)),
    GIF(magic('G', 'I', 'F', '8', '7', 'a'), magic('G', 'I', 'F', '8', '9', 'a')),
    JAVA_SERIALIZED(magic(0xAC, 0xED, 0x00, 0x05));

    private final List<byte[]> magics;

    BinarySignature(byte[]... magics) {
        this.magics = List.of(magics);
    }

    /** True if {@code bytes} starts with one of this format's magic byte sequences. */
    public boolean matches(byte[] bytes) {
        for (byte[] magic : magics) {
            if (startsWith(bytes, magic)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] bytes, byte[] magic) {
        if (bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] magic(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }
}
