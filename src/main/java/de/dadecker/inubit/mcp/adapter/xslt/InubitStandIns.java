package de.dadecker.inubit.mcp.adapter.xslt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.lib.ExtensionFunctionCall;
import net.sf.saxon.lib.ExtensionFunctionDefinition;
import net.sf.saxon.om.Item;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.om.StructuredQName;
import net.sf.saxon.query.QueryResult;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.value.BooleanValue;
import net.sf.saxon.value.EmptySequence;
import net.sf.saxon.value.SequenceType;
import net.sf.saxon.value.StringValue;

/**
 * Deterministic stand-ins for the INUBIT XSLT extension functions (research D-11,
 * clarification 4), registered as Saxon <em>integrated extension functions</em> under the
 * namespace URIs real stylesheets use, with the arities found in the spike corpus. One instance
 * serves one run: it records which stand-ins were called ({@link #used()}) and holds the
 * variables of {@code Misc:setVariable}.
 *
 * <p>Fixed values: identifiers are {@value #GUID}; "now" is the run's {@code now} or
 * {@code 2000-01-01T00:00:00Z}; date patterns are {@link SimpleDateFormat} patterns and dates are
 * read and written in UTC unless a time zone is passed; {@code sleep} returns at once. Where the
 * INUBIT documentation does not say what a function does, the stand-in's Javadoc states the
 * assumed behaviour; a run that used a stand-in is reported as such, one that used an assumed
 * stand-in ({@link #ASSUMED}) or took a fallback ({@link #fallbacks()}) also gets a warning.
 * {@code Formatter:calculateDateDifference} has no stand-in: nothing about its eleven
 * parameters is documented, so a stylesheet that calls it is not testable locally.
 */
public final class InubitStandIns {

    /** The identifier every GUID/UUID stand-in returns. */
    public static final String GUID = "00000000-0000-0000-0000-000000000000";
    /** The instant of the date/time stand-ins without a {@code now} of the run. */
    public static final Instant FIXED_NOW = Instant.parse("2000-01-01T00:00:00Z");

    /**
     * The stand-ins whose behaviour is assumed because the INUBIT documentation does not say
     * it; a run that uses one gets the warning {@code XSLT_STANDIN_ASSUMED} (review I2).
     */
    static final Set<String> ASSUMED = Set.of("Misc.encode", "Misc.decode",
        "Misc.encodeWithCompression", "Misc.stringToBranch", "Misc.setVariableStorage",
        "Misc.setVariable", "Misc.getVariable", "Formatter.convertDateString",
        "Formatter.formatNumber", "Formatter.parseSchemaDateToSQLTimestamp",
        "ISFunctions.encode");

    static final String MISC = "java:com.inubit.ibis.xsltext.Misc";
    static final String FORMATTER = "java:com.inubit.ibis.xsltext.Formatter";
    static final String IS_FUNCTIONS = "java:com.inubit.ibis.xsltext.ISFunctions";
    static final String UUID = "java:java.util.UUID";
    static final String THREAD = "java:java.lang.Thread";
    static final String XALAN_THREAD = "http://xml.apache.org/xalan/java/java.lang.Thread";
    static final String URL_DECODER = "java:java.net.URLDecoder";

    private final Instant now;
    private final SortedSet<String> used = new TreeSet<>();
    private final SortedSet<String> assumed = new TreeSet<>();
    private final SortedSet<String> fallbacks = new TreeSet<>();
    private final Map<String, String> variables = new ConcurrentHashMap<>();

    /** @param now the instant of the date/time stand-ins; {@link #FIXED_NOW} if empty */
    public InubitStandIns(Optional<Instant> now) {
        this.now = now.orElse(FIXED_NOW);
    }

    /** The stand-ins called so far, e.g. {@code Misc.guid}, sorted. */
    public SortedSet<String> used() {
        synchronized (used) {
            return new TreeSet<>(used);
        }
    }

    /**
     * The stand-ins called so far whose behaviour is assumed, not documented (review I2), e.g.
     * {@code Misc.encode}, sorted.
     */
    public SortedSet<String> assumed() {
        synchronized (used) {
            return new TreeSet<>(assumed);
        }
    }

    /**
     * The fallbacks taken so far, e.g. a date that could not be read and was kept; each starts
     * with the stand-in's name (review I2).
     */
    public SortedSet<String> fallbacks() {
        synchronized (used) {
            return new TreeSet<>(fallbacks);
        }
    }

