package de.dadecker.inubit.mcp.adapter.xslt;

import de.dadecker.inubit.mcp.domain.model.CheckFinding;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Check;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Severity;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NameCodec;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.model.XsltRun;
import de.dadecker.inubit.mcp.domain.model.XsltRun.Outcome;
import de.dadecker.inubit.mcp.domain.port.XsltPort;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.transform.TransformerException;
import net.sf.saxon.Configuration;
import net.sf.saxon.lib.EnvironmentVariableResolver;
import net.sf.saxon.lib.Feature;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.SaxonApiUncheckedException;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.StaticError;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XsltTransformer;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.value.DateTimeValue;

/**
 * Runs a workspace stylesheet on a workspace input with Saxon-HE 10 and the
 * {@link InubitStandIns} (research D-11, FR-029 – FR-033).
 *
 * <ul>
 *   <li>The {@code xslt.transformer} of a module does not select the engine: everything runs on
 *       Saxon-HE; a call of an extension function without a stand-in (a {@code java:} class, the
 *       Saxon namespace), a construct only Saxon-PE/EE has, or a document type declaration
 *       (never read locally) is {@link Outcome#NOT_TESTABLE} with the reason, never
 *       {@link Outcome#OK}.
 *   <li>A static error is {@link Outcome#ERROR} with one {@code XSLT_STATIC_ERROR} per error and
 *       its {@code line:column}; a dynamic error is {@link Outcome#ERROR} with
 *       {@code XSLT_RUNTIME_ERROR}.
 *   <li>Every read goes through {@link WorkspaceUriResolver} (workspace only, no DTDs);
 *       {@code collection()} is refused; {@code xsl:result-document} is disabled together
 *       with external functions (review C1) and makes a stylesheet
 *       {@link Outcome#NOT_TESTABLE}. The output is
 *       {@code .tests/<group>/<owner>/<module>/<input file name>.out}.
 *   <li>A run that takes longer than {@link #DEADLINE} is an {@code XSLT_RUNTIME_ERROR}; it runs
 *       on a daemon thread that is left behind (Saxon-HE cannot be stopped), and its output
 *       never appears (review I1).
 *   <li>The server's host stays hidden (review C1): no environment variables, no Java system
 *       properties, no reflexive Java calls.
 *   <li>Deterministic (clarification 4): a stylesheet (or a module it imports) that calls
 *       {@code random-number-generator()} without a seed is {@link Outcome#NOT_TESTABLE};
 *       {@code current-dateTime()} and the date stand-ins return the request's {@code now} or
 *       {@link InubitStandIns#FIXED_NOW}.
 * </ul>
 */
public final class SaxonXsltRunner implements XsltPort {

    static final String TESTS = ".tests";
    private static final Pattern RANDOM = Pattern.compile("random-number-generator");
    private static final Pattern EMPTY_SEQUENCE = Pattern.compile("\\(\\s*\\)\\s*\\)");
    private static final Pattern XML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern XPATH_COMMENT = Pattern.compile("(?s)\\(:.*?:\\)");
    private static final Pattern FUNCTION = Pattern.compile("Q\\{([^}]*)\\}([\\w.-]+)");
    private static final Set<String> STANDARD_NAMESPACES = Set.of(
        "http://www.w3.org/2005/xpath-functions", "http://www.w3.org/2001/XMLSchema",
        "http://www.w3.org/2005/xpath-functions/math", "http://www.w3.org/2005/xpath-functions/map",
        "http://www.w3.org/2005/xpath-functions/array", "http://www.w3.org/1999/XSL/Transform");
    private static final Pattern LICENSED = Pattern.compile(
        "Saxon-(PE|EE)|requires a license|not available in Saxon-HE|Saxon-HE does not",
        Pattern.CASE_INSENSITIVE);

    /** An empty environment for {@code environment-variable()} (review C1). */
    static final EnvironmentVariableResolver NO_ENVIRONMENT = new EnvironmentVariableResolver() {
        @Override
        public Set<String> getAvailableEnvironmentVariables() {
            return Set.of();
        }

        @Override
        public String getEnvironmentVariable(String name) {
            return null;
        }
    };

    private final Path root;
    private final XsdValidator validator;
    private final Duration deadline;
    private final long maxOutputBytes;

    /** The longest a stylesheet may run (review I1: a check holds the workspace lock). */
    public static final Duration DEADLINE = Duration.ofSeconds(60);
    /** The largest output of a run (64 MiB); a larger one is an {@code XSLT_RUNTIME_ERROR}. */
    public static final long MAX_OUTPUT_BYTES = 64L << 20;

    /** @param root the workspace root */
    public SaxonXsltRunner(Path root) {
        this(root, DEADLINE);
    }

