package de.dadecker.inubit.mcp.adapter.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Test double for {@link ProcessLauncher} (research R-17): replays recorded StartCLI runs from
 * {@code fixtures/v8_1/cli/<case>.{stdout,stderr,exit}} and records every launch (argument array,
 * environment, working directory, stdin bytes, destroy calls).
 *
 * <p>Variants: {@link #hanging()} never exits until destroyed, {@link #ignoringDestroy()} also
 * survives {@code destroy()} and needs {@code destroyForcibly()}, {@link #producing(int)} streams
 * a large output through a pipe and exits only after the reader consumed all of it.
 */
public final class FakeProcessLauncher implements ProcessLauncher {

    private final byte[] stdout;
    private final byte[] stderr;
    private final int exitCode;
    private boolean hang;
    private boolean ignoreDestroy;
    private boolean survivor;
    private int producedBytes = -1;
    private IOException launchFailure;
    private Consumer<LaunchSpec> onLaunch = spec -> { };

    private final List<FakeProcess> processes = new CopyOnWriteArrayList<>();

    private FakeProcessLauncher(byte[] stdout, byte[] stderr, int exitCode) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.exitCode = exitCode;
    }

    /** Replays {@code fixtures/v8_1/cli/<fixtureCase>.*}. */
    public static FakeProcessLauncher replaying(String fixtureCase) {
        return new FakeProcessLauncher(fixture(fixtureCase + ".stdout"),
            fixture(fixtureCase + ".stderr"),
            Integer.parseInt(new String(fixture(fixtureCase + ".exit"), StandardCharsets.UTF_8)
                .strip()));
    }

    public static FakeProcessLauncher of(String stdout, String stderr, int exitCode) {
        return new FakeProcessLauncher(stdout.getBytes(StandardCharsets.UTF_8),
            stderr.getBytes(StandardCharsets.UTF_8), exitCode);
    }

    /** Reads a fixture file as bytes. */
    public static byte[] fixture(String name) {
        try (InputStream in = FakeProcessLauncher.class.getResourceAsStream(
            "/fixtures/v8_1/cli/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads a fixture file as UTF-8 text. */
    public static String fixtureText(String name) {
        return new String(fixture(name), StandardCharsets.UTF_8);
    }

    public FakeProcessLauncher hanging() {
        this.hang = true;
        return this;
    }

    public FakeProcessLauncher ignoringDestroy() {
        this.hang = true;
        this.ignoreDestroy = true;
        return this;
    }

    /**
     * Hangs until destroyed; {@code destroy()} ends the root process, but a descendant survives
     * ({@code treeAlive()}) until {@code destroyForcibly()}.
     */
    public FakeProcessLauncher leavingSurvivor() {
        this.hang = true;
        this.survivor = true;
        return this;
    }

    /** Writes {@code bytes} of output to stdout and stderr each through bounded pipes. */
    public FakeProcessLauncher producing(int bytes) {
        this.producedBytes = bytes;
        return this;
    }

    public FakeProcessLauncher failingToLaunch(IOException failure) {
        this.launchFailure = failure;
        return this;
    }

    /** Runs {@code action} with the launch spec before the process "starts" (e.g. writes a file). */
    public FakeProcessLauncher onLaunch(Consumer<LaunchSpec> action) {
        this.onLaunch = action;
        return this;
    }

    /** The current launch action, e.g. to extend it with {@code andThen}. */
    public Consumer<LaunchSpec> onLaunchAction() {
        return onLaunch;
    }

    @Override
    public LaunchedProcess launch(LaunchSpec spec) throws IOException {
        onLaunch.accept(spec);
        if (launchFailure != null) {
            processes.add(new FakeProcess(spec, new byte[0], new byte[0], 0, false, false, false,
                -1));
            throw launchFailure;
        }
        FakeProcess process = new FakeProcess(spec, stdout, stderr, exitCode, hang,
            ignoreDestroy, survivor, producedBytes);
        processes.add(process);
        return process;
    }

    public List<FakeProcess> processes() {
        return List.copyOf(processes);
    }

    public int launchCount() {
        return processes.size();
    }

    public FakeProcess last() {
        if (processes.isEmpty()) {
            throw new AssertionError("no process was launched");
        }
        return processes.get(processes.size() - 1);
    }

    /** One fake process; exposes what the runner did with it. */
    public static final class FakeProcess implements LaunchedProcess {

        private final LaunchSpec spec;
        private final ByteArrayOutputStream stdinBytes = new ByteArrayOutputStream();
        private volatile boolean stdinClosed;
        private final InputStream stdout;
        private final InputStream stderr;
        private final int exitCode;
        private final boolean ignoreDestroy;
        private final boolean survivor;
        private volatile boolean survivorAlive;
        private final CountDownLatch exited = new CountDownLatch(1);
        private volatile long destroyNanos = -1;
        private volatile long destroyForciblyNanos = -1;

        FakeProcess(LaunchSpec spec, byte[] stdout, byte[] stderr, int exitCode, boolean hang,
            boolean ignoreDestroy, boolean survivor, int producedBytes) {
            this.spec = spec;
            this.exitCode = exitCode;
            this.ignoreDestroy = ignoreDestroy;
            this.survivor = survivor;
            this.survivorAlive = survivor;
            if (producedBytes >= 0) {
                PipedInputStream out = new PipedInputStream(64 * 1024);
                PipedInputStream err = new PipedInputStream(64 * 1024);
                this.stdout = out;
                this.stderr = err;
                CountDownLatch writers = new CountDownLatch(2);
                produce(out, producedBytes, 'o', writers);
                produce(err, producedBytes, 'e', writers);
                Thread.ofVirtual().start(() -> {
                    try {
                        writers.await();
                        exited.countDown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } else {
                this.stdout = new ByteArrayInputStream(stdout);
                this.stderr = new ByteArrayInputStream(stderr);
                if (!hang) {
                    exited.countDown();
                }
            }
        }

        private static void produce(PipedInputStream sink, int bytes, char fill,
            CountDownLatch done) {
            try {
                PipedOutputStream source = new PipedOutputStream(sink);
                Thread.ofVirtual().start(() -> {
                    byte[] chunk = new byte[8192];
                    java.util.Arrays.fill(chunk, (byte) fill);
                    try (source) {
                        int left = bytes;
                        while (left > 0) {
                            int n = Math.min(left, chunk.length);
                            source.write(chunk, 0, n);
                            left -= n;
                        }
                    } catch (IOException e) {
                        // reader gone
                    } finally {
                        done.countDown();
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public LaunchSpec spec() {
            return spec;
        }

        public byte[] stdinBytes() {
            synchronized (stdinBytes) {
                return stdinBytes.toByteArray();
            }
        }

        public boolean stdinClosed() {
            return stdinClosed;
        }

        public boolean destroyed() {
            return destroyNanos >= 0;
        }

        public boolean destroyedForcibly() {
            return destroyForciblyNanos >= 0;
        }

        /** Time between {@code destroy()} and {@code destroyForcibly()}, if both happened. */
        public Optional<Duration> forcibleKillDelay() {
            return destroyNanos >= 0 && destroyForciblyNanos >= 0
                ? Optional.of(Duration.ofNanos(destroyForciblyNanos - destroyNanos))
                : Optional.empty();
        }

        @Override
        public OutputStream stdin() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                    synchronized (stdinBytes) {
                        stdinBytes.write(b);
                    }
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    synchronized (stdinBytes) {
                        stdinBytes.write(b, off, len);
                    }
                }

                @Override
                public void close() {
                    stdinClosed = true;
                }
            };
        }

        @Override
        public InputStream stdout() {
            return stdout;
        }

        @Override
        public InputStream stderr() {
            return stderr;
        }

        @Override
        public boolean waitFor(Duration timeout) throws InterruptedException {
            return exited.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int exitValue() {
            if (exited.getCount() > 0) {
                throw new IllegalThreadStateException("still running");
            }
            return destroyed() || destroyedForcibly() ? 143 : exitCode;
        }

        @Override
        public boolean isAlive() {
            return exited.getCount() > 0;
        }

        @Override
        public boolean treeAlive() {
            return isAlive() || survivorAlive;
        }

        @Override
        public void destroy() {
            destroyNanos = System.nanoTime();
            if (!ignoreDestroy) {
                exited.countDown();
            }
        }

        @Override
        public void destroyForcibly() {
            destroyForciblyNanos = System.nanoTime();
            survivorAlive = false;
            exited.countDown();
        }
    }
}
