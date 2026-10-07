package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.SecretRedactor.Kind;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.domain.model.SecretPlaceholder;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The secret positions of an export archive and their property paths (feature 003 research D-7,
 * feature 004 research D-6): the one walk over workflow and module elements that both the
 * {@link SecretRedactor} (which writes placeholders) and {@link SecretValues} (which reads the
 * values) use, so that the path of a placeholder and the path of the value it stands for are
 * derived identically. What happens at a secret is decided by the {@link Replacer}; the walk
 * counts the kinds and the suspicious names (see {@link SecretRedactor} for the rules).
 */
final class SecretPaths {

    /** The artifact a secret belongs to: a workflow or a module, by name. */
    record Artifact(boolean workflow, String name) {
        Artifact {
            Objects.requireNonNull(name, "name");
        }
    }

    /** What to put where a secret is. */
    @FunctionalInterface
    interface Replacer {

        /**
         * @param path  the property path as a placeholder carries it (already normalized)
         * @param value the secret value (never logged)
         * @return the text to write instead of {@code value}
         */
        String replace(Artifact artifact, String path, Kind kind, String value);
    }

    static final Set<String> UNTYPED_SECRETS = Set.of("SSLKeyStoreRemoteConnector",
        "SSLKeyStorePasswordRemoteConnector", "smime.keystore.data",
        "smime.keystore.alias.password");
    private static final Set<String> SAVED_TEST_MESSAGES = Set.of("xslt.source", "xslt.target");
    private static final String SOURCE_VARIABLES = "xslt.sourceVariables";
    private static final Pattern SUSPICIOUS =
        Pattern.compile("password|secret|keystore|token", Pattern.CASE_INSENSITIVE);
    private static final Set<String> SCALAR_TYPES = Set.of("Boolean", "Integer");
    private static final Pattern UNSAFE_PATH_CHARACTERS = Pattern.compile("[{}\\p{Cntrl}]");
    /** The namespace of the variable types ({@code is:password}). */
    static final String VARIABLE_TYPES = "http://inubit.com/variables/types";

    private final Replacer replacer;
    private final Counts counts = new Counts();
    private Artifact artifact;

    SecretPaths(Replacer replacer) {
        this.replacer = Objects.requireNonNull(replacer, "replacer");
    }

    /** The {@code Workflow} element {@code element} of workflow {@code name}, walked. */
    Element workflow(String name, Element element) {
        artifact = new Artifact(true, name);
        return workflowElement(element, "", Map.of(), counts);
    }

    /** The {@code Properties} element of module {@code name}, walked. */
    Element module(String name, Element element) {
        artifact = new Artifact(false, name);
        return properties(element, "", counts);
    }

    /** The kinds counted so far. */
    Map<Kind, Integer> kinds() {
        return new EnumMap<>(counts.kinds);
    }

    /** The number of kept values with a secret-like name. */
    int suspicious() {
        return counts.suspicious;
    }

    /** The property path as a placeholder carries it. */
    static String normalize(String path) {
        return UNSAFE_PATH_CHARACTERS.matcher(path.strip()).replaceAll("_");
    }

    // --- module properties ----------------------------------------------------------------------

    /** Redacts the {@code Property} elements below {@code element}; {@code prefix} ends in "/". */
    private Element properties(Element element, String prefix, Counts counts) {
        return element.withChildren(map(element, child -> child.localName().equals("Property")
            ? property(child, prefix, false, counts) : properties(child, prefix, counts)));
    }

    private Element property(Element property, String prefix, boolean inSourceVariables,
        Counts counts) {
        String name = property.attribute("name").orElse("");
        String path = prefix + name;
        if (property.hasElements()) {
            boolean variables = inSourceVariables || name.equals(SOURCE_VARIABLES);
            return property.withChildren(map(property, child -> child.localName().equals(
                "Property") ? property(child, path + "/", variables, counts) : child));
        }
        String value = property.text();
        if (value.isBlank()) {
            return property;
        }
        String type = property.attribute("type").orElse("");
        Kind kind = inSourceVariables ? Kind.SOURCE_VARIABLE : kind(name, type,
            property.attribute("encrypted").filter("true"::equals).isPresent(), value);
        if (kind == null && isKeyMaterial(property, type, value)) {
            kind = Kind.KEY_MATERIAL;
        }
        if (kind == null) {
            if (SUSPICIOUS.matcher(name).find() && !SCALAR_TYPES.contains(type)) {
                counts.suspicious++;
            }
            return property;
        }
        counts.add(kind);
        return property.withText(replace(path, kind, value));
    }

    /** The kind of secret a property holds, or {@code null}. */
    private static Kind kind(String name, String type, boolean encrypted, String value) {
        if (type.equals("Password")) {
            return Kind.PASSWORD;
        }
        if (encrypted) {
            return Kind.ENCRYPTED;
        }
        if (type.equals("KeyStore")) {
            return Kind.KEYSTORE;
        }
        if (UNTYPED_SECRETS.contains(name)) {
            return Kind.UNTYPED_SECRET;
        }
        if (SAVED_TEST_MESSAGES.contains(name)) {
            return Kind.SAVED_TEST_MESSAGE;
        }
        boolean certificate = type.equals("X509")
            || name.toLowerCase(Locale.ROOT).contains("certificate");
        if (certificate && !isPlainCertificate(value)) {
            return Kind.PRIVATE_KEY_CERTIFICATE;
        }
        return null;
    }

