package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.RepositoryReference.Kind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepositoryClosureTest {

    private static final GroupId DEV = new GroupId("dev");
    private static final String OWNER = "jdoe";
    private static final WorkspacePath WORKFLOW =
        WorkspacePath.workflow(DEV, OWNER, "Orders", "Receive");
    private static final WorkspacePath MODULE =
        WorkspacePath.module(DEV, OWNER, "XSLT", "Map");

    /** The workspace files with their references; a path not in the map is missing. */
    private final Map<String, List<RepositoryReference>> files = new HashMap<>();
    private final List<String> lookups = new ArrayList<>();

    private final Function<String, Optional<List<RepositoryReference>>> referencesOf = path -> {
        lookups.add(path);
        return Optional.ofNullable(files.get(path));
    };

    @Test
    void followsReferencesTransitivelyBelowTheOwnersRoot() {
        file("/Root/jdoe/xsl/main.xsl", "/Root/jdoe/xsl/common.xsl");
        file("/Root/jdoe/xsl/common.xsl", "/Root/jdoe/xsd/msg.xsd");
        file("/Root/jdoe/xsd/msg.xsd", "/Root/jdoe/xsd/types.xsd");
        file("/Root/jdoe/xsd/types.xsd");

        RepositoryClosure closure = RepositoryClosure.of(OWNER,
            List.of(uri(WORKFLOW, "/Root/jdoe/xsl/main.xsl")), referencesOf);

        assertThat(closure.included()).containsExactly("/Root/jdoe/xsd/msg.xsd",
            "/Root/jdoe/xsd/types.xsd", "/Root/jdoe/xsl/common.xsl", "/Root/jdoe/xsl/main.xsl");
        assertThat(closure.outsideOwner()).isEmpty();
    }

    @Test
    void recordsWhoReferencesEachIncludedPath() {
        file("/Root/jdoe/xsl/main.xsl", "/Root/jdoe/xsd/msg.xsd");
        file("/Root/jdoe/xsd/msg.xsd");

        RepositoryClosure closure = RepositoryClosure.of(OWNER, List.of(
            uri(WORKFLOW, "/Root/jdoe/xsl/main.xsl"),
            uri(MODULE, "/Root/jdoe/xsd/msg.xsd")), referencesOf);

        assertThat(closure.referencedBy()).containsOnlyKeys(
            "/Root/jdoe/xsl/main.xsl", "/Root/jdoe/xsd/msg.xsd");
        assertThat(closure.referencedBy().get("/Root/jdoe/xsl/main.xsl"))
            .containsExactly("dev/jdoe/workflows/Orders/Receive.xml");
        assertThat(closure.referencedBy().get("/Root/jdoe/xsd/msg.xsd")).containsExactly(
            "dev/jdoe/modules/XSLT/Map/module.xml",
            "dev/jdoe/repository/Root/jdoe/xsl/main.xsl");
    }

    @Test
    void visitsEachPathOnceSoACycleEnds() {
        file("/Root/jdoe/a.xsd", "/Root/jdoe/b.xsd");
        file("/Root/jdoe/b.xsd", "/Root/jdoe/a.xsd", "/Root/jdoe/b.xsd");

        RepositoryClosure closure = RepositoryClosure.of(OWNER, List.of(
            uri(WORKFLOW, "/Root/jdoe/a.xsd"), uri(MODULE, "/Root/jdoe/a.xsd")), referencesOf);

        assertThat(closure.included()).containsExactly("/Root/jdoe/a.xsd", "/Root/jdoe/b.xsd");
        assertThat(lookups).containsExactly("/Root/jdoe/a.xsd", "/Root/jdoe/b.xsd");
        assertThat(closure.referencedBy().get("/Root/jdoe/a.xsd")).containsExactly(
            "dev/jdoe/modules/XSLT/Map/module.xml",
            "dev/jdoe/repository/Root/jdoe/b.xsd",
            "dev/jdoe/workflows/Orders/Receive.xml");
        assertThat(closure.referencedBy().get("/Root/jdoe/b.xsd")).containsExactly(
            "dev/jdoe/repository/Root/jdoe/a.xsd", "dev/jdoe/repository/Root/jdoe/b.xsd");
    }

    @Test
    void listsReferencesIntoAnotherOwnersRootWithoutFollowingThem() {
        file("/Root/jdoe/xsl/main.xsl", "/Root/shared/xsl/lib.xsl");
        file("/Root/shared/xsl/lib.xsl", "/Root/jdoe/never.xsd");
        RepositoryReference intoOther = uri(MODULE, "/Root/shared/xsd/common.xsd");

        RepositoryClosure closure = RepositoryClosure.of(OWNER, List.of(
            uri(WORKFLOW, "/Root/jdoe/xsl/main.xsl"), intoOther), referencesOf);

        assertThat(closure.included()).containsExactly("/Root/jdoe/xsl/main.xsl");
        assertThat(closure.outsideOwner()).containsExactly(intoOther,
            relative("/Root/jdoe/xsl/main.xsl", "/Root/shared/xsl/lib.xsl"));
        assertThat(closure.referencedBy()).containsOnlyKeys("/Root/jdoe/xsl/main.xsl");
        assertThat(lookups).containsExactly("/Root/jdoe/xsl/main.xsl");
    }

    @Test
    void listsAnOutsideReferenceOnceEvenWhenItIsReachedTwice() {
        file("/Root/jdoe/a.xsd", "/Root/other/x.xsd");
        RepositoryReference outside = uri(WORKFLOW, "/Root/other/x.xsd");

        RepositoryClosure closure = RepositoryClosure.of(OWNER,
            List.of(outside, outside, uri(WORKFLOW, "/Root/jdoe/a.xsd")), referencesOf);

        assertThat(closure.outsideOwner()).containsExactly(outside,
            relative("/Root/jdoe/a.xsd", "/Root/other/x.xsd"));
    }

    @Test
    void keepsAMissingFileAsANameWithoutFollowingIt() {
        file("/Root/jdoe/xsl/main.xsl", "/Root/jdoe/xsd/gone.xsd");

        RepositoryClosure closure = RepositoryClosure.of(OWNER,
            List.of(uri(WORKFLOW, "/Root/jdoe/xsl/main.xsl")), referencesOf);

        assertThat(closure.included())
            .containsExactly("/Root/jdoe/xsd/gone.xsd", "/Root/jdoe/xsl/main.xsl");
        assertThat(closure.referencedBy().get("/Root/jdoe/xsd/gone.xsd"))
            .containsExactly("dev/jdoe/repository/Root/jdoe/xsl/main.xsl");
        assertThat(lookups).containsExactly("/Root/jdoe/xsl/main.xsl", "/Root/jdoe/xsd/gone.xsd");
    }

    /**
     * A path counts as inside only in canonical form: {@code /Root/<owner>/} plus non-empty
     * segments other than {@code .} and {@code ..}. The resolvers refuse every other form, and
     * {@code /Root/jdoe/../other/x.xsd} names another owner's file, so such a reference is listed
     * as outside and never followed.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/Root/jdoe/../other/x.xsd", "/Root/jdoe/xsd/../../other/x.xsd", "/Root/jdoe/./x.xsd",
        "/Root/jdoe//x.xsd", "/Root/jdoe/x.xsd/", "/Root/jdoe/..", "/Root/jdoe/", "/Root/jdoe",
        "/outside.xsl", "/Root", "/", "", "Root/jdoe/x.xsd", "/root/jdoe/x.xsd",
        "/Root/JDOE/x.xsd", "/Root/jdoex/x.xsd", "//Root/jdoe/x.xsd"})
    void neverCountsANonCanonicalOrForeignPathAsInsideTheOwner(String path) {
        files.put(path, List.of(uri(WORKFLOW, "/Root/jdoe/never.xsd")));
        RepositoryReference reference = uri(WORKFLOW, path);

        RepositoryClosure closure = RepositoryClosure.of(OWNER, List.of(reference), referencesOf);

        assertThat(closure.included()).isEmpty();
        assertThat(closure.referencedBy()).isEmpty();
        assertThat(closure.outsideOwner()).containsExactly(reference);
        assertThat(lookups).isEmpty();
    }

    @Test
    void anEmptyStartGivesAnEmptyClosure() {
        RepositoryClosure closure = RepositoryClosure.of(OWNER, List.of(), referencesOf);

        assertThat(closure.included()).isEmpty();
        assertThat(closure.outsideOwner()).isEmpty();
        assertThat(closure.referencedBy()).isEmpty();
    }

    private void file(String path, String... references) {
        List<RepositoryReference> list = new ArrayList<>();
        for (String to : references) {
            list.add(relative(path, to));
        }
        files.put(path, list);
    }

    private static RepositoryReference uri(WorkspacePath from, String to) {
        return new RepositoryReference(from, to, Kind.URI);
    }

    private static RepositoryReference relative(String fromPath, String to) {
        return new RepositoryReference(WorkspacePath.repository(DEV, OWNER, fromPath), to,
            Kind.RELATIVE);
    }
}
