package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The neutral excerpt of feature 007 ({@code fixtures/v8_1/connection-order}, research R-6): one
 * workspace workflow file {@code Workflow-0001} with two modules, each with the connections
 * {@code 315/63} and {@code 171/174} — in {@code workflow-a.xml} in that order, in
 * {@code workflow-b.xml} swapped and otherwise byte-identical — and the real differences that
 * must stay differences (FR-003, FR-004).
 */
final class ConnectionOrderFixtures {

    /** The first connection of {@code workflow-a.xml}, as rendered (indented). */
    static final String FIRST = """
            <Connection moduleOutId="315">
              <ConnectionId>63</ConnectionId>
              <StyleSheet labelPosition="49.705882352941174"/>
            </Connection>
        """;

    /** The real difference that lies in the layout only. */
    static final String LABEL_POSITION = "other labelPosition";

    private ConnectionOrderFixtures() {
    }

    static byte[] bytes(String name) {
        try (InputStream in = ConnectionOrderFixtures.class.getResourceAsStream(
            "/fixtures/v8_1/connection-order/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String text(String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    /** {@code text} with its first {@code from} replaced (it must be there). */
    static String edit(String text, String from, String to) {
        assertThat(text).contains(from);
        return text.replaceFirst(Pattern.quote(from), Matcher.quoteReplacement(to));
    }

    /**
     * Description → a version of {@code workflow-b.xml} (the swapped order) with one real
     * difference from {@code workflow-a.xml}.
     */
    static Map<String, String> realDifferences() {
        String a = text("workflow-a.xml");
        String b = text("workflow-b.xml");
        String first = a.substring(a.indexOf("  <WorkflowModule"),
            a.indexOf("</WorkflowModule>") + "</WorkflowModule>\n".length());
        String second = a.substring(a.lastIndexOf("  <WorkflowModule"),
            a.lastIndexOf("</WorkflowModule>") + "</WorkflowModule>\n".length());
        Map<String, String> differences = new LinkedHashMap<>();
        differences.put("other moduleOutId", edit(b, "moduleOutId=\"315\"",
            "moduleOutId=\"316\""));
        differences.put("other ConnectionId", edit(b, "<ConnectionId>174</ConnectionId>",
            "<ConnectionId>175</ConnectionId>"));
        differences.put(LABEL_POSITION, edit(b, "60.833333333333336", "61.5"));
        differences.put("one connection missing", edit(b, FIRST, ""));
        differences.put("one connection added", edit(b, FIRST, FIRST + FIRST.replace("315",
            "316").replace(">63<", ">64<")));
        differences.put("modules swapped", edit(a, first + second, second + first));
        return differences;
    }
}
