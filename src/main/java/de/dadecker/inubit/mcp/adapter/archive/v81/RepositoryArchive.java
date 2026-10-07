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
 *   <li>Key material (a key file name or keystore/private key content, {@link KeyMaterial}) is
 *       never part of an import archive ({@code PRECONDITION_FAILED}): the target keeps its own
 *       keys.
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

    private RepositoryArchive() {
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
        return pairs(zip, name -> {
            if (!name.startsWith("Root/")) {
                throw unexpected("The repository archive has an entry outside Root/");
            }
            return "/" + name;
        });
    }

    /**
     * The files of an import archive built by {@link #build} for {@code owner}, sorted by path.
     *
     * @throws ToolErrorException as {@link #read}
     */
    public static List<RepositoryFile> readRelative(String owner, byte[] zip) {
        return pairs(zip, name -> "/Root/" + owner + "/" + name);
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
            if (KeyMaterial.hasKeyName(file.path()) || KeyMaterial.isKeyMaterial(file.content)) {
                throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                    "The repository file " + file.path() + " holds key material; key material"
                        + " is never imported",
                    "Keystores and private keys are stage-specific and stay on the target",
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
                write(out, relative + METADATA, entry.getValue().metadata);
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

    private static void write(ZipOutputStream out, String name, byte[] data) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(data);
        out.closeEntry();
    }

    private interface PathOf {
        String path(String entryBase);
    }

    private static List<RepositoryFile> pairs(byte[] zip, PathOf pathOf) {
        Map<String, byte[]> metadata = new TreeMap<>();
        Map<String, byte[]> content = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                if (name.endsWith(METADATA)) {
                    metadata.put(pathOf.path(base(name, METADATA)), in.readAllBytes());
                } else if (name.endsWith(CONTENT)) {
                    content.put(pathOf.path(base(name, CONTENT)), in.readAllBytes());
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
