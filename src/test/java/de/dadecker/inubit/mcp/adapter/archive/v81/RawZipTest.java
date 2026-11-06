package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.RawZip.RawEntry;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Raw entries of an export ZIP (feature 015 research R-4). */
class RawZipTest {

    private static final LocalDateTime EXPORTED = LocalDateTime.of(2026, 10, 6, 5, 50, 44);

    @Test
    void readsTheEntriesOfAnExportInArchiveOrderWithBytesAndTimes() {
        RawZip zip = RawZip.read(ArtifactFixtures.bytes("grp-b.zip"));

        Map<String, byte[]> expected = ArtifactFixtures.entries("grp-b.zip");
        assertThat(zip.entries()).hasSize(23);
        assertThat(names(zip)).containsExactlyElementsOf(expected.keySet());
        assertThat(names(zip)).startsWith("archive.properties", "Repository.zip",
            "workflow/workflow.xml").endsWith("module/module-0014.xml");
        for (RawEntry entry : zip.entries()) {
            assertThat(entry.bytes()).as(entry.header().getName())
                .isEqualTo(expected.get(entry.header().getName()));
            assertThat(entry.header().getTimeLocal()).as(entry.header().getName())
                .isEqualTo(EXPORTED);
            assertThat(entry.header().getMethod()).isEqualTo(ZipEntry.DEFLATED);
        }
        assertThat(zip.entry("module/module.xml")).get()
            .extracting(e -> e.bytes().length).isEqualTo(24244);
        assertThat(zip.entry("module/missing.xml")).isEmpty();
        assertThat(zip.comment()).isEmpty();
    }

    @Test
    void readsTheNestedRepositoryWithItsTimes() {
        RawZip repository = nested("grp-b.zip");

        assertThat(names(repository)).containsExactly(
            "Root/OWNERS/xsd/msg.xsd.xml", "Root/OWNERS/xsd/msg.xsd.dat",
            "Root/OWNERS/xsd/core.xsd.xml", "Root/OWNERS/xsd/core.xsd.dat",
            "Root/OWNERS/keys/fixture-client.p12.xml", "Root/OWNERS/keys/fixture-client.p12.dat");
        assertThat(repository.entry("Root/OWNERS/xsd/msg.xsd.xml")).get()
            .extracting(e -> e.header().getTimeLocal())
            .isEqualTo(LocalDateTime.of(2025, 11, 27, 8, 59, 44));
        assertThat(repository.entry("Root/OWNERS/xsd/msg.xsd.dat")).get()
            .extracting(e -> e.header().getTimeLocal()).isEqualTo(EXPORTED);
        // the recorded grp-b keeps no archive comment in its Repository.zip
        assertThat(repository.comment()).isEmpty();
    }

    @Test
    void readsTheArchiveCommentOfTheNestedRepository() {
        RawZip repository = nested("grp-a.zip");

        assertThat(repository.entries()).isEmpty();
        assertThat(repository.comment()).contains("5.3");
    }

    @Test
    void writeReproducesNamesBytesTimesOrderAndComment() {
        for (RawZip original : List.of(RawZip.read(ArtifactFixtures.bytes("grp-b.zip")),
            nested("grp-b.zip"), nested("grp-a.zip"))) {
            byte[] written = RawZip.write(original.entries(), original.comment());

            RawZip copy = RawZip.read(written);
            assertThat(names(copy)).containsExactlyElementsOf(names(original));
            for (int i = 0; i < original.entries().size(); i++) {
                ZipEntry before = original.entries().get(i).header();
                ZipEntry after = copy.entries().get(i).header();
                assertThat(copy.entries().get(i).bytes()).as(before.getName())
                    .isEqualTo(original.entries().get(i).bytes());
                assertThat(after.getTimeLocal()).as(before.getName())
                    .isEqualTo(before.getTimeLocal());
                assertThat(after.getMethod()).isEqualTo(before.getMethod());
            }
            assertThat(copy.comment()).isEqualTo(original.comment());
        }
    }

    @Test
    void writeKeepsAGivenComment() {
        RawZip original = nested("grp-b.zip");

        RawZip copy = RawZip.read(RawZip.write(original.entries(), Optional.of("5.3")));

        assertThat(copy.comment()).contains("5.3");
        assertThat(names(copy)).containsExactlyElementsOf(names(original));
    }

