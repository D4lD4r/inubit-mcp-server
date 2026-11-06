package de.dadecker.inubit.mcp.infra;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.UpdateCache.CachedRelease;
import de.dadecker.inubit.mcp.domain.model.SemVer;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileUpdateCacheTest {

    private static final Instant T0 = Instant.parse("2026-11-06T09:15:00Z");

    @TempDir
    Path home;

    private final MutableClock clock = new MutableClock(T0);

    private FileUpdateCache cache() {
        return FileUpdateCache.inHome(home, clock);
    }

    @Test
    void theFileLivesBelowTheDotDirectoryOfTheHome() {
        assertThat(cache().file()).isEqualTo(home.resolve(".inubit-mcp/update-check.json"));
    }

    @Test
    void aMissingFileIsEmpty() {
        assertThat(cache().read()).isEmpty();
    }

    @Test
    void aWrittenResultIsReadBackAndTheDirectoryIsOwnerOnly() throws IOException {
        cache().write(v("0.8.0"), T0);

        assertThat(cache().read()).contains(new CachedRelease(v("0.8.0"), T0, Optional.empty()));
        assertThat(Files.readString(cache().file()))
            .isEqualTo("{\"latestVersion\":\"0.8.0\",\"checkedAt\":\"2026-11-06T09:15:00Z\"}\n");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(
                home.resolve(".inubit-mcp")))).isEqualTo("rwx------");
        }
        try (var files = Files.list(home.resolve(".inubit-mcp"))) {
            assertThat(files).as("no temporary file left").containsExactly(cache().file());
        }
    }

    @Test
    void aDismissedVersionSurvivesTheNextWrite() {
        cache().write(v("0.8.0"), T0);
        cache().dismiss(v("0.8.0"));
        Instant later = T0.plusSeconds(86_400);
        cache().write(v("0.8.0"), later);

        assertThat(cache().read()).contains(new CachedRelease(v("0.8.0"), later,
            Optional.of(v("0.8.0"))));
    }

    @Test
    void dismissingWithoutAFileRecordsTheVersionAsLatest() {
        cache().dismiss(v("0.8.0"));

        assertThat(cache().read()).contains(new CachedRelease(v("0.8.0"), T0,
            Optional.of(v("0.8.0"))));
    }

    @Test
    void unknownFieldsAreIgnored() throws IOException {
        write("{\"latestVersion\":\"0.8.0\",\"checkedAt\":\"2026-11-06T09:15:00Z\","
            + "\"future\":{\"x\":1}}");

        assertThat(cache().read()).contains(new CachedRelease(v("0.8.0"), T0, Optional.empty()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[]", "{}", "{\"latestVersion\":\"0.8.0\"}",
        "{\"latestVersion\":\"v0.8.0\",\"checkedAt\":\"2026-11-06T09:15:00Z\"}",
        "{\"latestVersion\":8,\"checkedAt\":\"2026-11-06T09:15:00Z\"}",
        "{\"latestVersion\":\"0.8.0\",\"checkedAt\":\"yesterday\"}",
        "{\"latestVersion\":\"0.8.0\",\"checkedAt\":\"2026-11-06T09:15:00Z\","
            + "\"dismissedVersion\":\"latest\"}"})
    void anInvalidFileIsIgnoredAsAWhole(String content) throws IOException {
        write(content);

        assertThat(cache().read()).isEmpty();
    }

    @Test
    void anOversizedFileIsIgnored() throws IOException {
        write("{\"latestVersion\":\"0.8.0\",\"checkedAt\":\"2026-11-06T09:15:00Z\",\"pad\":\""
            + "x".repeat((int) FileUpdateCache.MAX_BYTES) + "\"}");

        assertThat(cache().read()).isEmpty();
    }

    @Test
    void aWriteFailureIsSwallowed() throws IOException {
        Files.writeString(home.resolve(".inubit-mcp"), "a file where the directory belongs");

        cache().write(v("0.8.0"), T0);
        cache().dismiss(v("0.8.0"));

        assertThat(cache().read()).isEmpty();
    }

    private void write(String content) throws IOException {
        Files.createDirectories(home.resolve(".inubit-mcp"));
        Files.writeString(cache().file(), content, StandardCharsets.UTF_8);
    }

    private static SemVer v(String version) {
        return SemVer.parse(version).orElseThrow();
    }
}
