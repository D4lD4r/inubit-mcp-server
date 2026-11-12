package de.dadecker.inubit.mcp.adapter.cli.helper;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.Objects;

/**
 * The code path of the client helper per INUBIT version (feature 020, research D-4, D-6; moved
 * and renamed from {@code QueueEntryVariant} in feature 021, D-3): the SOAP client of 8.1 or the
 * REST client of 9.0. The name is the request's {@code variant}.
 */
public enum HelperVariant {

    /** INUBIT 8.1: the SOAP client of the client installation. */
    SOAP_81,
    /** INUBIT 9.0: the REST client of the client installation. */
    REST_90;

    private static final MajorMinor V81 = new MajorMinor(8, 1);
    private static final MajorMinor V90 = new MajorMinor(9, 0);

    /**
     * The variant for {@code version}.
     *
     * @throws ToolErrorException {@code UNSUPPORTED_VERSION} (without node) for any version but
     *     8.1 and 9.0
     */
    public static HelperVariant forVersion(MajorMinor version) {
        Objects.requireNonNull(version, "version");
        if (version.equals(V81)) {
            return SOAP_81;
        }
        if (version.equals(V90)) {
            return REST_90;
        }
        throw new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
            "Queue Manager entries cannot be read on INUBIT " + version.text()
                + "; the queue-entry helper knows INUBIT 8.1 and 9.0 only; nothing was sent",
            "The unsupported INUBIT interface the helper uses is only known for 8.1 and 9.0",
            "Read the entry in the Workbench (Queue Manager, View file)"));
    }

    /** The object type of the error queue of {@code workflow}. */
    public String objectType(String workflow) {
        return "queue_manager@@@" + Objects.requireNonNull(workflow, "workflow") + "@@@error";
    }
}