    /**
     * Key material in a property that is not a secret type (review I3): an
     * {@code InternalDocument} named like a keystore or holding one, or a PEM private key.
     */
    private static boolean isKeyMaterial(Element property, String type, String value) {
        if (SecretPlaceholder.isPlaceholder(value.strip())) {
            return false;
        }
        if (type.equals("InternalDocument")) {
            if (KeyMaterial.hasKeyName(property.attribute("documentName").orElse(""))) {
                return true;
            }
            if (KeyMaterial.decodeDocument(value).filter(KeyMaterial::isKeyMaterial)
                .isPresent()) {
                return true;
            }
        }
        if (KeyMaterial.isKeyMaterial(value.getBytes(StandardCharsets.ISO_8859_1))) {
            return true;
        }
        // a keystore can also sit base64-encoded in an untyped property (stage-2 review)
        return longBase64(value) && KeyMaterial.decodeDocument(value)
            .filter(KeyMaterial::isKeyMaterial).isPresent();
    }

    /**
     * At least 64 characters of the base64 alphabet, white space allowed — a loop, not a regular
     * expression with a group loop, which overflows the stack on long values.
     */
    static boolean longBase64(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            boolean base64 = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                || c == '+' || c == '/' || c == '=';
            if (!base64) {
                return false;
            }
            count++;
        }
        return count >= 64;
    }

    /** True if {@code value} is one X.509 certificate (base64 DER or PEM) and nothing else. */
    static boolean isPlainCertificate(String value) {
        if (value.contains("PRIVATE KEY")) {
            return false;
        }
        byte[] bytes;
        String stripped = value.strip();
        try {
            bytes = stripped.startsWith("-----BEGIN") ? stripped.getBytes(
                StandardCharsets.US_ASCII)
                : Base64.getMimeDecoder().decode(stripped);
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(bytes));
            return true;
        } catch (CertificateException | RuntimeException e) {
            return false;
        }
    }

    // --- workflows -------------------------------------------------------------------------------

    /**
     * Redacts below a workflow element: node properties (prefixed by the node), password
     * literals of assignments and defaults of password variables.
     */
    private Element workflowElement(Element element, String prefix,
        Map<String, String> inherited, Counts counts) {
        Map<String, String> namespaces = new HashMap<>(inherited);
        element.namespaces().forEach(ns -> namespaces.put(ns.prefix(), ns.uri()));
        String scope = prefix;
        if (element.localName().equals("WorkflowModule")) {
            scope = "WorkflowModule(" + element.child("ModuleId").map(Element::text)
                .orElse("?").strip() + ")/";
        }
        String nodeScope = scope;
        return element.withChildren(map(element, child -> switch (child.localName()) {
            case "Property" -> property(child, nodeScope, false, counts);
            case "copy" -> copy(child, nodeScope, counts);
            case "Variable" -> variable(child, namespaces, counts);
            default -> workflowElement(child, nodeScope, namespaces, counts);
        }));
    }

    private Element copy(Element copy, String scope, Counts counts) {
        String target = copy.child("to").flatMap(to -> to.attribute("variable")
            .or(() -> to.attribute("moduleProperty"))).orElse("?");
        return copy.withChildren(map(copy, child -> child.localName().equals("from")
            ? child.withChildren(map(child, literal -> literal.localName().equals("literal")
                && literal.attribute("isPassword").filter("true"::equals).isPresent()
                && !literal.text().isBlank() ? counted(literal, Kind.PASSWORD_LITERAL,
                    scope + "Assignments/" + target, counts) : literal))
            : child));
    }

    /**
     * A variable whose type is {@code password} in the namespace {@value #VARIABLE_TYPES}
     * (usually written {@code is:password}; the prefix is resolved, review M3).
     */
    private Element variable(Element variable, Map<String, String> inherited,
        Counts counts) {
        Map<String, String> namespaces = new HashMap<>(inherited);
        variable.namespaces().forEach(ns -> namespaces.put(ns.prefix(), ns.uri()));
        String type = variable.attribute("type").orElse("").strip();
        int colon = type.indexOf(':');
        String uri = colon < 0 ? null : namespaces.get(type.substring(0, colon));
        // an undeclared prefix cannot be resolved: fail closed (stage-2 review)
        if (colon < 0 || !type.substring(colon + 1).equals("password")
            || uri != null && !VARIABLE_TYPES.equals(uri)) {
            return variable;
        }
        String name = variable.attribute("name").orElse("?");
        return variable.withChildren(map(variable, child -> child.localName()
            .equals("DefaultValue") && !child.text().isBlank()
            ? counted(child, Kind.PASSWORD_DEFAULT, "Variables/" + name, counts) : child));
    }

    private Element counted(Element element, Kind kind, String path, Counts counts) {
        counts.add(kind);
        return element.withText(replace(path, kind, element.text()));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private String replace(String path, Kind kind, String value) {
        return replacer.replace(artifact, normalize(path), kind, value);
    }

    /** The children of {@code element} with every child element mapped by {@code mapper}. */
    private static List<Node> map(Element element,
        UnaryOperator<Element> mapper) {
        List<Node> children = new ArrayList<>(element.children().size());
        for (Node child : element.children()) {
            children.add(child instanceof Element e ? mapper.apply(e) : child);
        }
        return children;
    }

    /** Counts per kind and of suspicious names. */
    private static final class Counts {

        private final Map<Kind, Integer> kinds = new EnumMap<>(Kind.class);
        private int suspicious;

        void add(Kind kind) {
            kinds.merge(kind, 1, Integer::sum);
        }

        RedactionReport report() {
            return new RedactionReport(kinds, suspicious);
        }
    }
}
