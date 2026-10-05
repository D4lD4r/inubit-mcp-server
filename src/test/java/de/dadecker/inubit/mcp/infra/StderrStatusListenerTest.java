package de.dadecker.inubit.mcp.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.ErrorStatus;
import ch.qos.logback.core.status.InfoStatus;
import ch.qos.logback.core.status.WarnStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Logback's own status messages (C1): only warnings and errors are printed, and only to stderr,
 * so that a configuration problem is visible without INFO noise and never reaches stdout.
 */
class StderrStatusListenerTest {

    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final LoggerContext context = new LoggerContext();

    private StderrStatusListener listener() {
        StderrStatusListener listener = new StderrStatusListener(
            () -> new PrintStream(stderr, true, StandardCharsets.UTF_8));
        listener.setContext(context);
        return listener;
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    @Test
    void printsWarningsAndErrorsButNotInfo() {
        StderrStatusListener listener = listener();
        listener.start();

        listener.addStatusEvent(new InfoStatus("Found resource [logback.xml]", this));
        listener.addStatusEvent(new WarnStatus("Appender named [X] not referenced", this));
        listener.addStatusEvent(new ErrorStatus("Could not create appender",
            this, new IllegalStateException("boom")));

        assertThat(listener.isStarted()).isTrue();
        assertThat(err()).doesNotContain("Found resource")
            .contains("WARN", "Appender named [X] not referenced")
            .contains("ERROR", "Could not create appender", "IllegalStateException", "boom");
    }

    @Test
    void startPrintsEarlierWarningsOfTheContextOnce() {
        context.getStatusManager().add(new InfoStatus("early info", this));
        context.getStatusManager().add(new WarnStatus("early warning", this));
        StderrStatusListener listener = listener();

        listener.start();

        assertThat(err()).contains("early warning").doesNotContain("early info");
        assertThat(err().split("early warning", -1)).hasSize(2);
    }

    @Test
    void nothingIsPrintedWhenStopped() {
        StderrStatusListener listener = listener();
        listener.start();
        listener.stop();

        listener.addStatusEvent(new ErrorStatus("after stop", this));

        assertThat(listener.isStarted()).isFalse();
        assertThat(err()).doesNotContain("after stop");
    }
}
