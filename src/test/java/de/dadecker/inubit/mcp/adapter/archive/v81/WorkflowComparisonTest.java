package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T003 (feature 007, contract P-1, research R-2): INUBIT writes the outgoing connections of a
 * workflow module in a non-deterministic order. For comparisons the direct {@code Connection}
 * children of every {@code WorkflowModule} below a {@code Workflow} take one order — plain
 * decimal ({@code moduleOutId}, {@code ConnectionId}) numerically first, then everything else,
 * ties by the normalized text — in the slots connections occupied; nothing else moves. The
 * volatile children that both archive ports ignore are dropped in the same place.
 */
class WorkflowComparisonTest {

    private static Element root(String xml) {
        return XmlTree.parse(xml.getBytes(StandardCharsets.UTF_8)).root();
    }

    private static String text(Element element) {
        return new String(XmlNormalizer.normalize(element), StandardCharsets.UTF_8);
    }

    /** The normalized, connection-ordered form of {@code xml}. */
    private static String ordered(String xml) {
        return text(WorkflowComparison.connectionsOrdered(root(xml)));
    }

    private static String ordered(byte[] xml) {
        return text(WorkflowComparison.connectionsOrdered(XmlTree.parse(xml).root()));
    }

    private static String workflow(String modules) {
        return "<Workflow><WorkflowName>Workflow-0001</WorkflowName>" + modules + "</Workflow>";
    }

    private static String module(String name, String children) {
        return "<WorkflowModule moduleType=\"technical\"><ModuleName>" + name + "</ModuleName>"
            + children + "</WorkflowModule>";
    }

