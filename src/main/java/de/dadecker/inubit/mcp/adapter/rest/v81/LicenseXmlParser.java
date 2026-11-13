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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Parses the 8.1 {@code GET /ibis/rest/license} answer into {@link LicenseFacts} (feature 022,
 * US1). The element names are those of the recording of an 8.1.17 server (T014, fixture
 * {@code admin-license.xml}, research "Live recording T014"): below the root {@code <license>} a
 * {@code LicenseInformation} block (edition, customer, contact, ids), an {@code inubit} block
 * (allowed addresses, {@code LicenseType} with the limits, {@code Features}) and an XML-DSig
 * {@code Signature}.
 *
 * <ul>
 *   <li>{@code expiryDate} ← {@code inubit/LicenseType/ExpirationDate}, an ISO date; required
 *   <li>{@code licensedThreads} ← {@code inubit/LicenseType/ThreadCount}, a whole number ≥ 1,
 *       reported as INUBIT writes it (the recording holds {@code 999999}); optional, and left
 *       empty when it is not such a number, as it is only the fallback for an unlicensed
 *       {@code /metrics}
 *   <li>{@code licensee} ← {@code LicenseInformation/CompanyName}; always
 *       {@link Masked#MASK_TOKEN} when present (research D-19), so the name never leaves the
 *       parser
 * </ul>
 *
 * Only these three elements are read; every other element, the signature included, is ignored.
 * A read element that {@link SecretRules} flags (by its name, its {@code type} or
 * {@code encrypted} attribute, or its value) is masked: its value is not reported. Without a
 * readable expiry date the answer is {@code UNEXPECTED_RESPONSE}, which the caller reports as the
 * unavailable part {@code license}. No error echoes the content.
 */
final class LicenseXmlParser {

    static final String ROOT = "license";
    static final List<String> EXPIRY_DATE = List.of("inubit", "LicenseType", "ExpirationDate");
    static final List<String> LICENSED_THREADS = List.of("inubit", "LicenseType", "ThreadCount");
    static final List<String> LICENSEE = List.of("LicenseInformation", "CompanyName");
    private static final String TYPE = "type";
    private static final String ENCRYPTED = "encrypted";

    private LicenseXmlParser() {
    }

    /**
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} (with the server id) if the body is
     *     not a {@code <license>} document or has no readable expiry date
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
            .flatMap(LicenseXmlParser::threads);
        Optional<String> licensee = element(root, LICENSEE)
            .filter(element -> !XmlSupport.text(element).isEmpty())
            .map(element -> Masked.MASK_TOKEN);
        return new LicenseFacts(Optional.of(expiry), threads, licensee);
    }

    /** The element at {@code path} below {@code root}, the first child of each name. */
    private static Optional<Element> element(Element root, List<String> path) {
        Optional<Element> element = Optional.of(root);
        for (String name : path) {
            element = element.flatMap(parent -> XmlSupport.firstChild(parent, name));
        }
        return element;
    }

    /** The text at {@code path}, unless it is empty or masked by SecretRules. */
    private static Optional<String> readable(Element root, List<String> path) {
        return element(root, path)
            .filter(element -> !SecretRules.masks(path.getLast(),
                XmlSupport.attribute(element, TYPE), encrypted(element), XmlSupport.text(element)))
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

    private static Optional<Long> threads(String text) {
        try {
            long threads = Long.parseLong(text);
            return threads >= 1 ? Optional.of(threads) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
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
