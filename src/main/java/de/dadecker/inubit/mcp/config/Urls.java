package de.dadecker.inubit.mcp.config;

import java.net.URI;

/** URL helpers that never need to print a URL. */
final class Urls {

    private Urls() {
    }

    /**
     * True if the URL contains {@code @} anywhere (user info always does): {@code https://u:p#x@h}
     * parses as host {@code u} with fragment {@code x@h}, so only "no {@code @} at all" is safe.
     */
    static boolean hasUserInfo(URI uri) {
        return uri.toString().indexOf('@') >= 0;
    }

    static boolean hasQueryOrFragment(URI uri) {
        return uri.getRawQuery() != null || uri.getRawFragment() != null;
    }

    static URI withoutQueryAndFragment(URI uri) {
        return hasQueryOrFragment(uri)
            ? rebuild(uri, uri.getRawAuthority(), uri.getRawPath(), null, null)
            : uri;
    }

    /** Removes trailing slashes from the path; scheme, authority, query and fragment stay. */
    static URI withoutTrailingSlash(URI uri) {
        String path = uri.getRawPath();
        if (path == null || !path.endsWith("/")) {
            return uri;
        }
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        return rebuild(uri, uri.getRawAuthority(), path.substring(0, end), uri.getRawQuery(),
            uri.getRawFragment());
    }

    private static URI rebuild(URI uri, String authority, String path, String query,
        String fragment) {
        if (uri.isOpaque()) {
            return uri;
        }
        StringBuilder text = new StringBuilder();
        if (uri.getScheme() != null) {
            text.append(uri.getScheme()).append(':');
        }
        if (authority != null) {
            text.append("//").append(authority);
        }
        if (path != null) {
            text.append(path);
        }
        if (query != null) {
            text.append('?').append(query);
        }
        if (fragment != null) {
            text.append('#').append(fragment);
        }
        return URI.create(text.toString());
    }
}
