package de.dadecker.inubit.mcp.adapter.cli.v81;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads one entry of a recorded CLI export ZIP ({@code fixtures/v8_1/cli/*.zip}). */
final class ExportFixtures {

    private ExportFixtures() {
    }

    static byte[] entry(String zipFixture, String entryName) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(
            FakeProcessLauncher.fixture(zipFixture)))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(entryName)) {
                    return zip.readAllBytes();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new IllegalArgumentException(zipFixture + " has no entry " + entryName);
    }
}
