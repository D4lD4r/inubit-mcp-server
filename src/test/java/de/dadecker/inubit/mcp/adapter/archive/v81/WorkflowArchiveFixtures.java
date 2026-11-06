package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.RawZip.RawEntry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

/**
 * Variants of the recorded diagram group exports {@code grp-a.zip} and {@code grp-b.zip} for the
 * Workbench archives (feature 015, research R-17), built with {@link RawZip}: every entry keeps
 * its header (name, time, method) and order, only the named content changes.
 */
public final class WorkflowArchiveFixtures {

    /** The owner of {@code grp-b.zip}. */
    public static final String OWNER = "OWNERS";
    /** The user whose personal diagram group {@code grp-a.zip} is. */
    public static final String USER = "jdoe";

    /** The directory entry of {@link #repositoryDirectories()} that is an ancestor of files. */
    public static final String XSD_DIRECTORY = "Root/OWNERS/xsd/";
    /** The directory entry of {@link #repositoryDirectories()} that is no ancestor of a file. */
    public static final String UNUSED_DIRECTORY = "Root/OWNERS/unused/";

    private static final String WORKFLOWS = "workflow/workflow.xml";
    private static final String REPOSITORY = "Repository.zip";
    private static final String INDEX = "module/module.xml";

    private WorkflowArchiveFixtures() {
    }

    /** {@code grp-a.zip}: diagram group {@code GRP-01}, {@code Workflow-0001} and 0002. */
    public static byte[] grpA() {
        return ArtifactFixtures.bytes("grp-a.zip");
    }

    /** {@code grp-b.zip}: diagram group {@code GRP-02}, {@code Workflow-0003} to 0006. */
    public static byte[] grpB() {
        return ArtifactFixtures.bytes("grp-b.zip");
    }

    /**
     * {@code grp-b.zip} where the index entry of {@code Module-0016} (Workflow Starter, a node of
     * {@code Workflow-0003} only) is bound to {@code Workflow-0004} by {@code WorkflowName}.
     */
    public static byte[] boundModule() {
        return edit(grpB(), INDEX, WorkflowArchiveFixtures::bindModule0016);
    }

    /**
     * {@code grp-b.zip} where a node of {@code Module-0020} (XSLT Converter, a node of
     * {@code Workflow-0003} only) is nested inside the {@code PartnerManagement} node of
     * {@code Workflow-0005}.
     */
    public static byte[] nestedNode() {
        return edit(grpB(), WORKFLOWS, xml -> {
            int partner = indexOf(xml, "<ModuleName>Partner Management 1</ModuleName>");
            int end = xml.indexOf("</WorkflowModule>", partner);
            return xml.substring(0, end) + "<WorkflowModule moduleType=\"technical\">"
                + "<ModuleId>25009999</ModuleId><ModuleName>Module-0020</ModuleName>"
                + "<OriginalModuleName>Module-0020</OriginalModuleName>"
                + "<ParentModule moduleId=\"25008718\"/></WorkflowModule>" + xml.substring(end);
        });
    }

    /**
     * {@code grp-b.zip} without the index entry and the module file of {@code Module-0023}
     * (XSLT Converter, a node of {@code Workflow-0004}), as for a module of another owner.
     */
    public static byte[] missingModule() {
        RawZip raw = RawZip.read(grpB());
        List<RawEntry> entries = new ArrayList<>(raw.entries());
        assertThat(entries.removeIf(e -> e.header().getName().equals("module/module-0023.xml")))
            .isTrue();
        byte[] edited = RawZip.write(entries, raw.comment());
        return edit(edited, INDEX, xml -> {
            int name = indexOf(xml, "<ModuleName>Module-0023</ModuleName>");
            int start = xml.lastIndexOf("<Module ", name);
            int end = xml.indexOf("</Module>", name) + "</Module>".length();
            return xml.substring(0, start) + xml.substring(end);
        });
    }

    /**
     * {@code grp-b.zip} where {@code Module-0016} (a node of {@code Workflow-0003}) has the
     * plug-in type {@link V81WorkflowArchives#WORKFLOW_CONNECTOR} (string unverified, research
     * R-5) and is also bound to {@code Workflow-0004}, which has no node of it.
     */
    public static byte[] workflowConnector() {
        return edit(grpB(), INDEX, xml -> bindModule0016(
            replaceOnce(replaceOnce(xml, "<ModuleGroupName>Workflow Starter</ModuleGroupName>",
                "<ModuleGroupName>" + V81WorkflowArchives.WORKFLOW_CONNECTOR
                    + "</ModuleGroupName>"),
                "<PluginName>Workflow Starter</PluginName>",
                "<PluginName>" + V81WorkflowArchives.WORKFLOW_CONNECTOR + "</PluginName>")));
    }

    /**
     * {@code grp-b.zip} with ten workflows in {@code GRP-02} (SC-002): {@code Workflow-0003} to
     * 0006 and the copies {@code Workflow-0007} to {@code Workflow-0012} of them, in turn.
     */
    public static byte[] tenWorkflows() {
        return edit(grpB(), WORKFLOWS, xml -> {
            List<String> originals = new ArrayList<>();
            for (int n = 3; n <= 6; n++) {
                originals.add(workflowElement(xml, "Workflow-000" + n));
            }
            StringBuilder copies = new StringBuilder();
            for (int n = 7; n <= 12; n++) {
                String original = originals.get((n - 7) % originals.size());
                String name = original.substring(original.indexOf("<WorkflowName>"),
                    original.indexOf("</WorkflowName>") + "</WorkflowName>".length());
                copies.append(replaceOnce(original, name,
                    "<WorkflowName>" + String.format("Workflow-%04d", n) + "</WorkflowName>"));
            }
            int end = indexOf(xml, "</WorkflowGroup>");
            return xml.substring(0, end) + copies + xml.substring(end);
        });
    }

