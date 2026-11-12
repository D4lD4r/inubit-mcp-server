package com.inubit.ibis.configuration.utils.io;

import com.inubit.ibis.utils.I_IBISClientInterface;
import de.dadecker.inubit.mcp.testing.FakeInubitScript;
import java.io.File;
import java.nio.file.Files;

/**
 * Test fake (feature 020, research "Spike facts", D-19) of the vendor's download thread: writes
 * the content the active {@link FakeInubitScript} holds for {@code fileName} to the output file,
 * or keeps the scripted failure for {@link #getException()}. The research names only
 * {@code null} for the last two constructor arguments, not their types; they are
 * {@code Object} here, so the helper must not depend on them.
 */
public class MultipartDownloadThread extends Thread {

    static {
        FakeInubitScript.initializing("MultipartDownloadThread");
    }

    private final String fileName;
    private File outputFile;
    private volatile Exception exception;
    private volatile boolean failedWithoutException;

    public MultipartDownloadThread(I_IBISClientInterface client, String user, String fileName,
        String objectType, Object fifth, Object sixth) {
        FakeInubitScript.active().record("MultipartDownloadThread.<init>", client, user,
            fileName, objectType, fifth, sixth);
        this.fileName = fileName;
    }

    /**
     * For the fake {@code ClusterClient.clusterDownload} only (feature 021), which records the
     * call itself; the real class has no such constructor.
     */
    public MultipartDownloadThread(String fileName, File outputFile) {
        this.fileName = fileName;
        this.outputFile = outputFile;
    }

    public void setOutputFile(File outputFile) {
        FakeInubitScript.active().record("MultipartDownloadThread.setOutputFile", outputFile);
        this.outputFile = outputFile;
    }

    @Override
    public synchronized void start() {
        FakeInubitScript.active().record("MultipartDownloadThread.start");
        super.start();
    }

    @Override
    public void run() {
        try {
            byte[] content = FakeInubitScript.active().content(fileName);
            if (content == null) {
                failedWithoutException = true;
                return;
            }
            Files.write(outputFile.toPath(), content);
        } catch (Exception e) {
            exception = e;
        }
    }

    public boolean exceptionOccured() {
        FakeInubitScript.active().record("MultipartDownloadThread.exceptionOccured");
        return exception != null || failedWithoutException;
    }

    public Exception getException() {
        FakeInubitScript.active().record("MultipartDownloadThread.getException");
        return exception;
    }
}
