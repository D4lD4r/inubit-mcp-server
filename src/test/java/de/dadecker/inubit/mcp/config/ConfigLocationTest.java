package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Feature 015 T014 (research R-9, data-model.md → ConfigLocation): the configuration roots an
 * archive target must stay out of, the default configuration directory first.
 */
class ConfigLocationTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getRoot();
    private static final Path DEFAULT = ROOT.resolve("home/test/.config/inubit-mcp");

    @Test
    void theRootsAreTheDefaultDirectoryAndTheFolderOfTheConfigurationFile() {
        ConfigLocation location = new ConfigLocation(DEFAULT,
            ROOT.resolve("home/test/elsewhere/profile.yaml"));

        assertThat(location.roots()).containsExactly(DEFAULT, ROOT.resolve("home/test/elsewhere"));
    }

    @Test
    void aFileInTheDefaultDirectoryGivesOneRoot() {
        ConfigLocation location = new ConfigLocation(DEFAULT, DEFAULT.resolve("acme.yaml"));

        assertThat(location.roots()).containsExactly(DEFAULT);
    }

    @Test
    void theSameFolderWrittenDifferentlyGivesOneRoot() {
        ConfigLocation location = new ConfigLocation(DEFAULT,
            DEFAULT.resolve(".").resolve("acme.yaml"));

        assertThat(location.roots()).containsExactly(DEFAULT);
    }

    @Test
    void aRelativeSourceIsTakenAsAbsolute() {
        ConfigLocation location = new ConfigLocation(DEFAULT, Path.of("profiles/acme.yaml"));

        assertThat(location.roots()).containsExactly(DEFAULT,
            Path.of("profiles/acme.yaml").toAbsolutePath().getParent());
        assertThat(location.roots().get(1)).isAbsolute();
    }

    @Test
    void bothPathsAreRequired() {
        assertThatNullPointerException().isThrownBy(
            () -> new ConfigLocation(null, DEFAULT.resolve("acme.yaml")));
        assertThatNullPointerException().isThrownBy(() -> new ConfigLocation(DEFAULT, null));
    }
}
