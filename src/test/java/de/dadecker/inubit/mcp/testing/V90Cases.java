package de.dadecker.inubit.mcp.testing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The INUBIT 9.0 fixture cases (feature 017, research D-10): the only way the 9.0 contract tests
 * open a fixture of {@code fixtures/v9_0/}. A case lives in {@code rest/} or {@code cli/} with its
 * mandatory {@code <case>.origin} ({@code reconstructed <documentation section>} or
 * {@code recorded <date> <INUBIT version>}); opening an unregistered case, or a case without its
 * origin file, fails, so no fixture can pass for recorded by omission.
 *
 * <p>REST cases: {@code <case>.http} ({@code status:}, {@code content-type:}) and one body file
 * {@code <case>.<json|xml|zip|html|txt>} (POST cases also {@code <case>.request.xml}). CLI cases:
 * {@code <case>.stdout}, {@code <case>.stderr}, {@code <case>.exit} (export cases also
 * {@code <case>.zip}, cases that send stdin lines also {@code <case>.stdin}).
 */
public final class V90Cases {

    /** Where a case lives. */
    public enum Kind {
        REST("rest/"), CLI("cli/");

        private final String directory;

        Kind(String directory) {
            this.directory = directory;
        }

        public String directory() {
            return directory;
        }
    }

    /** The resource root of the 9.0 fixtures. */
    public static final String ROOT = "/fixtures/v9_0/";

    /** Every registered case: the catalogue of the recorder and the not-recordable cases. */
    public static final Map<String, Kind> CASES = cases();

    private static final List<String> BODY_EXTENSIONS = List.of("json", "xml", "zip", "html",
        "txt");
    private static final V90Cases FIXTURES = new V90Cases(CASES, V90Cases::resource);

    private final Map<String, Kind> registered;
    private final Function<String, Optional<byte[]>> loader;

    /**
     * A registry over {@code loader} (path below {@link #ROOT}, e.g. {@code rest/ready.json});
     * tests of the registry itself pass their own.
     */
    V90Cases(Map<String, Kind> registered, Function<String, Optional<byte[]>> loader) {
        this.registered = Map.copyOf(registered);
        this.loader = loader;
    }

    private static Map<String, Kind> cases() {
        Map<String, Kind> cases = new LinkedHashMap<>();
        for (String rest : List.of("healthcheck", "ready", "system_info", "metrics",
            "log_systemLog", "log_systemLog_filtered", "model_models_owner", "modelByName_sample",
            "model_export_sample", "rest_auth_failed", "rest_not_found", "rest_forbidden",
            "rest_internal_error", "rest_unavailable")) {
            cases.put(rest, Kind.REST);
        }
        for (String cli : List.of("version", "help", "processErrorStart_unknown", "kill_unknown",
            "unknown_command", "login_failed", "unreachable", "ps_all", "export_history_sample",
            "export_modules_sample", "import_created", "tag_ok", "processErrorStart_ok",
            "kill_ok")) {
            cases.put(cli, Kind.CLI);
        }
        // feature 022 (T024, T028b): administration recordings, see NOT_RECORDABLE.txt
        for (String rest : List.of("admin-license", "admin-configurations", "admin-users")) {
            cases.put(rest, Kind.REST);
        }
        for (String cli : List.of("admin-settings-session", "admin-settings-threads-refused",
            "admin-settings-privileged-percentage-refused",
            "admin-settings-privileged-list-refused", "admin-settings-db-tolerance",
            "admin-settings-date", "admin-settings-license-days", "admin-settings-license-seconds",
            "admin-users-session", "admin-users-user", "admin-users-finger",
            "admin-users-user-capitalized", "admin-users-finger-capitalized",
            "admin-users-user-underscore", "admin-users-finger-no-name",
            "admin-users-msglist-empty", "admin-users-who", "admin-users-nwho")) {
            cases.put(cli, Kind.CLI);
        }
        return Collections.unmodifiableMap(cases);
    }

