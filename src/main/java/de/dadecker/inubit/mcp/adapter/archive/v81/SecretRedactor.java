package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.RepositoryFile;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowGroupXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Replaces every secret of an {@link ExportArchive} in memory by a placeholder before anything
 * can be written (research D-7, FR-022 – FR-025); the result is a {@link RedactedArchive}, the
 * only type the workspace writer and the assembler accept (T014).
 *
 * <p>Replaced, in module files and in the instance properties of workflow nodes:
 *
 * <ul>
 *   <li>{@link Kind#PASSWORD} every {@code Property} of {@code type="Password"}, with or without
 *       {@code encrypted}; {@link Kind#ENCRYPTED} every other {@code Property} with
 *       {@code encrypted="true"} (e.g. {@code MaskedString});
 *   <li>{@link Kind#KEYSTORE} {@code type="KeyStore"}; {@link Kind#UNTYPED_SECRET} the untyped
 *       {@code SSLKeyStoreRemoteConnector}, {@code SSLKeyStorePasswordRemoteConnector},
 *       {@code smime.keystore.data} and {@code smime.keystore.alias.password};
 *   <li>{@link Kind#PRIVATE_KEY_CERTIFICATE} certificate properties ({@code type="X509"} or a
 *       name containing {@code certificate}) unless the value is a plain X.509 certificate —
 *       anything that may hold a private key counts as secret;
 *   <li>{@link Kind#SOURCE_VARIABLE} every value below {@code xslt.sourceVariables} and
 *       {@link Kind#SAVED_TEST_MESSAGE} {@code xslt.source} / {@code xslt.target} (saved test
 *       data, also as {@code InternalDocument});
 *   <li>in workflows, {@link Kind#PASSWORD_LITERAL} a {@code literal isPassword="true"} and
 *       {@link Kind#PASSWORD_DEFAULT} the {@code DefaultValue} of a variable of type
 *       {@code is:password} (the prefix resolved to {@value #VARIABLE_TYPES}; an undeclared
 *       prefix counts as that namespace);
 *   <li>{@link Kind#KEY_MATERIAL} key material in any other property: an {@code InternalDocument}
 *       named like or holding a JKS, JCEKS or PKCS#12 keystore, a base64 value of at least 64
 *       characters that decodes to one, or a PEM private key
 *       ({@link KeyMaterial}); {@link Kind#REPOSITORY_KEY_MATERIAL} a repository file of that
 *       kind, which is withheld entirely: its content is dropped, its metadata loses
 *       {@code contentSize} and {@code contentMD5}, and it is listed in
 *       {@link RedactedArchive#withheldRepositoryFiles()} (review I3).
 * </ul>
 *
 * <p>The placeholder is {@code ${secret:<property path>}}: the property names inside the
 * artifact joined with {@code /} (e.g. {@code xslt.sourceVariables/var.sender}); in workflows
 * prefixed by {@code WorkflowModule(<ModuleId>)/}, for literals
 * {@code WorkflowModule(<ModuleId>)/Assignments/<target>}, for defaults
 * {@code Variables/<variable>}. Nothing derived from the value is kept; empty values stay empty.
 * Properties that keep their value although their name contains {@code password},
 * {@code secret}, {@code keystore} or {@code token} are counted in
 * {@link RedactionReport#suspicious()} (count only, never names or values).
 */
public final class SecretRedactor {

    /** What kind of secret a placeholder replaced. */
    public enum Kind {
        PASSWORD,
        ENCRYPTED,
        KEYSTORE,
        PRIVATE_KEY_CERTIFICATE,
        UNTYPED_SECRET,
        PASSWORD_LITERAL,
        PASSWORD_DEFAULT,
        SOURCE_VARIABLE,
        SAVED_TEST_MESSAGE,
        KEY_MATERIAL,
        REPOSITORY_KEY_MATERIAL
    }

    /** Proof of redaction: only this class can create one ({@link RedactedArchive}). */
    public static final class Seal {

        private Seal() {
        }
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
    private static final Set<String> CONTENT_VALUES = Set.of("contentSize", "contentMD5");
    /** The namespace of the variable types ({@code is:password}). */
    static final String VARIABLE_TYPES = "http://inubit.com/variables/types";

    /** The redaction of {@code archive}; the input is not changed. */
    public RedactedArchive redact(ExportArchive archive) {
        Counts counts = new Counts();
        List<WorkflowGroupXml> groups = new ArrayList<>();
        for (WorkflowGroupXml group : archive.workflowGroups()) {
            List<WorkflowXml> workflows = new ArrayList<>();
            for (WorkflowXml workflow : group.workflows()) {
                workflows.add(new WorkflowXml(workflow.name(), workflow.diagramGroup(),
                    workflowElement(workflow.element(), "", Map.of(), counts),
                    workflow.context()));
            }
            groups.add(new ExportArchive.WorkflowGroupXml(group.name(), group.attributes(),
                workflows));
        }
        Map<String, ModuleXml> modules = new LinkedHashMap<>();
        archive.moduleFiles().forEach((name, module) -> modules.put(name, new ModuleXml(
            module.name(), module.pluginType(), module.entryName(),
            properties(module.element(), "", counts))));
        Map<String, RepositoryFile> repository = new LinkedHashMap<>();
        Set<String> withheld = new LinkedHashSet<>();
        archive.repository().forEach((path, file) -> {
            if (isWithheld(file)) {
                counts.add(Kind.REPOSITORY_KEY_MATERIAL);
                withheld.add(path);
                repository.put(path, new RepositoryFile(path, new byte[0], file.metadata()
                    .map(SecretRedactor::withoutContentValues)));
            } else {
                repository.put(path, file);
            }
        });
        ExportArchive redacted = new ExportArchive(archive.properties(), archive.entries(), groups,
            archive.moduleIndex(), modules, repository);
        return new RedactedArchive(redacted, counts.report(), withheld, new Seal());
    }

    // --- repository ------------------------------------------------------------------------------

    /**
     * A repository file with key material (by name or content) is not written at all (review
     * I3); a placeholder written by a rebuild is no key material.
     */
    private static boolean isWithheld(RepositoryFile file) {
        byte[] content = file.content();
        if (SecretPlaceholder.isPlaceholder(new String(content, StandardCharsets.UTF_8))) {
            return false;
        }
        return KeyMaterial.hasKeyName(file.path()) || KeyMaterial.isKeyMaterial(content);
    }

    /** The metadata without {@code contentSize} and {@code contentMD5} (derived from the value). */
    private static Element withoutContentValues(Element metadata) {
        return metadata.withAttributes(metadata.attributes().stream()
            .filter(a -> !CONTENT_VALUES.contains(a.qualifiedName())).toList());
    }

    // --- module properties ----------------------------------------------------------------------

    /** Redacts the {@code Property} elements below {@code element}; {@code prefix} ends in "/". */
    private static Element properties(Element element, String prefix, Counts counts) {
        return element.withChildren(map(element, child -> child.localName().equals("Property")
            ? property(child, prefix, false, counts) : properties(child, prefix, counts)));
    }

    private static Element property(Element property, String prefix, boolean inSourceVariables,
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
        return property.withText(placeholder(path));
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
    private static Element workflowElement(Element element, String prefix,
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

    private static Element copy(Element copy, String scope, Counts counts) {
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
    private static Element variable(Element variable, Map<String, String> inherited,
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

    private static Element counted(Element element, Kind kind, String path, Counts counts) {
        counts.add(kind);
        return element.withText(placeholder(path));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static String placeholder(String path) {
        return new SecretPlaceholder(UNSAFE_PATH_CHARACTERS.matcher(path.strip()).replaceAll("_"))
            .render();
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
