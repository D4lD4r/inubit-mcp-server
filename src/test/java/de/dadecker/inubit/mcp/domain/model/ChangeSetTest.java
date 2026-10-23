package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChangeSetTest {

    private static final GroupId DEV = new GroupId("dev");
    private static final String OWNER = "jdoe";
    private static final ImportScope SCOPE =
        ImportScope.repositoryPaths(DEV, OWNER, List.of("xsd"));
    private static final String WORKFLOW_PATH = "dev/jdoe/workflows/Orders/Route.xml";
    private static final String MODULE_PATH = "dev/jdoe/modules/XSLT Converter/Map/module.xml";
    private static final String NEW_FILE_PATH = "dev/jdoe/repository/Root/jdoe/xsd/new.xsd";
    private static final String OLD_FILE_PATH = "dev/jdoe/repository/Root/jdoe/xsd/old.xsd";

    private static final ChangedArtifact WORKFLOW = new ChangedArtifact(
        ArtifactRef.workflow(DEV, OWNER, "Orders", "Route"), ChangedArtifact.Kind.MODIFIED,
        List.of(WORKFLOW_PATH), Optional.of("c1"));
    private static final ChangedArtifact MODULE = new ChangedArtifact(
        ArtifactRef.module(DEV, OWNER, "XSLT Converter", "Map"), ChangedArtifact.Kind.NEW,
        List.of(MODULE_PATH), Optional.empty());
    private static final ChangedArtifact NEW_FILE = file("/Root/jdoe/xsd/new.xsd",
        ChangedArtifact.Kind.NEW, NEW_FILE_PATH, Optional.empty());
    private static final ChangedArtifact OLD_FILE = file("/Root/jdoe/xsd/old.xsd",
        ChangedArtifact.Kind.MODIFIED, OLD_FILE_PATH, Optional.of("c2"));

    @Test
    void aChangeSetOfRepositoryFilesOnlyIsNotEmpty() {
        ChangeSet changes = repository(List.of(NEW_FILE));

        assertThat(changes.isEmpty()).isFalse();
        assertThat(repository(List.of()).isEmpty()).isTrue();
    }

    @Test
    void artifactsAndPathsListRepositoryFilesAfterWorkflowsAndModules() {
        ChangeSet changes = new ChangeSet(SCOPE, "c0", List.of(WORKFLOW), List.of(MODULE),
            List.of(NEW_FILE, OLD_FILE), List.of(), List.of(), List.of());

        assertThat(changes.repository()).containsExactly(NEW_FILE, OLD_FILE);
        assertThat(changes.artifacts()).containsExactly(WORKFLOW, MODULE, NEW_FILE, OLD_FILE);
        assertThat(changes.paths())
            .containsExactly(WORKFLOW_PATH, MODULE_PATH, NEW_FILE_PATH, OLD_FILE_PATH);
    }

    @Test
    void createdModifiedAndExistingIncludeRepositoryFiles() {
        ChangedArtifact existing = file("/Root/jdoe/xsd/again.xsd", ChangedArtifact.Kind.NEW,
            "dev/jdoe/repository/Root/jdoe/xsd/again.xsd", Optional.empty()).existing();

        ChangeSet changes = repository(List.of(NEW_FILE, OLD_FILE, existing));

        assertThat(changes.created()).containsExactly("/Root/jdoe/xsd/new.xsd");
        assertThat(changes.modified())
            .containsExactly("/Root/jdoe/xsd/old.xsd", "/Root/jdoe/xsd/again.xsd");
        assertThat(changes.existing()).containsExactly("/Root/jdoe/xsd/again.xsd");
    }

    @Test
    void theConstructorsWithoutRepositoryHaveNoRepositoryFiles() {
        assertThat(new ChangeSet(SCOPE, "c0", List.of(WORKFLOW), List.of(), List.of())
            .repository()).isEmpty();
        assertThat(new ChangeSet(SCOPE, "c0", List.of(WORKFLOW), List.of(), List.of(),
            List.of(), List.of()).repository()).isEmpty();
    }

    @Test
    void refusesARepositoryEntryThatIsNoRepositoryFile() {
        assertThatThrownBy(() -> repository(List.of(MODULE)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesARepositoryFileWhoseNameIsNoFullPathOfItsOwner() {
        assertThatThrownBy(() -> repository(List.of(file("Root/jdoe/xsd/new.xsd",
            ChangedArtifact.Kind.NEW, NEW_FILE_PATH, Optional.empty()))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository(List.of(file("/Root/other/xsd/new.xsd",
            ChangedArtifact.Kind.NEW, "dev/jdoe/repository/Root/other/xsd/new.xsd",
            Optional.empty())))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesARepositoryFileWithoutExactlyOneMatchingRepositoryPath() {
        assertThatThrownBy(() -> repository(List.of(new ChangedArtifact(
            ArtifactRef.repositoryFile(DEV, OWNER, "/Root/jdoe/xsd/new.xsd"),
            ChangedArtifact.Kind.NEW, List.of(), Optional.empty()))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository(List.of(new ChangedArtifact(
            ArtifactRef.repositoryFile(DEV, OWNER, "/Root/jdoe/xsd/new.xsd"),
            ChangedArtifact.Kind.NEW, List.of(NEW_FILE_PATH, OLD_FILE_PATH),
            Optional.empty())))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository(List.of(file("/Root/jdoe/xsd/new.xsd",
            ChangedArtifact.Kind.NEW, OLD_FILE_PATH, Optional.empty()))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository(List.of(file("/Root/jdoe/xsd/new.xsd",
            ChangedArtifact.Kind.NEW, MODULE_PATH, Optional.empty()))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static ChangeSet repository(List<ChangedArtifact> files) {
        return new ChangeSet(SCOPE, "c0", List.of(), List.of(), files, List.of(), List.of(),
            List.of());
    }

    private static ChangedArtifact file(String name, ChangedArtifact.Kind kind, String path,
        Optional<String> base) {
        return new ChangedArtifact(ArtifactRef.repositoryFile(DEV, OWNER, name), kind,
            List.of(path), base);
    }
}
