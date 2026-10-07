package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The reason check of the verification (004 research D-11; 005 live acceptance): INUBIT keeps the
 * archive's comment of an update and of a created workflow, but puts one more
 * {@code DefaultCommitCommentImport###} in front of the comment of a created module.
 */
class CheckinCommentReasonTest {

    private static final String SUFFIX = "@@@Deploying User: jdoe@@@Server: inubit-dev-1.example.test"
        + "@@@Version: 1@@@Export/Deployment: 07.10.2026 15:08:30@@@";

    @Test
    void anUpdateOrACreatedWorkflowCarriesTheReasonAfterOnePrefix() {
        assertThat(ImportService.carriesReason(
            "DefaultCommitCommentImport###deploy TAG-01 from dev#########" + SUFFIX,
            "deploy TAG-01 from dev")).isTrue();
    }

    @Test
    void aCreatedModuleCarriesTheReasonAfterTheExtraPrefixOfInubit() {
        assertThat(ImportService.carriesReason(
            "DefaultCommitCommentImport###DefaultCommitCommentImport###deploy TAG-01 from dev######"
                + SUFFIX, "deploy TAG-01 from dev")).isTrue();
    }

    @Test
    void anotherReasonOrAnOlderSegmentIsNotTheReason() {
        assertThat(ImportService.carriesReason(
            "DefaultCommitCommentImport###deploy TAG-02 from dev###" + SUFFIX,
            "deploy TAG-01 from dev")).isFalse();
        assertThat(ImportService.carriesReason(
            "DefaultCommitCommentImport###deploy TAG-01 from dev###older comment###" + SUFFIX,
            "deploy TAG-01 from dev")).isFalse();
        assertThat(ImportService.carriesReason(
            "DefaultCommitCommentImport###DefaultCommitCommentImport###DefaultCommitCommentImport###"
                + "deploy TAG-01 from dev###" + SUFFIX, "deploy TAG-01 from dev")).isFalse();
    }
}