    /**
     * @param root     the workspace root
     * @param deadline the longest a run may take ({@link #DEADLINE}; tests use less)
     */
    public SaxonXsltRunner(Path root, Duration deadline) {
        this(root, deadline, MAX_OUTPUT_BYTES);
    }

    /**
     * @param root           the workspace root
     * @param deadline       the longest a run may take
     * @param maxOutputBytes the largest output of a run ({@link #MAX_OUTPUT_BYTES})
     */
    public SaxonXsltRunner(Path root, Duration deadline, long maxOutputBytes) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.validator = new XsdValidator(this.root);
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.maxOutputBytes = maxOutputBytes;
    }

    @Override
    public XsltRun run(XsltRequest request) {
        Path stylesheet = workspaceFile(request.stylesheet(), "stylesheet");
        Path input = workspaceFile(request.input(), "input");
        AtomicBoolean abandoned = new AtomicBoolean();
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "inubit-mcp-xslt-run");
            thread.setDaemon(true);
            return thread;
        });
        Future<XsltRun> run = worker.submit(() -> execute(request, stylesheet, input,
            abandoned));
        try {
            return run.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            abandoned.set(true);
            run.cancel(true); // Saxon-HE cannot be stopped; the daemon thread is left behind
            String stylesheetPath = relative(stylesheet);
            return new XsltRun(stylesheetPath, relative(input), Optional.empty(), Outcome.ERROR,
                List.of(), List.of(finding(Severity.ERROR, stylesheetPath, Optional.empty(),
                    "XSLT_RUNTIME_ERROR", "the transformation did not finish within "
                        + deadline.toSeconds() + " s")));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } finally {
            worker.shutdownNow();
        }
    }

    /** One run on the worker thread; the output is moved into place only when complete. */
    private XsltRun execute(XsltRequest request, Path stylesheet, Path input,
        AtomicBoolean abandoned) {
        String stylesheetPath = relative(stylesheet);
        String inputPath = relative(input);
        Optional<WorkspacePath> artifact = artifact(stylesheetPath);
        WorkspaceUriResolver resolver = new WorkspaceUriResolver(root,
            artifact.map(WorkspacePath::group).orElse(null),
            artifact.map(WorkspacePath::owner).orElse(null));
        InubitStandIns standIns = new InubitStandIns(request.now());
        Processor processor = new Processor(false);
        Configuration configuration = processor.getUnderlyingConfiguration();
        // review C1: nothing of the server's host is visible — environment-variable() and
        // available-environment-variables() see no variables (the environment holds the INUBIT
        // credentials), system-property() only the xsl:* properties; integrated stand-ins
        // still work
        configuration.setConfigurationProperty(Feature.ALLOW_EXTERNAL_FUNCTIONS, false);
        configuration.setConfigurationProperty(Feature.ENVIRONMENT_VARIABLE_RESOLVER,
            NO_ENVIRONMENT);
        configuration.setURIResolver(resolver);
        configuration.setUnparsedTextURIResolver(resolver);
        configuration.setCollectionFinder((context, uri) -> {
            throw new XPathException("collection() is not available in a local check",
                "FODC0002");
        });
        standIns.register(processor);

        XsltCompiler compiler = processor.newXsltCompiler();
        compiler.setURIResolver(resolver);
        List<StaticError> errors = new ArrayList<>();
        compiler.setErrorList(errors);
        XsltExecutable executable;
        try {
            executable = compiler.compile(resolver.source(stylesheet));
        } catch (SaxonApiException | TransformerException e) {
            return compileFailure(stylesheetPath, inputPath, errors, e);
        }

        // review M4 (clarification 4): an unseeded random number generator is not
        // deterministic, and Saxon-HE has no way to seed it from outside
        List<Path> modules = new ArrayList<>(List.of(stylesheet));
        modules.addAll(resolver.loaded());
        if (modules.stream().anyMatch(SaxonXsltRunner::unseededRandom)) {
            return new XsltRun(stylesheetPath, inputPath, Optional.empty(), Outcome.NOT_TESTABLE,
                List.of(), List.of(finding(Severity.WARNING, stylesheetPath, Optional.empty(),
                    "XSLT_NOT_TESTABLE", "not testable locally: random-number-generator()"
                        + " without a seed gives other numbers on every run")));
        }
        Path output = output(artifact, stylesheet, input);
        Path partial = null;
        GuardedOutput guarded = null;
        try {
            Files.createDirectories(output.getParent());
            // one temporary file per run: an abandoned run never touches a later run's file
            partial = Files.createTempFile(output.getParent(), output.getFileName() + ".",
                ".partial");
            XsltTransformer transformer = executable.load();
            transformer.setURIResolver(resolver);
            transformer.getUnderlyingController().setCurrentDateTime(DateTimeValue.fromJavaDate(
                Date.from(request.now().orElse(InubitStandIns.FIXED_NOW))));
            for (Map.Entry<String, String> parameter : request.params().entrySet()) {
                transformer.setParameter(new QName(parameter.getKey()),
                    new XdmAtomicValue(parameter.getValue()));
            }
            transformer.setSource(resolver.source(input));
            try (OutputStream file = Files.newOutputStream(partial)) {
                guarded = new GuardedOutput(file, abandoned, maxOutputBytes);
                transformer.setDestination(processor.newSerializer(guarded));
                transformer.transform();
            }
            if (abandoned.get()) {
                deleteQuietly(partial);
            } else {
                Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (SaxonApiException | SaxonApiUncheckedException | TransformerException e) {
            deleteQuietly(partial);
            if (guarded != null && guarded.exceeded()) {
                return new XsltRun(stylesheetPath, inputPath, Optional.empty(), Outcome.ERROR,
                    List.copyOf(standIns.used()), List.of(finding(Severity.ERROR,
                        stylesheetPath, Optional.empty(), "XSLT_RUNTIME_ERROR",
                        "the output exceeds " + maxOutputBytes + " bytes")));
            }
            return new XsltRun(stylesheetPath, inputPath, Optional.empty(), Outcome.ERROR,
                List.copyOf(standIns.used()), List.of(finding(Severity.ERROR, stylesheetPath,
                    Optional.empty(), "XSLT_RUNTIME_ERROR", "the transformation failed"
                        + errorCode(e).map(code -> " (" + code + ")").orElse("") + ": "
                        + e.getMessage())));
        } catch (IOException e) {
            deleteQuietly(partial);
            throw new UncheckedIOException(e);
        }
        List<String> used = List.copyOf(standIns.used());
        List<CheckFinding> findings = new ArrayList<>();
        if (!used.isEmpty()) {
            findings.add(finding(Severity.INFO, stylesheetPath, Optional.empty(),
                "XSLT_STANDINS_USED", "stand-ins used: " + String.join(", ", used)));
        }
        // review I2: an OK run never rests silently on an assumed or invented result
        List<String> assumptions = new ArrayList<>();
        if (!standIns.assumed().isEmpty()) {
            assumptions.add("stand-ins with assumed behaviour: " + String.join(", ",
                standIns.assumed()));
        }
        if (!standIns.fallbacks().isEmpty()) {
            assumptions.add("fallbacks: " + String.join("; ", standIns.fallbacks()));
        }
        if (!assumptions.isEmpty()) {
            findings.add(finding(Severity.WARNING, stylesheetPath, Optional.empty(),
                "XSLT_STANDIN_ASSUMED", "the output rests on assumptions — "
                    + String.join("; ", assumptions)));
        }
        return new XsltRun(stylesheetPath, inputPath, Optional.of(relative(output)), Outcome.OK,
            used, findings);
    }

    @Override
    public List<CheckFinding> validate(Path xml, Optional<Path> xsd) {
        return validator.validate(xml, xsd);
    }

    /**
     * True if {@code module} may call {@code random-number-generator} without a seed: outside XML
     * and XPath comments, a call with no argument or the empty sequence {@code (())}, a function
     * reference ({@code #0}/{@code #1}), or the name as text (e.g. for {@code function-lookup}).
     * A call with any other argument counts as seeded.
     */
    static boolean unseededRandom(Path module) {
        String text;
        try {
            text = Files.readString(module);
        } catch (IOException | UncheckedIOException e) {
            return false;
        }
        String code = XPATH_COMMENT.matcher(XML_COMMENT.matcher(text).replaceAll(" "))
            .replaceAll(" ");
        Matcher name = RANDOM.matcher(code);
        while (name.find()) {
            String rest = code.substring(name.end()).stripLeading();
            if (!rest.startsWith("(")) {
                return true; // a function reference or the name as a string
            }
            String argument = rest.substring(1).stripLeading();
            if (argument.startsWith(")") || EMPTY_SEQUENCE.matcher(argument).lookingAt()) {
                return true;
            }
        }
        return false;
    }

    /** The XPath/XSLT error code of a failed transformation, e.g. {@code FORG0001}. */
    static Optional<String> errorCode(Exception failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof SaxonApiException api && api.getErrorCode() != null) {
                return Optional.of(api.getErrorCode().getLocalName());
            }
            if (cause instanceof XPathException xpath && xpath.getErrorCodeLocalPart() != null) {
                return Optional.of(xpath.getErrorCodeLocalPart());
            }
            cause = cause.getCause();
        }
        return Optional.empty();
    }

    /** NOT_TESTABLE if every error is a missing extension or a licensed feature; else ERROR. */
    private XsltRun compileFailure(String stylesheet, String input,
        List<StaticError> errors, Exception failure) {
        List<String> reasons = new ArrayList<>();
        List<CheckFinding> staticErrors = new ArrayList<>();
        for (StaticError error : errors) {
            if (error.isWarning()) {
                continue;
            }
            Optional<String> reason = notTestable(error);
            if (reason.isPresent()) {
                reasons.add(reason.get());
            } else {
                staticErrors.add(finding(Severity.ERROR, stylesheet, location(error),
                    "XSLT_STATIC_ERROR", (error.getErrorCode() == null ? ""
                        : "(" + error.getErrorCode().getLocalName() + ") ")
                        + error.getMessage()));
            }
        }
        if (staticErrors.isEmpty() && !reasons.isEmpty()) {
            return new XsltRun(stylesheet, input, Optional.empty(), Outcome.NOT_TESTABLE,
                List.of(), List.of(finding(Severity.WARNING, stylesheet, Optional.empty(),
                    "XSLT_NOT_TESTABLE", "not testable locally: " + String.join("; ",
                        reasons.stream().distinct().toList()))));
        }
        if (staticErrors.isEmpty()) {
            staticErrors.add(finding(Severity.ERROR, stylesheet, Optional.empty(),
                "XSLT_STATIC_ERROR", String.valueOf(failure.getMessage())));
        }
        return new XsltRun(stylesheet, input, Optional.empty(), Outcome.ERROR, List.of(),
            staticErrors);
    }

    /** The reason a compile error means "needs INUBIT or a licensed Saxon", if it does. */
    private static Optional<String> notTestable(StaticError error) {
        String message = String.valueOf(error.getMessage());
        String code = error.getErrorCode() == null ? "" : error.getErrorCode().getLocalName();
        Matcher function = FUNCTION.matcher(message);
        if (code.equals("XPST0017") && function.find()
            && !STANDARD_NAMESPACES.contains(function.group(1))) {
            return Optional.of("the extension function " + function.group(1) + " "
                + function.group(2) + " has no local stand-in (only available in INUBIT)");
        }
        if (message.contains("result-document") && message.contains("disabled")) {
            return Optional.of("xsl:result-document (secondary output files are not written"
                + " locally)");
        }
        if (message.contains("DOCTYPE")) {
            return Optional.of("a document type declaration (DTDs and entities are not read"
                + " locally)");
        }
        if (LICENSED.matcher(message).find()) {
            return Optional.of("a feature of a licensed Saxon edition (Saxon-HE runs locally)");
        }
        return Optional.empty();
    }

    private static Optional<String> location(StaticError error) {
        if (error.getLineNumber() <= 0) {
            return Optional.empty();
        }
        return Optional.of(error.getLineNumber() + ":" + Math.max(error.getColumnNumber(), 0));
    }

    /** {@code .tests/<group>/<owner>/<module>/<input file name>.out}. */
    private Path output(Optional<WorkspacePath> artifact, Path stylesheet, Path input) {
        Path directory = root.resolve(TESTS);
        if (artifact.isPresent()) {
            WorkspacePath path = artifact.get();
            String unit = switch (path.kind()) {
                case MODULE, MODULE_INDEX, EMBEDDED, WORKFLOW -> path.segments().get(1);
                case REPOSITORY -> path.segments().get(path.segments().size() - 1);
            };
            directory = directory.resolve(path.group().value())
                .resolve(NameCodec.encode(path.owner())).resolve(NameCodec.encode(unit));
        } else {
            directory = directory.resolve("other").resolve(stylesheet.getFileName().toString());
        }
        return directory.resolve(input.getFileName() + ".out");
    }

    private Optional<WorkspacePath> artifact(String relative) {
        try {
            return Optional.of(WorkspacePath.parse(Path.of(relative)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code path} (workspace-relative) as an existing file inside the workspace. */
    private Path workspaceFile(Path path, String what) {
        Path file = root.resolve(path).normalize();
        try {
            if (path.isAbsolute() || !file.startsWith(root) || !Files.isRegularFile(file)
                || !file.toRealPath().startsWith(root.toRealPath())) {
                throw invalid(what, path);
            }
        } catch (IOException e) {
            throw invalid(what, path);
        }
        return file;
    }

    private static ToolErrorException invalid(String what, Path path) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, "The " + what + " "
            + path + " is not a file of the workspace", "The path is missing, absolute, or"
            + " leaves the workspace", "Give a workspace-relative path of an existing file"));
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static CheckFinding finding(Severity severity, String path,
        Optional<String> location, String code, String message) {
        return new CheckFinding(severity, Check.XSLT, path, location, code, message);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // nothing written that needs to go
        }
    }
}
