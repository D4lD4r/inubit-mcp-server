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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the fake vendor classes under {@code src/test/java/com/inubit/ibis} do (feature 020,
 * research D-19): the login result, the content or failure per downloaded file name, vendor noise
 * printed to stdout at login and an optional lingering non-daemon thread (as the 9.0 client
 * leaves), (feature 021, D-22) the cluster node name or its failure and a failure thrown by
 * {@code clusterDownload} itself, and (feature 024, D-27) the cluster members, the node
 * information per node, the node configuration properties and the answers of
 * {@code getObject}, each or its failure; it records every call the fakes receive, including a
 * call of a method the helper must never make, which also fails the test.
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

    /**
     * A cluster member as {@code ClusterClient.getNodes()} lists it: {@code status} names a
     * {@code Node.Status} constant; {@code null} texts and uptime 0 are a member that did not
     * answer.
     */
    public record Member(String name, String status, String version, String zone, String nodeId,
        long uptimeMs) implements Serializable {

        /** A test-setup error, not a vendor failure: the status must be a vendor constant or null. */
        public Member {
            if (status != null && !java.util.Set.of("ACTIVE", "MAINTENANCE", "OFFLINE")
                .contains(status)) {
                throw new IllegalArgumentException("Member status must be ACTIVE, MAINTENANCE,"
                    + " OFFLINE or null, not " + status);
            }
        }

        /** A member that did not answer: listed with its name only. */
        public static Member unanswered(String name) {
            return new Member(name, null, null, null, null, 0);
        }
    }

    /** A node configuration property as {@code getNodeConfigurationProperty} answers it. */
    public record Property(Class<?> type, String value, String displayName,
        String shortDescription, String category, boolean editable, boolean deprecated)
        implements Serializable {
    }

    /** A scripted answer ({@code null} allowed) or, if the chain is not empty, its failure. */
    private record Answer<T extends Serializable>(T value, List<Failure> failure)
        implements Serializable {
    }

    /** The call of the node lookup that {@link #nodeLookupReturnsNull} makes return null. */
    public enum NullFrom {
        /** {@code ClusterClient.getNode()}. */
        GET_NODE,
        /** {@code Node.getNodeName()}. */
        GET_NODE_NAME,
        /** {@code NodeName.getName()}. */
        GET_NAME
    }

    private final List<Failure> loginFailure = new ArrayList<>();
    private final List<Failure> nodeFailure = new ArrayList<>();
    private String nodeName;
    private NullFrom nodeLookupNull;
    private final List<Failure> clusterDownloadFailure = new ArrayList<>();
    private final Map<String, Download> files = new HashMap<>();
    private Answer<ArrayList<Member>> members;
    private final Map<String, Answer<LinkedHashMap<String, Serializable>>> nodeInformation =
        new HashMap<>();
    private final Map<String, Answer<Property>> properties = new HashMap<>();
    private final Map<String, Answer<String>> objects = new HashMap<>();
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

    /** The node lookup's call {@code call} returns {@code null} instead of a node or name. */
    public FakeInubitScript nodeLookupReturnsNull(NullFrom call) {
        nodeLookupNull = call;
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

    /** {@code ClusterClient.getNodes} lists {@code members} in this order. */
    public FakeInubitScript members(Member... members) {
        this.members = new Answer<>(new ArrayList<>(List.of(members)), List.of());
        return this;
    }

    /** {@code ClusterClient.getNodes} fails with the exception chain {@code chain}. */
    public FakeInubitScript membersFail(Failure... chain) {
        members = new Answer<>(null, chain(chain));
        return this;
    }

    /**
     * {@code ClusterClient.getNode(NodeName)} of {@code nodeName} returns a node whose
     * information map holds {@code entries} in their iteration order: a value is a text,
     * {@code null}, or a nested map of the same kind (a nested {@code MapProperty}).
     */
    public FakeInubitScript nodeInformation(String nodeName, Map<String, ?> entries) {
        nodeInformation.put(nodeName, new Answer<>(entriesCopy(entries), List.of()));
        return this;
    }

    /** {@code ClusterClient.getNode(NodeName)} of {@code nodeName} fails with {@code chain}. */
    public FakeInubitScript nodeInformationFails(String nodeName, Failure... chain) {
        nodeInformation.put(nodeName, new Answer<>(null, chain(chain)));
        return this;
    }

    /**
     * {@code getNodeConfigurationProperty} of {@code name} answers {@code property}; a name
     * without a scripted property answers {@code null}, as the server does for an unknown name.
     */
    public FakeInubitScript property(String name, Property property) {
        properties.put(name, new Answer<>(property, List.of()));
        return this;
    }

    /** {@code getNodeConfigurationProperty} of {@code name} fails with {@code chain}. */
    public FakeInubitScript propertyFails(String name, Failure... chain) {
        properties.put(name, new Answer<>(null, chain(chain)));
        return this;
    }

    /** The client's {@code getObject(objectName, user, objectType)} answers {@code answer}. */
    public FakeInubitScript object(String objectName, String objectType, String answer) {
        objects.put(objectName + "|" + objectType, new Answer<>(answer, List.of()));
        return this;
    }

    /** The client's {@code getObject(objectName, user, objectType)} fails with {@code chain}. */
    public FakeInubitScript objectFails(String objectName, String objectType, Failure... chain) {
        objects.put(objectName + "|" + objectType, new Answer<>(null, chain(chain)));
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

    /** For the fakes: the node lookup call scripted to return {@code null}, or {@code null}. */
    public NullFrom nodeLookupNull() {
        return nodeLookupNull;
    }

    /** For the fakes: throws the scripted failure of {@code clusterDownload}, if any. */
    public void clusterDownload() throws Exception {
        if (!clusterDownloadFailure.isEmpty()) {
            throw exception(clusterDownloadFailure);
        }
    }

    /**
     * For the fakes: the scripted members, or their scripted failure (not {@code members()},
     * which would be the setter with no member).
     */
    public List<Member> scriptedMembers() throws Exception {
        if (members == null) {
            throw new IllegalStateException("FakeInubitScript: no members scripted");
        }
        return List.copyOf(value(members));
    }

    /**
     * For the fakes: the scripted node information entries of {@code nodeName} (values: text,
     * {@code null} or a nested map), or their scripted failure.
     */
    public Map<String, ?> nodeInformation(String nodeName) throws Exception {
        Answer<LinkedHashMap<String, Serializable>> answer = nodeInformation.get(nodeName);
        if (answer == null) {
            throw new IllegalStateException(
                "FakeInubitScript: no node information scripted for " + nodeName);
        }
        return value(answer);
    }

    /** For the fakes: the scripted property {@code name}, its failure, or {@code null}. */
    public Property property(String name) throws Exception {
        Answer<Property> answer = properties.get(name);
        return answer == null ? null : value(answer);
    }

    /** For the fakes: the scripted answer of {@code getObject}, or its scripted failure. */
    public String object(String objectName, String objectType) throws Exception {
        Answer<String> answer = objects.get(objectName + "|" + objectType);
        if (answer == null) {
            throw new IllegalStateException("FakeInubitScript: nothing scripted for "
                + objectName + " of type " + objectType);
        }
        return value(answer);
    }

    /**
     * For the fakes: records the call {@code name} of a method the helper must never make
     * (research D-27) and returns the test failure the fake throws. The record stays even if the
     * helper catches the failure, so a test comparing the calls fails as well.
     */
    public AssertionError forbidden(String name, Object... arguments) {
        record(name, arguments);
        return new AssertionError("FakeInubitScript: forbidden vendor call " + name);
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

    private static <T extends Serializable> T value(Answer<T> answer) throws Exception {
        if (!answer.failure().isEmpty()) {
            throw exception(answer.failure());
        }
        return answer.value();
    }

    /** A serializable copy of node information entries, nested maps included, order kept. */
    private static LinkedHashMap<String, Serializable> entriesCopy(Map<String, ?> entries) {
        LinkedHashMap<String, Serializable> copy = new LinkedHashMap<>();
        entries.forEach((key, value) -> {
            if (value == null || value instanceof String) {
                copy.put(key, (String) value);
            } else if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, ?> inner = (Map<String, ?>) nested;
                copy.put(key, entriesCopy(inner));
            } else {
                throw new IllegalArgumentException("A node information value is a text, null or"
                    + " a map: " + key);
            }
        });
        return copy;
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