    @Test
    void anEntryWithOtherBytesKeepsItsHeaderAndIsWrittenWithThem() {
        RawEntry deflated = RawZip.read(ArtifactFixtures.bytes("grp-b.zip")).entries().get(0);
        ZipEntry header = new ZipEntry("Root/OWNERS/xsd/");
        header.setMethod(ZipEntry.STORED);
        header.setSize(0);
        header.setCompressedSize(0);
        header.setCrc(0);
        header.setTimeLocal(EXPORTED);
        RawEntry stored = new RawEntry(header, new byte[0]);
        byte[] replaced = "replaced".getBytes(StandardCharsets.UTF_8);

        List<RawEntry> changed = List.of(deflated.withBytes(replaced), stored.withBytes(replaced));
        RawZip copy = RawZip.read(RawZip.write(changed, Optional.empty()));

        for (int i = 0; i < changed.size(); i++) {
            ZipEntry before = List.of(deflated, stored).get(i).header();
            ZipEntry after = copy.entries().get(i).header();
            assertThat(after.getName()).isEqualTo(before.getName());
            assertThat(after.getTimeLocal()).isEqualTo(before.getTimeLocal());
            assertThat(after.getMethod()).isEqualTo(before.getMethod());
            assertThat(copy.entries().get(i).bytes()).isEqualTo(replaced);
        }
        assertThat(deflated.bytes()).isNotEqualTo(replaced);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil.xml", "module/../../evil.xml", "/etc/evil.xml",
        "\\evil.xml", "C:/evil.xml", "c:evil.xml", "module\\..\\evil.xml"})
    void refusesEntryNamesThatLeaveTheArchive(String name) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("archive.properties", new byte[] {1});
        entries.put(name, new byte[] {2});

        assertUnexpected(() -> RawZip.read(ArtifactFixtures.zip(entries)), "entry name");
    }

    @Test
    void refusesADuplicateEntry() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("module/module-0023.xml", new byte[] {1});
        entries.put("module/module-002X.xml", new byte[] {2});
        // ZipOutputStream refuses duplicates: write another name of the same length, then patch it
        byte[] zip = ArtifactFixtures.zip(entries);
        byte[] from = "module/module-002X.xml".getBytes(StandardCharsets.UTF_8);
        byte[] to = "module/module-0023.xml".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + from.length <= zip.length; i++) {
            if (Arrays.equals(zip, i, i + from.length, from, 0, from.length)) {
                System.arraycopy(to, 0, zip, i, to.length);
            }
        }

        assertUnexpected(() -> RawZip.read(zip), "module/module-0023.xml", "twice");
    }

    @Test
    void refusesAnInflatedEntryOverTheBound() {
        byte[] zip = ArtifactFixtures.zip(Map.of("module/module.xml", new byte[4097]));

        assertUnexpected(() -> RawZip.read(zip, 4096), "module/module.xml", "too large");
        assertThat(RawZip.read(zip, 4097).entries()).hasSize(1);
    }

    @Test
    void refusesInflatedEntriesOverTheBoundInTotal() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("module/module.xml", new byte[3000]);
        entries.put("module/module-0023.xml", new byte[3000]);
        byte[] zip = ArtifactFixtures.zip(entries);

        assertUnexpected(() -> RawZip.read(zip, 4096), "module/module-0023.xml", "too large");
        assertThat(RawZip.read(zip, 6000).entries()).hasSize(2);
    }

    @Test
    void refusesBytesThatAreNoZipArchive() {
        assertUnexpected(() -> RawZip.read("not a zip".getBytes(StandardCharsets.UTF_8)),
            "not a readable ZIP archive");
    }

    @Test
    void entriesAreDefensiveCopies() {
        RawZip zip = nested("grp-b.zip");
        RawEntry entry = zip.entries().get(0);
        byte first = entry.bytes()[0];

        entry.bytes()[0] ^= 1;
        entry.header().setTime(0);

        assertThat(entry.bytes()[0]).isEqualTo(first);
        assertThat(zip.entries().get(0).bytes()[0]).isEqualTo(first);
        assertThat(entry.header().getTimeLocal())
            .isEqualTo(LocalDateTime.of(2025, 11, 27, 8, 59, 44));
        byte[] source = {1, 2};
        ZipEntry header = new ZipEntry("x");
        RawEntry made = new RawEntry(header, source);
        source[0] = 9;
        header.setComment("changed");
        assertThat(made.bytes()).containsExactly(1, 2);
        assertThat(made.header().getComment()).isNull();
    }

    private static RawZip nested(String export) {
        return RawZip.read(RawZip.read(ArtifactFixtures.bytes(export)).entry("Repository.zip")
            .orElseThrow().bytes());
    }

    private static List<String> names(RawZip zip) {
        return zip.entries().stream().map(e -> e.header().getName()).toList();
    }

    private static void assertUnexpected(Runnable read, String... messageParts) {
        assertThatThrownBy(read::run).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
            assertThat(e.error().message()).contains(messageParts);
        });
    }
}
