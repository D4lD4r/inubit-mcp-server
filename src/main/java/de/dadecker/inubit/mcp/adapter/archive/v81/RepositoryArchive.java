package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Repository files in the archives of INUBIT 8.1 (feature 005, research D-1).
 *
 * <ul>
 *   <li>A repository export ({@code export --exportRepositoryPath}) and the {@code Repository.zip}
 *       of a diagram group export hold, per file, {@code Root/<owner>/<path>/<name>.xml} (the
 *       metadata, one {@code <Property type="RepositoryFile" path="…">}) and {@code <name>.dat}
 *       (the content); {@link #read} turns them into {@link RepositoryFile}s.
 *   <li>A repository import ({@code import --importRepositoryPath '/Root/<owner>'}) takes its
 *       entries <em>relative to the import path</em>: an entry in the export shape would be
 *       stored below {@code /Root/<owner>/Root/<owner>/…}. {@link #build} therefore writes
 *       {@code <path>/<name>.xml} and {@code .dat} relative to {@code /Root/<owner>}, with a
 *       directory entry before the first file of each folder (the probed shape), sorted by path;
 *       {@link #readRelative} reads such an archive back.
 *   <li>Every file must lie below {@code /Root/<owner>/} (segments of letters, digits,
 *       {@code _ . -} and spaces, no {@code .} or {@code ..}), its metadata must name the same
 *       path, and no path may occur twice ({@code INVALID_INPUT}).
 *   <li>Key material and certificates ({@link #isKeyMaterial}) are never part of an import
 *       archive ({@code PRECONDITION_FAILED}, FR-015a): the target keeps its own.
 *   <li>The metadata keeps its exported form except {@code contentSize} and {@code contentMD5}
 *       (of the content written) and {@code tagName} (dropped).
 *   <li>Reading inflates at most {@link #MAX_ENTRY_BYTES} per entry and 128 MiB in all.
 * </ul>
 *
 * <p>INUBIT ignores the archive's {@code uuid} on create and its {@code versionComment}; the
 * metadata is passed as exported (it carries the {@code Description} INUBIT takes).
 */
public final class RepositoryArchive {

    private static final String METADATA = ".xml";
    private static final String CONTENT = ".dat";
    private static final Pattern SEGMENT =
        Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$");

    /** Upper bound of one inflated entry (the bound of the StartCLI exports). */
    public static final long MAX_ENTRY_BYTES = 64L << 20;
    /** Upper bound of all inflated entries of one archive. */
    static final long MAX_TOTAL_BYTES = 128L << 20;
    /** File names of certificates and certificate containers (FR-015a). */
    private static final Pattern CERTIFICATE_NAME =
        Pattern.compile("(?i).*\\.(cer|crt|der|pem|p7b|p7c|spc)$");
    private static final Pattern ATTRIBUTE_VALUE = Pattern.compile("=\\s*(\"[^\"]*\"|'[^']*')");

    private RepositoryArchive() {
    }

    /**
     * True if the repository file holds key material or a certificate, which is never deployed
     * (FR-015a; the target keeps its own): a key or keystore ({@link KeyMaterial}), a file name
     * ending in {@code .cer}, {@code .crt}, {@code .der}, {@code .pem}, {@code .p7b},
     * {@code .p7c} or {@code .spc}, PEM text with {@code BEGIN CERTIFICATE},
     * {@code BEGIN PKCS7} or {@code BEGIN PGP PRIVATE KEY BLOCK}, a DER X.509 certificate or a
     * DER private key ({@link #isDerPrivateKey}).
     */
    public static boolean isKeyMaterial(String path, byte[] content) {
        if (KeyMaterial.hasKeyName(path) || KeyMaterial.isKeyMaterial(content)
            || (path != null && CERTIFICATE_NAME.matcher(path.strip()).matches())) {
            return true;
        }
        String text = new String(content, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (text.contains("-----BEGIN CERTIFICATE") || text.contains("-----BEGIN TRUSTED"
            + " CERTIFICATE") || text.contains("-----BEGIN PKCS7")
            || text.contains("-----BEGIN PGP PRIVATE KEY BLOCK")) {
            return true;
        }
        return isDerCertificate(content) || isDerPrivateKey(content);
    }

    /**
     * A DER private key (stage 2 review m1), by the shape of its outer {@code SEQUENCE}, which
     * must span the whole content: PKCS#8 {@code PrivateKeyInfo} (INTEGER, SEQUENCE starting
     * with an OID, OCTET STRING, …), PKCS#8 {@code EncryptedPrivateKeyInfo} (SEQUENCE starting
     * with an OID, OCTET STRING), PKCS#1 {@code RSAPrivateKey} (at least nine INTEGERs) or SEC1
     * {@code ECPrivateKey} (INTEGER 1, OCTET STRING, optional {@code [0]}/{@code [1]}).
     */
    static boolean isDerPrivateKey(byte[] content) {
        List<int[]> children = derChildren(content, 0, content.length, true);
        if (children == null || children.isEmpty()) {
            return false;
        }
        int[] first = children.get(0);
        if (children.size() >= 3 && first[0] == 0x02 && children.get(1)[0] == 0x30
            && children.get(2)[0] == 0x04 && startsWithOid(content, children.get(1))) {
            return true; // PKCS#8
        }
        if (children.size() == 2 && first[0] == 0x30 && children.get(1)[0] == 0x04
            && startsWithOid(content, first)) {
            return true; // EncryptedPrivateKeyInfo
        }
        if (children.size() >= 9 && children.stream().limit(9).allMatch(c -> c[0] == 0x02)) {
            return true; // PKCS#1
        }
        return children.size() >= 2 && children.size() <= 4 && first[0] == 0x02
            && first[2] == 1 && content[first[1]] == 1 && children.get(1)[0] == 0x04
            && children.stream().skip(2).allMatch(c -> c[0] == 0xa0 || c[0] == 0xa1); // SEC1
    }

    private static boolean startsWithOid(byte[] content, int[] sequence) {
        List<int[]> inner = derChildren(content, sequence[1], sequence[1] + sequence[2], false);
        return inner != null && !inner.isEmpty() && inner.get(0)[0] == 0x06;
    }

    /**
     * The children {@code {tag, content offset, content length}} of the SEQUENCE at
     * {@code from} (if {@code outer}, it must span exactly {@code [from, to)}), or the elements
     * between {@code from} and {@code to}; {@code null} if the bytes are no such DER.
     */
    private static List<int[]> derChildren(byte[] der, int from, int to, boolean outer) {
        int start = from;
        int end = to;
        if (outer) {
            int[] sequence = element(der, from, to);
            if (sequence == null || sequence[0] != 0x30 || sequence[1] + sequence[2] != to) {
                return null;
            }
            start = sequence[1];
            end = to;
        }
        List<int[]> children = new ArrayList<>();
        int at = start;
        while (at < end) {
            int[] child = element(der, at, end);
            if (child == null) {
                return null;
            }
            children.add(child);
            at = child[1] + child[2];
        }
        return children;
    }

    /** {@code {tag, content offset, content length}} of the element at {@code at}, or null. */
    private static int[] element(byte[] der, int at, int limit) {
        if (at + 2 > limit) {
            return null;
        }
        int tag = der[at] & 0xff;
        int length = der[at + 1] & 0xff;
        int offset = at + 2;
        if (length > 0x80) {
            int bytes = length & 0x7f;
            if (bytes > 3 || offset + bytes > limit) {
                return null;
            }
            length = 0;
            for (int i = 0; i < bytes; i++) {
                length = (length << 8) | (der[offset + i] & 0xff);
            }
            offset += bytes;
        } else if (length == 0x80) {
            return null;
        }
        return offset + length <= limit ? new int[] {tag, offset, length} : null;
    }

    private static boolean isDerCertificate(byte[] content) {
        if (content.length < 2 || content[0] != 0x30) {
            return false;
        }
        try {
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(content));
            return true;
        } catch (java.security.cert.CertificateException | RuntimeException e) {
            return false;
        }
    }

    /**
     * One repository file.
     *
     * @param path     absolute repository path, {@code /Root/<owner>/…/<name>}
     * @param metadata the {@code <name>.xml} entry as exported
     * @param content  the {@code <name>.dat} entry
     */
    public record RepositoryFile(String path, byte[] metadata, byte[] content) {
        public RepositoryFile {
            Objects.requireNonNull(path, "path");
            metadata = Objects.requireNonNull(metadata, "metadata").clone();
            content = Objects.requireNonNull(content, "content").clone();
        }

        @Override
        public byte[] metadata() {
            return metadata.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        /** The path and sizes; never the content. */
        @Override
        public String toString() {
            return "RepositoryFile[" + path + ", " + content.length + " bytes]";
        }
    }

    /**
     * The files of a repository export or of a diagram group's {@code Repository.zip}, sorted by
     * path; directory entries are skipped.
     *
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} for an entry outside {@code Root/},
     *     a metadata entry without content or the other way round, or an unreadable archive
     */
    public static List<RepositoryFile> read(byte[] zip) {
        return read(zip, MAX_ENTRY_BYTES);
    }

    /** {@link #read} with the bound {@code maxEntryBytes} per inflated entry (tests). */
    static List<RepositoryFile> read(byte[] zip, long maxEntryBytes) {
        return pairs(zip, name -> {
            if (!name.startsWith("Root/")) {
                throw unexpected("The repository archive has an entry outside Root/");
            }
            return "/" + name;
        }, maxEntryBytes);
    }

    /**
     * The files of an import archive built by {@link #build} for {@code owner}, sorted by path.
     *
     * @throws ToolErrorException as {@link #read}
     */
    public static List<RepositoryFile> readRelative(String owner, byte[] zip) {
        return pairs(zip, name -> "/Root/" + owner + "/" + name, MAX_ENTRY_BYTES);
    }

    /**
     * The archive of a repository import into {@code /Root/<owner>}; see the class description.
     *
     * @throws ToolErrorException {@code INVALID_INPUT} for no file, a path outside the owner's
     *     root, a duplicate or metadata of another path; {@code PRECONDITION_FAILED} for key
     *     material
     */
    public static byte[] build(String owner, List<RepositoryFile> files) {
        if (files.isEmpty()) {
            throw invalid("The repository import has no file");
        }
        String root = "/Root/" + owner + "/";
        Map<String, RepositoryFile> byRelative = new TreeMap<>();
        for (RepositoryFile file : files) {
            String relative = relative(root, file.path());
            checkMetadata(file);
            if (isKeyMaterial(file.path(), file.content)) {
                throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                    "The repository file " + file.path() + " holds key material (a key,"
                        + " keystore or certificate); key material is never imported",
                    "Keys and certificates are stage-specific and stay on the target",
                    "Exclude the file from the deployment and maintain it on the target"));
            }
            if (byRelative.put(relative, file) != null) {
                throw invalid("The repository file " + file.path() + " occurs twice");
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            Set<String> directories = new HashSet<>();
            for (Map.Entry<String, RepositoryFile> entry : byRelative.entrySet()) {
                String relative = entry.getKey();
                for (int at = relative.indexOf('/'); at >= 0; at = relative.indexOf('/', at + 1)) {
                    String directory = relative.substring(0, at + 1);
                    if (directories.add(directory)) {
                        out.putNextEntry(new ZipEntry(directory));
                        out.closeEntry();
                    }
                }
                write(out, relative + METADATA, importMetadata(entry.getValue()));
                write(out, relative + CONTENT, entry.getValue().content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static String relative(String root, String path) {
        if (!path.startsWith(root)) {
            throw invalid("The repository file " + path + " is not below " + root);
        }
        String relative = path.substring(root.length());
        for (String segment : relative.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches() || segment.equals(".")
                || segment.equals("..")) {
                throw invalid("The repository path " + path + " has a segment that cannot be"
                    + " imported");
            }
        }
        return relative;
    }

    private static void checkMetadata(RepositoryFile file) {
        Element property;
        try {
            property = XmlTree.parse(file.metadata).root();
        } catch (RuntimeException e) {
            throw invalid("The metadata of the repository file " + file.path()
                + " is not readable");
        }
        if (!property.localName().equals("Property")
            || !property.attribute("type").orElse("").equals("RepositoryFile")
            || !property.attribute("path").orElse("").equals(file.path())) {
            throw invalid("The metadata of the repository file " + file.path()
                + " does not describe that file");
        }
    }

    /**
     * The metadata as imported (stage 1 review #5): {@code contentSize} and {@code contentMD5} of
     * the content actually written, no {@code tagName} of the source; everything else (the
     * {@code uuid} included, which INUBIT ignores on create) byte for byte as exported.
     */
    private static byte[] importMetadata(RepositoryFile file) {
        String xml = new String(file.metadata, java.nio.charset.StandardCharsets.UTF_8);
        int start = xml.indexOf("<Property");
        int end = startTagEnd(xml, start);
        String tag = xml.substring(start, end);
        boolean empty = tag.endsWith("/");
        String body = empty ? tag.substring(0, tag.length() - 1) : tag;
        body = withAttribute(body, "tagName", null);
        body = withAttribute(body, "contentSize", Integer.toString(file.content.length));
        body = withAttribute(body, "contentMD5", md5(file.content));
        return (xml.substring(0, start) + body + (empty ? "/" : "") + xml.substring(end))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The index of the {@code >} that ends the start tag at {@code start} (outside quotes). */
    private static int startTagEnd(String xml, int start) {
        char quote = 0;
        for (int i = start; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        throw invalid("The metadata of a repository file is not readable");
    }

    /** {@code tag} with attribute {@code name} set to {@code value}, or removed if null. */
    private static String withAttribute(String tag, String name, String value) {
        java.util.regex.Matcher matcher = Pattern.compile("\\s" + name
            + ATTRIBUTE_VALUE.pattern()).matcher(tag);
        String replacement = value == null ? "" : " " + name + "=\"" + value + "\"";
        if (matcher.find()) {
            return tag.substring(0, matcher.start()) + replacement + tag.substring(matcher.end());
        }
        return value == null ? tag : tag + replacement;
    }

    private static String md5(byte[] content) {
        try {
            return String.format("%032x", new java.math.BigInteger(1,
                java.security.MessageDigest.getInstance("MD5").digest(content)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(ZipOutputStream out, String name, byte[] data) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(data);
        out.closeEntry();
    }

    private interface PathOf {
        String path(String entryBase);
    }

    private static List<RepositoryFile> pairs(byte[] zip, PathOf pathOf, long maxEntryBytes) {
        Map<String, byte[]> metadata = new TreeMap<>();
        Map<String, byte[]> content = new TreeMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] bytes = bounded(in, maxEntryBytes);
                total += bytes.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw unexpected("The repository archive is too large (more than "
                        + MAX_TOTAL_BYTES + " bytes inflated)");
                }
                if (name.endsWith(METADATA)) {
                    metadata.put(pathOf.path(base(name, METADATA)), bytes);
                } else if (name.endsWith(CONTENT)) {
                    content.put(pathOf.path(base(name, CONTENT)), bytes);
                } else {
                    throw unexpected("The repository archive has an entry that is neither"
                        + " metadata nor content");
                }
            }
        } catch (IOException e) {
            throw unexpected("The repository archive is not a readable ZIP archive ("
                + e.getClass().getSimpleName() + ")");
        }
        if (!metadata.keySet().equals(content.keySet())) {
            throw unexpected("The repository archive has metadata without content or content"
                + " without metadata");
        }
        List<RepositoryFile> files = new ArrayList<>();
        metadata.forEach((path, xml) -> files.add(new RepositoryFile(path, xml,
            content.get(path))));
        files.sort(Comparator.comparing(RepositoryFile::path));
        return List.copyOf(files);
    }

    /** The rest of the current entry, at most {@code maxBytes} (stage 1 review #4). */
    private static byte[] bounded(ZipInputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            total += n;
            if (total > maxBytes) {
                throw unexpected("An entry of the repository archive is too large (more than "
                    + maxBytes + " bytes)");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String base(String name, String suffix) {
        return name.substring(0, name.length() - suffix.length());
    }

    private static ToolErrorException invalid(String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message,
            "Repository files are imported below /Root/<owner> only, once each, with their own"
                + " metadata",
            "Check the repository paths of the release"));
    }

    private static ToolErrorException unexpected(String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE, message,
            "The export format behaved differently than recorded for INUBIT 8.1",
            "Check the INUBIT client version (cliHome) and retry; see the MCP server's log"));
    }
}
