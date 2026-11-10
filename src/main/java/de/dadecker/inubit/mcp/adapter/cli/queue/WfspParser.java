package de.dadecker.inubit.mcp.adapter.cli.queue;

import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent.PartResult;
import de.dadecker.inubit.mcp.domain.model.RawVariable;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads INUBIT's variables file ({@code .wfsp}) of a Queue Manager entry (research D-13): the
 * root {@code Properties} (any attributes, e.g. {@code version}) holds one {@code Property} per
 * variable with a required, non-empty {@code name}, an optional {@code type} and its text
 * (character and CDATA content, XML-unescaped, whitespace kept) as value; an empty element is an
 * empty value. Variables keep the file order; other attributes and comments are ignored.
 *
 * <p>Parsing streams with the secure StAX reader of {@link XmlSupport} (a DTD or entity reference
 * is refused, nothing external is resolved) and stops at the first element that does not fit, so
 * the work is linear in the input. Any other structure and any malformed input give
 * {@link PartResult.Failed} with the fixed {@link #UNEXPECTED_FORMAT} reason; the parser's own
 * message is never used, as it may quote content.
 */
public final class WfspParser {

    static final String UNEXPECTED_FORMAT =
        "unexpected format; the unsupported INUBIT interface may have changed";

    private static final String ROOT = "Properties";
    private static final String PROPERTY = "Property";

    private WfspParser() {
    }

    /** The variables of {@code wfsp}, or the fixed failure; never throws for any content. */
    public static PartResult<List<RawVariable>> parse(byte[] wfsp) {
        Objects.requireNonNull(wfsp, "wfsp");
        try {
            XMLStreamReader reader = XmlSupport.newStreamReader(new ByteArrayInputStream(wfsp));
            try {
                return new PartResult.Read<>(List.copyOf(variables(reader)));
            } finally {
                reader.close();
            }
        } catch (XMLStreamException | RuntimeException e) {
            // the StAX contract does not rule out unchecked exceptions on hostile input; none
            // may escape, as its message could quote content
            return new PartResult.Failed<>(UNEXPECTED_FORMAT);
        }
    }

    private static List<RawVariable> variables(XMLStreamReader reader) throws XMLStreamException {
        List<RawVariable> variables = new ArrayList<>();
        int depth = 0;
        String name = null;
        Optional<String> type = Optional.empty();
        StringBuilder value = new StringBuilder();
        while (reader.hasNext()) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    String element = reader.getLocalName();
                    if (depth == 0 && ROOT.equals(element)) {
                        // the root; StAX itself refuses a missing or a second root element
                    } else if (depth == 1 && PROPERTY.equals(element)) {
                        name = reader.getAttributeValue(null, "name");
                        if (name == null || name.isEmpty()) {
                            throw unexpected();
                        }
                        type = Optional.ofNullable(reader.getAttributeValue(null, "type"));
                        value.setLength(0);
                    } else {
                        throw unexpected();
                    }
                    depth++;
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    if (depth == 2) {
                        variables.add(new RawVariable(name, type, value.toString()));
                    }
                    depth--;
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA,
                     XMLStreamConstants.SPACE -> {
                    if (depth == 2) {
                        value.append(reader.getText());
                    } else if (!reader.isWhiteSpace()) {
                        throw unexpected();
                    }
                }
                default -> {
                    // start/end of document, comments and processing instructions
                }
            }
        }
        return variables;
    }

    private static XMLStreamException unexpected() {
        return new XMLStreamException("unexpected structure");
    }
}
