package de.dadecker.inubit.mcp.adapter.archive.v81;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic Partner Management exports of feature 018 (T010): {@code workflow/workflow.xml}
 * documents in the structure of the recorded {@code model_export_systemdiagram} and
 * {@code export_systemdiagram} fixtures (research D-2 facts), with synthetic values only.
 *
 * <p>A system diagram holds {@code WorkflowModule moduleType="sdCommunicationCloud"} modules; each
 * carries {@code Properties/Property name="PartnerManagementSettings" type="Map"} with
 * {@code PartnerFields}, {@code ReferencableTWFs} and {@code Partners}. The sets:
 * <ul>
 *   <li>{@link #detail()}: three partners in one cloud: {@code ACME-0001} with two FTP entries
 *       for two workflows and the AES password {@link #AES_PASSWORD}, {@code ACME-0002} with an
 *       SFTP-via-FTP entry ({@code FTPProtocol=SFTP}) holding the clear-text legacy password
 *       {@link #LEGACY_PASSWORD} and an {@code AS4} entry with the unknown setting
 *       {@link #UNKNOWN_SETTING} and the encrypted {@code Password} {@link #AES_PASSWORD},
 *       {@code ACME-0003} with a WebService entry with WS-Security
 *       credentials ({@code WSSecurityPassword} without type: {@link #WS_SECURITY_PASSWORD}) and
 *       a WebService entry whose {@code referencableTWFId} ({@link #UNRESOLVED_TWF_ID}) does not
 *       resolve. {@code ACME-0001} has the secret custom field {@link #SECRET_FIELD},
 *       {@code ACME-0002} the plain field {@link #KEY_MATERIAL_FIELD} holding key material.</li>
 *   <li>{@link #list(int)}: any number of partners with one entry each.</li>
 *   <li>{@link #twoClouds()}: two clouds of one diagram that both hold {@link #SHARED_ID}.</li>
 *   <li>{@link #stages()}: two stages with two servers each and the differences named there.</li>
 * </ul>
 */
public final class PartnerFixtures {

    /** An AES-encrypted password ({@code encrypted="true" type="Password"}). */
    public static final String AES_PASSWORD = "AES-c3ludGhldGljLXBhcnRuZXItMDAx";
    /** A second AES-encrypted password (the one that differs in {@link #stages()}). */
    public static final String OTHER_AES_PASSWORD = "AES-c3ludGhldGljLXBhcnRuZXItMDAy";
    /** A clear-text legacy {@code Password} ({@code type="Password"}, no {@code encrypted}). */
    public static final String LEGACY_PASSWORD = "synthetic-legacy-password-0001";
    /** A {@code WSSecurityPassword} without {@code type} and without {@code encrypted}. */
    public static final String WS_SECURITY_PASSWORD = "synthetic-ws-security-0001";
    /** The custom partner field whose {@code fieldType} is not plain. */
    public static final String SECRET_FIELD = "PortalPin";
    /** The value of {@link #SECRET_FIELD}. */
    public static final String SECRET_FIELD_VALUE = "synthetic-portal-pin-0001";
    /** A custom partner field of the plain {@code fieldType} {@code String}. */
    public static final String KEY_MATERIAL_FIELD = "Note";
    /** Key material held by the plain field {@link #KEY_MATERIAL_FIELD}. */
    public static final String KEY_MATERIAL = """
        -----BEGIN PRIVATE KEY-----
        MIIBfixture
        -----END PRIVATE KEY-----""";
    /** Every synthetic secret value of these fixtures, for the leak checks. */
    public static final List<String> SECRETS = List.of(AES_PASSWORD, OTHER_AES_PASSWORD,
        LEGACY_PASSWORD, WS_SECURITY_PASSWORD, SECRET_FIELD_VALUE, KEY_MATERIAL);

    /** The setting name of the {@code AS4} entry that no known protocol uses. */
    public static final String UNKNOWN_SETTING = "EndpointProfile";
    /** A {@code referencableTWFId} that no {@code ReferencableTWFs} entry has. */
    public static final String UNRESOLVED_TWF_ID = "99";
    /** The partner {@code Id} both clouds of {@link #twoClouds()} hold. */
    public static final String SHARED_ID = "ACME-0001";
    /** The diagram group of every set. */
    public static final String GROUP = "GRP-PM";
    /** The owner of every set. */
    public static final String OWNER = "OWNERS";

    private static final List<TwfXml> WORKFLOWS = List.of(new TwfXml("1", "34", "Workflow-0001"),
        new TwfXml("2", "86", "Workflow-0002"), new TwfXml("3", "301", "Workflow-0003"));
    private static final List<FieldXml> ID_AND_NAME =
        List.of(new FieldXml("Id", "String"), new FieldXml("Name", "String"));

    private PartnerFixtures() {
    }

    /** A leaf {@code Property}: {@code type} is empty if absent. */
    public record Leaf(String name, String type, boolean encrypted, String text) {

        public static Leaf plain(String name, String text) {
            return new Leaf(name, "", false, text);
        }

        public static Leaf typed(String name, String type, String text) {
            return new Leaf(name, type, false, text);
        }

        /** {@code encrypted="true" type="Password"}, as recorded for AES values. */
        public static Leaf encrypted(String name, String text) {
            return new Leaf(name, "Password", true, text);
        }
    }

    /** One {@code PartnerFields} entry. */
    public record FieldXml(String name, String fieldType) {
    }

    /** One {@code ReferencableTWFs} entry. */
    public record TwfXml(String id, String moduleId, String name) {
    }

    /** One {@code ReferencedTWF}. */
    public record EntryXml(String protocol, String referencableTWFId, List<Leaf> protocolSettings,
        List<Leaf> additionalParameters) {

        public EntryXml {
            protocolSettings = List.copyOf(protocolSettings);
            additionalParameters = List.copyOf(additionalParameters);
        }
    }

    /** One {@code Partner}: its {@code Fields} and {@code ReferencedTWFs}. */
    public record PartnerXml(List<Leaf> fields, List<EntryXml> entries) {

        public PartnerXml {
            fields = List.copyOf(fields);
            entries = List.copyOf(entries);
        }
    }

    /** One {@code sdCommunicationCloud} module with its Partner Management. */
    public record CloudXml(String moduleId, String moduleName, List<FieldXml> partnerFields,
        List<TwfXml> referencable, List<PartnerXml> partners) {

        public CloudXml {
            partnerFields = List.copyOf(partnerFields);
            referencable = List.copyOf(referencable);
            partners = List.copyOf(partners);
        }
    }

    /** One system diagram. */
    public record DiagramXml(String group, String name, String owner, List<CloudXml> clouds) {

        public DiagramXml {
            clouds = List.copyOf(clouds);
        }
    }

    /** The three detail partners (see the class comment), in {@code SystemDiagram-01}. */
    public static DiagramXml detail() {
        PartnerXml ftp = new PartnerXml(
            List.of(Leaf.plain("Id", "ACME-0001"), Leaf.plain("Name", "ACME Corp"),
                Leaf.plain(SECRET_FIELD, SECRET_FIELD_VALUE)),
            List.of(ftp("1", "ftp1.example.test", "login1",
                    Leaf.encrypted("Password", AES_PASSWORD), "FTP"),
                ftp("2", "ftp1.example.test", "login1",
                    Leaf.encrypted("Password", AES_PASSWORD), "FTP")));
        PartnerXml sftp = new PartnerXml(
            List.of(Leaf.plain("Id", "ACME-0002"), Leaf.plain("Name", "ACME Logistics"),
                Leaf.plain(KEY_MATERIAL_FIELD, KEY_MATERIAL)),
            List.of(ftp("1", "sftp2.example.test", "login2",
                    Leaf.typed("Password", "Password", LEGACY_PASSWORD), "SFTP"),
                new EntryXml("AS4", "3", List.of(
                    Leaf.plain("ServerName", "https://as4.example.test/msh"),
                    Leaf.plain(UNKNOWN_SETTING, "profile-1"),
                    Leaf.encrypted("Password", AES_PASSWORD)), List.of())));
        PartnerXml webService = new PartnerXml(
            List.of(Leaf.plain("Id", "ACME-0003"), Leaf.plain("Name", "ACME Retail")),
            List.of(webService("3", "http://host3.example.test/path1", "login3",
                    Leaf.encrypted("Password", AES_PASSWORD),
                    Leaf.plain("WSSecurityPassword", WS_SECURITY_PASSWORD)),
                webService(UNRESOLVED_TWF_ID, "http://host3.example.test/path2", "login3",
                    Leaf.encrypted("Password", AES_PASSWORD),
                    Leaf.encrypted("WSSecurityPassword", AES_PASSWORD))));
        List<FieldXml> fields = List.of(new FieldXml("Id", "String"),
            new FieldXml("Name", "String"), new FieldXml(KEY_MATERIAL_FIELD, "String"),
            new FieldXml(SECRET_FIELD, "Password"));
        return new DiagramXml(GROUP, "SystemDiagram-01", OWNER, List.of(new CloudXml("33",
            "Module-0901", fields, WORKFLOWS, List.of(ftp, sftp, webService))));
    }

    /**
     * {@code count} partners {@code ACME-0001} … in one cloud of {@code SystemDiagram-02}, named
     * {@code ACME Partner <n>}, each with one entry: odd numbers FTP ({@code FTPProtocol=SFTP}),
     * even numbers WebService; the workflow is {@code Workflow-000<n % 3 + 1>}.
     */
    public static DiagramXml list(int count) {
        List<PartnerXml> partners = new ArrayList<>();
        for (int n = 1; n <= count; n++) {
            String twf = String.valueOf(n % 3 + 1);
            String login = "login-" + n;
            EntryXml entry = n % 2 == 1
                ? ftp(twf, "host" + n % 5 + ".example.test", login,
                    Leaf.encrypted("Password", AES_PASSWORD), "SFTP")
                : webService(twf, "http://host" + n % 5 + ".example.test/path1", login,
                    Leaf.encrypted("Password", AES_PASSWORD),
                    Leaf.encrypted("WSSecurityPassword", AES_PASSWORD));
            partners.add(partner("ACME-%04d".formatted(n), "ACME Partner " + n, entry));
        }
        return new DiagramXml(GROUP, "SystemDiagram-02", OWNER,
            List.of(new CloudXml("33", "Module-0901", ID_AND_NAME, WORKFLOWS, partners)));
    }

    /**
     * {@code SystemDiagram-03} with the clouds {@code Module-0901} and {@code Module-0902}, each
     * holding {@link #SHARED_ID} with one FTP entry ({@code ServerName} {@code cloud1…} and
     * {@code cloud2…}).
     */
    public static DiagramXml twoClouds() {
        List<CloudXml> clouds = new ArrayList<>();
        for (int n = 1; n <= 2; n++) {
            clouds.add(new CloudXml(n == 1 ? "33" : "213", "Module-090" + n, ID_AND_NAME,
                WORKFLOWS, List.of(partner(SHARED_ID, "ACME Corp", ftp("1",
                    "cloud" + n + ".example.test", "login1",
                    Leaf.encrypted("Password", AES_PASSWORD), "FTP")))));
        }
        return new DiagramXml(GROUP, "SystemDiagram-03", OWNER, clouds);
    }

    /**
     * The {@code workflow.xml} of {@code SystemDiagram-01} per server {@code test/node1},
     * {@code test/node2}, {@code prod/node1}, {@code prod/node2} (US3 independent test). Equal
     * everywhere except:
     * <ul>
     *   <li>{@code ACME-0001} (FTP): {@code ServerName} {@code ftp.test.example.test} on stage
     *       {@code test}, {@code ftp.prod.example.test} on {@code prod}; {@code Login}
     *       {@code login-b} on {@code test/node2}, {@code login-a} elsewhere;</li>
     *   <li>{@code ACME-0002} (WebService): {@code Password} {@link #OTHER_AES_PASSWORD} on
     *       {@code prod/node2}, {@link #AES_PASSWORD} elsewhere;</li>
     *   <li>{@code ACME-0003} exists on stage {@code test} only.</li>
     * </ul>
     */
    public static Map<String, byte[]> stages() {
        Map<String, byte[]> servers = new LinkedHashMap<>();
        for (String server : List.of("test/node1", "test/node2", "prod/node1", "prod/node2")) {
            boolean test = server.startsWith("test/");
            List<PartnerXml> partners = new ArrayList<>(List.of(
                partner("ACME-0001", "ACME Corp", ftp("1",
                    test ? "ftp.test.example.test" : "ftp.prod.example.test",
                    server.equals("test/node2") ? "login-b" : "login-a",
                    Leaf.encrypted("Password", AES_PASSWORD), "FTP")),
                partner("ACME-0002", "ACME Logistics", webService("2",
                    "http://host2.example.test/path1", "login2", Leaf.encrypted("Password",
                        server.equals("prod/node2") ? OTHER_AES_PASSWORD : AES_PASSWORD),
                    Leaf.encrypted("WSSecurityPassword", AES_PASSWORD)))));
            if (test) {
                partners.add(partner("ACME-0003", "ACME Retail", ftp("3", "ftp3.example.test",
                    "login3", Leaf.encrypted("Password", AES_PASSWORD), "SFTP")));
            }
            servers.put(server, workflowXml(new DiagramXml(GROUP, "SystemDiagram-01", OWNER,
                List.of(new CloudXml("33", "Module-0901", ID_AND_NAME, WORKFLOWS, partners)))));
        }
        return servers;
    }

    /** The {@code workflow/workflow.xml} of an export of {@code diagrams}, grouped by group. */
    public static byte[] workflowXml(DiagramXml... diagrams) {
        Map<String, List<DiagramXml>> groups = new LinkedHashMap<>();
        for (DiagramXml diagram : diagrams) {
            groups.computeIfAbsent(diagram.group(), g -> new ArrayList<>()).add(diagram);
        }
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<IBISWorkflow version=\"5.3\">\n<Workflows>\n");
        groups.forEach((group, members) -> {
            xml.append("<WorkflowGroup workflowType=\"systemdiagram\">\n<WorkflowGroupName>")
                .append(escape(group)).append("</WorkflowGroupName>\n");
            members.forEach(diagram -> diagram(xml, diagram));
            xml.append("</WorkflowGroup>\n");
        });
        return xml.append("</Workflows>\n</IBISWorkflow>\n").toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    private static void diagram(StringBuilder xml, DiagramXml diagram) {
        xml.append("<Workflow version=\"head\" workflowType=\"systemdiagram\">\n<WorkflowName>")
            .append(escape(diagram.name())).append("</WorkflowName>\n<UserOrUserGroupName>")
            .append(escape(diagram.owner())).append("</UserOrUserGroupName>\n")
            .append("<IsActive>true</IsActive>\n");
        for (CloudXml cloud : diagram.clouds()) {
            xml.append("<WorkflowModule moduleType=\"sdCommunicationCloud\">\n<ModuleId>")
                .append(escape(cloud.moduleId())).append("</ModuleId>\n<ModuleName>")
                .append(escape(cloud.moduleName())).append("</ModuleName>\n")
                .append("<Properties version=\"4.1\">\n")
                .append("<Property name=\"PartnerManagementSettings\" type=\"Map\">\n")
                .append("<Property name=\"PartnerFields\" type=\"List\">\n");
            cloud.partnerFields().forEach(field -> xml.append("<Property fieldType=\"")
                .append(escape(field.fieldType())).append("\" name=\"")
                .append(escape(field.name())).append("\" showInTable=\"true\" />\n"));
            xml.append("</Property>\n<Property name=\"ReferencableTWFs\" type=\"List\">\n");
            cloud.referencable().forEach(twf -> xml.append("<Property id=\"")
                .append(escape(twf.id())).append("\" moduleId=\"").append(escape(twf.moduleId()))
                .append("\" name=\"").append(escape(twf.name())).append("\" />\n"));
            xml.append("</Property>\n<Property name=\"Partners\" type=\"List\">\n");
            cloud.partners().forEach(partner -> partner(xml, partner));
            xml.append("</Property>\n</Property>\n</Properties>\n</WorkflowModule>\n");
        }
        xml.append("</Workflow>\n");
    }

    private static void partner(StringBuilder xml, PartnerXml partner) {
        xml.append("<Property name=\"Partner\" type=\"Map\">\n");
        map(xml, "Fields", partner.fields());
        xml.append("<Property name=\"ReferencedTWFs\" type=\"List\">\n");
        for (EntryXml entry : partner.entries()) {
            xml.append("<Property format=\"format-1\" name=\"ReferencedTWF\" protocol=\"")
                .append(escape(entry.protocol())).append("\" referencableTWFId=\"")
                .append(escape(entry.referencableTWFId())).append("\" type=\"Map\">\n");
            map(xml, "ProtocolSettings", entry.protocolSettings());
            map(xml, "AdditionalParameterSettings", entry.additionalParameters());
            xml.append("</Property>\n");
        }
        xml.append("</Property>\n</Property>\n");
    }

    private static void map(StringBuilder xml, String name, List<Leaf> leaves) {
        xml.append("<Property name=\"").append(name).append("\" type=\"Map\">\n");
        for (Leaf leaf : leaves) {
            xml.append("<Property").append(leaf.encrypted() ? " encrypted=\"true\"" : "")
                .append(" name=\"").append(escape(leaf.name())).append('"')
                .append(leaf.type().isEmpty() ? "" : " type=\"" + escape(leaf.type()) + '"')
                .append(leaf.text().isEmpty() ? " />\n"
                    : ">" + escape(leaf.text()) + "</Property>\n");
        }
        xml.append("</Property>\n");
    }

    private static PartnerXml partner(String id, String name, EntryXml entry) {
        return new PartnerXml(List.of(Leaf.plain("Id", id), Leaf.plain("Name", name)),
            List.of(entry));
    }

    /** An FTP entry as recorded: server, login, password, file name; FTP auth and protocol. */
    private static EntryXml ftp(String twfId, String serverName, String login, Leaf password,
        String ftpProtocol) {
        return new EntryXml("FTP", twfId, List.of(Leaf.plain("ServerName", serverName),
                Leaf.plain("Login", login), password, Leaf.plain("FileName", "FileName-1")),
            List.of(Leaf.typed("FTPAuth", "Boolean", "false"),
                Leaf.plain("FTPProtocol", ftpProtocol)));
    }

    /** A WebService entry as recorded, with WS-Security credentials as additional parameters. */
    private static EntryXml webService(String twfId, String serverName, String login,
        Leaf password, Leaf wsSecurityPassword) {
        return new EntryXml("WebService", twfId, List.of(Leaf.plain("ServerName", serverName),
                Leaf.typed("Auth", "Boolean", "true"), Leaf.plain("Login", login), password,
                Leaf.typed("SSLKeyStore", "HTTPConnectorTrustStore", "")),
            List.of(Leaf.plain("WSSecurityUsername", "ws-" + login), wsSecurityPassword));
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
