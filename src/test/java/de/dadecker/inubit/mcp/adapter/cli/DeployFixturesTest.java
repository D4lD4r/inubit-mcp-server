package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T001 (feature 005, research D-1): the synthetic fixtures of the repository and release probes
 * are complete and keep the observed structure (documented in {@code fixtures/v8_1/cli/README.md}),
 * so that the runner and harness tests can rely on them.
 */
class DeployFixturesTest {

    private static final String TAG = "TAG-01";
    private static final Pattern METADATA = Pattern.compile(
        "<Property name=\"([^\"]+)\" type=\"RepositoryFile\"[^>]*>");

    @ParameterizedTest
    @ValueSource(strings = {"export_release", "export_repository", "export_repository_not_found",
        "import_repository_ok", "tag_repository_user_refused"})
    void everyCaseHasStdoutStderrAndExitCode(String fixtureCase) {
        for (String suffix : List.of(".stdout", ".stderr", ".exit")) {
            assertThat(resource("/fixtures/v8_1/cli/" + fixtureCase + suffix))
                .as(fixtureCase + suffix).isNotNull();
        }
        assertThat(FakeProcessLauncher.fixtureText(fixtureCase + ".stderr"))
            .startsWith("Picked up JAVA_TOOL_OPTIONS: -Duser.language=en -Duser.country=US\n");
        assertThat(FakeProcessLauncher.fixtureText(fixtureCase + ".stdout"))
            .startsWith("JAVA_HOME is set\nPassword: \n");
    }

    @Test
    void theReleaseExportHoldsOnlyTaggedVersions() {
        Map<String, byte[]> entries = ArtifactFixtures.entries(
            FakeProcessLauncher.fixture("export_release.zip"));
        String workflows = text(entries.get("workflow/workflow.xml"));
        String index = text(entries.get("module/module.xml"));

        assertThat(entries).containsKeys("archive.properties", "Repository.zip",
            "workflow/workflow.xml", "usertags.xml", "module/module.xml");
        assertThat(text(entries.get("usertags.xml"))).contains("<Tag>" + TAG + "</Tag>");
        assertThat(workflows).contains("<WorkflowGroupName>GRP-01</WorkflowGroupName>")
            .doesNotContain("version=\"head\"").doesNotContain("<CheckoutUser>")
            .contains("<WorkflowModule moduleType=\"technical\" tag=\"" + TAG + "\">");
        assertThat(index).doesNotContain("version=\"head\"");
        assertThat(count(workflows, "@@@Tag: " + TAG + "@@@"))
            .isEqualTo(count(workflows, "<CheckinComment>"));
        assertThat(count(index, "@@@Tag: " + TAG + "@@@"))
            .isEqualTo(count(index, "<CheckinComment>"));
        assertThat(entries.keySet().stream().filter(name -> name.startsWith("module/module-"))
            .map(name -> text(entries.get(name))))
            .anyMatch(module -> module.contains(
                "href=\"inubitrepository:/Root/jdoe/xsd/release.xsl\""));
    }

    @Test
    void theReleaseCarriesTheReferencedRepositoryFileInItsOlderTaggedVersion() {
        Map<String, byte[]> repository = ArtifactFixtures.entries(ArtifactFixtures.entries(
            FakeProcessLauncher.fixture("export_release.zip")).get("Repository.zip"));
        Map<String, byte[]> head = ArtifactFixtures.entries(
            FakeProcessLauncher.fixture("export_repository.zip"));

        assertThat(repository).containsOnlyKeys("Root/jdoe/xsd/release.xsl.xml",
            "Root/jdoe/xsd/release.xsl.dat");
        String tagged = text(repository.get("Root/jdoe/xsd/release.xsl.xml"));
        assertThat(tagged).contains("tagName=\"" + TAG + "\"").contains("version=\"1.0\"")
            .contains("path=\"/Root/jdoe/xsd/release.xsl\"");
        String current = text(head.get("Root/jdoe/xsd/release.xsl.xml"));
        assertThat(current).contains("version=\"1.1\"").doesNotContain("tagName=");
        assertThat(head.get("Root/jdoe/xsd/release.xsl.dat"))
            .isNotEqualTo(repository.get("Root/jdoe/xsd/release.xsl.dat"));
    }

    @Test
    void repositoryExportsArePairsOfMetadataAndContentBelowTheExportedPath() {
        Map<String, byte[]> entries = ArtifactFixtures.entries(
            FakeProcessLauncher.fixture("export_repository.zip"));
        Map<String, byte[]> release = ArtifactFixtures.entries(ArtifactFixtures.entries(
            FakeProcessLauncher.fixture("export_release.zip")).get("Repository.zip"));

        assertThat(entries.keySet()).first().isEqualTo("Root/jdoe/xsd/");
        assertThat(entries.keySet()).allMatch(name -> name.startsWith("Root/jdoe/xsd/"));
        for (Map<String, byte[]> archive : List.of(entries, release)) {
            archive.forEach((name, bytes) -> {
                if (!name.endsWith(".xml")) {
                    return;
                }
                String base = name.substring(0, name.length() - ".xml".length());
                byte[] content = archive.get(base + ".dat");
                String metadata = text(bytes);
                Matcher property = METADATA.matcher(metadata);
                assertThat(property.find()).as(name).isTrue();
                assertThat(content).as(base + ".dat").isNotNull();
                assertThat(metadata).as(name)
                    .contains("path=\"/" + base + "\"")
                    .contains("contentSize=\"" + content.length + "\"")
                    .contains("contentMD5=\"" + md5(content) + "\"")
                    .contains("<Description>");
            });
        }
    }

    @Test
    void theFailureCasesNameTheirReason() {
        assertThat(exit("export_repository_not_found")).isEqualTo(1);
        assertThat(FakeProcessLauncher.fixtureText("export_repository_not_found.stderr"))
            .contains("Internal INUBIT error!").contains("Path not found //ibis:Root/jdoe/");
        assertThat(exit("tag_repository_user_refused")).isEqualTo(1);
        assertThat(FakeProcessLauncher.fixtureText("tag_repository_user_refused.stdout"))
            .contains("Cannot specify user when repository is tagged.");
    }

    @Test
    void successfulRunsConfirmWithAnOkLineAndTheRepositoryImportHasNoProtocol() {
        assertThat(exit("export_release")).isZero();
        assertThat(FakeProcessLauncher.fixtureText("export_release.stdout"))
            .contains("1-OK: Workflow group exported successfully.");
        assertThat(exit("export_repository")).isZero();
        assertThat(FakeProcessLauncher.fixtureText("export_repository.stdout").lines())
            .anyMatch(line -> line.matches("^\\d+-OK: .+"));
        assertThat(exit("import_repository_ok")).isZero();
        assertThat(FakeProcessLauncher.fixtureText("import_repository_ok.stdout"))
            .contains("Completed = 0 MB / 0 MB").contains("1-OK: Imported successfully")
            .doesNotContain("TYPE ").doesNotContain("Total:");
    }

    private static int count(String text, String token) {
        int count = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + 1)) {
            count++;
        }
        return count;
    }

    private static String md5(byte[] content) {
        try {
            return String.format("%032x", new BigInteger(1,
                MessageDigest.getInstance("MD5").digest(content)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int exit(String fixtureCase) {
        return Integer.parseInt(FakeProcessLauncher.fixtureText(fixtureCase + ".exit").strip());
    }

    private static InputStream resource(String path) {
        return DeployFixturesTest.class.getResourceAsStream(path);
    }
}
