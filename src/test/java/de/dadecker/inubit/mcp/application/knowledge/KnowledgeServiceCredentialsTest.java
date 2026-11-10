package de.dadecker.inubit.mcp.application.knowledge;

import static de.dadecker.inubit.mcp.application.knowledge.KnowledgeFixture.BOUND;
import static de.dadecker.inubit.mcp.application.knowledge.KnowledgeFixture.NEWER;
import static de.dadecker.inubit.mcp.application.knowledge.KnowledgeFixture.all;
import static de.dadecker.inubit.mcp.application.knowledge.KnowledgeFixture.error;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.knowledge.NoteSelector.ByName;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.testing.FakeConfiguredSecrets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 019 T020: notes are shown only once every configured credential of the profile is known
 * (research D-7, option A: fail closed).
 */
class KnowledgeServiceCredentialsTest {

    private static final String HELD = "S3cond-held-value";

    @TempDir
    Path temp;

    private KnowledgeFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new KnowledgeFixture(temp);
        fixture.note("a.md", "the value " + HELD + " in a note", NEWER);
    }

    private static Search all20() {
        return new Search(Optional.empty(), Optional.empty(), 20, 0);
    }

    @Test
    void aWaitingReadRefusesSearchAndRead() {
        FakeConfiguredSecrets secrets = FakeConfiguredSecrets.waiting("dev", "credential store");
        KnowledgeService service = fixture.service(secrets);

        ToolError search = error(ErrorCode.PRECONDITION_FAILED, () -> service.search(all20()));
        ToolError read = error(ErrorCode.PRECONDITION_FAILED,
            () -> service.read(new ByName("a"), 0));

        assertThat(all(search)).contains("dev", "credential store: waiting",
            "have not been read yet", "access dialog").doesNotContain("the value");
        assertThat(all(read)).isEqualTo(all(search));
        assertThat(secrets.prepared()).containsExactly(BOUND, BOUND);
    }

    @Test
    void aFailedReadNamesItsKind() {
        KnowledgeService service = fixture.service(
            FakeConfiguredSecrets.failed("prod", "AWS Parameter Store", "unreachable"));

        ToolError error = error(ErrorCode.PRECONDITION_FAILED, () -> service.search(all20()));

        assertThat(all(error)).contains("prod", "AWS Parameter Store: unreachable");
    }

    @Test
    void oncePreparedTheHeldValuesAreMasked() throws IOException {
        FakeConfiguredSecrets secrets = FakeConfiguredSecrets.waiting("dev", "credential store");
        KnowledgeService service = fixture.service(secrets);
        error(ErrorCode.PRECONDITION_FAILED, () -> service.search(all20()));

        secrets.hold(HELD);

        assertThat(service.read(new ByName("a"), 0).content()).isEqualTo("the value *** in a note");
        assertThat(service.search(all20()).hits().getFirst().masked()).isEqualTo(1);
    }

    @Test
    void nothingConfiguredIsReady() throws IOException {
        KnowledgeService service = fixture.service(FakeConfiguredSecrets.nothingConfigured());

        assertThat(service.read(new ByName("a"), 0).content()).contains(HELD);
    }
}
