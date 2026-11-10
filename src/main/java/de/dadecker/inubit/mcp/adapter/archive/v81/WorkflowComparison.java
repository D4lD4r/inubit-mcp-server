package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * What the comparisons of workflow and module files ignore (feature 007, contract P-1, research
 * R-2), in one place for {@link V81ImportArchives}, {@link V81ReleaseArchives} and the
 * workspace:
 *
 * <ul>
 *   <li>{@link #reviewed}: the volatile children of a workflow or module index entry — what
 *       INUBIT rewrites on every import or what a workspace file keeps in {@code .meta/}.
 *   <li>{@link #connectionsOrdered}: INUBIT writes the outgoing {@code Connection} children of a
 *       {@code WorkflowModule} in a non-deterministic order, so for comparisons they take one
 *       order. Only the direct connections of a module whose parent is a {@code Workflow} are
 *       reordered, within the slots connections occupy; the order of the modules and of
 *       everything else stays significant.
 *   <li>{@link #contentOrdered}: also the {@code Variable} children of a workflow's
 *       {@code Variables}, which INUBIT keeps in hash order: a variable added at the end comes
 *       back behind an arbitrary other one (observed on 8.1). They take one order by their
 *       {@code name}, within the slots variables occupy. Only for the comparison of reviewed
 *       content ({@link V81ImportArchives}), not for fingerprints: backup manifests keep
 *       fingerprints over {@link #connectionsOrdered} (contract P-4, P-5), and two exports of one
 *       stored version list the variables in the same order.
 * </ul>
 *
 * <p>Both forms are only compared or hashed, never imported, deployed or stored (FR-006).
 *
 * <p>Connections with equal keys, and connections whose {@code moduleOutId} or
 * {@code ConnectionId} is not a plain decimal number, are ordered by their normalized text, which
 * includes the label position ({@code StyleSheet labelPosition}). Two such connections that are
 * swapped <em>and</em> have another label position may therefore not line up, and the workflow
 * counts as changed instead of layout only ({@link LayoutDiff}). That fails safe (a real
 * comparison, never a hidden change); with numeric ids, as INUBIT writes them, it does not occur.
 */
final class WorkflowComparison {

    /** What INUBIT rewrites on every import, or what a workspace file keeps in .meta/. */
    private static final Set<String> VOLATILE = Set.of("CheckinComment", "LastUpdate",
        "WorkflowUId", "ModuleUId", "CheckoutUser");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+");

    /**
     * Variables with a {@code name} first, by it (UTF-8, unsigned byte order, i.e. case-sensitive
     * code point order); then those without; ties and the others by their normalized text. A
     * total order, as {@link #ORDER}.
     */
    private static final Comparator<Named> BY_NAME = Comparator
        .comparing((Named named) -> named.name().isEmpty())
        .thenComparing((a, b) -> a.name().isEmpty() ? 0
            : Arrays.compareUnsigned(a.name().get(), b.name().get()))
        .thenComparing((a, b) -> Arrays.compareUnsigned(a.text(), b.text()));

    /**
     * Connections with a plain decimal {@code moduleOutId} and {@code ConnectionId} first, by
     * both numerically; then all others; ties and the others by their normalized text (UTF-8,
     * unsigned byte order). A total order: a pairwise "numeric if both are" would not be
     * transitive.
     */
    private static final Comparator<Keyed> ORDER = Comparator
        .comparing((Keyed keyed) -> keyed.number().isEmpty())
        .thenComparing((a, b) -> a.number().isEmpty() ? 0
            : a.number().get().compareTo(b.number().get()))
        .thenComparing((a, b) -> Arrays.compareUnsigned(a.text(), b.text()));

    private WorkflowComparison() {
    }

    /**
     * The root without the volatile children of a workflow or module index entry; with
     * {@code withoutActive} also without a workflow's {@code IsActive}. Any other root is
     * returned as it is.
     */
    static Element reviewed(Element root, boolean withoutActive) {
        boolean workflow = root.localName().equals("Workflow");
        if (!workflow && !root.localName().equals("Module")) {
            return root;
        }
        List<Node> children = new ArrayList<>();
        for (Node child : root.children()) {
            if (child instanceof Element element && (VOLATILE.contains(element.localName())
                || (withoutActive && workflow && element.localName().equals("IsActive")))) {
                continue;
            }
            children.add(child);
        }
        return root.withChildren(children);
    }

    /**
     * {@code element} with the direct {@code Connection} children of every
     * {@code WorkflowModule} whose parent is a {@code Workflow} (at any depth: a workspace file
     * or an archive's {@code IBISWorkflow/…/Workflow}) in the order of contract P-1, in the
     * positions connections occupied; nothing else changes. Idempotent.
     */
    static Element connectionsOrdered(Element element) {
        return ordered(element, false, false);
    }

    /**
     * {@link #connectionsOrdered}, and the direct {@code Variable} children of every
     * {@code Variables} element whose parent is a {@code Workflow} in the order of
     * {@link #BY_NAME}, in the positions variables occupied; nothing else changes. Idempotent.
     */
    static Element contentOrdered(Element element) {
        return ordered(element, false, true);
    }

    /**
     * True if {@code existing} and {@code rendered} are two different renderings of the same
     * workflow that differ <em>only</em> in the order of the modules' connections (contract
     * P-6): both are normalized as the workspace writes them, and their connection-ordered
     * forms are equal. A file that differs in anything else — its formatting included — or
     * cannot be read is no such pair.
     */
    static boolean differsOnlyInConnectionOrder(byte[] existing, byte[] rendered) {
        if (Arrays.equals(existing, rendered)) {
            return false;
        }
        try {
            Element left = XmlTree.parse(existing).root();
            Element right = XmlTree.parse(rendered).root();
            return Arrays.equals(XmlNormalizer.normalize(left), existing)
                && Arrays.equals(XmlNormalizer.normalize(right), rendered)
                && Arrays.equals(XmlNormalizer.normalize(connectionsOrdered(left)),
                    XmlNormalizer.normalize(connectionsOrdered(right)));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Element ordered(Element element, boolean parentIsWorkflow,
        boolean variables) {
        if (!element.hasElements()) {
            return element;
        }
        boolean workflow = is(element, "Workflow");
        List<Node> children = new ArrayList<>(element.children().size());
        boolean changed = false;
        for (Node child : element.children()) {
            Node now = child instanceof Element e ? ordered(e, workflow, variables) : child;
            changed |= now != child;
            children.add(now);
        }
        if (parentIsWorkflow && is(element, "WorkflowModule")) {
            changed |= sortInSlots(children, "Connection", Keyed::of, ORDER, Keyed::connection);
        }
        if (variables && parentIsWorkflow && is(element, "Variables")) {
            changed |= sortInSlots(children, "Variable", Named::of, BY_NAME, Named::variable);
        }
        return changed ? element.withChildren(children) : element;
    }

    /**
     * Sorts the {@code name} elements of {@code children} by {@code order} within the slots they
     * occupy; true if any moved.
     */
    private static <T> boolean sortInSlots(List<Node> children, String name,
        Function<Element, T> keyed, Comparator<T> order, Function<T, Element> element) {
        List<Integer> slots = new ArrayList<>();
        List<T> items = new ArrayList<>();
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) instanceof Element e && is(e, name)) {
                slots.add(i);
                items.add(keyed.apply(e));
            }
        }
        if (items.size() < 2) {
            return false;
        }
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(order);
        boolean moved = false;
        for (int i = 0; i < slots.size(); i++) {
            moved |= sorted.get(i) != items.get(i);
            children.set(slots.get(i), element.apply(sorted.get(i)));
        }
        return moved;
    }

    /** INUBIT's own elements: no prefix, no namespace. */
    private static boolean is(Element element, String name) {
        return element.localName().equals(name) && element.prefix().isEmpty()
            && element.namespaceUri().isEmpty();
    }

    /** A connection with its sort key: (moduleOutId, ConnectionId) if plain decimal. */
    private record Keyed(Element connection, Optional<NumericKey> number, byte[] text) {

        static Keyed of(Element connection) {
            Optional<String> target = connection.attribute("moduleOutId");
            Optional<String> id = connection.elements().stream()
                .filter(child -> is(child, "ConnectionId")).findFirst().map(Element::text);
            Optional<NumericKey> number = target.isPresent() && id.isPresent()
                && DECIMAL.matcher(target.get()).matches() && DECIMAL.matcher(id.get()).matches()
                ? Optional.of(new NumericKey(new BigInteger(target.get()),
                    new BigInteger(id.get()))) : Optional.empty();
            return new Keyed(connection, number, XmlNormalizer.normalize(connection));
        }
    }

    /** A workflow variable with its sort key: its {@code name} (UTF-8), if it has one. */
    private record Named(Element variable, Optional<byte[]> name, byte[] text) {

        static Named of(Element variable) {
            return new Named(variable, variable.attribute("name")
                .map(name -> name.getBytes(StandardCharsets.UTF_8)),
                XmlNormalizer.normalize(variable));
        }
    }

    /** The numeric part of the key. */
    private record NumericKey(BigInteger moduleOutId, BigInteger connectionId)
        implements Comparable<NumericKey> {

        @Override
        public int compareTo(NumericKey other) {
            int target = moduleOutId.compareTo(other.moduleOutId);
            return target != 0 ? target : connectionId.compareTo(other.connectionId);
        }
    }
}
