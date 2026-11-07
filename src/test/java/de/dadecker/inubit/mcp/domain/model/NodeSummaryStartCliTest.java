package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Feature 017 (FR-015, research D-18): the invariants of {@code list_nodes.startCli}. */
class NodeSummaryStartCliTest {

    @Test
    void aVersionEntryNamesItsDirectoryAndKey() {
        NodeSummary.StartCli startCli = new NodeSummary.StartCli(Optional.of("/opt/c90"),
            "version_entry", Optional.of("9.0"), "base_url", true);

        assertThat(startCli.clientDirectory()).contains("/opt/c90");
        assertThat(startCli.versionEntry()).contains("9.0");
        assertThat(startCli.finalChoice()).isTrue();
    }

    @Test
    void noneHasNoDirectoryAndTheServletAddress() {
        assertThat(NodeSummary.StartCli.none(false)).isEqualTo(new NodeSummary.StartCli(
            Optional.empty(), "none", Optional.empty(), "servlet_path", false));
    }

    @Test
    void unknownKindsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new NodeSummary.StartCli(
            Optional.of("/opt/c"), "cliHome", Optional.empty(), "servlet_path", true));
        assertThatIllegalArgumentException().isThrownBy(() -> new NodeSummary.StartCli(
            Optional.of("/opt/c"), "node", Optional.empty(), "url", true));
    }

    @Test
    void theDirectoryAndEntryMustMatchTheSource() {
        assertThatIllegalArgumentException().isThrownBy(() -> new NodeSummary.StartCli(
            Optional.empty(), "node", Optional.empty(), "servlet_path", true));
        assertThatIllegalArgumentException().isThrownBy(() -> new NodeSummary.StartCli(
            Optional.of("/opt/c"), "none", Optional.empty(), "servlet_path", true));
        assertThatIllegalArgumentException().isThrownBy(() -> new NodeSummary.StartCli(
            Optional.of("/opt/c"), "version_entry", Optional.empty(), "base_url", true));
        assertThatIllegalArgumentException().isThrownBy(() -> new NodeSummary.StartCli(
            Optional.of("/opt/c"), "defaults", Optional.of("9.0"), "base_url", true));
    }
}
