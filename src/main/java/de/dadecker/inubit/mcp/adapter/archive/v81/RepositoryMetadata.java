package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.Locale;
import java.util.Map;

/**
 * The metadata of a repository file that the node does not hold yet (feature 008, research D-7).
 *
 * <p>INUBIT stores the content type that is sent and derives none from the extension (without
 * one a file becomes {@code application/octet-stream}, probe P-1). A new file therefore gets
 * {@code <Property name type="RepositoryFile" path contentType><Description/></Property>}, the
 * form probe P-1 sent, with the content type from a fixed table of extensions.
 * {@code anonymousRead} is not synthesized, so INUBIT's own import default applies.
 * {@link RepositoryArchive#build} adds {@code contentSize} and {@code contentMD5}.
 */
public final class RepositoryMetadata {

    /** Extension (lower case) → content type, final after probe P-1 (spike section 11). */
    private static final Map<String, String> CONTENT_TYPES = Map.of(
        ".xsd", "application/x-xsd+xml",
        ".xsl", "application/xslt+xml",
        ".xslt", "application/xslt+xml",
        ".wsdl", "application/wsdl+xml",
        ".xml", "application/xml");
    private static final String SUPPORTED = ".xsd, .xsl, .xslt, .wsdl, .xml";

    private RepositoryMetadata() {
    }

    /**
     * The synthesized metadata of the new repository file {@code path}.
     *
     * @param path the full repository path, e.g. {@code /Root/jdoe/xsd/msg.xsd}
     * @throws ToolErrorException {@code INVALID_INPUT} if the extension is not in the table
     */
    public static String forNewFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String contentType = dot < 0 ? null
            : CONTENT_TYPES.get(name.substring(dot).toLowerCase(Locale.ROOT));
        if (contentType == null) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "The new repository file " + path + " has no known content type; supported"
                    + " extensions for new files are " + SUPPORTED,
                "INUBIT derives no content type from the extension, so this server creates only"
                    + " files whose type it knows",
                "Create the file in the Workbench and export it first, or use one of the"
                    + " extensions " + SUPPORTED));
        }
        return "<Property name=\"" + escaped(name) + "\" type=\"RepositoryFile\" path=\""
            + escaped(path) + "\" contentType=\"" + contentType + "\"><Description/></Property>";
    }

    private static String escaped(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }
}
