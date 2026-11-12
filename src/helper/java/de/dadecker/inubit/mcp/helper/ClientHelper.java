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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads through the INUBIT client installation whose class path it runs on: one queue manager
 * entry (feature 020, research D-1, D-2, D-4), the web service log of the node it reaches
 * (feature 021, D-2, D-4, D-5), the name of that node, the cluster's nodes or the node's
 * configuration (feature 024, D-2, D-3, D-7, D-10). The server starts it in a child JVM; it is
 * compiled for Java 17, uses JDK classes only and is a single class without nested or anonymous
 * classes, because it is shipped as one class-file resource.
 *
 * <p>Protocol: the request arrives on stdin (UTF-8, {@code qe=1} first, one {@code key=value} per
 * line with every value percent-encoded, {@code end} last; the optional key {@code op} names the
 * operation, {@code queueEntry} (also when absent), {@code serverLog} (feature 021 D-4) or
 * {@code ownNode}, {@code clusterNodes}, {@code nodeConfig}, {@code ioLog} (feature 024 D-2),
 * each with its own keys; this version performs {@code ownNode}, {@code clusterNodes} and
 * {@code nodeConfig} and answers a valid {@code ioLog} request with exit 2 after the API check);
 * results are the stdout lines that start with {@code #QE1:<nonce> }, followed by space-separated
 * percent-encoded tokens, so output of the vendor libraries cannot forge one; an empty token is
 * written as {@code -}. Exit codes: 0 after {@code end}, 1 anything else (also any unexpected
 * {@link Throwable}), 2 malformed request, 3 login failed (also a vendor class that fails to
 * initialize while the client is created), 4 vendor API missing. The request, the passwords and
 * the content are never printed.
 *
 * <p>Vendor classes are resolved only through the {@link ClassLoader} given to {@link #run} and
 * used only by reflection: 8.1 ({@code SOAP_81}) through {@code IBISSoapClient}, 9.0
 * ({@code REST_90}) through {@code ConnectionData} and {@code InubitClientFactory}; both log in
 * with {@code loginUserCli}. A queue entry is read part by part with a
 * {@code MultipartDownloadThread} (the Workbench's "View file", FR-007); the web service log
 * through a {@code ClusterClient}, as the Workbench's Cluster Manager does: the answering node
 * from {@code getNode()}, then {@code clusterDownload} of the constant {@code webservice.log}
 * from that node (FR-006); the own node through the same {@code getNode()}, the cluster's nodes
 * through {@code getNodes()} and one node's information through {@code getNode(NodeName)}
 * (FR-010, FR-011), the own node's configuration through
 * {@code getNodeConfigurationProperty} (FR-030, FR-032). 8.1 logs out at the end. Client methods
 * are looked up on {@code I_IBISClientInterface}, because the 9.0 client is a private class.
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
    private static final String OWN_NODE = "ownNode";
    private static final String CLUSTER_NODES = "clusterNodes";
    private static final String NODE_CONFIG = "nodeConfig";
    private static final String IO_LOG = "ioLog";
    private static final List<String> SHARED_KEYS = List.of("qe", OP, "nonce", "variant", "url",
        "trustStore", "trustStorePassword", "hostnameVerification", "user", "password");
    /** The keys of each operation, every one required exactly once (021 D-4, 024 D-2). */
    private static final Map<String, List<String>> OPERATION_KEYS = Map.of(
        QUEUE_ENTRY, List.of("objectType", "fileName", "parts", "dataOut", "variablesOut"),
        SERVER_LOG, List.of("log", "logOut"),
        OWN_NODE, List.of(),
        CLUSTER_NODES, List.of("details"),
        NODE_CONFIG, List.of("names"),
        IO_LOG, List.of("processId", "workflow", "ref", "listOut", "messageOut"));
    private static final List<String> NOT_EMPTY = List.of("url", "objectType", "fileName",
        "variablesOut", "logOut", "names", "processId", "workflow", "listOut", "user",
        "password");
    /**
     * The node configuration names {@code nodeConfig} may read, the helper's own list (024 D-10):
     * no name outside it reaches the server.
     */
    private static final List<String> NODE_CONFIG_NAMES = List.of("OperationMode", "ClusterName",
        "ZoneName", "NodeName", "LocalMaintenanceMode", "ActivateClusterManager", "ClusterDebug",
        "ContainerShutdownOnNodeFailure", "RegisterMBeans", "RegisterInternalMBeans",
        "RuntimeDataCacheXMXPercentage", "socketTimeoutInMillisecs", "indexEnabled",
        "iterationCount", "DataSourceLocation", "LogsDBDataSourceLocation",
        "RepositoryDataSourceLocation", "ClusterBindAddress", "noOfRetries", "retryInterval");
    private static final int MAX_WORKFLOW_CHARS = 200;
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
    private static final String NODE_INFORMATION =
        "com.inubit.ibis.server.datafacade.NodeInformation";
    private static final String MAP_PROPERTY = "com.inubit.ibis.utils.property.type.MapProperty";
    private static final String IBIS_PROPERTY = "com.inubit.ibis.utils.property.type.IBISProperty";
    private static final String CURRENT_USER = "com.inubit.ibis.common.CurrentUser";
    private static final int MESSAGE_LIMIT = 1_000;
    private static final String MASK = "******";
    private static final String EMPTY_TOKEN = "-";
    /** The {@code member} frames of {@code clusterNodes} at most (024 D-2, D-7). */
    private static final int MAX_MEMBERS = 200;
    private static final int MAX_MEMBER_CHARS = 200;
    /**
     * The bytes of one percent-encoded {@code member} token at most: never cuts 200 ASCII
     * characters (3 bytes each at most) and keeps a {@code member} frame at about 3,070 bytes, so
     * 200 of them stay below 600 KiB (024 D-2).
     */
    private static final int MAX_MEMBER_TOKEN_BYTES = 600;
    private static final int MAX_DETAILS = 500;
    private static final int MAX_DETAIL_KEY_CHARS = 200;
    private static final int MAX_DETAIL_VALUE_CHARS = 4_000;
    /** The encoded {@code detail} frames together (whole lines) at most (024 D-2). */
    private static final int DETAILS_BUDGET_BYTES = 256 * 1024;
    private static final int MAX_CONFIG_TEXT_CHARS = 4_000;
    /**
     * The bytes of one percent-encoded {@code config} text at most: never cuts 4,000 ASCII
     * characters (3 bytes each at most) and keeps 20 {@code config} frames of four texts each
     * below the 1 MiB stdout cap (020).
     */
    private static final int MAX_CONFIG_TEXT_BYTES = 12_000;

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
        String op = request.get(OP);
        if (IO_LOG.equals(op)) {
            // a valid request, but this helper version does not perform it yet: nothing sent
            return EXIT_MALFORMED;
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
        if (QUEUE_ENTRY.equals(request.get(OP))) {
            Class<?> download = downloadThread(api, vendor);
            api.put("MultipartDownloadThread", sixArgumentConstructor(download));
            api.put("setOutputFile", download.getMethod("setOutputFile", File.class));
        } else {
            boolean serverLog = SERVER_LOG.equals(request.get(OP));
            if (serverLog) {
                // first, as in feature 021, so the same member is named when several are missing
                downloadThread(api, vendor);
            }
            // every other operation starts with the node lookup (021 D-5, 024 D-3); NodeName and
            // Node first: clusterDownload's parameter type is the NodeName found here
            Class<?> nodeName = Class.forName(NODE_NAME, false, vendor);
            api.put("getName", nodeName.getMethod("getName"));
            Class<?> node = Class.forName(NODE, false, vendor);
            api.put("getNodeName", node.getMethod("getNodeName"));
            Class<?> currentUser = Class.forName(CURRENT_USER, false, vendor);
            api.put("getClientUID", currentUser.getMethod("getClientUID"));
            api.put("getUsedAdditionalUserGroup",
                currentUser.getMethod("getUsedAdditionalUserGroup"));
            Class<?> cluster = Class.forName(CLUSTER_CLIENT, false, vendor);
            api.put("ClusterClient", cluster.getConstructor(client, String.class, String.class,
                String.class));
            api.put("getNode", cluster.getMethod("getNode"));
            if (serverLog) {
                api.put("clusterDownload", cluster.getMethod("clusterDownload", File.class,
                    nodeName, String.class));
            }
            if (CLUSTER_NODES.equals(request.get(OP))) {
                clusterNodesApi(api, vendor, cluster, nodeName, node);
            }
            if (NODE_CONFIG.equals(request.get(OP))) {
                nodeConfigApi(api, vendor, cluster, nodeName);
            }
        }
        return api;
    }

    /**
     * The members {@code clusterNodes} calls beyond the node lookup (024 D-2); the named node's
     * {@code getNode} is keyed {@code getNamedNode}.
     */
    private static void clusterNodesApi(Map<String, Executable> api, ClassLoader vendor,
        Class<?> cluster, Class<?> nodeName, Class<?> node) throws ReflectiveOperationException {
        api.put("nodeName", nodeName.getMethod("nodeName", String.class));
        api.put("getNodes", cluster.getMethod("getNodes"));
        api.put("getNamedNode", cluster.getMethod("getNode", nodeName));
        for (String getter : List.of("getStatus", "getVersion", "getZoneName", "getNodeID",
            "getUptime", "getNodeInformation")) {
            api.put(getter, node.getMethod(getter));
        }
        api.put("getNodeInformationMap", Class.forName(NODE_INFORMATION, false, vendor)
            .getMethod("getNodeInformationMap"));
        api.put("getStringValue", Class.forName(IBIS_PROPERTY, false, vendor)
            .getMethod("getStringValue"));
        Class<?> map = Class.forName(MAP_PROPERTY, false, vendor);
        api.put("getPropertyNames", map.getMethod("getPropertyNames"));
        api.put("get", map.getMethod("get", String.class));
    }

    /**
     * The members {@code nodeConfig} calls beyond the node lookup (024 D-10); never
     * {@code setNodeConfigurationProperty} (FR-032).
     */
    private static void nodeConfigApi(Map<String, Executable> api, ClassLoader vendor,
        Class<?> cluster, Class<?> nodeName) throws ReflectiveOperationException {
        api.put("getNodeConfigurationProperty", cluster.getMethod("getNodeConfigurationProperty",
            nodeName, String.class));
        Class<?> property = Class.forName(IBIS_PROPERTY, false, vendor);
        for (String getter : List.of("getType", "getStringValue", "getDisplayName",
            "getShortDescription", "getCategory", "isEditable", "isDeprecated")) {
            api.put(getter, property.getMethod(getter));
        }
    }

    /** The download thread class and the members that report its result. */
    private static Class<?> downloadThread(Map<String, Executable> api, ClassLoader vendor)
        throws ReflectiveOperationException {
        Class<?> download = Class.forName(DOWNLOAD_THREAD, false, vendor);
        if (!Thread.class.isAssignableFrom(download)) {
            throw new ClassNotFoundException(DOWNLOAD_THREAD + " as a Thread");
        }
        api.put("exceptionOccured", download.getMethod("exceptionOccured"));
        api.put("getException", download.getMethod("getException"));
        return download;
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
     * Logs in, reads the requested parts, the web service log, the own node, the cluster's
     * nodes or the own node's configuration and logs out (8.1); returns the exit code.
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
            } else if (OWN_NODE.equals(request.get(OP))) {
                lookUpNode(api, client, request, out, secrets);
            } else if (CLUSTER_NODES.equals(request.get(OP))) {
                listClusterNodes(api, client, request, out, secrets);
            } else if (NODE_CONFIG.equals(request.get(OP))) {
                readNodeConfig(api, client, request, out, secrets);
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
     * naming that node explicitly (D-5); after {@code node ok}, writes the {@code part log}
     * frame.
     */
    private static void readServerLog(Map<String, Executable> api, Object client,
        Map<String, String> request, PrintStream out, List<String> secrets)
        throws ReflectiveOperationException, InterruptedException {
        Object[] node = lookUpNode(api, client, request, out, secrets);
        if (node == null) {
            return;
        }
        String nonce = request.get("nonce");
        File file = new File(request.get("logOut"));
        try {
            Thread thread = (Thread) call(api, "clusterDownload", node[0], file, node[1],
                WEBSERVICE_LOG);
            transfer(api, thread, file, nonce, "log", out, secrets);
        } catch (InvocationTargetException | LinkageError e) {
            partError(out, nonce, "log", vendorFailure(e), secrets);
        }
    }

    /**
     * Creates the {@code ClusterClient}, asks it for the answering node ({@code getNode()}) and
     * writes the {@code node} frame (021 D-5, 024 D-3). Returns the cluster client and the
     * node's {@code NodeName}, in this order, after {@code node ok}; {@code null} after
     * {@code node error}. A lookup that yields no node or no node name is a {@code node error}.
     */
    private static Object[] lookUpNode(Map<String, Executable> api, Object client,
        Map<String, String> request, PrintStream out, List<String> secrets)
        throws ReflectiveOperationException {
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
            return null;
        }
        if (name == null) {
            // a node lookup without a node name is a failed one: no node to work with;
            // nothing was thrown, so the frame carries no exception chain
            frame(out, nonce, "node", "error", "",
                "the cluster client returned no node or node name");
            return null;
        }
        frame(out, nonce, "node", "ok", name);
        return new Object[] {cluster, node};
    }

    /**
     * Looks up the answering node, lists the cluster's members ({@code getNodes()}) and, if
     * {@code details} names one of them, writes that node's information (024 D-2, D-7). The
     * members are read before the first {@code member} frame, so a failure or a {@code null}
     * node list gives {@code members error} alone.
     */
    private static void listClusterNodes(Map<String, Executable> api, Object client,
        Map<String, String> request, PrintStream out, List<String> secrets)
        throws ReflectiveOperationException {
        Object[] node = lookUpNode(api, client, request, out, secrets);
        if (node == null) {
            return;
        }
        String nonce = request.get("nonce");
        String details = request.get("details");
        List<String[]> members = new ArrayList<>();
        int count = 0;
        boolean listed = false;
        Object nodes;
        try {
            nodes = call(api, "getNodes", node[0]);
            for (Object member : nodes == null ? List.of() : (Iterable<?>) nodes) {
                Object nodeName = call(api, "getNodeName", member);
                String name = nodeName == null ? null : (String) call(api, "getName", nodeName);
                listed |= !details.isEmpty() && details.equals(name);
                if (count++ < MAX_MEMBERS) {
                    members.add(member(api, member, name));
                }
            }
        } catch (InvocationTargetException | LinkageError e) {
            Throwable failure = vendorFailure(e);
            frame(out, nonce, "members", "error", chain(failure), messages(failure, secrets));
            return;
        }
        if (nodes == null) {
            // nothing was thrown, so the frame carries no exception chain
            frame(out, nonce, "members", "error", "", "the cluster client returned no node list");
            return;
        }
        for (String[] member : members) {
            frame(out, nonce, member);
        }
        frame(out, nonce, "members", "ok", Integer.toString(count));
        if (details.isEmpty()) {
            return;
        }
        if (!listed) {
            // the name never reaches the server unless it is a listed member (D-7)
            frame(out, nonce, "details", "unknown");
            return;
        }
        writeDetails(api, node[0], details, nonce, out, secrets);
    }

    /**
     * The tokens of the {@code member} frame of {@code member}: the status constant's
     * {@code name()}; a member without status did not answer, so its uptime (a primitive, 0)
     * is none; every token cut by {@link #cut} at 200 characters and 600 encoded bytes.
     */
    private static String[] member(Map<String, Executable> api, Object member, String name)
        throws ReflectiveOperationException {
        Object status = call(api, "getStatus", member);
        String[] tokens = {"member", name, status == null ? null : ((Enum<?>) status).name(),
            (String) call(api, "getVersion", member), (String) call(api, "getZoneName", member),
            (String) call(api, "getNodeID", member),
            status == null ? null : String.valueOf(call(api, "getUptime", member))};
        for (int i = 1; i < tokens.length; i++) {
            tokens[i] = tokens[i] == null ? ""
                : cut(tokens[i], MAX_MEMBER_CHARS, MAX_MEMBER_TOKEN_BYTES);
        }
        return tokens;
    }

    /**
     * Asks for the node {@code name} ({@code getNode(NodeName)}) and writes its node information
     * as {@code detail} frames: at most 500, key and value cut (token {@code cut}), and only as
     * long as the frames stay within the 256 KiB budget; then {@code details ok} (D-2). The
     * entries are read before the first {@code detail} frame, so a failure gives
     * {@code details error} alone.
     */
    private static void writeDetails(Map<String, Executable> api, Object cluster, String name,
        String nonce, PrintStream out, List<String> secrets) throws ReflectiveOperationException {
        List<String[]> entries = new ArrayList<>();
        Object map = null;
        try {
            Object node = call(api, "getNamedNode", cluster, call(api, "nodeName", null, name));
            Object information = node == null ? null : call(api, "getNodeInformation", node);
            map = information == null ? null : call(api, "getNodeInformationMap", information);
            if (map != null) {
                flatten(api, map, "", entries);
            }
        } catch (InvocationTargetException | LinkageError e) {
            Throwable failure = vendorFailure(e);
            frame(out, nonce, "details", "error", chain(failure), messages(failure, secrets));
            return;
        }
        if (map == null) {
            // nothing was thrown, so the frame carries no exception chain
            frame(out, nonce, "details", "error", "",
                "the cluster client returned no node information");
            return;
        }
        int written = 0;
        int budget = DETAILS_BUDGET_BYTES;
        boolean truncated = false;
        for (String[] entry : entries) {
            String key = cut(entry[0], MAX_DETAIL_KEY_CHARS, Integer.MAX_VALUE);
            String value = cut(entry[1], MAX_DETAIL_VALUE_CHARS, Integer.MAX_VALUE);
            // percent-encoded, so its length is its size in bytes
            String line = key.length() < entry[0].length() || value.length() < entry[1].length()
                ? line(nonce, "detail", key, value, "cut") : line(nonce, "detail", key, value);
            if (written == MAX_DETAILS || line.length() > budget) {
                truncated = true;
                break;
            }
            out.print(line);
            out.flush();
            budget -= line.length();
            written++;
        }
        frame(out, nonce, "details", "ok", Integer.toString(written), Boolean.toString(truncated));
    }

    /**
     * Adds the entries of the map property {@code map} to {@code entries} as key and value (empty
     * for none), in the map's order; the entries of a nested map property as
     * {@code <outer>.<inner>}.
     */
    private static void flatten(Map<String, Executable> api, Object map, String prefix,
        List<String[]> entries) throws ReflectiveOperationException {
        for (Object name : (Iterable<?>) call(api, "getPropertyNames", map)) {
            String key = prefix + name;
            Object property = call(api, "get", map, name);
            if (property != null && isMapProperty(property)) {
                flatten(api, property, key + ".", entries);
            } else {
                Object value = property == null ? null : call(api, "getStringValue", property);
                entries.add(new String[] {key, value == null ? "" : (String) value});
            }
        }
    }

    private static boolean isMapProperty(Object property) {
        for (Class<?> type = property.getClass(); type != null; type = type.getSuperclass()) {
            if (MAP_PROPERTY.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Looks up the answering node and asks it for each requested configuration property, in the
     * request's order, writing one {@code config} frame per name (024 D-10): {@code ok} with the
     * type's simple name, the flags and the texts, {@code absent} for a {@code null} answer,
     * {@code error} for a failed request, after which the other names are still read. Only names
     * of {@link #NODE_CONFIG_NAMES} get here (checked with the request).
     */
    private static void readNodeConfig(Map<String, Executable> api, Object client,
        Map<String, String> request, PrintStream out, List<String> secrets)
        throws ReflectiveOperationException {
        Object[] node = lookUpNode(api, client, request, out, secrets);
        if (node == null) {
            return;
        }
        String nonce = request.get("nonce");
        for (String name : request.get("names").split(",")) {
            String[] tokens;
            try {
                tokens = config(api, name,
                    call(api, "getNodeConfigurationProperty", node[0], node[1], name));
            } catch (InvocationTargetException | LinkageError e) {
                Throwable failure = vendorFailure(e);
                frame(out, nonce, "config", name, "error", chain(failure),
                    messages(failure, secrets));
                continue;
            }
            frame(out, nonce, tokens);
        }
    }

    /**
     * The tokens of the {@code config} frame of the property {@code name}; every text cut by
     * {@link #cut} at 4,000 characters and 12,000 encoded bytes, empty for none.
     */
    private static String[] config(Map<String, Executable> api, String name, Object property)
        throws ReflectiveOperationException {
        if (property == null) {
            return new String[] {"config", name, "absent"};
        }
        Class<?> type = (Class<?>) call(api, "getType", property);
        String[] tokens = {"config", name, "ok", type == null ? "" : type.getSimpleName(),
            String.valueOf(call(api, "isEditable", property)),
            String.valueOf(call(api, "isDeprecated", property)),
            (String) call(api, "getDisplayName", property),
            (String) call(api, "getCategory", property),
            (String) call(api, "getShortDescription", property),
            (String) call(api, "getStringValue", property)};
        for (int i = 6; i < tokens.length; i++) {
            tokens[i] = tokens[i] == null ? ""
                : cut(tokens[i], MAX_CONFIG_TEXT_CHARS, MAX_CONFIG_TEXT_BYTES);
        }
        return tokens;
    }

    /**
     * {@code text} cut, whole characters only, at {@code maxChars} characters and at
     * {@code maxEncodedBytes} bytes of its percent-encoded form.
     */
    private static String cut(String text, int maxChars, int maxEncodedBytes) {
        int end = 0;
        int bytes = 0;
        while (end < text.length()) {
            int next = text.offsetByCodePoints(end, 1);
            bytes += encode(text.substring(end, next)).length();
            if (next > maxChars || bytes > maxEncodedBytes) {
                break;
            }
            end = next;
        }
        return text.substring(0, end);
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
            if ((!SHARED_KEYS.contains(key) && !isOperationKey(key))
                || request.containsKey(key)) {
                throw new IllegalArgumentException("unknown or repeated key");
            }
            request.put(key, decode(line.substring(equals + 1)));
        }
        // a request without op is a feature-020 queue-entry read (021 D-4)
        request.putIfAbsent(OP, QUEUE_ENTRY);
        validate(request);
        return request;
    }

    private static boolean isOperationKey(String key) {
        for (List<String> keys : OPERATION_KEYS.values()) {
            if (keys.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** Checks the keys of the operation, each exactly once, and their values. */
    private static void validate(Map<String, String> request) {
        String op = request.get(OP);
        List<String> operationKeys = OPERATION_KEYS.get(op);
        if (operationKeys == null) {
            throw new IllegalArgumentException("unknown operation");
        }
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
        boolean valid = switch (op) {
            case QUEUE_ENTRY -> List.of("data,variables", "variables").contains(
                request.get("parts"))
                && !(request.get("parts").startsWith("data") && request.get("dataOut").isEmpty());
            // the only kind; the helper maps it to the constant file name (D-2, D-4)
            case SERVER_LOG -> "webservice".equals(request.get("log"));
            case CLUSTER_NODES -> request.get("details").isEmpty()
                || request.get("details").matches("[A-Za-z0-9_.:-]{1,128}");
            case NODE_CONFIG -> validNames(request.get("names"));
            case IO_LOG -> validIoLog(request);
            default -> true;
        };
        if (!valid) {
            throw new IllegalArgumentException("invalid value");
        }
    }

    /** Comma-separated, distinct names of {@link #NODE_CONFIG_NAMES} (at most all of them). */
    private static boolean validNames(String names) {
        Set<String> seen = new HashSet<>();
        for (String name : names.split(",", -1)) {
            if (!NODE_CONFIG_NAMES.contains(name) || !seen.add(name)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@code ioLog} values (024 D-2, D-8): a process id, a workflow name that cannot change
     * the object or download name it is put into, a message reference with its target file or
     * neither.
     */
    private static boolean validIoLog(Map<String, String> request) {
        String workflow = request.get("workflow");
        for (int i = 0; i < workflow.length(); i++) {
            if (Character.isISOControl(workflow.charAt(i))) {
                return false;
            }
        }
        String ref = request.get("ref");
        return request.get("processId").matches("[0-9A-Za-z_-]{1,64}")
            && workflow.length() <= MAX_WORKFLOW_CHARS
            && !workflow.contains("@@@") && !workflow.contains("###")
            && (ref.isEmpty() || ref.matches("[A-Za-z0-9_-]{22}"))
            && ref.isEmpty() == request.get("messageOut").isEmpty();
    }

    /**
     * Writes the frame {@code #QE1:<nonce> <token> …}, every token percent-encoded and an empty
     * token as {@code -} (so every token is visible; no class name is {@code -}, and a message
     * that is just {@code -} carries nothing either).
     */
    static void frame(PrintStream out, String nonce, String... tokens) {
        out.print(line(nonce, tokens));
        out.flush();
    }

    /** The line of the frame {@code tokens}, with its line feed, as {@link #frame} writes it. */
    private static String line(String nonce, String... tokens) {
        StringBuilder line = new StringBuilder(FRAME_PREFIX).append(nonce);
        for (String token : tokens) {
            line.append(' ').append(token.isEmpty() ? EMPTY_TOKEN : encode(token));
        }
        return line.append('\n').toString();
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
