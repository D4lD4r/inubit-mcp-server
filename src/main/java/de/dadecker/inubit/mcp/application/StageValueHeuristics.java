package de.dadecker.inubit.mcp.application;

import java.util.regex.Pattern;

/**
 * Which changed property of a deployed artifact looks stage-specific (feature 005, US5 AS2,
 * research D-5): its name says host, URL/URI, endpoint, server, port, address, login, user or
 * account, or one of its values is a URL, a host name with a domain (optionally with a port) or
 * an IPv4 address. Secret placeholders never count (secrets come from the target anyway). The
 * deployment does not change the value; the preview warns with the property name only.
 */
final class StageValueHeuristics {

    private static final Pattern NAME = Pattern.compile(
        "(?i).*(host|url|uri|endpoint|server|port|address|login|user|account).*");
    private static final Pattern URL = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://\\S+$");
    private static final Pattern HOST = Pattern.compile(
        "(?i)^[a-z0-9-]*[a-z][a-z0-9-]*(\\.[a-z0-9-]+)+(:\\d{1,5})?$");
    private static final Pattern IPV4 = Pattern.compile(
        "^\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?$");
    private static final String PLACEHOLDER = "${secret:";

    private StageValueHeuristics() {
    }

    /** True if the property {@code name} with these two values looks stage-specific. */
    static boolean looksStageSpecific(String name, String release, String target) {
        if (release.startsWith(PLACEHOLDER) || target.startsWith(PLACEHOLDER)) {
            return false;
        }
        return NAME.matcher(name).matches() || looksLikeAddress(release.strip())
            || looksLikeAddress(target.strip());
    }

    private static boolean looksLikeAddress(String value) {
        return URL.matcher(value).matches() || HOST.matcher(value).matches()
            || IPV4.matcher(value).matches();
    }
}
