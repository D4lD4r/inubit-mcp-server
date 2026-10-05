package de.dadecker.inubit.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.security.IdentifierLists.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Location and parsing of the local identifier lists (denylist, neutralize map, synthetic map)
 * behind {@link NoCustomerIdentifiersTest}. Fictitious values only.
 */
class IdentifierListsTest {

    @TempDir
    Path temp;

    private Path root() throws IOException {
        return Files.createDirectories(temp.resolve("repo"));
    }

    private Path defaults() throws IOException {
        return Files.createDirectories(temp.resolve("home/.config/inubit-mcp"));
    }

    // --- lookup order: environment variable > repository file > default directory -----------

    @Test
    void theEnvironmentVariableWinsOverTheRepositoryFileAndTheDefaultDirectory()
            throws IOException {
        for (Kind kind : Kind.values()) {
            Path fromEnv = Files.writeString(temp.resolve("env-" + kind.label()), "x\n");
            Files.writeString(root().resolve(kind.repositoryFile()), "x\n");
            Files.writeString(defaults().resolve(kind.defaultFile()), "x\n");

            assertThat(IdentifierLists.locate(kind,
                    Map.of(kind.environmentVariable(), fromEnv.toString()), root(), defaults()))
                .as(kind.label()).contains(fromEnv);
        }
    }

    @Test
    void theRepositoryFileWinsOverTheDefaultDirectory() throws IOException {
        for (Kind kind : Kind.values()) {
            Path local = Files.writeString(root().resolve(kind.repositoryFile()), "x\n");
            Files.writeString(defaults().resolve(kind.defaultFile()), "x\n");

            assertThat(IdentifierLists.locate(kind, Map.of(), root(), defaults()))
                .as(kind.label()).contains(local);
            assertThat(IdentifierLists.locate(kind,
                    Map.of(kind.environmentVariable(), "  "), root(), defaults()))
                .as(kind.label() + " with a blank variable").contains(local);
        }
    }

    @Test
    void theDefaultDirectoryIsUsedLast() throws IOException {
        for (Kind kind : Kind.values()) {
            Path fallback = Files.writeString(defaults().resolve(kind.defaultFile()), "x\n");

            assertThat(IdentifierLists.locate(kind, Map.of(), root(), defaults()))
                .as(kind.label()).contains(fallback);
        }
    }

