package de.dadecker.inubit.mcp.testing;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the fake vendor classes under {@code src/test/java/com/inubit/ibis} do (feature 020,
 * research D-19): the login result, the content or failure per downloaded file name, vendor noise
 * printed to stdout at login and an optional lingering non-daemon thread (as the 9.0 client
 * leaves), and (feature 021, D-22) the cluster node name or its failure and a failure thrown by
 * {@code clusterDownload} itself; it records every call the fakes receive.
 *
 * <p>In-process, a test {@linkplain #activate() activates} a script. In a child JVM, whose
 * class path holds the {@code lib/ibis.jar} of a {@link FakeInubitClient}, the fakes load the
 * script that {@link FakeInubitClient#install} wrote into {@code <home>/conf} and record their
 * calls there. This class is packed into that JAR, so it uses the JDK only.
 */
public final class FakeInubitScript implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    static final String SCRIPT_FILE = "fake-inubit-script.ser";
    static final String CALLS_FILE = "fake-inubit-calls.ser";
    /** The name of the thread {@link #leavesNonDaemonThread()} leaves behind. */
    public static final String LINGERING_THREAD = "fake-inubit-lingering";

    private static FakeInubitScript active;

    /**
     * One level of an exception chain, outermost first; {@code exceptionClass} names a JDK
     * exception with a {@code (String)} constructor.
     */
    public record Failure(String exceptionClass, String message) implements Serializable {
    }

    /**
     * One call a fake received: {@code Class.method} ({@code <init>} for a constructor) and its
     * arguments as text (a client argument as its simple class name, a file as its path, a node
     * name as its name).
     */
    public record Call(String name, List<String> arguments) implements Serializable {

        public Call {
            arguments = List.copyOf(arguments);
        }
    }

    private record Download(byte[] content, List<Failure> failure) implements Serializable {
    }

    private final List<Failure> loginFailure = new ArrayList<>();
    private final List<Failure> nodeFailure = new ArrayList<>();
    private String nodeName;
    private final List<Failure> clusterDownloadFailure = new ArrayList<>();
    private final Map<String, Download> files = new HashMap<>();
    private final List<String> loginNoise = new ArrayList<>();
    private final Set<String> failingInitializers = new HashSet<>();
    private boolean lingeringThread;
    private transient List<Call> calls = new ArrayList<>();
    private transient Path callLog;

    /** Login fails with the exception chain {@code chain}, outermost first. */
    public FakeInubitScript loginFails(Failure... chain) {
        loginFailure.clear();
        loginFailure.addAll(chain(chain));
        return this;
    }

    /** {@code ClusterClient.getNode} returns a node named {@code name}. */
    public FakeInubitScript node(String name) {
        nodeName = name;
        return this;
    }

    /** {@code ClusterClient.getNode} fails with the exception chain {@code chain}. */
    public FakeInubitScript nodeFails(Failure... chain) {
        nodeFailure.clear();
        nodeFailure.addAll(chain(chain));
        return this;
    }

    /**
     * {@code ClusterClient.clusterDownload} throws the exception chain {@code chain} itself,
     * before any download thread exists (the real method declares {@code InubitException}).
     */
    public FakeInubitScript clusterDownloadFails(Failure... chain) {
        clusterDownloadFailure.clear();
        clusterDownloadFailure.addAll(chain(chain));
        return this;
    }

    /** A download of {@code fileName} writes {@code content} to the output file. */
    public FakeInubitScript file(String fileName, byte[] content) {
        files.put(fileName, new Download(content.clone(), List.of()));
        return this;
    }

    /** A download of {@code fileName} fails with the exception chain {@code chain}. */
    public FakeInubitScript fileFails(String fileName, Failure... chain) {
        files.put(fileName, new Download(null, chain(chain)));
        return this;
    }

    /**
     * A download of {@code fileName} fails, but the thread reports no exception
     * ({@code exceptionOccured()} is true, {@code getException()} is {@code null}).
     */
    public FakeInubitScript fileFailsWithoutException(String fileName) {
        files.put(fileName, new Download(null, List.of()));
        return this;
    }

    /**
     * The static initializer of the fake vendor class {@code simpleName} throws an
     * {@link IllegalStateException}. A class is initialized once per class loader, so this works
     * only in-process with a class loader that defines a fresh copy of that class.
     */
    public FakeInubitScript initializerFails(String simpleName) {
        failingInitializers.add(simpleName);
        return this;
    }

    /** Login prints {@code line} to stdout first, as the vendor's logging does. */
    public FakeInubitScript printsOnLogin(String line) {
        loginNoise.add(line);
        return this;
    }

    /**
     * The 9.0 client's login starts a non-daemon thread that never ends, so only
     * {@code System.exit} ends the JVM. Meant for child JVMs; in-process it would outlive the
     * test.
     */
    public FakeInubitScript leavesNonDaemonThread() {
        lingeringThread = true;
        return this;
    }

    /** The calls the fakes received while this script was active. */
    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    /** Makes this script the one the fakes of this JVM use until the activation is closed. */
    public Activation activate() {
        synchronized (FakeInubitScript.class) {
            if (active != null) {
                throw new IllegalStateException("Another FakeInubitScript is active");
            }
            active = this;
        }
        return new Activation();
    }

    /** Ends an {@link #activate() activation}. */
    public final class Activation implements AutoCloseable {

        private Activation() {
        }

        @Override
        public void close() {
            synchronized (FakeInubitScript.class) {
                if (active == FakeInubitScript.this) {
                    active = null;
                }
            }
        }
    }

    /**
     * For the fakes: the active script, or in a child JVM the script of the client home whose
     * {@code lib/ibis.jar} holds this class.
     */
    public static synchronized FakeInubitScript active() {
        if (active == null) {
            Path conf = installedConf();
            if (conf == null) {
                throw new IllegalStateException("No FakeInubitScript is active");
            }
            active = (FakeInubitScript) read(conf.resolve(SCRIPT_FILE));
            active.callLog = conf.resolve(CALLS_FILE);
        }
        return active;
    }

    /** For the fakes: records one call. */
    public synchronized void record(String name, Object... arguments) {
        calls.add(new Call(name, Arrays.stream(arguments).map(FakeInubitScript::text).toList()));
        if (callLog != null) {
            write(callLog, new ArrayList<>(calls));
        }
    }

    /**
     * For the fakes' static initializers: throws if the script active in this JVM scripted
     * {@link #initializerFails}; without an active script it does nothing.
     */
    public static void initializing(String simpleName) {
        FakeInubitScript script;
        synchronized (FakeInubitScript.class) {
            script = active;
        }
        if (script != null && script.failingInitializers.contains(simpleName)) {
            throw new IllegalStateException("static initializer of " + simpleName + " failed");
        }
    }

    /** For the fakes: performs the scripted login. */
    public void login() throws Exception {
        loginNoise.forEach(System.out::println);
        System.out.flush();
        if (lingeringThread) {
            Thread thread = new Thread(FakeInubitScript::parkForever, LINGERING_THREAD);
            thread.setDaemon(false);
            thread.start();
        }
        if (!loginFailure.isEmpty()) {
            throw exception(loginFailure);
        }
    }

    /** For the fakes: the scripted node name, or its scripted failure. */
    public String node() throws Exception {
        if (!nodeFailure.isEmpty()) {
            throw exception(nodeFailure);
        }
        if (nodeName == null) {
            throw new IllegalStateException("FakeInubitScript: no node scripted");
        }
        return nodeName;
    }

    /** For the fakes: throws the scripted failure of {@code clusterDownload}, if any. */
    public void clusterDownload() throws Exception {
        if (!clusterDownloadFailure.isEmpty()) {
            throw exception(clusterDownloadFailure);
        }
    }

    /**
     * For the fakes: the scripted content of {@code fileName}, its scripted failure, or
     * {@code null} for {@link #fileFailsWithoutException}.
     */
    public byte[] content(String fileName) throws Exception {
        Download download = files.get(fileName);
        if (download == null) {
            throw new IllegalStateException("FakeInubitScript: nothing scripted for " + fileName);
        }
        if (!download.failure().isEmpty()) {
            throw exception(download.failure());
        }
        return download.content() == null ? null : download.content().clone();
    }

    /** Writes this script (without its calls) to {@code file}. */
    void writeTo(Path file) {
        write(file, this);
    }

    /** The calls recorded into {@code file} by a child JVM, empty if none were. */
    @SuppressWarnings("unchecked")
    static List<Call> readCalls(Path file) {
        return Files.exists(file) ? List.copyOf((List<Call>) read(file)) : List.of();
    }

    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        calls = new ArrayList<>();
    }

    private static List<Failure> chain(Failure[] chain) {
        if (chain.length == 0) {
            throw new IllegalArgumentException("A failure needs at least one exception");
        }
        return List.of(chain);
    }

    private static Exception exception(List<Failure> chain) throws ReflectiveOperationException {
        Throwable cause = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            Failure failure = chain.get(i);
            Throwable level = (Throwable) Class.forName(failure.exceptionClass())
                .getConstructor(String.class).newInstance(failure.message());
            if (cause != null) {
                level.initCause(cause);
            }
            cause = level;
        }
        if (!(cause instanceof Exception exception)) {
            throw new IllegalArgumentException(chain.get(0).exceptionClass()
                + " is not an Exception");
        }
        return exception;
    }

    private static String text(Object argument) {
        if (argument == null || argument instanceof String || argument instanceof Boolean) {
            return String.valueOf(argument);
        }
        if (argument instanceof File file) {
            return file.getPath();
        }
        return argument.getClass().getSimpleName();
    }

    /** {@code <home>/conf} if this class was loaded from {@code <home>/lib/ibis.jar}. */
    private static Path installedConf() {
        try {
            Path source = Path.of(FakeInubitScript.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
            if (!source.getFileName().toString().equals("ibis.jar")) {
                return null;
            }
            return source.getParent().resolveSibling("conf");
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void parkForever() {
        while (true) {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                // keeps the JVM alive as the vendor's threads do
            }
        }
    }

    private static Object read(Path file) {
        try (InputStream in = Files.newInputStream(file);
            ObjectInputStream objects = new ObjectInputStream(in)) {
            return objects.readObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Path file, Object value) {
        Path next = file.resolveSibling(file.getFileName() + ".next");
        try (OutputStream out = Files.newOutputStream(next);
            ObjectOutputStream objects = new ObjectOutputStream(out)) {
            objects.writeObject(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Files.move(next, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