    /**
     * {@code grp-b.zip} where {@code Workflow-0005} has the {@code workflowType} {@code business}
     * and {@code Workflow-0006} has no {@code workflowType} attribute.
     */
    public static byte[] nonTechnicalWorkflow() {
        return edit(grpB(), WORKFLOWS, xml -> {
            String type = " workflowType=\"technical\"";
            int business = workflowStart(xml, "Workflow-0005");
            String changed = xml.substring(0, business) + replaceOnce(
                xml.substring(business, xml.indexOf('>', business)), type,
                " workflowType=\"business\"") + xml.substring(xml.indexOf('>', business));
            int absent = workflowStart(changed, "Workflow-0006");
            return changed.substring(0, absent) + replaceOnce(
                changed.substring(absent, changed.indexOf('>', absent)), type, "")
                + changed.substring(changed.indexOf('>', absent));
        });
    }

    /**
     * {@code grp-b.zip} whose nested {@code Repository.zip} has two directory entries:
     * {@link #UNUSED_DIRECTORY} first (no ancestor of a file) and {@link #XSD_DIRECTORY} right
     * before {@code msg.xsd.xml} (ancestor of {@code msg.xsd} and {@code core.xsd} of
     * {@code Workflow-0006}), each with a time of its own; everything else unchanged.
     */
    public static byte[] repositoryDirectories() {
        RawZip export = RawZip.read(grpB());
        List<RawEntry> entries = new ArrayList<>();
        for (RawEntry entry : export.entries()) {
            if (!entry.header().getName().equals(REPOSITORY)) {
                entries.add(entry);
                continue;
            }
            RawZip repository = RawZip.read(entry.bytes());
            List<RawEntry> files = new ArrayList<>();
            files.add(directory(UNUSED_DIRECTORY, LocalDateTime.of(2026, 1, 2, 3, 4, 6)));
            for (RawEntry file : repository.entries()) {
                if (file.header().getName().equals(XSD_DIRECTORY + "msg.xsd.xml")) {
                    files.add(directory(XSD_DIRECTORY, LocalDateTime.of(2025, 11, 27, 8, 58, 0)));
                }
                files.add(file);
            }
            assertThat(files).hasSize(repository.entries().size() + 2);
            entries.add(withBytes(entry, RawZip.write(files, repository.comment())));
        }
        return RawZip.write(entries, export.comment());
    }

    /** {@code grp-b.zip} without the nested {@code Repository.zip}; everything else unchanged. */
    public static byte[] withoutRepository() {
        RawZip raw = RawZip.read(grpB());
        List<RawEntry> entries = new ArrayList<>(raw.entries());
        assertThat(entries.removeIf(e -> e.header().getName().equals(REPOSITORY))).isTrue();
        return RawZip.write(entries, raw.comment());
    }

    private static RawEntry directory(String name, LocalDateTime time) {
        ZipEntry header = new ZipEntry(name);
        header.setTimeLocal(time);
        header.setMethod(ZipEntry.STORED);
        header.setSize(0);
        header.setCompressedSize(0);
        header.setCrc(0);
        return new RawEntry(header, new byte[0]);
    }

    private static int workflowStart(String xml, String workflow) {
        int name = indexOf(xml, "<WorkflowName>" + workflow + "</WorkflowName>");
        return xml.lastIndexOf("<Workflow ", name);
    }

    private static String bindModule0016(String xml) {
        int name = indexOf(xml, "<ModuleName>Module-0016</ModuleName>");
        int user = xml.indexOf("<ExportUser>", name);
        assertThat(user).isLessThan(xml.indexOf("</Module>", name));
        return xml.substring(0, user) + "<WorkflowName>Workflow-0004</WorkflowName>"
            + xml.substring(user);
    }

    private static String workflowElement(String xml, String workflow) {
        int start = workflowStart(xml, workflow);
        int end = xml.indexOf("</Workflow>", start) + "</Workflow>".length();
        return xml.substring(start, end);
    }

    /** {@code zip} with the text of {@code entry} changed by {@code change}. */
    static byte[] edit(byte[] zip, String entry, UnaryOperator<String> change) {
        RawZip raw = RawZip.read(zip);
        List<RawEntry> entries = new ArrayList<>();
        boolean found = false;
        for (RawEntry e : raw.entries()) {
            if (e.header().getName().equals(entry)) {
                String changed = change.apply(new String(e.bytes(), StandardCharsets.UTF_8));
                entries.add(withBytes(e, changed.getBytes(StandardCharsets.UTF_8)));
                found = true;
            } else {
                entries.add(e);
            }
        }
        assertThat(found).as("entry %s", entry).isTrue();
        return RawZip.write(entries, raw.comment());
    }

    /** {@code entry} with other content; header (name, time, method) otherwise unchanged. */
    static RawEntry withBytes(RawEntry entry, byte[] bytes) {
        ZipEntry header = entry.header();
        CRC32 crc = new CRC32();
        crc.update(bytes);
        header.setSize(bytes.length);
        header.setCrc(crc.getValue());
        header.setCompressedSize(header.getMethod() == ZipEntry.STORED ? bytes.length : -1);
        return new RawEntry(header, bytes);
    }

    private static String replaceOnce(String text, String from, String to) {
        int at = indexOf(text, from);
        assertThat(text.indexOf(from, at + 1)).as("second %s", from).isNegative();
        return text.substring(0, at) + to + text.substring(at + from.length());
    }

    private static int indexOf(String text, String part) {
        int at = text.indexOf(part);
        assertThat(at).as("%s in the fixture", part).isNotNegative();
        return at;
    }
}
