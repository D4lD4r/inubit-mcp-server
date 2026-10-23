package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ImportPreview;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T035 (feature 008, SC-001): a {@code repositoryPaths} import of 20 repository files in 3
 * folders on {@link ImportHarness}, with every StartCLI call taking the upper P-2 duration
 * (research D-5, spike section 11: an export 2.7 s, an import 2.8 s). The delays are not slept:
 * each scripted call advances the harness clock by its duration, so the time of a call is that
 * simulated StartCLI time plus the measured time of this server's own work.
 *
 * <p>The calls follow D-5: one export per folder (not per file) for the preview's node read,
 * one {@code importRepository} for all files and one export per folder for the verification.
 * The confirmed call first repeats the node read, because the confirmation code is bound to the
 * node state (D-6).
 */
@Timeout(60)
class RepositoryImportTimingTest {

    private static final Duration EXPORT = Duration.ofMillis(2700);
    private static final Duration IMPORT = Duration.ofMillis(2800);
    private static final Duration BUDGET = Duration.ofMinutes(2);
    /**
     * The folders below {@code /Root/jdoe}, named after the extension of their files, in path
     * order (the order of the node read); 6 + 7 + 7 = 20 files, the first 3 of each folder are
     * on the node and edited in the workspace, the others are new.
     */
    private static final List<String> ORDER = List.of("wsdl", "xsd", "xsl");
    private static final Map<String, Integer> COUNTS = Map.of("wsdl", 6, "xsd", 7, "xsl", 7);
    private static final int ON_NODE = 3;

    @TempDir
    Path temp;

    private ImportHarness harness;
    private final FakeServer node = new FakeServer();

    private static String content(String name, String version) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<file name=\"" + name
            + "\" version=\"" + version + "\"/>\n";
    }

    /** The full repository path of {@code relative} (a folder or a file) of the owner. */
    private static String full(String relative) {
        return "/Root/jdoe/" + relative;
    }

    private static String name(String folder, int i) {
        return folder + "/file-" + i + "." + folder;
    }

    private static String file(String repositoryPath) {
        return WorkspacePath.repository(ImportHarness.DEV.group(), "jdoe", repositoryPath)
            .toRelativePath().toString().replace('\\', '/');
    }

    /** Every repository export of the three folders, each taking {@link #EXPORT}. */
    private void exports() {
        ORDER.forEach(folder -> {
            harness.exportRepository(node, full(folder));
            harness.cli.then(spec -> harness.clock.advance(EXPORT));
        });
    }

    /** The response of a call, its simulated StartCLI time and the measured own time. */
    private record Timed(ImportService.Response response, Duration startCli, Duration own) {
        Duration total() {
            return startCli.plus(own);
        }
    }

    private Timed timed(ImportService service, ImportService.ImportRequest request) {
        Instant before = harness.clock.instant();
        long start = System.nanoTime();
        ImportService.Response response = service.importArtifacts(request);
        Duration own = Duration.ofNanos(System.nanoTime() - start);
        return new Timed(response, Duration.between(before, harness.clock.instant()), own);
    }

    /** The kind of the StartCLI calls from index {@code from} on. */
    private List<String> calls(int from) {
        List<String> commands = harness.cli.execCommands();
        return commands.subList(from, commands.size()).stream().map(line -> line.startsWith(
            "export --exportRepositoryPath") ? "export" : line.contains("--importRepositoryPath")
                ? "import" : line).toList();
    }

    @Test
    void aConfirmedImportOfTwentyFilesInThreeFoldersStaysWithinTheBudget() throws IOException {
        harness = ImportHarness.grpA(temp);
        List<String> created = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        for (String folder : ORDER) {
            for (int i = 1; i <= COUNTS.get(folder); i++) {
                String name = name(folder, i);
                String path = full(name);
                if (i <= ON_NODE) {
                    harness.repositoryFile(name, content(name, "1"), "application/xml");
                    node.putRepositoryFile(path, content(name, "1"));
                    modified.add(path);
                } else {
                    created.add(path);
                }
            }
        }
        harness.exports.history.commitAll("export dev/jdoe: repository",
            Map.of(VersionHistoryPort.SERVER_STATE, ImportHarness.DEV.group().value()));
        for (String folder : ORDER) {
            for (int i = 1; i <= COUNTS.get(folder); i++) {
                String name = name(folder, i);
                harness.write(file(full(name)), content(name, "2"));
            }
        }
        ImportService service = harness.repositoryWrite(ImportHarness.DEV, true).service();
        ImportService.ImportRequest request = harness.repositoryPaths("SC-001", "xsd", "xsl",
            "wsdl");
        assertThat(created.size() + modified.size()).isEqualTo(20);

        exports();
        Timed preview = timed(service, request);

        harness.cli.verifyComplete();
        assertThat(calls(0)).containsExactly("export", "export", "export");
        assertThat(preview.response()).isInstanceOf(ImportService.Response.Challenge.class);
        ImportPreview shown = ((ImportService.Response.Challenge) preview.response()).preview();
        assertThat(shown.create()).containsExactlyInAnyOrderElementsOf(created);
        assertThat(shown.modify()).containsExactlyInAnyOrderElementsOf(modified);

        int before = harness.cli.execCommands().size();
        exports(); // the node read again
        harness.importRepositoryApplied(node);
        harness.cli.then(spec -> harness.clock.advance(IMPORT));
        exports(); // the verification
        Timed confirmed = timed(service, ImportHarness.confirmed(request,
            shown.confirmationCode()));

        harness.cli.verifyComplete();
        assertThat(calls(before)).containsExactly("export", "export", "export", "import",
            "export", "export", "export");
        assertThat(confirmed.response()).isInstanceOf(ImportService.Response.Completed.class);
        WriteOutcome outcome = ((ImportService.Response.Completed) confirmed.response())
            .outcome();
        assertThat(outcome.outcome()).as(outcome.toString())
            .isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.created()).containsExactlyInAnyOrderElementsOf(created);
        assertThat(outcome.modified()).containsExactlyInAnyOrderElementsOf(modified);
        assertThat(node.repositoryImports).hasSize(1);

        System.out.printf("SC-001 harness timing (20 files, 3 folders): preview %d ms"
            + " (StartCLI %d ms simulated, own %d ms), confirmed import %d ms (StartCLI %d ms"
            + " simulated, own %d ms)%n", preview.total().toMillis(),
            preview.startCli().toMillis(), preview.own().toMillis(),
            confirmed.total().toMillis(), confirmed.startCli().toMillis(),
            confirmed.own().toMillis());
        assertThat(confirmed.total()).isLessThan(BUDGET);
    }
}
