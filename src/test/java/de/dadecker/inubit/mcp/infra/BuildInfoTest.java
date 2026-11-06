package de.dadecker.inubit.mcp.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildInfoTest {

    @TempDir
    Path dir;

    @Test
    void readsImplementationVersionFromAJarWhateverItsName() throws IOException {
        Path jar = jar("inubit-mcp-server.jar", "1.2.3");
        assertThat(BuildInfo.manifestVersion(Optional.of(jar))).contains("1.2.3");
    }

    @Test
    void ignoresAMissingBlankOrUnfilteredAttribute() throws IOException {
        assertThat(BuildInfo.manifestVersion(Optional.of(jar("a.jar", null)))).isEmpty();
        assertThat(BuildInfo.manifestVersion(Optional.of(jar("b.jar", "  ")))).isEmpty();
        assertThat(BuildInfo.manifestVersion(Optional.of(jar("c.jar", "${project.version}"))))
            .isEmpty();
    }

    @Test
    void aDirectoryAMissingFileOrNoLocationHasNoManifestVersion() throws IOException {
        Path notAJar = Files.writeString(dir.resolve("broken.jar"), "no zip");
        assertThat(BuildInfo.manifestVersion(Optional.of(dir))).isEmpty();
        assertThat(BuildInfo.manifestVersion(Optional.of(dir.resolve("missing.jar")))).isEmpty();
        assertThat(BuildInfo.manifestVersion(Optional.of(notAJar))).isEmpty();
        assertThat(BuildInfo.manifestVersion(Optional.empty())).isEmpty();
    }

    @Test
    void manifestVersionWinsOverBuildProperties() {
        assertThat(BuildInfo.resolve(Optional.of("1.2.3"), () -> "9.9.9")).isEqualTo("1.2.3");
        assertThat(BuildInfo.resolve(Optional.empty(), () -> "9.9.9")).isEqualTo("9.9.9");
    }

    @Test
    void buildPropertiesMustBePresentAndFiltered() {
        assertThat(BuildInfo.fromBuildProperties(stream("version=0.7.1\n"))).isEqualTo("0.7.1");
        assertThatIllegalStateException().isThrownBy(() -> BuildInfo.fromBuildProperties(null))
            .withMessageContaining("missing");
        assertThatIllegalStateException()
            .isThrownBy(() -> BuildInfo.fromBuildProperties(stream("version=${project.version}\n")))
            .withMessageContaining("not filtered");
    }

    @Test
    void outsideAJarTheVersionComesFromBuildProperties() {
        assertThat(BuildInfo.version()).isNotBlank().doesNotContain("${");
    }

    private Path jar(String name, String version) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (version != null) {
            manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        }
        Path jar = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream ignored = new JarOutputStream(out, manifest)) {
            // the manifest is the only entry
        }
        return jar;
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }
}