    private static String connection(String moduleOutId, String connectionId) {
        return "<Connection moduleOutId=\"" + moduleOutId + "\"><ConnectionId>" + connectionId
            + "</ConnectionId></Connection>";
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        List<List<T>> all = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T first = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> permutation = new ArrayList<>(List.of(first));
                permutation.addAll(tail);
                all.add(permutation);
            }
        }
        return all;
    }

    /** The {@code (moduleOutId|-)/(ConnectionId|-)} of each connection, in document order. */
    private static List<String> keys(String xml) {
        Matcher matcher = Pattern.compile("(?s)<Connection(?: moduleOutId=\"([^\"]*)\")?"
            + "(?:/>|>(.*?)</Connection>)").matcher(xml);
        List<String> keys = new ArrayList<>();
        while (matcher.find()) {
            Matcher id = Pattern.compile("<ConnectionId>([^<]*)</ConnectionId>")
                .matcher(matcher.group(2) == null ? "" : matcher.group(2));
            keys.add((matcher.group(1) == null ? "-" : matcher.group(1)) + "/"
                + (id.find() ? id.group(1) : "-"));
        }
        return keys;
    }

    @Test
    void twoSwappedConnectionsHaveOneOrderedForm() {
        String a = ordered(ConnectionOrderFixtures.bytes("workflow-a.xml"));
        String b = ordered(ConnectionOrderFixtures.bytes("workflow-b.xml"));

        assertThat(ConnectionOrderFixtures.text("workflow-a.xml"))
            .isNotEqualTo(ConnectionOrderFixtures.text("workflow-b.xml"));
        assertThat(a).isEqualTo(b);
        // numerically by moduleOutId: 171 before 315, in each of the two modules
        assertThat(keys(a)).containsExactly("171/174", "315/63", "171/174", "315/63");
        assertThat(a).contains("labelPosition=\"60.833333333333336\"",
            "labelPosition=\"49.705882352941174\"");
    }

    @Test
    void everyPermutationOfThreeConnectionsHasTheSameNumericOrder() {
        // numeric, not textual: 9 < 10 < 100; equal moduleOutId by ConnectionId (3 < 20)
        List<String> connections = List.of(connection("100", "1"), connection("9", "7"),
            connection("10", "20"), connection("10", "3"));
        Set<String> results = new TreeSet<>();

        for (List<String> permutation : permutations(connections.subList(0, 3))) {
            results.add(ordered(workflow(module("Module-0001", String.join("", permutation)))));
        }
        String four = ordered(workflow(module("Module-0001", String.join("", connections))));

        assertThat(permutations(connections.subList(0, 3))).hasSize(6);
        assertThat(results).singleElement().satisfies(result -> assertThat(keys(result))
            .containsExactly("9/7", "10/20", "100/1"));
        assertThat(keys(four)).containsExactly("9/7", "10/3", "10/20", "100/1");
    }

    @Test
    void numbersBeyondTheRangeOfALongAreComparedNumerically() {
        // both exceed Long.MAX_VALUE; textually the larger one would sort first
        String large = connection("100000000000000000000", "1");
        String smaller = connection("99999999999999999999", "1");

        String ab = ordered(workflow(module("Module-0001", large + smaller)));
        String ba = ordered(workflow(module("Module-0001", smaller + large)));

        assertThat(ab).isEqualTo(ba);
        assertThat(keys(ab)).containsExactly("99999999999999999999/1",
            "100000000000000000000/1");
    }

    @Test
    void otherChildrenKeepTheirSlotsAndOnlyConnectionSlotsAreReordered() {
        String xml = workflow(module("Module-0001", "<ModuleId>1</ModuleId>"
            + connection("8", "2") + "<StyleSheet xPos=\"1\" yPos=\"2\"/><!-- note -->"
            + connection("3", "1") + "<Properties version=\"4.1\"/>" + connection("5", "4")));

        String result = ordered(xml);

        assertThat(keys(result)).containsExactly("3/1", "5/4", "8/2");
        assertThat(result.replaceAll("(?s)<Connection .*?</Connection>", "C").replaceAll(
            "\\s+", "")).isEqualTo(text(root(xml)).replaceAll("(?s)<Connection .*?</Connection>",
                "C").replaceAll("\\s+", ""));
        assertThat(result).containsSubsequence("<ModuleId>", "moduleOutId=\"3\"", "<StyleSheet",
            "<!-- note -->", "moduleOutId=\"5\"", "<Properties", "moduleOutId=\"8\"");
    }

    @Test
    void nonNumericOrIncompleteKeysFollowTheNumericOnesInATotalOrder() {
        List<String> connections = List.of(connection("7", "2"), connection("abc", "1"),
            "<Connection moduleOutId=\"3\"/>", "<Connection><ConnectionId>1</ConnectionId>"
                + "</Connection>", connection("5", "x1"), connection("5", "1"),
            connection("+6", "1"));
        Set<String> results = new TreeSet<>();

        for (List<String> permutation : permutations(connections)) {
            results.add(ordered(workflow(module("Module-0001", String.join("", permutation)))));
        }

        assertThat(results).singleElement().satisfies(result -> assertThat(keys(result))
            // numeric pairs first; then by the normalized text, in which the space before an
            // attribute sorts before '>' and '"+' before digits before letters
            .containsExactly("5/1", "7/2", "+6/1", "3/-", "5/x1", "abc/1", "-/1"));
    }

    @Test
    void aConnectionIdOfAnotherNamespaceIsNoNumericKey() {
        String foreign = "<Connection moduleOutId=\"5\"><x:ConnectionId xmlns:x=\"urn:x\">1"
            + "</x:ConnectionId></Connection>";

        for (String modules : List.of(foreign + connection("7", "2"), connection("7", "2")
            + foreign)) {
            assertThat(ordered(workflow(module("Module-0001", modules))))
                .containsSubsequence("moduleOutId=\"7\"", "moduleOutId=\"5\"");
        }
    }

    @Test
    void equalKeysAreOrderedByTheirNormalizedText() {
        String first = "<Connection moduleOutId=\"5\"><ConnectionId>1</ConnectionId>"
            + "<StyleSheet labelPosition=\"10.5\"/></Connection>";
        String second = "<Connection moduleOutId=\"5\"><ConnectionId>1</ConnectionId>"
            + "<StyleSheet labelPosition=\"20.5\"/></Connection>";

        String ab = ordered(workflow(module("Module-0001", first + second)));
        String ba = ordered(workflow(module("Module-0001", second + first)));

        assertThat(ab).isEqualTo(ba).containsSubsequence("10.5", "20.5");
        // a leading zero is the same number: decided by the text as well
        assertThat(ordered(workflow(module("Module-0001", connection("07", "1")
            + connection("7", "1"))))).isEqualTo(ordered(workflow(module("Module-0001",
                connection("7", "1") + connection("07", "1")))));
    }

    @Test
    void orderingIsIdempotent() {
        Element once = WorkflowComparison.connectionsOrdered(XmlTree.parse(
            ConnectionOrderFixtures.bytes("workflow-a.xml")).root());

        assertThat(text(WorkflowComparison.connectionsOrdered(once))).isEqualTo(text(once));
    }

    @Test
    void theConnectionsOfEachModuleAreOrderedWithinTheirModule() {
        String a = workflow(module("Module-0001", connection("4", "9") + connection("3", "6"))
            + module("Module-0002", connection("2", "8") + connection("1", "5")));
        String b = workflow(module("Module-0001", connection("3", "6") + connection("4", "9"))
            + module("Module-0002", connection("1", "5") + connection("2", "8")));

        assertThat(ordered(a)).isEqualTo(ordered(b));
        assertThat(keys(ordered(a))).containsExactly("3/6", "4/9", "1/5", "2/8");
    }

    @Test
    void theOrderOfTheModulesStaysSignificant() {
        String one = module("Module-0001", connection("2", "1"));
        String two = module("Module-0002", connection("1", "2"));

        assertThat(ordered(workflow(one + two))).isNotEqualTo(ordered(workflow(two + one)));
    }

    @Test
    void connectionsElsewhereKeepTheirOrder() {
        String swapped = connection("4", "9") + connection("3", "6");
        List<String> documents = List.of(
            // a WorkflowModule whose parent is not a Workflow
            "<Other>" + module("Module-0001", swapped) + "</Other>",
            // connections below another child of a module
            workflow(module("Module-0001", "<Properties>" + swapped + "</Properties>")),
            // connections directly below the workflow
            workflow(swapped),
            // a module file
            "<Properties version=\"4.1\">" + swapped + "</Properties>");

        for (String document : documents) {
            assertThat(ordered(document)).as(document).isEqualTo(text(root(document)));
        }
    }

    @Test
    void theArchiveShapeIsOrderedToo() {
        String archive = new String(ArtifactFixtures.entries(ArtifactFixtures.bytes("grp-a.zip"))
            .get("workflow/workflow.xml"), StandardCharsets.UTF_8);
        String pair = connection("4", "9") + connection("3", "6");
        assertThat(archive).contains(pair);
        String swapped = archive.replace(pair, connection("3", "6") + connection("4", "9"));

        assertThat(text(root(archive))).isNotEqualTo(text(root(swapped)));
        assertThat(ordered(archive)).isEqualTo(ordered(swapped));
    }

    @Test
    void onlyTwoNormalizedRenderingsDifferOnlyInConnectionOrder() {
        byte[] existing = ConnectionOrderFixtures.bytes("workflow-a.xml");
        byte[] swapped = ConnectionOrderFixtures.bytes("workflow-b.xml");
        byte[] reindented = ConnectionOrderFixtures.text("workflow-b.xml")
            .replace("\n  <", "\n    <").getBytes(StandardCharsets.UTF_8);

        assertThat(WorkflowComparison.differsOnlyInConnectionOrder(existing, swapped)).isTrue();
        assertThat(WorkflowComparison.differsOnlyInConnectionOrder(existing, existing)).isFalse();
        // the new rendering is formatted otherwise: it differs in more than the order
        assertThat(WorkflowComparison.differsOnlyInConnectionOrder(existing, reindented))
            .isFalse();
        assertThat(WorkflowComparison.differsOnlyInConnectionOrder(reindented, existing))
            .isFalse();
        assertThat(WorkflowComparison.differsOnlyInConnectionOrder(existing,
            "<Workflow>".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void reviewedDropsExactlyTheVolatileChildren() {
        Element workflow = root("<Workflow><WorkflowName>W</WorkflowName><WorkflowUId>u"
            + "</WorkflowUId><CheckinComment>c</CheckinComment><LastUpdate>l</LastUpdate>"
            + "<CheckoutUser>jdoe</CheckoutUser><ModuleUId>m</ModuleUId><IsActive>true"
            + "</IsActive><XPathVersion>3.1</XPathVersion>" + module("Module-0001",
                "<ModuleUId>nested</ModuleUId>") + "</Workflow>");
        Element index = root("<Module><ModuleName>M</ModuleName><ModuleUId>m</ModuleUId>"
            + "<CheckinComment>c</CheckinComment><LastUpdate>l</LastUpdate><IsActive>x"
            + "</IsActive></Module>");
        Element other = root("<Properties><CheckinComment>c</CheckinComment></Properties>");

        assertThat(text(WorkflowComparison.reviewed(workflow, false))).isEqualTo(text(root(
            "<Workflow><WorkflowName>W</WorkflowName><IsActive>true</IsActive><XPathVersion>3.1"
                + "</XPathVersion>" + module("Module-0001", "<ModuleUId>nested</ModuleUId>")
                + "</Workflow>")));
        assertThat(text(WorkflowComparison.reviewed(workflow, true))).isEqualTo(text(root(
            "<Workflow><WorkflowName>W</WorkflowName><XPathVersion>3.1</XPathVersion>"
                + module("Module-0001", "<ModuleUId>nested</ModuleUId>") + "</Workflow>")));
        // the IsActive of a module entry is no workflow flag
        assertThat(text(WorkflowComparison.reviewed(index, true))).isEqualTo(text(root(
            "<Module><ModuleName>M</ModuleName><IsActive>x</IsActive></Module>")));
        assertThat(WorkflowComparison.reviewed(other, true)).isEqualTo(other);
    }
}
