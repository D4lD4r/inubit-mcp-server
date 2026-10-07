package de.dadecker.inubit.mcp.domain.port;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Sends one SOAP test message to an endpoint below a node's configured SOAP base address
 * (feature 004, research D-17, D-25 H8). The envelope is sent unchanged; redirects are never
 * followed; a timeout is part of the exchange (the caller still collects what INUBIT did), not
 * an error. Failures are thrown as
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException} with the server id.
 */
public interface E2ePort {

    /**
     * One test message.
     *
     * @param path       the endpoint path relative to the SOAP base address (no scheme, host,
     *                   {@code ..} or query)
     * @param envelope   the bytes of the envelope file, sent unchanged
     * @param soapAction the {@code SOAPAction}, if any
     * @param testId     the value of the {@code X-Inubit-Mcp-Test-Id} header
     * @param timeout    how long to wait for the answer
     */
    record Message(String path, byte[] envelope, Optional<String> soapAction, String testId,
        Duration timeout) {

        public Message {
            Objects.requireNonNull(path, "path");
            envelope = envelope.clone();
            soapAction = soapAction == null ? Optional.empty() : soapAction;
            Objects.requireNonNull(testId, "testId");
            Objects.requireNonNull(timeout, "timeout");
        }

        @Override
        public byte[] envelope() {
            return envelope.clone();
        }

        /** No payload: the envelope may hold business data. */
        @Override
        public String toString() {
            return "Message[" + path + ", " + envelope.length + " bytes, " + testId + "]";
        }
    }

    /**
     * What came back.
     *
     * @param endpoint  the URL the message was sent to (without credentials)
     * @param status    the HTTP status; empty on timeout
     * @param body      the response body, at most {@link #MAX_BODY_BYTES}
     * @param truncated true if the body was longer
     */
    record Exchange(String endpoint, Optional<Integer> status, byte[] body, boolean truncated,
        Duration duration, boolean timedOut) {

        public Exchange {
            Objects.requireNonNull(endpoint, "endpoint");
            status = status == null ? Optional.empty() : status;
            body = body.clone();
            Objects.requireNonNull(duration, "duration");
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            return "Exchange[" + endpoint + ", " + status + ", " + body.length + " bytes"
                + (timedOut ? ", timed out" : "") + "]";
        }
    }

    /** The most bytes of a response body that are kept. */
    int MAX_BODY_BYTES = 8 << 20;

    /**
     * The URL a message to {@code path} goes to, without sending anything (for the preview and
     * the input check).
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code INVALID_INPUT} for a
     *     path that leaves the base address
     */
    String endpoint(String path);

    /**
     * Posts {@code message}.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code INVALID_INPUT} for a
     *     path that leaves the base address (nothing sent), {@code UNREACHABLE},
     *     {@code TLS_ERROR}
     */
    Exchange post(Message message);
}