    private void fallback(String standIn, String what) {
        synchronized (used) {
            fallbacks.add(standIn + ": " + what);
        }
    }

    /** True if extension calls in {@code namespace} are served by stand-ins. */
    public static boolean serves(String namespace) {
        return namespace.equals(MISC) || namespace.equals(FORMATTER)
            || namespace.equals(IS_FUNCTIONS) || namespace.equals(UUID)
            || namespace.equals(THREAD) || namespace.equals(XALAN_THREAD)
            || namespace.equals(URL_DECODER);
    }

    /** Registers every stand-in with {@code processor}. */
    public void register(Processor processor) {
        // --- Misc --------------------------------------------------------------------------
        /* guid(): a fixed identifier instead of a random one. */
        define(processor, MISC, "Misc", "guid", 0, 0, false, (c, a) -> string(GUID));
        /* encode(text[, charset]): assumed base64 of the text in the charset (default UTF-8). */
        define(processor, MISC, "Misc", "encode", 1, 2, false, (c, a) -> string(
            Base64.getEncoder().encodeToString(text(a, 0).getBytes(charset(a, 1)))));
        /* decode(base64): assumed the inverse of encode, read as UTF-8. */
        define(processor, MISC, "Misc", "decode", 1, 2, false, (c, a) -> string(new String(
            Base64.getMimeDecoder().decode(text(a, 0).strip()), charset(a, 1))));
        /* encodeWithCompression(text): assumed base64 of the gzip-compressed UTF-8 text. */
        define(processor, MISC, "Misc", "encodeWithCompression", 1, 1, false,
            (c, a) -> string(Base64.getEncoder().encodeToString(gzip(text(a, 0)))));
        /* stringToBranch(xml): assumed to parse the text into a document node. */
        define(processor, MISC, "Misc", "stringToBranch", 1, 1, false,
            (c, a) -> parse(c, text(a, 0), "Misc.stringToBranch"));
        /* setVariableStorage(storage): no storage is kept beyond the run; returns nothing. */
        define(processor, MISC, "Misc", "setVariableStorage", 1, 1, true,
            (c, a) -> EmptySequence.getInstance());
        /* setVariable(name, value[, storage]): kept for this run only; returns nothing. */
        define(processor, MISC, "Misc", "setVariable", 2, 3, true, (c, a) -> {
            variables.put(text(a, 0), text(a, 1));
            return EmptySequence.getInstance();
        });
        /* getVariable(name[, storage]): the value set in this run, else the empty string. */
        define(processor, MISC, "Misc", "getVariable", 1, 2, true,
            (c, a) -> string(variables.getOrDefault(text(a, 0), "")));

        // --- Formatter ---------------------------------------------------------------------
        /* changeDateFormat(date, 'in|out'): an unparseable date is returned unchanged. */
        define(processor, FORMATTER, "Formatter", "changeDateFormat", 2, 2, false, (c, a) -> {
            String patterns = text(a, 1);
            int bar = patterns.indexOf('|');
            return bar < 0 ? string(text(a, 0)) : string(convert(
                "Formatter.changeDateFormat", text(a, 0),
                patterns.substring(0, bar), Locale.ROOT, "UTC", patterns.substring(bar + 1),
                Locale.ROOT, "UTC"));
        });
        /*
         * convertDateString: assumed (date, inPattern, outPattern, timeZone) with four arguments
         * and (date, inPattern, inLanguage, inCountry, inTimeZone, outPattern, outLanguage,
         * outCountry, outTimeZone) with nine; an unparseable date is returned unchanged.
         */
        define(processor, FORMATTER, "Formatter", "convertDateString", 4, 9, false, (c, a) ->
            a.length >= 9
                ? string(convert("Formatter.convertDateString", text(a, 0), text(a, 1),
                    locale(text(a, 2), text(a, 3)),
                    text(a, 4), text(a, 5), locale(text(a, 6), text(a, 7)), text(a, 8)))
                : string(convert("Formatter.convertDateString", text(a, 0), text(a, 1),
                    Locale.ROOT, text(a, 3), text(a, 2),
                    Locale.ROOT, text(a, 3))));
        /* trim(text): leading and trailing white space removed. */
        define(processor, FORMATTER, "Formatter", "trim", 1, 1, false,
            (c, a) -> string(text(a, 0).strip()));
        /* formatNumber(number, pattern, language): DecimalFormat; unparseable → unchanged. */
        define(processor, FORMATTER, "Formatter", "formatNumber", 2, 3, false, (c, a) -> {
            try {
                Locale locale = a.length > 2 ? Locale.forLanguageTag(text(a, 2)) : Locale.ROOT;
                return string(new DecimalFormat(text(a, 1), DecimalFormatSymbols.getInstance(
                    locale)).format(new BigDecimal(text(a, 0).strip())));
            } catch (IllegalArgumentException e) {
                fallback("Formatter.formatNumber", "the number could not be read and was kept");
                return string(text(a, 0));
            }
        });
        define(processor, FORMATTER, "Formatter", "crlf", 0, 0, false, (c, a) -> string("\r\n"));
        define(processor, FORMATTER, "Formatter", "lf", 0, 0, false, (c, a) -> string("\n"));
        /* getDateTime(pattern) / getDateAsString(pattern): "now" in UTC in the pattern. */
        define(processor, FORMATTER, "Formatter", "getDateTime", 1, 1, false,
            (c, a) -> string(format(Date.from(now), text(a, 0), Locale.ROOT, "UTC")));
        define(processor, FORMATTER, "Formatter", "getDateAsString", 1, 1, false,
            (c, a) -> string(format(Date.from(now), text(a, 0), Locale.ROOT, "UTC")));
        /* isNumber(text): true if the trimmed text is a decimal number. */
        define(processor, FORMATTER, "Formatter", "isNumber", 1, 1, false,
            (c, a) -> BooleanValue.get(isNumber(text(a, 0))));
        /*
         * parseSchemaDateToSQLTimestamp(date): assumed the java.sql.Timestamp text
         * ({@code yyyy-MM-dd HH:mm:ss.f}) of an xs:date or xs:dateTime; else unchanged.
         */
        define(processor, FORMATTER, "Formatter", "parseSchemaDateToSQLTimestamp", 1, 1, false,
            (c, a) -> string(sqlTimestamp(text(a, 0))));
        // --- ISFunctions -------------------------------------------------------------------
        /* serialize(node): the node as XML text without declaration. */
        define(processor, IS_FUNCTIONS, "ISFunctions", "serialize", 1, 1, false, (c, a) -> {
            Item item = a[0].head();
            return string(item instanceof NodeInfo node ? serialize(node)
                : item == null ? "" : item.getStringValue());
        });
        /* deserialize(xml): the text parsed into a document node. */
        define(processor, IS_FUNCTIONS, "ISFunctions", "deserialize", 1, 1, false,
            (c, a) -> parse(c, text(a, 0), "ISFunctions.deserialize"));
        /* encode(text): assumed base64 of the UTF-8 text, like Misc:encode. */
        define(processor, IS_FUNCTIONS, "ISFunctions", "encode", 1, 1, false, (c, a) -> string(
            Base64.getEncoder().encodeToString(text(a, 0).getBytes(StandardCharsets.UTF_8))));

        // --- Java classes ------------------------------------------------------------------
        define(processor, UUID, "UUID", "randomUUID", 0, 0, false, (c, a) -> string(GUID));
        /* sleep(millis): returns at once (no waiting in a check). */
        define(processor, THREAD, "Thread", "sleep", 1, 1, true,
            (c, a) -> EmptySequence.getInstance());
        define(processor, XALAN_THREAD, "Thread", "sleep", 1, 1, true,
            (c, a) -> EmptySequence.getInstance());
        /* decode(text[, charset]): URL decoding (UTF-8 unless a charset is given). */
        define(processor, URL_DECODER, "URLDecoder", "decode", 1, 2, false, (c, a) -> string(
            URLDecoder.decode(text(a, 0), charset(a, 1))));
    }

