package de.dadecker.inubit.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpServerTransport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Phase 3 review M1 / re-review N3: the session transport wrapper emits one message at a time,
 * also for sends subscribed before the transport is ready, and passes cancellation on.
 */
@Timeout(30)
class SerializingTransportProviderTest {

    private static JSONRPCMessage message(String name) {
        return new JSONRPCNotification(name, null);
    }

    /**
     * Behaves like SDK 2.0.1's stdio session transport: a send waits for the ready signal and
     * then emits on the thread that subscribes or completes the signal; it records overlapping
     * emissions, which the real (non-serialized) sink would reject.
     */
    private static class FakeTransport implements McpServerTransport {

        final Sinks.One<Void> ready = Sinks.one();
        final AtomicInteger emitting = new AtomicInteger();
        final AtomicBoolean overlapped = new AtomicBoolean();
        final AtomicInteger cancelled = new AtomicInteger();
        final List<String> emitted = new ArrayList<>();
        volatile String blockOn;
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Mono<Void> sendMessage(JSONRPCMessage message) {
            String name = ((JSONRPCNotification) message).method();
            return ready.asMono().then(Mono.defer(() -> {
                if (emitting.incrementAndGet() > 1) {
                    overlapped.set(true);
                }
                try {
                    if (name.equals(blockOn)) {
                        blocked.countDown();
                        await(release);
                    }
                    synchronized (emitted) {
                        emitted.add(name);
                    }
                } finally {
                    emitting.decrementAndGet();
                }
                return Mono.<Void>empty();
            })).doOnCancel(cancelled::incrementAndGet);
        }

        List<String> emitted() {
            synchronized (emitted) {
                return List.copyOf(emitted);
            }
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }

        @Override
        public void close() {
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aSendSubscribedBeforeReadyDoesNotOverlapWithALaterSend() throws Exception {
        FakeTransport fake = new FakeTransport();
        McpServerTransport transport = new SerializingTransportProvider.SerializingTransport(
            fake);
        fake.blockOn = "early";
        CountDownLatch earlyDone = new CountDownLatch(1);
        transport.sendMessage(message("early")).subscribe(null, error -> { },
            earlyDone::countDown);

        // the ready signal arrives on another thread and emits "early" there
        Thread readyThread = Thread.ofVirtual().start(() -> fake.ready.tryEmitEmpty());
        await(fake.blocked);
        // while "early" is being emitted, another thread sends
        CountDownLatch lateDone = new CountDownLatch(1);
        Thread sender = Thread.ofVirtual().start(() -> transport.sendMessage(message("late"))
            .subscribe(null, error -> { }, lateDone::countDown));
        sender.join(Duration.ofSeconds(5));
        fake.release.countDown();
        await(earlyDone);
        await(lateDone);
        readyThread.join(Duration.ofSeconds(5));

        assertThat(fake.overlapped).as("emissions overlapped").isFalse();
        assertThat(fake.emitted()).containsExactly("early", "late");
    }

    @Test
    void concurrentSendsAfterReadyAreEmittedOneAtATime() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.ready.tryEmitEmpty();
        McpServerTransport transport = new SerializingTransportProvider.SerializingTransport(
            fake);
        int count = 200;
        CountDownLatch done = new CountDownLatch(count);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = "m" + i;
            threads.add(Thread.ofVirtual().start(() -> transport.sendMessage(message(name))
                .subscribe(null, error -> { }, done::countDown)));
        }
        await(done);

        assertThat(fake.overlapped).isFalse();
        assertThat(fake.emitted()).hasSize(count);
    }

    @Test
    void cancellingAPendingSendCancelsTheInnerSendAndLetsTheNextOneThrough() {
        FakeTransport fake = new FakeTransport();
        McpServerTransport transport = new SerializingTransportProvider.SerializingTransport(
            fake);

        Disposable pending = transport.sendMessage(message("cancelled")).subscribe();
        CountDownLatch nextDone = new CountDownLatch(1);
        transport.sendMessage(message("next")).subscribe(null, error -> { },
            nextDone::countDown);
        pending.dispose();
        fake.ready.tryEmitEmpty();
        await(nextDone);

        assertThat(fake.cancelled).as("the inner subscription was cancelled").hasValue(1);
        assertThat(fake.emitted()).containsExactly("next");
    }

    @Test
    void anErrorOfTheInnerSendIsPassedOnAndDoesNotBlockTheNextSend() {
        McpServerTransport failing = new FakeTransport() {
            @Override
            public Mono<Void> sendMessage(JSONRPCMessage message) {
                return ((JSONRPCNotification) message).method().equals("bad")
                    ? Mono.error(new IllegalStateException("Failed to enqueue message"))
                    : Mono.empty();
            }
        };
        McpServerTransport transport = new SerializingTransportProvider.SerializingTransport(
            failing);

        AtomicBoolean failed = new AtomicBoolean();
        transport.sendMessage(message("bad")).subscribe(null, error -> failed.set(true));
        CountDownLatch done = new CountDownLatch(1);
        transport.sendMessage(message("good")).subscribe(null, error -> { }, done::countDown);

        assertThat(failed).isTrue();
        await(done);
    }
}
