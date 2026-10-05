package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Parses the 8.1 {@code SystemInformationList} of {@code GET /ibis/rest/system/info}: a list of
 * {@code <SystemInformation name="…" value="…"/>} (recorded fixture {@code system_info.xml}).
 *
 * <ul>
 *   <li>{@code version} ← {@code Version}
 *   <li>{@code jdk} ← {@code ServerJDKName ServerJDKVersion (ServerJDKVendor)}
 *   <li>{@code os} ← {@code ServerOSName ServerOSVersion}
 *   <li>{@code maxHeap} ← {@code ServerXMX} + " MB"
 *   <li>{@code tracingEnabled} ← {@code TracingIsActive}
 *   <li>{@code schedulerThreads} ← {@code NumberSchedulerThreads}
 *   <li>{@code raw}: every other pair in document order, at most
 *       {@link SystemInfo#MAX_RAW_ENTRIES}, values cut to {@link SystemInfo#MAX_RAW_VALUE_CHARS},
 *       names cut to {@value #MAX_NAME_CHARS} (a collision after the cut gets a {@code ~n}
 *       suffix)
 * </ul>
 */
final class SystemInfoXmlParser {

    private static final Set<String> MAPPED = Set.of("Version", "ServerJDKName",
        "ServerJDKVersion", "ServerJDKVendor", "ServerOSName", "ServerOSVersion", "ServerXMX",
        "TracingIsActive", "NumberSchedulerThreads");
    private static final int MAX_NAME_CHARS = 100;

    private SystemInfoXmlParser() {
    }

    /**
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} (with the server id) if the body is
     *     not a {@code SystemInformationList}
     */
    static SystemInfo parse(NodeId server, byte[] body) {
        Document document;
        try {
            document = XmlSupport.parse(body);
        } catch (ToolErrorException e) {
            throw new ToolErrorException(e.error().withNode(server));
        }
        if (!XmlSupport.localName(document.getDocumentElement()).equals("SystemInformationList")) {
            throw new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
                "The INUBIT system information of " + server + " is not a"
                    + " SystemInformationList",
                "The endpoint answered with an unexpected document (another INUBIT version or a"
                    + " proxy page)",
                "Check the INUBIT server and its version line").withNode(server));
        }
        Map<String, String> all = new LinkedHashMap<>();
        for (Element info : XmlSupport.descendants(document, "SystemInformation")) {
            Optional<String> name = XmlSupport.attribute(info, "name").map(String::strip)
                .filter(n -> !n.isEmpty());
            if (name.isPresent()) {
                all.putIfAbsent(name.get(), XmlSupport.attribute(info, "value").orElse("")
                    .strip());
            }
        }
        Map<String, String> raw = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            if (raw.size() == SystemInfo.MAX_RAW_ENTRIES) {
                break;
            }
            if (!MAPPED.contains(entry.getKey())) {
                raw.put(uniqueName(raw, entry.getKey()),
                    cut(entry.getValue(), SystemInfo.MAX_RAW_VALUE_CHARS));
            }
        }
        return new SystemInfo(
            value(all, "Version"),
            jdk(all),
            join(value(all, "ServerOSName"), value(all, "ServerOSVersion")),
            value(all, "ServerXMX").map(xmx -> xmx + " MB"),
            value(all, "TracingIsActive").map(Boolean::parseBoolean),
            value(all, "NumberSchedulerThreads").flatMap(SystemInfoXmlParser::integer),
            raw);
    }

    /**
     * The name cut to {@value #MAX_NAME_CHARS} chars; if that collides with an earlier name, it
     * is cut further and gets a suffix {@code ~2}, {@code ~3}, … so no entry is lost.
     */
    private static String uniqueName(Map<String, String> raw, String name) {
        String candidate = cut(name, MAX_NAME_CHARS);
        for (int suffix = 2; raw.containsKey(candidate); suffix++) {
            String tag = "~" + suffix;
            candidate = cut(name, MAX_NAME_CHARS - tag.length()) + tag;
        }
        return candidate;
    }

    private static Optional<String> jdk(Map<String, String> all) {
        Optional<String> jdk = join(value(all, "ServerJDKName"), value(all, "ServerJDKVersion"));
        Optional<String> vendor = value(all, "ServerJDKVendor");
        if (jdk.isPresent() && vendor.isPresent()) {
            return Optional.of(jdk.get() + " (" + vendor.get() + ")");
        }
        return jdk.or(() -> vendor);
    }

    private static Optional<String> value(Map<String, String> all, String name) {
        return Optional.ofNullable(all.get(name)).filter(v -> !v.isEmpty())
            .map(v -> cut(v, SystemInfo.MAX_RAW_VALUE_CHARS));
    }

    private static Optional<String> join(Optional<String> first, Optional<String> second) {
        if (first.isPresent() && second.isPresent()) {
            return Optional.of(first.get() + " " + second.get());
        }
        return first.or(() -> second);
    }

    private static Optional<Integer> integer(String text) {
        try {
            return Optional.of(Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
