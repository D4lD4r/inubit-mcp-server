package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImportScopeTest {

    private static final GroupId DEV = new GroupId("dev");
    private static final String OWNER = "jdoe";
    private static final ImportScope.Module MAP = new ImportScope.Module("Map", Optional.empty());

    @Test
    void aRepositoryScopeHoldsItsPaths() {
        ImportScope scope = ImportScope.repositoryPaths(DEV, OWNER,
            List.of("schemas/order.xsd", "xsl"));

        assertThat(scope.repositoryPaths()).containsExactly("schemas/order.xsd", "xsl");
        assertThat(scope.diagramGroup()).isEmpty();
        assertThat(scope.modules()).isEmpty();
    }

    @Test
    void describesEachAlternative() {
        assertThat(ImportScope.repositoryPaths(DEV, OWNER, List.of("schemas/order.xsd", "xsl"))
            .describe()).isEqualTo("repository paths schemas/order.xsd, xsl");
        assertThat(ImportScope.diagramGroup(DEV, OWNER, "Orders").describe())
            .isEqualTo("diagram group Orders");
        assertThat(ImportScope.modules(DEV, OWNER, List.of(MAP)).describe())
            .isEqualTo("modules Map");
    }

    @Test
    void theOtherAlternativesHaveNoRepositoryPaths() {
        assertThat(ImportScope.diagramGroup(DEV, OWNER, "Orders").repositoryPaths()).isEmpty();
        assertThat(ImportScope.modules(DEV, OWNER, List.of(MAP)).repositoryPaths()).isEmpty();
    }

    @Test
    void requiresExactlyOneAlternative() {
        assertThatThrownBy(() -> new ImportScope(DEV, OWNER, Optional.empty(), List.of(),
            List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImportScope(DEV, OWNER, Optional.of("Orders"), List.of(),
            List.of("xsl"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImportScope(DEV, OWNER, Optional.empty(), List.of(MAP),
            List.of("xsl"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImportScope(DEV, OWNER, Optional.of("Orders"), List.of(MAP),
            List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsUpTo50Paths() {
        List<String> fifty = IntStream.rangeClosed(1, 50).mapToObj(i -> "f" + i + ".xsd").toList();

        assertThat(ImportScope.repositoryPaths(DEV, OWNER, fifty).repositoryPaths()).hasSize(50);
    }

    @Test
    void refusesMoreThan50Paths() {
        List<String> fiftyOne =
            IntStream.rangeClosed(1, 51).mapToObj(i -> "f" + i + ".xsd").toList();

        assertThatThrownBy(() -> ImportScope.repositoryPaths(DEV, OWNER, fiftyOne))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesDuplicatePaths() {
        assertThatThrownBy(() -> ImportScope.repositoryPaths(DEV, OWNER,
            List.of("xsl/a.xsl", "xsd", "xsl/a.xsl"))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "/Root/jdoe/a.xsd", "/a.xsd", "xsl//a.xsl", "xsl/",
        "../other/a.xsd", "xsl/../a.xsd", "./a.xsd", "xsl%2Fa.xsl", "-a.xsd"})
    void refusesPathsThatAreNotNormalizedRelativePaths(String path) {
        assertThatThrownBy(() -> ImportScope.repositoryPaths(DEV, OWNER, List.of("ok.xsd", path)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
