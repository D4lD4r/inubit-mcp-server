package de.dadecker.inubit.mcp.adapter.os;

/**
 * The one Windows API call the Credential Manager adapter needs (feature 011, research R-4): read a
 * generic credential by its target name. A seam, so that decoding and outcome mapping are tested
 * without Windows; production uses {@link JnaCredApi}.
 */
interface CredApi {

    /** The outcome of {@code CredReadW}. */
    sealed interface Result {

        /** The credential's blob bytes (a copy the caller may wipe). */
        record Blob(byte[] bytes) implements Result {

            @Override
            public String toString() {
                return "Blob[" + bytes.length + " bytes]";
            }
        }

        /** {@code CredReadW} failed with {@code GetLastError() == code}. */
        record Error(int code) implements Result {
        }
    }

    /**
     * Reads the generic credential {@code targetName}.
     *
     * @throws LinkageError if the native library cannot be loaded
     */
    Result readGeneric(String targetName);
}
