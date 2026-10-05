package de.dadecker.inubit.mcp.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ScrubbingJsonEncoderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SecretScrubber scrubber = new SecretScrubber();
    private final LoggerContext context = new LoggerContext();
    private final Logger logger = context.getLogger("de.dadecker.test");
    private ScrubbingJsonEncoder encoder;

    @BeforeEach
    void setUp() {
        context.setMDCAdapter(new LogbackMDCAdapter());
        encoder = new ScrubbingJsonEncoder(scrubber);
        encoder.setContext(context);
        encoder.start();
        scrubber.register("hunter2");
    }

    @AfterEach
    void tearDown() {
        encoder.stop();
        context.stop();
    }

    private String encode(LoggingEvent event) {
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    private LoggingEvent event(String message, Throwable throwable, Object... arguments) {
        return new LoggingEvent(getClass().getName(), logger, Level.ERROR, message, throwable,
            arguments);
    }

    @Test
    void secretInTheMessageIsScrubbedAndTheOutputIsJson() {
        String line = encode(event("login with hunter2 failed", null));

        assertThat(line).doesNotContain("hunter2").contains("***");
        JsonNode json = JSON.readTree(line);
        assertThat(json.get("level").asString()).isEqualTo("ERROR");
    }

    @Test
    void secretInAnArgumentIsScrubbed() {
        String raw = encode(event("password is {}", null, "hunter2"));
        encoder.setWithFormattedMessage(true);
        String formatted = encode(event("password is {}", null, "hunter2"));

        assertThat(raw).doesNotContain("hunter2").contains("\"arguments\": [\"***\"]");
        assertThat(formatted).doesNotContain("hunter2").contains("password is ***");
    }

    @Test
    void secretInTheThrowableAndItsCauseIsScrubbed() {
        Exception cause = new IllegalStateException("cause mentions hunter2");
        Exception exception = new RuntimeException("top-level hunter2", cause);
        exception.addSuppressed(new IllegalArgumentException("suppressed hunter2"));

        String line = encode(event("call failed", exception));

        assertThat(line).doesNotContain("hunter2");
        assertThat(line).contains("RuntimeException", "IllegalStateException", "top-level ***",
            "cause mentions ***", "suppressed ***");
        JSON.readTree(line);
    }

    @Test
    void secretInMdcAndKeyValuePairsIsScrubbed() {
        LoggingEvent event = event("with context", null);
        event.setMDCPropertyMap(Map.of("auth", "Basic hunter2"));
        event.addKeyValuePair(new KeyValuePair("token", "hunter2"));

        String line = encode(event);

        assertThat(line).doesNotContain("hunter2");
    }

    @Test
    void secretThatNeedsJsonEscapingIsScrubbed() {
        scrubber.register("quote\"back\\slash");

        String line = encode(event("value quote\"back\\slash end", null));

        assertThat(line).doesNotContain("quote\\\"back\\\\slash").doesNotContain("quote\"back");
        assertThat(JSON.readTree(line).toString()).contains("value *** end");
    }

    @Test
    void eventsWithoutSecretsAreEncodedUnchanged() {
        ScrubbingJsonEncoder plain = new ScrubbingJsonEncoder(new SecretScrubber());
        plain.setContext(context);
        plain.start();

        String line =
            new String(plain.encode(event("nothing secret", null)), StandardCharsets.UTF_8);

        assertThat(line).contains("nothing secret");
    }

    @Test
    void productionLogbackConfigurationWritesScrubbedJsonToStderrOnly() throws Exception {
        String secret = "global-" + UUID.randomUUID();
        SecretScrubber.global().register(secret);
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LoggerContext production = new LoggerContext();
        production.setMDCAdapter(new LogbackMDCAdapter());
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            URL config = getClass().getClassLoader().getResource("logback.xml");
            assertThat(config).as("src/main/resources/logback.xml").isNotNull();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(production);
            configurator.doConfigure(config);

            production.getLogger("de.dadecker.inubit").warn("secret {} leaked", secret,
                new IllegalStateException("boom " + secret));
        } finally {
            production.stop();
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        String stderr = err.toString(StandardCharsets.UTF_8);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(stderr).isNotBlank().doesNotContain(secret).contains("secret *** leaked");
        JsonNode json = JSON.readTree(stderr.lines().filter(l -> l.contains("leaked")).findFirst()
            .orElseThrow());
        assertThat(json.get("level").asString()).isEqualTo("WARN");
        assertThat(json.get("loggerName").asString()).isEqualTo("de.dadecker.inubit");
    }

    @Test
    void rootLevelCanBeSetFromConfiguration() {
        LogLevels.apply(context, "DEBUG");
        assertThat(context.getLogger(Logger.ROOT_LOGGER_NAME).getLevel()).isEqualTo(Level.DEBUG);

        LogLevels.apply(context, "trace");
        assertThat(context.getLogger(Logger.ROOT_LOGGER_NAME).getLevel()).isEqualTo(Level.TRACE);
    }
}
