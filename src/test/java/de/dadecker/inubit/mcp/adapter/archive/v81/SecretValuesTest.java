package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.SyntheticSecret;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.domain.model.SecretPlaceholder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/**
 * T011 (feature 004, research D-6): the secret values of a raw export, found by the same walk
 * as the redaction, so that every placeholder the redactor writes resolves to the value at its
 * position.
 */
class SecretValuesTest {

    private final ArchiveReader reader = new ArchiveReader();

    static List<String> exports() {
        return ArtifactFixtures.EXPORTS;
    }

    /** One placeholder of the redacted archive: the artifact and its property path. */
    private record Placeholder(boolean workflow, String artifact, String path) {
    }

    private static List<Placeholder> placeholders(RedactedArchive redacted) {
        List<Placeholder> found = new ArrayList<>();
        for (var group : redacted.archive().workflowGroups()) {
            for (WorkflowXml workflow : group.workflows()) {
                collect(workflow.element(), true, workflow.name(), found);
            }
        }
        for (ModuleXml module : redacted.archive().moduleFiles().values()) {
            collect(module.element(), false, module.name(), found);
        }
        return found;
    }

    private static void collect(Element element, boolean workflow, String artifact,
        List<Placeholder> found) {
        if (!element.hasElements()) {
            SecretPlaceholder.parse(element.text()).ifPresent(placeholder ->
                found.add(new Placeholder(workflow, artifact, placeholder.propertyPath())));
            return;
        }
        element.elements().forEach(child -> collect(child, workflow, artifact, found));
    }

    private static Optional<String> lookup(SecretValues values, Placeholder placeholder) {
        return placeholder.workflow() ? values.workflow(placeholder.artifact(), placeholder.path())
            : values.module(placeholder.artifact(), placeholder.path());
    }

    @ParameterizedTest
    @MethodSource("exports")
    void everyPlaceholderOfTheRedactorResolvesToTheValueAtItsPosition(String fixture) {
        ExportArchive raw = reader.read(ArtifactFixtures.bytes(fixture));
        RedactedArchive redacted = new SecretRedactor().redact(raw);

        SecretValues values = SecretValues.of(raw);

        List<Placeholder> placeholders = placeholders(redacted);
        assertThat(values.size()).isEqualTo(placeholders.size());
        Set<String> resolved = new HashSet<>();
        for (Placeholder placeholder : placeholders) {
            Optional<String> value = lookup(values, placeholder);
            assertThat(value).as(placeholder.toString()).isPresent();
            assertThat(SecretPlaceholder.isPlaceholder(value.get())).isFalse();
            resolved.add(value.get());
        }
        for (SyntheticSecret secret : ArtifactFixtures.syntheticSecrets()) {
            if (secret.fixture().equals(fixture) && !secret.kind().equals("savedTestMessage")) {
                assertThat(resolved).as(secret.kind() + " " + secret.location())
                    .anyMatch(value -> value.contains(secret.value()));
            }
        }
    }

    @Test
    void theFixturesHaveSecretsToResolve() {
        assertThat(SecretValues.of(reader.read(ArtifactFixtures.bytes("grp-b.zip"))).size())
            .isGreaterThan(40);
        assertThat(SecretValues.of(reader.read(ArtifactFixtures.bytes("module-smime.zip")))
            .size()).isPositive();
    }

    @Test
    void anUnknownArtifactOrPathHasNoValue() {
        SecretValues values = SecretValues.of(reader.read(ArtifactFixtures.bytes("grp-b.zip")));

        assertThat(values.module("Module-0028", "No.Such.Property")).isEmpty();
        assertThat(values.module("Module-9999", "Password")).isEmpty();
        assertThat(values.workflow("Module-0028", "Password")).as("kind matters").isEmpty();
        assertThat(values.module("Module-0028", "Password")).isPresent();
    }

    @Test
    void valuesAreNeverInToString() {
        SecretValues values = SecretValues.of(reader.read(ArtifactFixtures.bytes("grp-b.zip")));

        String text = values.toString();
        for (SyntheticSecret secret : ArtifactFixtures.syntheticSecrets()) {
            assertThat(text).doesNotContain(secret.value());
        }
        assertThat(text).contains(String.valueOf(values.size()));
    }

    @Test
    void theRedactorStillWritesThePlaceholdersOfTheSharedWalk() {
        // the extraction keeps the redaction: grp-b has placeholders for module and workflow
        RedactedArchive redacted = new SecretRedactor().redact(reader.read(
            ArtifactFixtures.bytes("grp-b.zip")));

        assertThat(placeholders(redacted)).contains(
            new Placeholder(false, "Module-0028", "Password"),
            new Placeholder(true, "Workflow-0006", "Variables/var.fixturePassword"));
    }
}
