package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The recorded artifact exports of feature 003 ({@code fixtures/v8_1/artifacts}, research D-14,
 * T002): the export ZIPs, the StartCLI recordings and the list of synthetic secret values in
 * {@code README.md}.
 */
public final class ArtifactFixtures {

    /** The diagram group and module-only exports. */
    public static final List<String> EXPORTS =
        List.of("grp-a.zip", "grp-b.zip", "module-one.zip", "module-smime.zip");

    private static final String ROOT = "/fixtures/v8_1/artifacts/";
    private static final String SECRETS_START = "```synthetic-secrets\n";

    private ArtifactFixtures() {
    }

    /** One replaced secret: its kind, fixture, location and synthetic value. */
    public record SyntheticSecret(String kind, String fixture, String location, String value) {

        public SyntheticSecret {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(fixture, "fixture");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(value, "value");
        }
    }

    /** A recorded StartCLI run: exit code, stdout and stderr. */
    public record CliRecording(int exitCode, String stdout, String stderr) {
    }

    /** The bytes of {@code fixtures/v8_1/artifacts/<name>}. */
    public static byte[] bytes(String name) {
        try (InputStream in = ArtifactFixtures.class.getResourceAsStream(ROOT + name)) {
            assertThat(in).as("fixture %s", ROOT + name).isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The entries of an export ZIP in archive order, by name (directories map to no bytes). */
    public static Map<String, byte[]> entries(String zipName) {
        return entries(bytes(zipName));
    }

    /** The entries of a ZIP in archive order, by name (directories map to no bytes). */
    public static Map<String, byte[]> entries(byte[] zip) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), entry.isDirectory() ? new byte[0] : in.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }

    /** A ZIP of {@code entries} in their order (test archives derived from the fixtures). */
    public static byte[] zip(Map<String, byte[]> entries) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** The synthetic secret values listed in the fixture README, in file order. */
    public static List<SyntheticSecret> syntheticSecrets() {
        String readme = new String(bytes("README.md"), StandardCharsets.UTF_8);
        int start = readme.indexOf(SECRETS_START);
        assertThat(start).as("README.md has a synthetic-secrets block").isNotNegative();
        int end = readme.indexOf("```", start + SECRETS_START.length());
        List<SyntheticSecret> secrets = new ArrayList<>();
        for (String line : readme.substring(start + SECRETS_START.length(), end).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            assertThat(fields).as("kind, fixture, location and value: %s", fields[0]).hasSize(4);
            secrets.add(new SyntheticSecret(fields[0], fields[1], fields[2], fields[3]));
        }
        return List.copyOf(secrets);
    }

    /**
     * {@code fixtures/v8_1/artifacts/cli/<name>.{stdout,stderr,exit}}, recorded like the CLI
     * fixtures of feature 001.
     */
    public static CliRecording cliRecording(String name) {
        return new CliRecording(Integer.parseInt(text("cli/" + name + ".exit").strip()),
            text("cli/" + name + ".stdout"), text("cli/" + name + ".stderr"));
    }

    private static String text(String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }
}
