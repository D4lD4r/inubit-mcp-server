package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.RepositoryFile;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowGroupXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.domain.model.SecretPlaceholder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *       {@code is:password} (the prefix resolved to {@value SecretPaths#VARIABLE_TYPES}; an undeclared
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
 *
 * <p>The walk itself (which property is a secret and its path) is {@link SecretPaths}, shared
 * with {@link SecretValues} (feature 004, research D-6).
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

    private static final Set<String> CONTENT_VALUES = Set.of("contentSize", "contentMD5");

    /** The redaction of {@code archive}; the input is not changed. */
    public RedactedArchive redact(ExportArchive archive) {
        SecretPaths paths = new SecretPaths((artifact, path, kind, value) ->
            new SecretPlaceholder(path).render());
        Map<Kind, Integer> repositoryKinds = new EnumMap<>(Kind.class);
        List<WorkflowGroupXml> groups = new ArrayList<>();
        for (WorkflowGroupXml group : archive.workflowGroups()) {
            List<WorkflowXml> workflows = new ArrayList<>();
            for (WorkflowXml workflow : group.workflows()) {
                workflows.add(new WorkflowXml(workflow.name(), workflow.diagramGroup(),
                    paths.workflow(workflow.name(), workflow.element()),
                    workflow.context()));
            }
            groups.add(new ExportArchive.WorkflowGroupXml(group.name(), group.attributes(),
                workflows));
        }
        Map<String, ModuleXml> modules = new LinkedHashMap<>();
        archive.moduleFiles().forEach((name, module) -> modules.put(name, new ModuleXml(
            module.name(), module.pluginType(), module.entryName(),
            paths.module(module.name(), module.element()))));
        Map<String, RepositoryFile> repository = new LinkedHashMap<>();
        Set<String> withheld = new LinkedHashSet<>();
        archive.repository().forEach((path, file) -> {
            if (isWithheld(file)) {
                repositoryKinds.merge(Kind.REPOSITORY_KEY_MATERIAL, 1, Integer::sum);
                withheld.add(path);
                repository.put(path, new RepositoryFile(path, new byte[0], file.metadata()
                    .map(SecretRedactor::withoutContentValues)));
            } else {
                repository.put(path, file);
            }
        });
        ExportArchive redacted = new ExportArchive(archive.properties(), archive.entries(), groups,
            archive.moduleIndex(), modules, repository);
        Map<Kind, Integer> kinds = paths.kinds();
        repositoryKinds.forEach((kind, n) -> kinds.merge(kind, n, Integer::sum));
        return new RedactedArchive(redacted, new RedactionReport(kinds, paths.suspicious()),
            withheld, new Seal());
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
}
