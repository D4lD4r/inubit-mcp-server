package de.dadecker.inubit.mcp.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * Serializes the outbound messages of a transport provider (Phase 3 review M1).
 *
 * <p>Root cause: in SDK 2.0.1 the stdio session transport ({@code StdioServerTransportProvider
 * .StdioMcpSessionTransport}) puts every outbound message into a
 * {@code Sinks.many().unicast().onBackpressureBuffer()} with {@code tryEmitNext}. Reactor's
 * standard sinks are not thread-safe for concurrent emission: a second thread emitting at the
 * same time gets {@code FAIL_NON_SERIALIZED}, the SDK turns that into "Failed to enqueue
 * message", and the response is lost. The sync server runs tool handlers on
 * {@code boundedElastic} threads, so parallel {@code tools/call} requests answer concurrently.
 * The SDK has no option to serialize this, short of {@code immediateExecution}, which would run
 * every tool on the single inbound thread and let one slow call block all others.
 *
 * <p>Fix: the session transport handed to the SDK's session factory is wrapped so that the
 * sends run one after the other ({@link SerializingTransport}): the next send is subscribed
 * only when the previous one has completed. Once the transport is ready, the SDK's
 * {@code sendMessage} emits synchronously during the subscription; a send subscribed before
 * that is emitted by the SDK on the thread that completes the ready signal, and the next send
 * waits for it (Phase 3 re-review N3). Cancellation is passed on to the SDK's subscription.
 */
final class SerializingTransportProvider implements McpServerTransportProvider {

    private final McpServerTransportProvider delegate;

    SerializingTransportProvider(McpServerTransportProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        delegate.setSessionFactory(transport -> sessionFactory.create(
            new SerializingTransport(transport)));
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        return delegate.notifyClients(method, params);
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return delegate.notifyClient(sessionId, method, params);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    /**
     * A session transport whose sends run strictly one after the other: a send is subscribed to
     * the delegate only after the previous one has completed (or failed, or was cancelled), so
     * emissions never overlap, wherever the SDK runs them. This also covers sends subscribed
     * before the transport is ready, which the SDK emits later on the thread that completes the
     * ready signal: the next send waits for that emission. Cancelling a send cancels its
     * delegate subscription (or skips it if it has not started) and lets the next one run.
     */
    static final class SerializingTransport implements McpServerTransport {

        private final McpServerTransport delegate;
        private final ReentrantLock lock = new ReentrantLock();
        private final ArrayDeque<Send> queue = new ArrayDeque<>();
        /** True while a send is running or the queue is being drained (guarded by lock). */
        private boolean busy;

        SerializingTransport(McpServerTransport delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public Mono<Void> sendMessage(JSONRPCMessage message) {
            return Mono.create(sink -> {
                Send send = new Send(message, sink);
                sink.onCancel(send::cancel);
                lock.lock();
                try {
                    queue.add(send);
                    if (busy) {
                        return;
                    }
                    busy = true;
                } finally {
                    lock.unlock();
                }
                drain();
            });
        }

        /** Starts queued sends until one completes asynchronously or the queue is empty. */
        private void drain() {
            while (true) {
                Send next;
                lock.lock();
                try {
                    next = queue.poll();
                    if (next == null) {
                        busy = false;
                        return;
                    }
                } finally {
                    lock.unlock();
                }
                if (!next.start()) {
                    return; // its completion continues the drain
                }
            }
        }

        /** One queued send; {@link #finish()} hands over to the next one exactly once. */
        private final class Send {

            private final JSONRPCMessage message;
            private final MonoSink<Void> sink;
            private final AtomicBoolean cancelled = new AtomicBoolean();
            private final AtomicBoolean finished = new AtomicBoolean();
            /** Set by whichever comes second: the return of start() or the completion. */
            private final AtomicBoolean handOver = new AtomicBoolean();
            private volatile Disposable subscription;

            Send(JSONRPCMessage message, MonoSink<Void> sink) {
                this.message = message;
                this.sink = sink;
            }

            /** @return true if the send completed synchronously (the caller drains on) */
            boolean start() {
                if (cancelled.get()) {
                    return true;
                }
                subscription = delegate.sendMessage(message).subscribe(ignored -> { },
                    error -> {
                        sink.error(error);
                        finish();
                    },
                    () -> {
                        sink.success();
                        finish();
                    });
                if (cancelled.get()) {
                    subscription.dispose();
                    finish();
                }
                return !handOver.compareAndSet(false, true);
            }

            void cancel() {
                if (!cancelled.compareAndSet(false, true)) {
                    return;
                }
                Disposable started = subscription;
                if (started != null) {
                    started.dispose();
                    finish();
                }
            }

            private void finish() {
                if (finished.compareAndSet(false, true) && !handOver.compareAndSet(false, true)) {
                    drain(); // start() has returned already: continue here
                }
            }
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return delegate.unmarshalFrom(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return delegate.closeGracefully();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public List<String> protocolVersions() {
            return delegate.protocolVersions();
        }
    }
}
