package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.Masked;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SecretRules;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Parses the 8.1 {@code GET /ibis/rest/license} answer into {@link LicenseFacts} (feature 022,
 * US1). The vendor documentation shows only the root {@code <license>}; the child element names
 * below are reconstructed (fixture {@code admin-license-reconstructed.xml}) until the recording of
 * T014 confirms them.
 *
 * <ul>
 *   <li>{@code expiryDate} ← {@value #EXPIRY_DATE}, an ISO date; required
 *   <li>{@code licensedThreads} ← {@value #LICENSED_THREADS}, a whole number ≥ 1; optional
 *   <li>{@code licensee} ← {@value #LICENSEE}; always {@link Masked#MASK_TOKEN} when present
 *       (research D-19), so the name never leaves the parser
 * </ul>
 *
 * Only direct children of the root are read; every other element is ignored. A read element that
 * {@link SecretRules} flags (by its name, its {@code type} or {@code encrypted} attribute, or its
 * value) is masked: its value is not reported. Without a readable expiry date the answer is
 * {@code UNEXPECTED_RESPONSE}, which the caller reports as the unavailable part
 * {@code license}. No error echoes the content.
 */
final class LicenseXmlParser {

    // The element and attribute names, in one place until T014 records the real document.
    static final String ROOT = "license";
    static final String EXPIRY_DATE = "expiryDate";
    static final String LICENSED_THREADS = "licensedThreads";
    static final String LICENSEE = "licensee";
    private static final String TYPE = "type";
    private static final String ENCRYPTED = "encrypted";

    private LicenseXmlParser() {
    }

    /**
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} (with the server id) if the body is
     *     not a {@code <license>} document, has no readable expiry date or licensed threads that
     *     are not a whole number ≥ 1
     */
    static LicenseFacts parse(NodeId server, byte[] body) {
        Document document;
        try {
            document = XmlSupport.parse(body);
        } catch (ToolErrorException e) {
            throw new ToolErrorException(e.error().withNode(server));
        }
        Element root = document.getDocumentElement();
        if (!XmlSupport.localName(root).equals(ROOT)) {
            throw unexpected(server, "is not a <" + ROOT + "> document");
        }
        LocalDate expiry = readable(root, EXPIRY_DATE).flatMap(LicenseXmlParser::date)
            .orElseThrow(() -> unexpected(server, "has no readable expiry date"));
        Optional<Long> threads = readable(root, LICENSED_THREADS)
            .map(text -> threads(server, text));
        Optional<String> licensee = XmlSupport.firstChild(root, LICENSEE)
            .filter(element -> !XmlSupport.text(element).isEmpty())
            .map(element -> Masked.MASK_TOKEN);
        return new LicenseFacts(Optional.of(expiry), threads, licensee);
    }

    /** The text of the first child {@code name}, unless it is empty or masked by SecretRules. */
    private static Optional<String> readable(Element root, String name) {
        return XmlSupport.firstChild(root, name)
            .filter(element -> !SecretRules.masks(name, XmlSupport.attribute(element, TYPE),
                encrypted(element), XmlSupport.text(element)))
            .map(XmlSupport::text)
            .filter(text -> !text.isEmpty());
    }

    private static boolean encrypted(Element element) {
        return XmlSupport.attribute(element, ENCRYPTED)
            .map(flag -> flag.strip().toLowerCase(Locale.ROOT).equals("true")).orElse(false);
    }

    private static Optional<LocalDate> date(String text) {
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static long threads(NodeId server, String text) {
        try {
            long threads = Long.parseLong(text);
            if (threads >= 1) {
                return threads;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw unexpected(server, "has licensed threads that are not a whole number of at least 1");
    }

    private static ToolErrorException unexpected(NodeId server, String problem) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
            "The INUBIT license of " + server + " " + problem,
            "The endpoint answered with an unexpected document (another INUBIT version, a proxy"
                + " page, or a license format this version does not know)",
            "Check the license with 'license -td' on the server and the INUBIT version line")
            .withNode(server));
    }
}