    @Test
    void theListsHaveTheirDocumentedNames() {
        assertThat(List.of(Kind.values())).extracting(Kind::label, Kind::environmentVariable,
                Kind::repositoryFile, Kind::defaultFile)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("denylist", "INUBIT_MCP_DENYLIST",
                    ".denylist", "denylist.txt"),
                org.assertj.core.groups.Tuple.tuple("neutralize-map",
                    "INUBIT_MCP_NEUTRALIZE_MAP", ".neutralize-map", "neutralize-map"),
                org.assertj.core.groups.Tuple.tuple("synthetic-map", "INUBIT_MCP_SYNTHETIC_MAP",
                    ".synthetic-map", "synthetic-map"));
    }

    @Test
    void aVariableNamingAMissingFileIsAnErrorNotAFallback() throws IOException {
        Files.writeString(root().resolve(".synthetic-map"), "x\ty\n");
        Path missing = temp.resolve("missing-map");

        assertThatThrownBy(() -> IdentifierLists.locate(Kind.SYNTHETIC_MAP,
                Map.of("INUBIT_MCP_SYNTHETIC_MAP", missing.toString()), root(), defaults()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("INUBIT_MCP_SYNTHETIC_MAP")
            .hasMessageContaining(missing.toString());
    }

    @Test
    void withoutAnyFileNothingIsLocated() throws IOException {
        for (Kind kind : Kind.values()) {
            assertThat(IdentifierLists.locate(kind, Map.of(), root(), defaults())).isEmpty();
        }
    }

    // --- default directory --------------------------------------------------------------------

    @Test
    void theDefaultDirectoryFollowsHomeOnUnixAndAppDataOnWindows() {
        assertThat(IdentifierLists.defaultDirectory(Map.of("HOME", "/home/acme"), "Linux",
                "/ignored"))
            .isEqualTo(Path.of("/home/acme", ".config", "inubit-mcp"));
        assertThat(IdentifierLists.defaultDirectory(Map.of(), "Mac OS X", "/Users/acme"))
            .isEqualTo(Path.of("/Users/acme", ".config", "inubit-mcp"));
        assertThat(IdentifierLists.defaultDirectory(
                Map.of("APPDATA", "C:\\Users\\acme\\AppData\\Roaming", "HOME", "/ignored"),
                "Windows 11", "C:\\Users\\acme"))
            .isEqualTo(Path.of("C:\\Users\\acme\\AppData\\Roaming", "inubit-mcp"));
        assertThat(IdentifierLists.defaultDirectory(Map.of(), "Windows 11", "C:\\Users\\acme"))
            .isEqualTo(Path.of("C:\\Users\\acme", "AppData", "Roaming", "inubit-mcp"));
    }

    // --- all lists together --------------------------------------------------------------------

    @Test
    void theSkipMessageNamesEveryMissingListAndWhereItWasLookedFor() throws IOException {
        IdentifierLists.Located located =
            IdentifierLists.locateAll(Map.of(), root(), defaults());

        assertThat(located.anyList()).isFalse();
        assertThat(located.missing()).containsExactly(
            Kind.DENYLIST, Kind.NEUTRALIZE_MAP, Kind.SYNTHETIC_MAP);
        assertThat(located.skipMessage())
            .contains("denylist", "INUBIT_MCP_DENYLIST", ".denylist", "denylist.txt")
            .contains("neutralize-map", "INUBIT_MCP_NEUTRALIZE_MAP", ".neutralize-map")
            .contains("synthetic-map", "INUBIT_MCP_SYNTHETIC_MAP", ".synthetic-map")
            .contains(defaults().toString())
            .contains("skipped");
    }

    @Test
    void missingSingleListsAreFineAndTheSummaryNamesCountsOnly() throws IOException {
        Files.writeString(defaults().resolve("synthetic-map"),
            "# fictitious\n(?<![\\w-])Globex\\-Billing(?![\\w-])\tWorkflow-0001\n");
        Files.writeString(root().resolve(".denylist"), "(?i)foocorp\nacme-secret\n");

        IdentifierLists.Located located =
            IdentifierLists.locateAll(Map.of(), root(), defaults());
        IdentifierScanner scanner = located.load();

        assertThat(located.anyList()).isTrue();
        assertThat(located.missing()).containsExactly(Kind.NEUTRALIZE_MAP);
        assertThat(scanner.ruleCount()).isEqualTo(3);
        assertThat(located.summary(scanner))
            .contains("denylist: 2 rules", "synthetic-map: 1 rule", "neutralize-map: missing")
            .doesNotContainIgnoringCase("foocorp").doesNotContain("Globex");
    }

    @Test
    void theAllowlistIsLocatedLikeTheLists() throws IOException {
        Path fallback = Files.writeString(defaults().resolve("identifier-allowlist"), "Initech\n");
        assertThat(IdentifierLists.locateAll(Map.of(), root(), defaults()).allowlist())
            .contains(fallback);

        Path local = Files.writeString(root().resolve(".identifier-allowlist"), "Initech\n");
        assertThat(IdentifierLists.locateAll(Map.of(), root(), defaults()).allowlist())
            .contains(local);

        Path fromEnv = Files.writeString(temp.resolve("allow"), "Initech\n");
        assertThat(IdentifierLists.locateAll(
                Map.of("INUBIT_MCP_IDENTIFIER_ALLOWLIST", fromEnv.toString()), root(),
                defaults()).allowlist())
            .contains(fromEnv);
    }

    // --- parsing ------------------------------------------------------------------------------

    @Test
    void mapsIgnoreCommentsBlankLinesAndTheReplacement() {
        IdentifierLists.Parsed map = IdentifierLists.parse(Kind.NEUTRALIZE_MAP, """
            # fictitious customer
            (?i)foocorp\tacme

              # indented comment
            \\bFC-\t(unbalanced replacement is literal text
            \r
            globex\\.invalid\tinubit-dev-1.example.test\r
            """);

        assertThat(map.rules()).extracting(IdentifierLists.Rule::number,
                IdentifierLists.Rule::line)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 2),
                org.assertj.core.groups.Tuple.tuple(2, 5),
                org.assertj.core.groups.Tuple.tuple(3, 7));
        assertThat(map.rules().get(0).pattern().matcher("FooCorp").find()).isTrue();
        assertThat(map.rules().get(1).pattern().matcher("diagram FC-Utils").find()).isTrue();
        assertThat(map.rules().get(2).pattern().matcher("globex.invalid").find()).isTrue();
        assertThat(map.rules().get(2).pattern().matcher("globex.invalid\r").find()).isTrue();
    }

    @Test
    void aMapRuleWithoutATabIsRejectedByRuleAndLineNumberWithoutItsText() {
        assertThatThrownBy(() -> IdentifierLists.parse(Kind.SYNTHETIC_MAP,
                "# c\nfoo\tbar\n\nno-tab-secret\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("synthetic-map rule 2 (line 4)")
            .hasMessageContaining("TAB")
            .hasMessageNotContaining("secret");
    }

    @Test
    void aPythonOnlyConstructIsRejectedByRuleAndLineNumberWithoutItsText() {
        assertThatThrownBy(() -> IdentifierLists.parse(Kind.NEUTRALIZE_MAP,
                "ok\tx\n# c\n(secret)?(?(1)acme|globex)\ty\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("neutralize-map rule 2 (line 3)")
            .hasMessageNotContaining("secret").hasMessageNotContaining("acme");
    }

    @Test
    void anInvalidMapRegexIsRejectedByRuleAndLineNumberWithoutItsText() {
        assertThatThrownBy(() -> IdentifierLists.parse(Kind.SYNTHETIC_MAP,
                "\n(unclosed-secret\tx\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("synthetic-map rule 1 (line 2)")
            .hasMessageNotContaining("unclosed-secret");
    }

    @Test
    void anInvalidDenylistPatternIsRejectedByRuleAndLineNumberWithoutItsText() {
        assertThatThrownBy(() -> IdentifierLists.parse(Kind.DENYLIST,
                "# c\nok\n(unclosed-secret\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("denylist rule 2 (line 3)")
            .hasMessageNotContaining("unclosed-secret");
    }

    @Test
    void denylistLinesAreJavaPatternsAndMayContainTabs() {
        IdentifierLists.Parsed denylist = IdentifierLists.parse(Kind.DENYLIST,
            "acme\\p{Alpha}+\nfoo\tbar\n");

        assertThat(denylist.rules().get(0).pattern().matcher("acmeX").find()).isTrue();
        assertThat(denylist.rules().get(1).pattern().matcher("foo\tbar").find()).isTrue();
    }

    @Test
    void aListWithoutRulesIsRejected() {
        for (Kind kind : Kind.values()) {
            assertThatThrownBy(() -> IdentifierLists.parse(kind, "# only comments\n\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(kind.label())
                .hasMessageContaining("no rules");
        }
    }

    @Test
    void theAllowlistHoldsExactValues() {
        assertThat(IdentifierLists.parseAllowlist("# generic words\nInitech\n\n  Hooli  \r\nx.y\n"))
            .containsExactlyInAnyOrder("Initech", "Hooli", "x.y");
    }
}
