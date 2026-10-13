package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepositoryPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {"/Root/jdoe", "/Root/jdoe/xsd/order.xsd",
        "/Root/jdoe/My Files/a-b_c.xsl", "/Root/jdoe/.hidden", "/Root/jdoe/..x"})
    void passesWhatStartCliTakes(String path) {
        assertThat(RepositoryPaths.passable(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/Root", "/Root/", "Root/jdoe/a.xsd", "/Other/jdoe/a.xsd",
        "/Root/jdoe/../other/a.xsd", "/Root/jdoe/./a.xsd", "/Root/jdoe//a.xsd", "/Root/jdoe/a.xsd/",
        "/Root/jdoe/a%2e.xsd", "/Root/jdoe/-a.xsd", "/Root/jdoe/ a.xsd", "/Root/jdoe/a$.xsd",
        "/Root/jdoe/a\\b.xsd"})
    void refusesWhatStartCliCannotTake(String path) {
        assertThat(RepositoryPaths.passable(path)).isFalse();
    }

    @Test
    void refusesNullAndSegmentsOver200Characters() {
        assertThat(RepositoryPaths.passable(null)).isFalse();
        assertThat(RepositoryPaths.passable("/Root/jdoe/" + "a".repeat(200))).isTrue();
        assertThat(RepositoryPaths.passable("/Root/jdoe/" + "a".repeat(201))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"order.xsd", "schemas/order.xsd", "My Files/a-b_c.xsl",
        ".hidden/x.xml"})
    void acceptsNormalizedRelativePaths(String relative) {
        assertThat(RepositoryPaths.relativeValid(relative)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", ".", "..", "/schemas/order.xsd", "/Root/jdoe/order.xsd",
        "schemas//order.xsd", "schemas/", "schemas/./order.xsd", "../other/order.xsd",
        "schemas/../order.xsd", "schemas%2Forder.xsd", "%2e%2e/order.xsd", "schemas\\order.xsd",
        "-order.xsd", " order.xsd"})
    void refusesRelativePathsThatAreNotNormalized(String relative) {
        assertThat(RepositoryPaths.relativeValid(relative)).isFalse();
    }

    @Test
    void refusesNullAndSegmentsOver200CharactersInRelativePaths() {
        assertThat(RepositoryPaths.relativeValid(null)).isFalse();
        assertThat(RepositoryPaths.relativeValid("a".repeat(200))).isTrue();
        assertThat(RepositoryPaths.relativeValid("a/" + "a".repeat(201))).isFalse();
    }

    @Test
    void buildsTheFullPathBelowTheOwnersRoot() {
        assertThat(RepositoryPaths.full("jdoe", "schemas/order.xsd"))
            .isEqualTo("/Root/jdoe/schemas/order.xsd");
    }

    @Test
    void refusesToBuildAFullPathFromAnInvalidRelativePath() {
        assertThatThrownBy(() -> RepositoryPaths.full("jdoe", "../other/order.xsd"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryPaths.full("jdoe", "/Root/other/order.xsd"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToBuildAFullPathForAnOwnerThatIsNoSingleSegment() {
        // T010 review: the owner must not leave /Root/<owner> either
        for (String owner : new String[] {"a/b", "..", ".", "", "j(doe)", "jdoe/../other"}) {
            assertThatThrownBy(() -> RepositoryPaths.full(owner, "schemas/order.xsd"))
                .as(owner).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
