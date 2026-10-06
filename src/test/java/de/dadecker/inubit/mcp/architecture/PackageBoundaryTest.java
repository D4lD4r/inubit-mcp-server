package de.dadecker.inubit.mcp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.Main;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * T123: the dependency boundaries of Constitution V (adapter isolation) and research R-2 (only
 * the MCP layer knows the MCP SDK), checked on the <em>compiled</em> production classes.
 *
 * <p>Every class file below {@code target/classes} is read without a bytecode library: the
 * constant pool is parsed and every type name it mentions is collected — class references,
 * field/method descriptors, generic signatures and annotation types (all are {@code Utf8}
 * entries in internal form, e.g. {@code Ljava/net/http/HttpClient;}) — plus the members it
 * calls or reads ({@code java/lang/Runtime.exec}). A {@code Utf8} entry that is referenced
 * <em>only</em> by {@code CONSTANT_String} entries backs nothing but a string literal and is
 * ignored, so a message text never counts as a dependency; one that is also referenced by a
 * class, method type or name-and-type entry is kept. This catches fully qualified uses that an
 * import scan would miss.
 *
 * <p>Not detectable this way: dependencies created by reflection from a computed or literal
 * class name ({@code Class.forName("…")}, {@code MethodHandles} lookups); the production code
 * does not use reflection to reach other layers.
 *
 * <p>Rules (Phase 7 review P3): {@code domain} may depend only on {@code java.*} and itself;
 * {@code application} only on {@code java.*}, {@code domain}, itself and SLF4J; only
 * {@code mcp} uses the MCP SDK; {@code mcp} never reaches into {@code adapter} or
 * {@code config}; only {@code adapter.rest} uses {@code java.net.http} (feature 004: and
 * {@code adapter.soap}); only {@code adapter.cli}
 * starts or inspects OS processes ({@code ProcessBuilder}, {@code Process},
 * {@code ProcessHandle}, {@code Runtime.exec}).
 *
 * <p>Feature 003 (T004, plan "Constitution Check" V): the workspace adapters
 * {@code adapter.archive}, {@code adapter.git} and {@code adapter.xslt} depend neither on
 * {@code mcp} nor on {@code application}; {@code application} reaches no adapter at all (only the
 * ports of {@code domain.port}); Saxon ({@code net.sf.saxon}) is used only in
 * {@code adapter.xslt}.
 *
 * <p>Feature 004 (T003, research D-17): the SOAP adapter {@code adapter.soap} exists, depends
 * neither on {@code mcp} nor on {@code application}, and is the only package besides
 * {@code adapter.rest} that uses {@code java.net.http} (the end-to-end test client); the new
 * adapters are reached from {@code application} only through ports (rule above).
 */
class PackageBoundaryTest {

    private static final String BASE = "de/dadecker/inubit/mcp/";
    private static final String MCP_SDK = "io/modelcontextprotocol/";
    private static final String HTTP_CLIENT = "java/net/http/";
    private static final Set<String> PROCESS_TYPES = Set.of("java/lang/ProcessBuilder",
        "java/lang/Process", "java/lang/ProcessHandle");
    private static final String RUNTIME_EXEC = "java/lang/Runtime.exec";
    private static final String SAXON = "net/sf/saxon/";
    /** The adapters of feature 003, each with its own package (T004). */
    private static final List<String> WORKSPACE_ADAPTERS =
        List.of("adapter/archive", "adapter/git", "adapter/xslt");
    /** The SOAP end-to-end adapter of feature 004 (T003). */
    private static final String SOAP_ADAPTER = "adapter/soap";

    /** Class (internal name) → its type names and member references. */
    private static Map<String, ClassRefs> classes;

