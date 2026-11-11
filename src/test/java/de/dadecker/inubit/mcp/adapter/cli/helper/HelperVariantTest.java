package de.dadecker.inubit.mcp.adapter.cli.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The helper's code path per INUBIT version and the queue's object type (research D-4, D-6). */
class HelperVariantTest {

    @Test
    void eightOneIsSoapAndNineZeroIsRest() {
        assertThat(HelperVariant.forVersion(new MajorMinor(8, 1)))
            .isEqualTo(HelperVariant.SOAP_81);
        assertThat(HelperVariant.forVersion(new MajorMinor(9, 0)))
            .isEqualTo(HelperVariant.REST_90);
    }

    @ParameterizedTest
    @CsvSource({"6, 1", "8, 0", "9, 1", "10, 0"})
    void anyOtherVersionIsUnsupported(int major, int minor) {
        ToolError error = catchThrowableOfType(ToolErrorException.class,
            () -> HelperVariant.forVersion(new MajorMinor(major, minor))).error();

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).isEqualTo("Queue Manager entries cannot be read on INUBIT "
            + major + "." + minor + "; the queue-entry helper knows INUBIT 8.1 and 9.0 only;"
            + " nothing was sent");
        assertThat(error.node()).isEmpty();
    }

    @Test
    void theObjectTypeNamesTheWorkflowsErrorQueue() {
        for (HelperVariant variant : HelperVariant.values()) {
            assertThat(variant.objectType("Workflow-0001"))
                .isEqualTo("queue_manager@@@Workflow-0001@@@error");
        }
    }

    @Test
    void theNamesAreTheHelpersVariants() {
        assertThat(HelperVariant.values()).extracting(HelperVariant::name)
            .containsExactly("SOAP_81", "REST_90");
    }
}
