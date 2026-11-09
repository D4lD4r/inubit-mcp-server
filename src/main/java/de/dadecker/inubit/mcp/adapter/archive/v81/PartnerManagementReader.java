package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.domain.model.partner.CommunicationCloud;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.Partner;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerField;
import de.dadecker.inubit.mcp.domain.model.partner.ProtocolEntry;
import de.dadecker.inubit.mcp.domain.model.partner.Setting;
import de.dadecker.inubit.mcp.domain.model.partner.SettingValue;
import de.dadecker.inubit.mcp.domain.model.partner.SystemDiagramPartners;
import de.dadecker.inubit.mcp.domain.model.partner.WorkflowRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the Partner Managements of one system diagram from the {@code workflow.xml} of its export
 * and masks their secrets (feature 018 FR-007 … FR-011, research D-3, D-2 facts).
 *
 * <p>Every {@code WorkflowModule moduleType="sdCommunicationCloud"} of the diagram whose
 * {@code Properties} carry {@code PartnerManagementSettings} becomes one
 * {@link CommunicationCloud}. Each leaf value is turned into a {@link SettingValue} in the method
 * that reads it: a value {@link PartnerSecretRules} calls secret leaves it only as
 * {@link SettingValue.Masked}, so no raw secret is part of the result (FR-010). Partners,
 * entries and settings keep export order; nothing is merged, and unknown protocols and setting
 * names are kept under the same masking rules.
 */
public final class PartnerManagementReader {

    private PartnerManagementReader() {
    }

    /**
     * The Partner Managements of a system diagram and the warnings found while reading them.
     *
     * @param partners the masked model
     * @param warnings one per setting with child elements (masked) and one per entry whose
     *                 {@code referencableTWFId} does not resolve to a named workflow; they name the
     *                 partner key, the cloud and the setting, never a value
     */
    public record Result(SystemDiagramPartners partners, List<String> warnings) {

        public Result {
            Objects.requireNonNull(partners, "partners");
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * A {@code Workflow} of a {@code workflow.xml} and the {@code WorkflowGroupName} of the
     * {@code WorkflowGroup} it lies in (empty if it has none).
     */
    public record GroupedWorkflow(String groupName, Element workflow) {

        public GroupedWorkflow {
            Objects.requireNonNull(groupName, "groupName");
            Objects.requireNonNull(workflow, "workflow");
        }
    }

    /**
     * Every {@code Workflow} whose {@code WorkflowName} is {@code name} (exactly, case-sensitive)
     * in the {@code workflow.xml} whose root element is {@code workflowRoot}, in document order
     * and of any group.
     */
    public static List<GroupedWorkflow> workflowsNamed(Element workflowRoot, String name) {
        List<GroupedWorkflow> found = new ArrayList<>();
        for (Element workflows : workflowRoot.elements("Workflows")) {
            for (Element group : workflows.elements("WorkflowGroup")) {
                for (Element workflow : group.elements("Workflow")) {
                    if (workflow.childText("WorkflowName").equals(name)) {
                        found.add(new GroupedWorkflow(group.childText("WorkflowGroupName"),
                            workflow));
                    }
                }
            }
        }
        return found;
    }

    /**
     * Reads {@code diagram} from the {@code workflow.xml} whose root element is
     * {@code workflowRoot}.
     *
     * @throws IllegalArgumentException if the export does not hold exactly one system diagram
     *     {@code diagram}, or a communication cloud has no {@code ModuleName} or {@code ModuleId},
     *     or an entry has no {@code protocol}
     */
    public static Result read(DiagramRef diagram, Element workflowRoot) {
        Element workflow = workflow(diagram, workflowRoot);
        List<CommunicationCloud> clouds = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Element module : workflow.elements("WorkflowModule")) {
            if (module.attribute("moduleType").filter("sdCommunicationCloud"::equals).isPresent()) {
                module.child("Properties")
                    .flatMap(properties -> property(properties, "PartnerManagementSettings"))
                    .ifPresent(settings -> {
                        List<List<String>> structured = new ArrayList<>();
                        CommunicationCloud cloud = cloud(module, settings, structured);
                        clouds.add(cloud);
                        warn(diagram, cloud, structured, warnings);
                    });
            }
        }
        return new Result(new SystemDiagramPartners(diagram, clouds), warnings);
    }

    private static Element workflow(DiagramRef diagram, Element root) {
        List<Element> found = workflowsNamed(root, diagram.name()).stream()
            .filter(workflow -> workflow.groupName().equals(diagram.diagramGroup()))
            .map(GroupedWorkflow::workflow)
            .toList();
        if (found.size() != 1) {
            throw new IllegalArgumentException("The export holds " + found.size()
                + " system diagrams " + diagram.diagramGroup() + "/" + diagram.name()
                + ", expected one");
        }
        return found.get(0);
    }