    @BeforeAll
    static void readCompiledClasses() throws IOException, URISyntaxException {
        Path root = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation()
            .toURI());
        assertThat(root.resolve(BASE + "Main.class")).as("compiled production classes")
            .isRegularFile();
        classes = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))
                ::iterator) {
                String name = root.relativize(file).toString().replace('\\', '/');
                classes.put(name.substring(0, name.length() - ".class".length()),
                    ConstantPool.read(Files.readAllBytes(file)));
            }
        }
    }

    // --- rules ----------------------------------------------------------------------------

    @Test
    void domainDependsOnlyOnTheJdkAndItself() {
        assertThat(violations(inPackage("domain"),
            allowedOnly("java/", BASE + "domain/")))
            .as("domain → anything but java.* and domain").isEmpty();
    }

    @Test
    void applicationDependsOnlyOnTheJdkTheDomainItselfAndSlf4j() {
        assertThat(violations(inPackage("application"),
            allowedOnly("java/", BASE + "domain/", BASE + "application/", "org/slf4j/")))
            .as("application → anything but java.*, domain, application, SLF4J").isEmpty();
    }

    @Test
    void neitherDomainNorApplicationUsesHttpOrProcesses() {
        Predicate<String> scope = inPackage("domain").or(inPackage("application"));
        assertThat(violations(scope, forbidden(HTTP_CLIENT).or(PROCESS_TYPES::contains)))
            .isEmpty();
        assertThat(memberViolations(scope, RUNTIME_EXEC)).isEmpty();
    }

    @Test
    void onlyTheMcpLayerUsesTheMcpSdk() {
        assertThat(violations(inPackage("mcp").negate(), forbidden(MCP_SDK)))
            .as("MCP SDK outside de.dadecker.inubit.mcp.mcp").isEmpty();
    }

    @Test
    void theMcpLayerDoesNotReachIntoAdaptersOrConfiguration() {
        assertThat(violations(inPackage("mcp"), forbidden(BASE + "adapter/", BASE + "config/")))
            .as("mcp → adapter/config").isEmpty();
    }

    @Test
    void onlyTheRestAndSoapAdaptersUseTheHttpClient() {
        assertThat(violations(inPackage("adapter/rest").or(inPackage(SOAP_ADAPTER)).negate(),
            forbidden(HTTP_CLIENT)))
            .as("java.net.http outside adapter.rest and adapter.soap").isEmpty();
    }

    @Test
    void theSoapAdapterReachesNeitherTheMcpLayerNorTheApplication() {
        assertThat(violations(inPackage(SOAP_ADAPTER), forbidden(BASE + "mcp/",
            BASE + "application/")))
            .as("adapter.soap → mcp / application").isEmpty();
    }

    @Test
    void onlyTheCliAdapterStartsOrInspectsProcesses() {
        assertThat(violations(inPackage("adapter/cli").negate(), PROCESS_TYPES::contains))
            .as("ProcessBuilder / Process / ProcessHandle outside adapter.cli").isEmpty();
        assertThat(memberViolations(inPackage("adapter/cli").negate(), RUNTIME_EXEC))
            .as("Runtime.exec outside adapter.cli").isEmpty();
    }

    @Test
    void theWorkspaceAdaptersReachNeitherTheMcpLayerNorTheApplication() {
        Predicate<String> scope = WORKSPACE_ADAPTERS.stream().map(PackageBoundaryTest::inPackage)
            .reduce(name -> false, Predicate::or);
        assertThat(violations(scope, forbidden(BASE + "mcp/", BASE + "application/")))
            .as("adapter.archive / adapter.git / adapter.xslt → mcp / application").isEmpty();
    }

    @Test
    void theApplicationReachesAdaptersOnlyThroughThePorts() {
        assertThat(violations(inPackage("application"), forbidden(BASE + "adapter/")))
            .as("application → adapter (use domain.port)").isEmpty();
    }

    @Test
    void onlyTheXsltAdapterUsesSaxon() {
        assertThat(violations(inPackage("adapter/xslt").negate(), forbidden(SAXON)))
            .as("net.sf.saxon outside adapter.xslt").isEmpty();
    }

    /**
     * The packages of the workspace adapters and of the SOAP adapter exist and are documented. A {@code package-info}
     * without annotations compiles to no class file, so the sources are checked.
     */
    @Test
    void theWorkspaceAdapterPackagesExist() {
        Path sources = Path.of("src", "main", "java");
        for (String pkg : List.of("adapter/archive/v81", "adapter/git", "adapter/xslt",
            SOAP_ADAPTER)) {
            assertThat(sources.resolve(BASE + pkg).resolve("package-info.java"))
                .as("package-info of %s", pkg).isRegularFile();
        }
    }

    /** Guards against a vacuous pass: the scan must see the dependencies that do exist. */
    @Test
    void theScanSeesTheExistingDependencies() {
        assertThat(classes).containsKeys(BASE + "domain/model/NodeId",
            BASE + "application/HealthService", BASE + "mcp/McpServerFactory");
        assertThat(types(BASE + "mcp/McpServerFactory")).anyMatch(t -> t.startsWith(MCP_SDK));
        assertThat(types(BASE + "adapter/rest/InubitHttpClient"))
            .anyMatch(t -> t.startsWith(HTTP_CLIENT));
        assertThat(types(BASE + "adapter/cli/SystemProcessLauncher"))
            .contains("java/lang/ProcessBuilder", "java/lang/Process");
        assertThat(types(BASE + "adapter/cli/CliResources")).contains("java/lang/ProcessHandle");
        assertThat(types(BASE + "application/FanOut")).anyMatch(t -> t.startsWith("org/slf4j/"));
        assertThat(classes.get(BASE + "Wiring").members())
            .contains("java/lang/Runtime.getRuntime");
    }

    // --- the constant-pool reader (review P4) ----------------------------------------------

    @Test
    void aTextUsedOnlyAsAStringLiteralIsNotADependency() {
        ClassRefs refs = ConstantPool.read(ClassFiles.build(pool -> {
            int literal = pool.utf8("java/net/http/HttpClient");
            pool.string(literal);
        }));

        assertThat(refs.texts()).noneMatch(text -> text.contains(HTTP_CLIENT));
        assertThat(referencedTypes(refs.texts())).isEmpty();
        assertThat(refs.texts()).contains("Dummy");
    }

    @Test
    void aTextSharedByAStringLiteralAndAClassReferenceIsKept() {
        ClassRefs refs = ConstantPool.read(ClassFiles.build(pool -> {
            int name = pool.utf8("java/lang/ProcessBuilder");
            pool.string(name);
            pool.classRef(name);
        }));

        assertThat(referencedTypes(refs.texts())).contains("java/lang/ProcessBuilder");
    }

    @Test
    void methodReferencesAreResolvedToOwnerAndName() {
        ClassRefs refs = ConstantPool.read(ClassFiles.build(pool -> {
            int owner = pool.classRef(pool.utf8("java/lang/Runtime"));
            int nameAndType = pool.nameAndType(pool.utf8("exec"),
                pool.utf8("(Ljava/lang/String;)Ljava/lang/Process;"));
            pool.methodRef(owner, nameAndType);
        }));

        assertThat(refs.members()).containsExactly("java/lang/Runtime.exec");
        assertThat(referencedTypes(refs.texts())).contains("java/lang/Process",
            "java/lang/Runtime", "java/lang/String");
    }

    // --- helpers --------------------------------------------------------------------------

    private static Predicate<String> inPackage(String pkg) {
        return name -> name.startsWith(BASE + pkg + "/");
    }

    private static Predicate<String> forbidden(String... prefixes) {
        return ref -> Stream.of(prefixes).anyMatch(ref::startsWith);
    }

    private static Predicate<String> allowedOnly(String... prefixes) {
        return forbidden(prefixes).negate();
    }

    private static Set<String> types(String className) {
        return referencedTypes(classes.get(className).texts());
    }

    private static List<String> violations(Predicate<String> scope, Predicate<String> rule) {
        List<String> found = new ArrayList<>();
        classes.forEach((name, refs) -> {
            if (scope.test(name)) {
                for (String type : referencedTypes(refs.texts())) {
                    if (rule.test(type)) {
                        found.add(name.replace('/', '.') + " → " + type.replace('/', '.'));
                    }
                }
            }
        });
        return found;
    }

    private static List<String> memberViolations(Predicate<String> scope, String member) {
        List<String> found = new ArrayList<>();
        classes.forEach((name, refs) -> {
            if (scope.test(name) && refs.members().contains(member)) {
                found.add(name.replace('/', '.') + " → " + member.replace('/', '.'));
            }
        });
        return found;
    }

    /**
     * The internal type names inside the texts: a class constant is a name itself
     * ({@code java/net/http/HttpClient}, or an array descriptor); descriptors and signatures
     * carry names as {@code L<name>;} or {@code L<name><…>;}. Array element types count as the
     * element type.
     */
    private static Set<String> referencedTypes(Set<String> texts) {
        Set<String> types = new TreeSet<>();
        for (String text : texts) {
            if (text.indexOf(';') < 0 && text.indexOf('(') < 0 && text.indexOf('<') < 0) {
                if (text.indexOf('/') >= 0) {
                    types.add(text);
                }
                continue;
            }
            int i = 0;
            while ((i = text.indexOf('L', i)) >= 0) {
                int end = i + 1;
                while (end < text.length() && ";<().".indexOf(text.charAt(end)) < 0) {
                    end++;
                }
                String candidate = text.substring(i + 1, end);
                if (candidate.indexOf('/') > 0) {
                    types.add(candidate);
                }
                i = end;
            }
        }
        return types;
    }

    /** What a class file refers to. */
    record ClassRefs(Set<String> texts, Set<String> members) {
    }

    /** A minimal class-file constant-pool reader (JVMS §4.4); no bytecode library needed. */
    static final class ConstantPool {

        private ConstantPool() {
        }

        static ClassRefs read(byte[] classFile) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile))) {
                if (in.readInt() != 0xCAFEBABE) {
                    throw new IllegalArgumentException("not a class file");
                }
                in.readUnsignedShort(); // minor
                in.readUnsignedShort(); // major
                int count = in.readUnsignedShort();
                String[] utf8 = new String[count];
                int[] classNames = new int[count];
                int[][] nameAndTypes = new int[count][];
                List<int[]> memberRefs = new ArrayList<>();
                Set<Integer> literalOnly = new HashSet<>();
                Set<Integer> structural = new HashSet<>();
                for (int i = 1; i < count; i++) {
                    int tag = in.readUnsignedByte();
                    switch (tag) {
                        case 1 -> utf8[i] = in.readUTF();
                        case 8 -> literalOnly.add(in.readUnsignedShort()); // String
                        case 7 -> { // Class
                            classNames[i] = in.readUnsignedShort();
                            structural.add(classNames[i]);
                        }
                        case 16 -> structural.add(in.readUnsignedShort()); // MethodType
                        case 19, 20 -> in.readUnsignedShort(); // Module, Package
                        case 15 -> in.skipBytes(3); // MethodHandle
                        case 9, 10, 11 -> memberRefs.add(new int[] {in.readUnsignedShort(),
                            in.readUnsignedShort()}); // Field-, Method-, InterfaceMethodref
                        case 12 -> { // NameAndType
                            int name = in.readUnsignedShort();
                            int descriptor = in.readUnsignedShort();
                            nameAndTypes[i] = new int[] {name, descriptor};
                            structural.add(name);
                            structural.add(descriptor);
                        }
                        case 3, 4, 17, 18 -> in.skipBytes(4);
                        case 5, 6 -> {
                            in.skipBytes(8);
                            i++; // Long and Double take two slots
                        }
                        default -> throw new IllegalArgumentException("constant tag " + tag);
                    }
                }
                literalOnly.removeAll(structural);
                Set<String> texts = new HashSet<>();
                for (int i = 1; i < count; i++) {
                    if (utf8[i] != null && !literalOnly.contains(i)) {
                        texts.add(utf8[i]);
                    }
                }
                Set<String> members = new HashSet<>();
                for (int[] ref : memberRefs) {
                    int[] nameAndType = nameAndTypes[ref[1]];
                    members.add(utf8[classNames[ref[0]]] + "." + utf8[nameAndType[0]]);
                }
                return new ClassRefs(texts, members);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Builds tiny class files for the reader tests. */
    static final class ClassFiles {

        /** Appends constants; every method returns the new constant's index. */
        static final class Pool {
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            private final DataOutputStream out = new DataOutputStream(bytes);
            private int next = 1;
            private final Map<String, Integer> utf8 = new HashMap<>();

            int utf8(String text) {
                return utf8.computeIfAbsent(text, key -> write(1, o -> o.writeUTF(key)));
            }

            int string(int utf8Index) {
                return write(8, o -> o.writeShort(utf8Index));
            }

            int classRef(int utf8Index) {
                return write(7, o -> o.writeShort(utf8Index));
            }

            int nameAndType(int name, int descriptor) {
                return write(12, o -> {
                    o.writeShort(name);
                    o.writeShort(descriptor);
                });
            }

            int methodRef(int owner, int nameAndType) {
                return write(10, o -> {
                    o.writeShort(owner);
                    o.writeShort(nameAndType);
                });
            }

            private int write(int tag, IoConsumer body) {
                try {
                    out.writeByte(tag);
                    body.accept(out);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return next++;
            }
        }

        @FunctionalInterface
        interface IoConsumer {
            void accept(DataOutputStream out) throws IOException;
        }

        private ClassFiles() {
        }

        static byte[] build(java.util.function.Consumer<Pool> constants) {
            Pool pool = new Pool();
            pool.classRef(pool.utf8("Dummy"));
            constants.accept(pool);
            pool.write(5, o -> o.writeLong(42)); // a Long takes two slots
            pool.next++;
            try (ByteArrayOutputStream file = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(file)) {
                out.writeInt(0xCAFEBABE);
                out.writeShort(0);
                out.writeShort(65);
                out.writeShort(pool.next);
                out.write(pool.bytes.toByteArray());
                return file.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