    /** The names of every registered case, sorted. */
    public static SortedSet<String> names() {
        return new TreeSet<>(CASES.keySet());
    }

    /** The first line of the case's {@code .origin} file. */
    public static String origin(String name) {
        return FIXTURES.originOf(name);
    }

    /** The WireMock response of a REST case: its status, content type and body. */
    public static ResponseDefinitionBuilder response(String name) {
        return FIXTURES.responseOf(name);
    }

    /** The body file of a REST case. */
    public static byte[] restBody(String name) {
        return FIXTURES.bodyOf(name);
    }

    /** A further file of a case, e.g. {@code request.xml} or {@code zip}. */
    public static byte[] file(String name, String extension) {
        return FIXTURES.fileOf(name, extension);
    }

    /** A further file of a case as UTF-8 text. */
    public static String text(String name, String extension) {
        return new String(file(name, extension), StandardCharsets.UTF_8);
    }

    /** The recorded result of a CLI case. */
    public static CliResult cliResult(String name) {
        return FIXTURES.cliResultOf(name);
    }

    String originOf(String name) {
        Kind kind = kind(name);
        byte[] origin = loader.apply(kind.directory() + name + ".origin").orElseThrow(() ->
            new AssertionError("9.0 fixture case " + name + " has no " + kind.directory() + name
                + ".origin (research D-10: every case states its provenance)"));
        String line = new String(origin, StandardCharsets.UTF_8).lines().findFirst().orElse("")
            .strip();
        if (!line.startsWith("reconstructed ") && !line.startsWith("recorded ")) {
            throw new AssertionError("9.0 fixture case " + name + ": the origin must start with"
                + " 'reconstructed ' or 'recorded ', not '" + line + "'");
        }
        return line;
    }

    ResponseDefinitionBuilder responseOf(String name) {
        ResponseDefinitionBuilder response = aResponse().withBody(bodyOf(name));
        String http = new String(fileOf(name, "http"), StandardCharsets.UTF_8);
        for (String line : http.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (key.equals("status")) {
                response.withStatus(Integer.parseInt(value));
            } else if (key.equals("content-type")) {
                response.withHeader("Content-Type", value);
            }
        }
        return response;
    }

    byte[] bodyOf(String name) {
        requireKind(name, Kind.REST);
        originOf(name);
        for (String extension : BODY_EXTENSIONS) {
            Optional<byte[]> body = loader.apply(Kind.REST.directory() + name + "." + extension);
            if (body.isPresent()) {
                return body.get();
            }
        }
        throw new AssertionError("9.0 REST case " + name + " has no body file");
    }

    byte[] fileOf(String name, String extension) {
        Kind kind = kind(name);
        originOf(name);
        return loader.apply(kind.directory() + name + "." + extension).orElseThrow(() ->
            new AssertionError("9.0 fixture " + kind.directory() + name + "." + extension
                + " not found"));
    }

    CliResult cliResultOf(String name) {
        requireKind(name, Kind.CLI);
        int exit = Integer.parseInt(new String(fileOf(name, "exit"), StandardCharsets.UTF_8)
            .strip());
        return new CliResult(exit, new String(fileOf(name, "stdout"), StandardCharsets.UTF_8),
            new String(fileOf(name, "stderr"), StandardCharsets.UTF_8), Duration.ofSeconds(2),
            false, CliRunner.MAX_OUTPUT_BYTES);
    }

    private Kind kind(String name) {
        Kind kind = registered.get(name);
        if (kind == null) {
            throw new AssertionError("9.0 fixture case " + name + " is not registered in"
                + " V90Cases (research D-10)");
        }
        return kind;
    }

    private void requireKind(String name, Kind expected) {
        if (kind(name) != expected) {
            throw new AssertionError("9.0 fixture case " + name + " is not a " + expected
                + " case");
        }
    }

    private static Optional<byte[]> resource(String path) {
        try (InputStream in = V90Cases.class.getResourceAsStream(ROOT + path)) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