    /**
     * @param structured receives, per partner in export order, the names of its settings that have
     *                   child elements
     */
    private static CommunicationCloud cloud(Element module, Element settings,
        List<List<String>> structured) {
        List<PartnerField> partnerFields = new ArrayList<>();
        Map<String, String> fieldTypes = new HashMap<>();
        for (Element field : items(settings, "PartnerFields")) {
            PartnerField declared = new PartnerField(name(field),
                field.attribute("fieldType").orElse(""));
            partnerFields.add(declared);
            fieldTypes.putIfAbsent(declared.name(), declared.fieldType());
        }
        List<String> workflows = new ArrayList<>();
        Map<String, String> workflowById = new HashMap<>();
        for (Element referencable : items(settings, "ReferencableTWFs")) {
            workflows.add(name(referencable));
            referencable.attribute("id")
                .ifPresent(id -> workflowById.putIfAbsent(id, name(referencable)));
        }
        List<Partner> partners = new ArrayList<>();
        for (Element partner : items(settings, "Partners")) {
            List<String> names = new ArrayList<>();
            partners.add(partner(partner, fieldTypes, workflowById, names));
            structured.add(names);
        }
        return new CommunicationCloud(required(module, "ModuleName"), required(module, "ModuleId"),
            partnerFields, workflows, partners);
    }

    /**
     * @param fieldTypes   the declared {@code fieldType} by field name; a field not declared has
     *                     none and is masked
     * @param workflowById the {@code ReferencableTWFs} names by their local {@code id}
     * @param structured   receives the names of the settings that have child elements
     */
    private static Partner partner(Element partner, Map<String, String> fieldTypes,
        Map<String, String> workflowById, List<String> structured) {
        List<Setting> fields = items(partner, "Fields").stream()
            .map(field -> setting(field,
                PartnerSecretRules.maskedField(field, fieldTypes.get(name(field))), structured))
            .toList();
        List<ProtocolEntry> entries = new ArrayList<>();
        Map<Map.Entry<String, WorkflowRef>, Integer> seen = new HashMap<>();
        for (Element entry : items(partner, "ReferencedTWFs")) {
            String protocol = entry.attribute("protocol").orElseThrow(
                () -> new IllegalArgumentException("A ReferencedTWF has no protocol attribute"));
            WorkflowRef workflow = new WorkflowRef(entry.attribute("referencableTWFId")
                .map(workflowById::get).filter(name -> !name.isBlank()));
            int position = seen.merge(Map.entry(protocol, workflow), 1, Integer::sum) - 1;
            entries.add(new ProtocolEntry(protocol, workflow, entry.attribute("format"),
                settings(entry, "ProtocolSettings", structured),
                settings(entry, "AdditionalParameterSettings", structured), position));
        }
        return new Partner(fields, entries);
    }

    private static List<Setting> settings(Element entry, String group, List<String> structured) {
        return items(entry, group).stream()
            .map(leaf -> setting(leaf, PartnerSecretRules.masked(leaf), structured)).toList();
    }

    /**
     * The setting of {@code property}. Its value is read here and, if {@code secret}, kept only as
     * "set" and the fingerprint of the stored value (FR-009, FR-010, FR-012b). A property with
     * child elements is not a leaf the rules can judge: it is masked as "set" if any descendant
     * has text, without a fingerprint, and its name is added to {@code structured}.
     */
    private static Setting setting(Element property, boolean secret, List<String> structured) {
        String value = property.text();
        SettingValue settingValue;
        if (property.hasElements()) {
            structured.add(name(property));
            settingValue = new SettingValue.Masked(hasText(property), Optional.empty());
        } else if (!secret) {
            settingValue = new SettingValue.Shown(value);
        } else if (value.isEmpty()) {
            settingValue = new SettingValue.Masked(false, Optional.empty());
        } else {
            settingValue = new SettingValue.Masked(true,
                Optional.of(SecretFingerprinter.process().fingerprint(value)));
        }
        return new Setting(name(property), settingValue);
    }

    private static boolean hasText(Element element) {
        return !element.text().isBlank()
            || element.elements().stream().anyMatch(PartnerManagementReader::hasText);
    }

    /**
     * @param structured per partner of {@code cloud}, the names of its settings with child
     *                   elements
     */
    private static void warn(DiagramRef diagram, CommunicationCloud cloud,
        List<List<String>> structured, List<String> warnings) {
        for (int i = 0; i < cloud.partners().size(); i++) {
            Partner partner = cloud.partners().get(i);
            String where = cloud.keyOf(diagram, partner)
                .map(key -> "Partner " + key.field() + " " + key.value())
                .orElse("The partner at position " + (i + 1))
                + " in the communication cloud " + cloud.moduleName() + " of the system diagram "
                + diagram.diagramGroup() + "/" + diagram.name() + ": ";
            for (String name : structured.get(i)) {
                warnings.add(where + "the setting " + name + " has child elements; it is shown"
                    + " as masked");
            }
            for (ProtocolEntry entry : partner.entries()) {
                if (entry.workflow().name().isEmpty()) {
                    warnings.add(where + "a " + entry.protocol() + " entry refers to a workflow"
                        + " its Partner Management does not list by name; the workflow is shown"
                        + " as unresolved");
                }
            }
        }
    }

    /** The child elements of the {@code Property} named {@code name}; none if it is absent. */
    private static List<Element> items(Element parent, String name) {
        return property(parent, name).map(Element::elements).orElse(List.of());
    }

    private static Optional<Element> property(Element parent, String name) {
        return parent.elements("Property").stream()
            .filter(property -> name(property).equals(name)).findFirst();
    }

    private static String required(Element module, String name) {
        return module.child(name).map(Element::text).orElseThrow(() ->
            new IllegalArgumentException("A communication cloud has no " + name));
    }

    private static String name(Element property) {
        return property.attribute("name").orElse("");
    }
}
