package de.dadecker.inubit.mcp.helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads through the INUBIT client installation whose class path it runs on: one queue manager
 * entry (feature 020, research D-1, D-2, D-4) or the web service log of the node it reaches
 * (feature 021, D-2, D-4, D-5). The server starts it in a child JVM; it is compiled for Java 17,
 * uses JDK classes only and is a single class without nested or anonymous classes, because it is
 * shipped as one class-file resource.
 *
 * <p>Protocol: the request arrives on stdin (UTF-8, {@code qe=1} first, one {@code key=value} per
 * line with every value percent-encoded, {@code end} last; the optional key {@code op} names the
 * operation, {@code queueEntry} (also when absent) or {@code serverLog}, each with its own keys,
 * feature 021 D-4); results are the stdout lines that start with {@code #QE1:<nonce> }, followed
 * by space-separated percent-encoded tokens, so output of the vendor libraries cannot forge one;
 * an empty token is written as {@code -}. Exit codes: 0 after {@code end}, 1 anything else (also
 * any unexpected {@link Throwable}), 2 malformed request, 3 login failed (also a vendor class
 * that fails to initialize while the client is created), 4 vendor API missing. The request, the
 * passwords and the content are never printed.
 *
 * <p>Vendor classes are resolved only through the {@link ClassLoader} given to {@link #run} and
 * used only by reflection: 8.1 ({@code SOAP_81}) through {@code IBISSoapClient}, 9.0
 * ({@code REST_90}) through {@code ConnectionData} and {@code InubitClientFactory}; both log in
 * with {@code loginUserCli}. A queue entry is read part by part with a
 * {@code MultipartDownloadThread} (the Workbench's "View file", FR-007); the web service log
 * through a {@code ClusterClient}, as the Workbench's Cluster Manager does: the answering node
 * from {@code getNode()}, then {@code clusterDownload} of the constant {@code webservice.log}
 * from that node (FR-006). 8.1 logs out at the end. Client methods are looked up on
 * {@code I_IBISClientInterface}, because the 9.0 client is a private class.
 */
public final class ClientHelper {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_MALFORMED = 2;
    static final int EXIT_LOGIN_FAILED = 3;
    static final int EXIT_API_MISSING = 4;

    private static final String END = "end";
    private static final String FRAME_PREFIX = "#QE1:";
    private static final String OP = "op";
    private static final String QUEUE_ENTRY = "queueEntry";
    private static final String SERVER_LOG = "serverLog";
    private static final List<String> SHARED_KEYS = List.of("qe", OP, "nonce", "variant", "url",
        "trustStore", "trustStorePassword", "hostnameVerification", "user", "password");
    private static final List<String> QUEUE_ENTRY_KEYS = List.of("objectType", "fileName",
        "parts", "dataOut", "variablesOut");
    private static final List<String> SERVER_LOG_KEYS = List.of("log", "logOut");
    private static final List<String> NOT_EMPTY = List.of("url", "objectType", "fileName",
        "variablesOut", "logOut", "user", "password");
    /** The only server log the helper reads, a constant so no request names a file (D-2). */
    private static final String WEBSERVICE_LOG = "webservice.log";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final String SOAP_81 = "SOAP_81";
    private static final String CLIENT_INTERFACE = "com.inubit.ibis.utils.I_IBISClientInterface";
    private static final String SOAP_CLIENT = "com.inubit.ibis.soap.IBISSoapClient";
    private static final String CONNECTION_DATA =
        "com.inubit.ibis.configuration.utils.ConnectionData";
    private static final String CLIENT_FACTORY = "com.inubit.ibis.client.rest.InubitClientFactory";
    private static final String DOWNLOAD_THREAD =
        "com.inubit.ibis.configuration.utils.io.MultipartDownloadThread";
    private static final String CLUSTER_CLIENT = "com.inubit.ibis.server.cluster.ClusterClient";
    private static final String NODE = "com.inubit.ibis.server.cluster.data.Node";
    private static final String NODE_NAME = "com.inubit.ibis.server.cache.NodeName";
    private static final String CURRENT_USER = "com.inubit.ibis.common.CurrentUser";
    private static final int MESSAGE_LIMIT = 1_000;
    private static final String MASK = "******";
    private static final String EMPTY_TOKEN = "-";

    private ClientHelper() {
    }

    public static void main(String[] args) {
        int exit = EXIT_FAILED;
        try {
            exit = run(System.in, System.out, ClassLoader.getSystemClassLoader());
        } finally {
            // always, so the vendor's non-daemon threads never keep the JVM alive (D-4)
            System.exit(exit);
        }
    }

    /**
     * Reads the request from {@code in}, performs its operation through the vendor classes of
     * {@code vendor} and writes the frames to {@code out}; returns the exit code. Nothing is
     * thrown: an unexpected {@link Throwable} gives exit 1 and is not printed, as it may carry
     * request values.
     */
    static int run(InputStream in, PrintStream out, ClassLoader vendor) {
        try {
            return respond(in, out, vendor);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EXIT_FAILED;
        } catch (Throwable e) {
            return EXIT_FAILED;
        }
    }

    private static int respond(InputStream in, PrintStream out, ClassLoader vendor)
        throws IOException, ReflectiveOperationException, InterruptedException {
        Map<String, String> request;
        try {
            request = readRequest(in);
        } catch (IllegalArgumentException e) {
            return EXIT_MALFORMED;
        }
        String nonce = request.get("nonce");
        Map<String, Executable> api;
        try {
            api = vendorApi(request, vendor);
        } catch (ReflectiveOperationException | LinkageError e) {
            frame(out, nonce, "api", "missing", String.valueOf(e.getMessage()));
            return EXIT_API_MISSING;
        }
        frame(out, nonce, "start", request.get("variant"));
        return read(request, api, out);
    }

    /**
     * The vendor constructors and methods of the variant and the operation, keyed by their simple
     * name; a missing class or member throws, its name being the exception message.
     */
    private static Map<String, Executable> vendorApi(Map<String, String> request,
        ClassLoader vendor) throws ReflectiveOperationException {
        Map<String, Executable> api = new HashMap<>();
        Class<?> client = Class.forName(CLIENT_INTERFACE, false, vendor);
        api.put("loginUserCli", client.getMethod("loginUserCli", String.class, String.class));
        if (SOAP_81.equals(request.get("variant"))) {
            Class<?> soapClient = Class.forName(SOAP_CLIENT, false, vendor);
            api.put("IBISSoapClient", soapClient.getConstructor(String.class, String.class,
                String.class, String.class, String.class, boolean.class));
            try {
                api.put("logoutUser", soapClient.getMethod("logoutUser", String.class));
            } catch (NoSuchMethodException e) {
                // logout is optional (D-4: "if present")
            }
        } else {
            Class<?> data = Class.forName(CONNECTION_DATA, false, vendor);
            api.put("ConnectionData", data.getConstructor());
            for (String setter : List.of("setServerURL", "setUserName", "setPassword",
                "setServerTrustStorePassword")) {
                api.put(setter, data.getMethod(setter, String.class));
            }
            api.put("setServerAuth", data.getMethod("setServerAuth", boolean.class));
            api.put("setSkipHostnameVerification",
                data.getMethod("setSkipHostnameVerification", boolean.class));
            api.put("setServerTrustStoreFile", data.getMethod("setServerTrustStoreFile",
                File.class));
            Class<?> factory = Class.forName(CLIENT_FACTORY, false, vendor);
            api.put("InubitClientFactory", factory.getConstructor());
            api.put("getRestInstance", factory.getMethod("getRestInstance", data));
        }
        Class<?> download = Class.forName(DOWNLOAD_THREAD, false, vendor);
        if (!Thread.class.isAssignableFrom(download)) {
            throw new ClassNotFoundException(DOWNLOAD_THREAD + " as a Thread");
        }
        api.put("exceptionOccured", download.getMethod("exceptionOccured"));
        api.put("getException", download.getMethod("getException"));
        if (QUEUE_ENTRY.equals(request.get(OP))) {
            api.put("MultipartDownloadThread", sixArgumentConstructor(download));
            api.put("setOutputFile", download.getMethod("setOutputFile", File.class));
        } else {
            // NodeName and Node first: clusterDownload's parameter type is the NodeName found here
            Class<?> nodeName = Class.forName(NODE_NAME, false, vendor);
            api.put("getName", nodeName.getMethod("getName"));
            api.put("getNodeName", Class.forName(NODE, false, vendor).getMethod("getNodeName"));
            Class<?> currentUser = Class.forName(CURRENT_USER, false, vendor);
            api.put("getClientUID", currentUser.getMethod("getClientUID"));
            api.put("getUsedAdditionalUserGroup",
                currentUser.getMethod("getUsedAdditionalUserGroup"));
            Class<?> cluster = Class.forName(CLUSTER_CLIENT, false, vendor);
            api.put("ClusterClient", cluster.getConstructor(client, String.class, String.class,
                String.class));
            api.put("getNode", cluster.getMethod("getNode"));
            api.put("clusterDownload", cluster.getMethod("clusterDownload", File.class, nodeName,
                String.class));
        }
        return api;
    }

    /**
     * The only public six-argument constructor of {@code download}; its parameter types are not
     * known (research "Spike facts" records only the argument values).
     */
    private static Constructor<?> sixArgumentConstructor(Class<?> download)
        throws NoSuchMethodException {
        Constructor<?> found = null;
        for (Constructor<?> constructor : download.getConstructors()) {
            if (constructor.getParameterCount() == 6) {
                if (found != null) {
                    throw new NoSuchMethodException(download.getName()
                        + ".<init> with exactly one six-argument form");
                }
                found = constructor;
            }
        }
        if (found == null) {
            throw new NoSuchMethodException(download.getName() + ".<init>(6 arguments)");
        }
        return found;
    }

    /**
     * Logs in, reads the requested parts or the web service log and logs out (8.1); returns the
     * exit code.
     */
    private static int read(Map<String, String> request, Map<String, Executable> api,
        PrintStream out) throws ReflectiveOperationException, InterruptedException {
        String nonce = request.get("nonce");
        String user = request.get("user");
        String password = request.get("password");
        String trustStorePassword = request.get("trustStorePassword");
        // longest first, so a password inside the other one cannot leave pieces of it unmasked
        List<String> secrets = password.length() >= trustStorePassword.length()
            ? List.of(password, trustStorePassword) : List.of(trustStorePassword, password);
        Object client = null;
        try {
            try {
                client = client(request, api);
                call(api, "loginUserCli", client, user, password);
            } catch (InvocationTargetException | LinkageError e) {
                Throwable failure = vendorFailure(e);
                frame(out, nonce, "login", "error", chain(failure), messages(failure, secrets));
                return EXIT_LOGIN_FAILED;
            }
            frame(out, nonce, "login", "ok");
            if (SERVER_LOG.equals(request.get(OP))) {
                readServerLog(api, client, request, out, secrets);
            } else {
                if (request.get("parts").startsWith("data")) {
                    download(api, client, request, "data", request.get("fileName"),
                        request.get("dataOut"), out, secrets);
                }
                download(api, client, request, "variables", request.get("fileName") + ".wfsp",
                    request.get("variablesOut"), out, secrets);
            }
        } finally {
            logout(api, client, user);
        }
        frame(out, nonce, "end");
        return EXIT_OK;
    }

    /** The vendor client of the variant, not yet logged in. */
    private static Object client(Map<String, String> request, Map<String, Executable> api)
        throws ReflectiveOperationException {
        String trustStore = request.get("trustStore");
        boolean verifyHostname = Boolean.parseBoolean(request.get("hostnameVerification"));
        if (api.containsKey("IBISSoapClient")) {
            return call(api, "IBISSoapClient", null, request.get("url") + "?isCli",
                emptyToNull(trustStore), emptyToNull(request.get("trustStorePassword")), null,
                null, verifyHostname);
        }
        Object data = call(api, "ConnectionData", null);
        call(api, "setServerURL", data, request.get("url"));
        call(api, "setUserName", data, request.get("user"));
        call(api, "setPassword", data, request.get("password"));
        call(api, "setServerAuth", data, true);
        if (!trustStore.isEmpty()) {
            call(api, "setServerTrustStoreFile", data, new File(trustStore));
            call(api, "setServerTrustStorePassword", data, request.get("trustStorePassword"));
        }
        call(api, "setSkipHostnameVerification", data, !verifyHostname);
        return call(api, "getRestInstance", call(api, "InubitClientFactory", null), data);
    }

    /** Downloads one part into {@code outFile} and writes its {@code part} frame. */
    private static void download(Map<String, Executable> api, Object client,
        Map<String, String> request, String part, String fileName, String outFile,
        PrintStream out, List<String> secrets)
        throws ReflectiveOperationException, InterruptedException {
        File file = new File(outFile);
        try {
            Thread thread = (Thread) call(api, "MultipartDownloadThread", null, client,
                request.get("user"), fileName, request.get("objectType"), null, null);
            call(api, "setOutputFile", thread, file);
            transfer(api, thread, file, request.get("nonce"), part, out, secrets);
        } catch (InvocationTargetException | LinkageError e) {
            partError(out, request.get("nonce"), part, vendorFailure(e), secrets);
        }
    }

    /**
     * Looks up the answering node and downloads its {@code webservice.log} into {@code logOut},
     * naming that node explicitly (D-5); writes the {@code node} frame and, after
     * {@code node ok}, the {@code part log} frame. A lookup that yields no node or no node name
     * is a {@code node error}.
     */
    private static void readServerLog(Map<String, Executable> api, Object client,
        Map<String, String> request, PrintStream out, List<String> secrets)
        throws ReflectiveOperationException, InterruptedException {
        String nonce = request.get("nonce");
        Object cluster;
        Object node;
        String name;
        try {
            cluster = call(api, "ClusterClient", null, client, request.get("user"),
                call(api, "getClientUID", null), call(api, "getUsedAdditionalUserGroup", null));
            Object found = call(api, "getNode", cluster);
            node = found == null ? null : call(api, "getNodeName", found);
            name = node == null ? null : (String) call(api, "getName", node);
        } catch (InvocationTargetException | LinkageError e) {
            Throwable failure = vendorFailure(e);
            frame(out, nonce, "node", "error", chain(failure), messages(failure, secrets));
            return;
        }
        if (name == null) {
            // a node lookup without a node name is a failed one: no node to download from;
            // nothing was thrown, so the frame carries no exception chain
            frame(out, nonce, "node", "error", "",
                "the cluster client returned no node or node name");
            return;
        }
        frame(out, nonce, "node", "ok", name);
        File file = new File(request.get("logOut"));
        try {
            Thread thread = (Thread) call(api, "clusterDownload", cluster, file, node,
                WEBSERVICE_LOG);
            transfer(api, thread, file, nonce, "log", out, secrets);
        } catch (InvocationTargetException | LinkageError e) {
            partError(out, nonce, "log", vendorFailure(e), secrets);
        }
    }

    /**
     * Runs the download {@code thread} into {@code file} and writes the {@code part} frame of its
     * result; a vendor call failing here is left to the caller.
     */
    private static void transfer(Map<String, Executable> api, Thread thread, File file,
        String nonce, String part, PrintStream out, List<String> secrets)
        throws ReflectiveOperationException, InterruptedException {
        thread.start();
        thread.join();
        if (!Boolean.TRUE.equals(call(api, "exceptionOccured", thread))) {
            frame(out, nonce, "part", part, "ok", Long.toString(file.length()));
            return;
        }
        partError(out, nonce, part, (Throwable) call(api, "getException", thread), secrets);
    }

    private static void partError(PrintStream out, String nonce, String part, Throwable failure,
        List<String> secrets) {
        frame(out, nonce, "part", part, "error", chain(failure), messages(failure, secrets));
    }

    /** Calls {@code logoutUser(user)} if the variant has it; its failure changes nothing. */
    private static void logout(Map<String, Executable> api, Object client, String user) {
        if (client == null || !api.containsKey("logoutUser")) {
            return;
        }
        try {
            call(api, "logoutUser", client, user);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // the read is complete; a failed logout only ends the session later
        }
    }

    private static Object call(Map<String, Executable> api, String name, Object target,
        Object... arguments) throws ReflectiveOperationException {
        Executable member = api.get(name);
        if (member instanceof Constructor<?> constructor) {
            return constructor.newInstance(arguments);
        }
        return ((Method) member).invoke(target, arguments);
    }

    /**
     * What a vendor call threw: the cause of an {@link InvocationTargetException}, or the
     * {@link LinkageError} itself (a vendor class failing to initialize is thrown directly).
     */
    private static Throwable vendorFailure(Throwable thrown) {
        return thrown instanceof InvocationTargetException ? thrown.getCause() : thrown;
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    /** The exception classes of {@code failure}, outermost first, joined by {@code >}. */
    private static String chain(Throwable failure) {
        List<String> classes = new ArrayList<>();
        for (Throwable level : levels(failure)) {
            classes.add(level.getClass().getName());
        }
        return String.join(">", classes);
    }

    /**
     * The messages of {@code failure}, one line per exception of the {@linkplain #chain chain}
     * (empty without a message), each with the {@code secrets} masked and then cut at 1,000
     * characters.
     */
    private static String messages(Throwable failure, List<String> secrets) {
        List<String> messages = new ArrayList<>();
        for (Throwable level : levels(failure)) {
            String message = level.getMessage() == null ? "" : level.getMessage();
            for (String secret : secrets) {
                if (!secret.isEmpty()) {
                    message = message.replace(secret, MASK);
                }
            }
            messages.add(message.length() > MESSAGE_LIMIT
                ? message.substring(0, MESSAGE_LIMIT) : message);
        }
        return String.join("\n", messages);
    }

    private static List<Throwable> levels(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> levels = new ArrayList<>();
        for (Throwable level = failure; level != null && seen.add(level);
            level = level.getCause()) {
            levels.add(level);
        }
        return levels;
    }

    /** The decoded request; malformed input throws {@link IllegalArgumentException}. */
    private static Map<String, String> readRequest(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(in, StandardCharsets.UTF_8));
        if (!"qe=1".equals(reader.readLine())) {
            throw new IllegalArgumentException("not a version 1 request");
        }
        Map<String, String> request = new HashMap<>();
        request.put("qe", "1");
        for (String line = reader.readLine(); !END.equals(line); line = reader.readLine()) {
            if (line == null) {
                throw new IllegalArgumentException("request without end");
            }
            int equals = line.indexOf('=');
            String key = equals < 0 ? "" : line.substring(0, equals);
            if ((!SHARED_KEYS.contains(key) && !QUEUE_ENTRY_KEYS.contains(key)
                && !SERVER_LOG_KEYS.contains(key)) || request.containsKey(key)) {
                throw new IllegalArgumentException("unknown or repeated key");
            }
            request.put(key, decode(line.substring(equals + 1)));
        }
        // a request without op is a feature-020 queue-entry read (021 D-4)
        request.putIfAbsent(OP, QUEUE_ENTRY);
        validate(request);
        return request;
    }

    /** Checks the keys of the operation, each exactly once, and their values. */
    private static void validate(Map<String, String> request) {
        boolean queueEntry = QUEUE_ENTRY.equals(request.get(OP));
        if (!queueEntry && !SERVER_LOG.equals(request.get(OP))) {
            throw new IllegalArgumentException("unknown operation");
        }
        List<String> operationKeys = queueEntry ? QUEUE_ENTRY_KEYS : SERVER_LOG_KEYS;
        if (request.size() != SHARED_KEYS.size() + operationKeys.size()
            || !request.keySet().containsAll(SHARED_KEYS)
            || !request.keySet().containsAll(operationKeys)) {
            throw new IllegalArgumentException("key missing or of the other operation");
        }
        for (String key : NOT_EMPTY) {
            if (request.containsKey(key) && request.get(key).isEmpty()) {
                throw new IllegalArgumentException("empty value");
            }
        }
        if (!request.get("nonce").matches("[0-9a-fA-F]{32}")
            || !List.of("SOAP_81", "REST_90").contains(request.get("variant"))
            || !List.of("true", "false").contains(request.get("hostnameVerification"))) {
            throw new IllegalArgumentException("invalid value");
        }
        if (queueEntry) {
            String parts = request.get("parts");
            if (!List.of("data,variables", "variables").contains(parts)
                || parts.startsWith("data") && request.get("dataOut").isEmpty()) {
                throw new IllegalArgumentException("invalid value");
            }
        } else if (!"webservice".equals(request.get("log"))) {
            // the only kind; the helper maps it to the constant file name (D-2, D-4)
            throw new IllegalArgumentException("invalid value");
        }
    }

    /**
     * Writes the frame {@code #QE1:<nonce> <token> …}, every token percent-encoded and an empty
     * token as {@code -} (so every token is visible; no class name is {@code -}, and a message
     * that is just {@code -} carries nothing either).
     */
    static void frame(PrintStream out, String nonce, String... tokens) {
        StringBuilder line = new StringBuilder(FRAME_PREFIX).append(nonce);
        for (String token : tokens) {
            line.append(' ').append(token.isEmpty() ? EMPTY_TOKEN : encode(token));
        }
        out.print(line.append('\n'));
        out.flush();
    }

    private static String encode(String token) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : token.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (unreserved(c)) {
                encoded.append(c);
            } else {
                encoded.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
        }
        return encoded.toString();
    }

    /** RFC 3986 percent-decoding; any character outside the unreserved set must be escaped. */
    private static String decode(String value) {
        byte[] bytes = new byte[value.length()];
        int length = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                int high = i + 2 < value.length() ? hex(value.charAt(i + 1)) : -1;
                int low = high < 0 ? -1 : hex(value.charAt(i + 2));
                if (low < 0) {
                    throw new IllegalArgumentException("invalid escape");
                }
                bytes[length++] = (byte) (high << 4 | low);
                i += 2;
            } else if (unreserved(c)) {
                bytes[length++] = (byte) c;
            } else {
                throw new IllegalArgumentException("value not percent-encoded");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("value not UTF-8");
        }
    }

    private static boolean unreserved(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
            || c == '-' || c == '.' || c == '_' || c == '~';
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return -1;
    }
}
