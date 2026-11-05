package de.dadecker.inubit.mcp.adapter.archive.v81;

import static de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveReader.unexpected;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The entries of a ZIP as they are stored, for writing them again unchanged (feature 015 research
 * R-4): the export of a diagram group and its nested {@code Repository.zip}.
 *
 * <p>Keeps the entries in archive order with their local headers (name, time, extra field,
 * method) and inflated bytes, plus the archive comment of the end-of-central-directory record,
 * which {@link ZipInputStream} does not expose. Entry comments live in the central directory only
 * and are not kept. Reading applies the rules of {@link ArchiveReader}: entry names must stay
 * inside the archive ({@link ArchiveReader#checkName}), no entry twice, and at most
 * {@link ArchiveReader#MAX_BYTES} inflated bytes per entry and in total. Every violation is
 * {@code UNEXPECTED_RESPONSE}; messages name entries, never their content.
 */
final class RawZip {

    private static final String ARCHIVE = "ZIP archive";
    private static final byte[] END_OF_CENTRAL_DIRECTORY = {'P', 'K', 5, 6};
    private static final int END_RECORD_LENGTH = 22;

    private final List<RawEntry> entries;
    private final Optional<String> comment;

    private RawZip(List<RawEntry> entries, Optional<String> comment) {
        this.entries = List.copyOf(entries);
        this.comment = comment;
    }

    /**
     * One entry: a copy of its local header and its inflated bytes (empty for a directory).
     *
     * @param header the header as read; {@link #write} writes a copy of it
     * @param bytes  the inflated content
     */
    record RawEntry(ZipEntry header, byte[] bytes) {
        RawEntry {
            header = new ZipEntry(Objects.requireNonNull(header, "header"));
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public ZipEntry header() {
            return new ZipEntry(header);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        /** The name and size; never the content. */
        @Override
        public String toString() {
            return "RawEntry[" + header.getName() + ", " + bytes.length + " bytes]";
        }
    }

    /**
     * The entries and the archive comment of {@code zip}.
     *
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} for an entry name that leaves the
     *     archive, a duplicate entry, more than {@link ArchiveReader#MAX_BYTES} inflated bytes in
     *     an entry or in total, or bytes that are no readable ZIP archive
     */
    static RawZip read(byte[] zip) {
        return read(zip, ArchiveReader.MAX_BYTES);
    }

    /** {@link #read} with the bound {@code maxBytes} per entry and in total (tests). */
    static RawZip read(byte[] zip, long maxBytes) {
        List<RawEntry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                String name = entry.getName();
                ArchiveReader.checkName(name, ARCHIVE);
                if (!names.add(name)) {
                    throw unexpected("The " + ARCHIVE + " has the entry " + name + " twice");
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    total += n;
                    if (bytes.size() + n > maxBytes || total > maxBytes) {
                        throw unexpected("The entry " + name + " of the " + ARCHIVE
                            + " is too large (more than " + maxBytes
                            + " bytes in the entry or the whole archive)");
                    }
                    bytes.write(buffer, 0, n);
                }
                // the header is complete (sizes, CRC) only after its data was read
                entries.add(new RawEntry(entry, bytes.toByteArray()));
            }
        } catch (IOException e) {
            throw unexpected("The " + ARCHIVE + " is not a readable ZIP archive ("
                + e.getClass().getSimpleName() + ")");
        }
        return new RawZip(entries, comment(zip));
    }

    /** The entries in archive order. */
    List<RawEntry> entries() {
        return entries;
    }

    /** The entry named {@code name}, if the archive has one. */
    Optional<RawEntry> entry(String name) {
        return entries.stream().filter(e -> e.header.getName().equals(name)).findFirst();
    }

    /** The archive comment; empty if there is none. */
    Optional<String> comment() {
        return comment;
    }

    /**
     * A ZIP of {@code entries} in their order with copies of their headers and their bytes, and
     * {@code comment} as archive comment. Deflated entries are compressed again, so the compressed
     * form may differ; names, times, extra fields, methods and inflated bytes stay the same.
     */
    static byte[] write(List<RawEntry> entries, Optional<String> comment) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (RawEntry entry : entries) {
                ZipEntry header = entry.header();
                if (header.getMethod() == ZipEntry.DEFLATED) {
                    header.setCompressedSize(-1);
                }
                out.putNextEntry(header);
                out.write(entry.bytes);
                out.closeEntry();
            }
            out.setComment(comment.orElse(null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** The comment of the end-of-central-directory record, searched from the end. */
    private static Optional<String> comment(byte[] zip) {
        int lowest = Math.max(0, zip.length - END_RECORD_LENGTH - 0xFFFF);
        for (int at = zip.length - END_RECORD_LENGTH; at >= lowest; at--) {
            int length = u16(zip, at + 20);
            if (Arrays.equals(zip, at, at + 4, END_OF_CENTRAL_DIRECTORY, 0, 4)
                && at + END_RECORD_LENGTH + length == zip.length) {
                return length == 0 ? Optional.empty() : Optional.of(new String(zip,
                    at + END_RECORD_LENGTH, length, StandardCharsets.UTF_8));
            }
        }
        throw unexpected("The " + ARCHIVE
            + " is not a readable ZIP archive (no end-of-central-directory record)");
    }

    private static int u16(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8;
    }
}
