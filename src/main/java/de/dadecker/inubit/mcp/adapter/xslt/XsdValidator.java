package de.dadecker.inubit.mcp.adapter.xslt;

import de.dadecker.inubit.mcp.domain.model.CheckFinding;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Check;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Severity;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.transform.TransformerException;
import javax.xml.transform.sax.SAXSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

/**
 * Well-formedness and XSD 1.0 validation of workspace documents with the JDK's
 * {@code javax.xml.validation} (research D-12, FR-035).
 *
 * <ul>
 *   <li>{@code XML_NOT_WELL_FORMED} (ERROR, check {@code XML}) with {@code line:column}; a
 *       document type declaration counts as such, because DTDs and entities are never read;
 *   <li>{@code XSD_INVALID} (ERROR, check {@code XSD}): one finding per position of a violation
 *       (the messages of one position joined), or one for a schema that cannot be loaded;
 *   <li>secure processing on, no external access by the parser itself: includes and imports of
 *       a schema resolve inside the workspace only ({@link WorkspaceUriResolver}, also
 *       {@code inubitrepository:}).
 * </ul>
 */
public final class XsdValidator {

    private final Path root;

    /** @param root the workspace root */
    public XsdValidator(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /**
     * The findings of {@code xml}, and with {@code xsd} of its validation (both
     * workspace-relative).
     */
    public List<CheckFinding> validate(Path xml, Optional<Path> xsd) {
        Path document = workspaceFile(xml);
        String path = relative(document);
        List<CheckFinding> findings = new ArrayList<>();
        Collector wellFormed = new Collector();
        try {
            XMLReader reader = WorkspaceUriResolver.secureReader();
            reader.setErrorHandler(wellFormed);
            reader.parse(new InputSource(document.toUri().toString()));
        } catch (SAXException | IOException | TransformerException e) {
            wellFormed.add(e);
        }
        if (!wellFormed.errors.isEmpty()) {
            Map.Entry<String, String> first = wellFormed.errors.entrySet().iterator().next();
            findings.add(new CheckFinding(Severity.ERROR, Check.XML, path,
                Optional.ofNullable(first.getKey()), "XML_NOT_WELL_FORMED",
                message(first.getValue())));
            return findings;
        }
        if (xsd.isEmpty()) {
            return findings;
        }
        Path schemaFile = workspaceFile(xsd.get());
        String schemaPath = relative(schemaFile);
        Optional<WorkspacePath> artifact = artifact(schemaPath);
        WorkspaceUriResolver resolver = new WorkspaceUriResolver(root,
            artifact.map(WorkspacePath::group).orElse(null),
            artifact.map(WorkspacePath::owner).orElse(null));
        Collector schemaErrors = new Collector();
        Schema schema = null;
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setResourceResolver(resources(resolver));
            factory.setErrorHandler(schemaErrors);
            schema = factory.newSchema(new SAXSource(WorkspaceUriResolver.secureReader(),
                new InputSource(schemaFile.toUri().toString())));
        } catch (SAXException | TransformerException e) {
            schemaErrors.add(e);
        }
        if (schema == null || !schemaErrors.errors.isEmpty()) {
            findings.add(new CheckFinding(Severity.ERROR, Check.XSD, schemaPath, Optional.empty(),
                "XSD_INVALID", "the schema cannot be loaded: " + message(String.join("; ",
                    schemaErrors.errors.values()))));
            return findings;
        }
        Collector violations = new Collector();
        try {
            Validator validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.setResourceResolver(resources(resolver));
            validator.setErrorHandler(violations);
            validator.validate(new SAXSource(WorkspaceUriResolver.secureReader(),
                new InputSource(document.toUri().toString())));
        } catch (SAXException | IOException | TransformerException e) {
            violations.add(e);
        }
        violations.errors.forEach((location, message) -> findings.add(new CheckFinding(
            Severity.ERROR, Check.XSD, path, Optional.ofNullable(location), "XSD_INVALID",
            message(message))));
        return findings;
    }

    /** Includes and imports of a schema: workspace files only, else the parser refuses them. */
    private static LSResourceResolver resources(WorkspaceUriResolver resolver) {
        return (type, namespace, publicId, systemId, baseUri) -> {
            if (systemId == null) {
                return null;
            }
            try {
                Path file = resolver.file(systemId, baseUri);
                return new FileInput(file, publicId);
            } catch (TransformerException e) {
                return null; // the parser may not access it itself (ACCESS_EXTERNAL_SCHEMA)
            }
        };
    }

    /** No DTD or external entity text in a message; at most one line. */
    private static String message(String text) {
        String message = text.strip();
        if (message.contains("DOCTYPE")) {
            return "the document has a document type declaration; DTDs and entities are not"
                + " read locally";
        }
        return message;
    }

    private Path workspaceFile(Path path) {
        Path file = root.resolve(path).normalize();
        try {
            if (path.isAbsolute() || !file.startsWith(root) || !Files.isRegularFile(file)
                || !file.toRealPath().startsWith(root.toRealPath())) {
                throw invalid(path);
            }
        } catch (IOException e) {
            throw invalid(path);
        }
        return file;
    }

    private static ToolErrorException invalid(Path path) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, "The document "
            + path + " is not a file of the workspace", "The path is missing, absolute, or"
            + " leaves the workspace", "Give a workspace-relative path of an existing file"));
    }

    private Optional<WorkspacePath> artifact(String relative) {
        try {
            return Optional.of(WorkspacePath.parse(Path.of(relative)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** Errors by {@code line:column} (null without a position), messages of one place joined. */
    private static final class Collector implements ErrorHandler {

        private final Map<String, String> errors = new LinkedHashMap<>();

        @Override
        public void warning(SAXParseException exception) {
            // warnings are no findings
        }

        @Override
        public void error(SAXParseException exception) {
            add(exception);
        }

        @Override
        public void fatalError(SAXParseException exception) {
            add(exception);
        }

        void add(Exception exception) {
            String location = exception instanceof SAXParseException parse
                && parse.getLineNumber() > 0 ? parse.getLineNumber() + ":"
                    + Math.max(parse.getColumnNumber(), 0) : null;
            String message = String.valueOf(exception.getMessage());
            errors.merge(location, message, (a, b) -> a.equals(b) || a.contains(b) ? a
                : a + " " + b);
        }
    }

    /** A workspace file as schema resource. */
    private record FileInput(Path file, String publicId) implements LSInput {

        @Override
        public InputStream getByteStream() {
            try {
                return Files.newInputStream(file);
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public String getSystemId() {
            return file.toUri().toString();
        }

        @Override
        public String getPublicId() {
            return publicId;
        }

        @Override
        public Reader getCharacterStream() {
            return null;
        }

        @Override
        public String getStringData() {
            return null;
        }

        @Override
        public String getBaseURI() {
            return null;
        }

        @Override
        public String getEncoding() {
            return null;
        }

        @Override
        public boolean getCertifiedText() {
            return false;
        }

        @Override
        public void setCharacterStream(Reader characterStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setByteStream(InputStream byteStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setStringData(String stringData) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setSystemId(String systemId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setPublicId(String publicId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setBaseURI(String baseUri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setEncoding(String encoding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setCertifiedText(boolean certifiedText) {
            throw new UnsupportedOperationException();
        }
    }
}