    /** One stand-in. */
    @FunctionalInterface
    private interface Body {

        Sequence call(XPathContext context, Sequence[] arguments) throws XPathException;
    }

    private void define(Processor processor, String namespace, String className, String local,
        int min, int max, boolean sideEffects, Body body) {
        String name = className + "." + local;
        processor.registerExtensionFunction(new ExtensionFunctionDefinition() {
            @Override
            public StructuredQName getFunctionQName() {
                return new StructuredQName("", namespace, local);
            }

            @Override
            public int getMinimumNumberOfArguments() {
                return min;
            }

            @Override
            public int getMaximumNumberOfArguments() {
                return max;
            }

            @Override
            public SequenceType[] getArgumentTypes() {
                SequenceType[] types = new SequenceType[max];
                Arrays.fill(types, SequenceType.ANY_SEQUENCE);
                return types;
            }

            @Override
            public SequenceType getResultType(SequenceType[] suppliedArgumentTypes) {
                return SequenceType.ANY_SEQUENCE;
            }

            @Override
            public boolean hasSideEffects() {
                return sideEffects;
            }

            @Override
            public ExtensionFunctionCall makeCallExpression() {
                return new ExtensionFunctionCall() {
                    @Override
                    public Sequence call(XPathContext context, Sequence[] arguments)
                        throws XPathException {
                        synchronized (used) {
                            used.add(name);
                            if (ASSUMED.contains(name)) {
                                assumed.add(name);
                            }
                        }
                        return body.call(context, arguments);
                    }
                };
            }
        });
    }

