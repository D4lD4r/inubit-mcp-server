package de.dadecker.inubit.mcp.adapter.rest;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every INUBIT REST endpoint the server sends (research D-8): the HTTP method and the path
 * template below {@code /ibis/rest}. A {@code {name}} in the template is a path parameter that
 * {@link #path(String...)} fills in, encoded as one segment.
 */
public enum RestEndpoint {

    HEALTHCHECK(Method.GET, "/healthcheck"),
    READY(Method.GET, "/ready"),
    SYSTEM_INFO(Method.GET, "/system/info"),
    METRICS(Method.GET, "/metrics"),
    LOG(Method.POST, "/log/{name}"),
    MODELS(Method.GET, "/model/models"),
    MODEL_BY_NAME(Method.GET, "/model/modelByName/{name}"),
    MODEL_EXPORT(Method.GET, "/model/export/{name}"),
    LICENSE(Method.GET, "/license"),
    CONFIGURATIONS(Method.GET, "/configurations");

    /** The HTTP methods of the endpoints. */
    public enum Method { GET, POST }

    private static final String ROOT = "/ibis/rest";
    private static final Pattern PARAMETER = Pattern.compile("\\{[^}/]+}");

    private final Method method;
    private final String template;

    RestEndpoint(Method method, String template) {
        this.method = method;
        this.template = template;
    }

    public Method method() {
        return method;
    }

    /** The path template below {@code /ibis/rest}, e.g. {@code /log/{name}}. */
    public String template() {
        return template;
    }

    /**
     * The absolute path of this endpoint, each parameter percent-encoded with
     * {@link InubitHttpClient#encodePathSegment}.
     *
     * @param params one value per placeholder of the template, in order
     * @throws IllegalArgumentException if the number of values does not match the template
     * @throws NullPointerException if a value is {@code null}
     */
    public String path(String... params) {
        long parameterCount = PARAMETER.matcher(template).results().count();
        if (params.length != parameterCount) {
            throw new IllegalArgumentException(name() + " takes " + parameterCount
                + " path parameter(s), got " + params.length);
        }
        Matcher matcher = PARAMETER.matcher(template);
        StringBuilder path = new StringBuilder(ROOT);
        int next = 0;
        while (matcher.find()) {
            String param = Objects.requireNonNull(params[next], name() + " parameter " + next);
            next++;
            matcher.appendReplacement(path,
                Matcher.quoteReplacement(InubitHttpClient.encodePathSegment(param)));
        }
        matcher.appendTail(path);
        return path.toString();
    }
}