    // --- helpers ---------------------------------------------------------------------------

    private static StringValue string(String value) {
        return new StringValue(value);
    }

    /** The string value of argument {@code index}; empty for an empty sequence. */
    private static String text(Sequence[] arguments, int index) throws XPathException {
        Item item = arguments[index].head();
        return item == null ? "" : item.getStringValue();
    }

    private static Charset charset(Sequence[] arguments, int index) throws XPathException {
        if (arguments.length <= index || text(arguments, index).isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(text(arguments, index).strip());
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static Locale locale(String language, String country) {
        return language.isBlank() ? Locale.ROOT : new Locale(language, country);
    }

    /**
     * The document node of {@code xml}; an empty sequence for a text with a document type
     * declaration (no entities or external documents are ever resolved) or one that is not
     * well-formed.
     */
    private Sequence parse(XPathContext context, String xml, String standIn) {
        if (xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")) {
            fallback(standIn, "the text has a document type declaration (not read); empty"
                + " result");
            return EmptySequence.getInstance();
        }
        try {
            return context.getConfiguration().buildDocumentTree(new StreamSource(
                new StringReader(xml))).getRootNode();
        } catch (XPathException e) {
            fallback(standIn, "the text is not well-formed XML; empty result");
            return EmptySequence.getInstance();
        }
    }

    private String convert(String standIn, String value, String inPattern, Locale inLocale,
        String inZone, String outPattern, Locale outLocale, String outZone) {
        try {
            SimpleDateFormat in = new SimpleDateFormat(inPattern, inLocale);
            in.setLenient(false);
            in.setTimeZone(TimeZone.getTimeZone(inZone.isBlank() ? "UTC" : inZone));
            return format(in.parse(value.strip()), outPattern, outLocale, outZone);
        } catch (ParseException | IllegalArgumentException e) {
            fallback(standIn, "the date could not be read and was kept");
            return value;
        }
    }

    private static String format(Date date, String pattern, Locale locale, String zone) {
        try {
            SimpleDateFormat out = new SimpleDateFormat(pattern, locale);
            out.setTimeZone(TimeZone.getTimeZone(zone.isBlank() ? "UTC" : zone));
            return out.format(date);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** The node as XML text: no declaration, no indentation. */
    private static String serialize(NodeInfo node) throws XPathException {
        Properties properties = new Properties();
        properties.setProperty("method", "xml");
        properties.setProperty("omit-xml-declaration", "yes");
        properties.setProperty("indent", "no");
        StringWriter out = new StringWriter();
        QueryResult.serialize(node, new StreamResult(out), properties);
        return out.toString();
    }

    private static boolean isNumber(String text) {
        try {
            new BigDecimal(text.strip());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String sqlTimestamp(String value) {
        String text = value.strip();
        LocalDateTime time;
        try {
            time = OffsetDateTime.parse(text).toLocalDateTime();
        } catch (DateTimeParseException e) {
            try {
                time = LocalDateTime.parse(text);
            } catch (DateTimeParseException f) {
                try {
                    time = LocalDate.parse(text).atStartOfDay();
                } catch (DateTimeParseException g) {
                    return value;
                }
            }
        }
        String nanos = String.format("%09d", time.getNano()).replaceAll("0+$", "");
        return String.format("%04d-%02d-%02d %02d:%02d:%02d.%s", time.getYear(),
            time.getMonthValue(), time.getDayOfMonth(), time.getHour(), time.getMinute(),
            time.getSecond(), nanos.isEmpty() ? "0" : nanos);
    }

    private static byte[] gzip(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
