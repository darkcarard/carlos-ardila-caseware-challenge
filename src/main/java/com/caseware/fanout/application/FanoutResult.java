package com.caseware.fanout.application;

import com.caseware.fanout.domain.model.ChunkCursor;
import com.caseware.fanout.domain.model.ChunkKey;
import java.time.Duration;
import java.util.Objects;

/**
 * Closed set of outcomes of processing one fan-out chunk, which the SQS adapter maps to
 * ack, nack or DLQ:
 *
 * <table>
 *   <caption>SQS mapping</caption>
 *   <tr><th>Outcome</th><th>SQS action</th></tr>
 *   <tr><td>{@link Completed}</td><td>ack (delete the message)</td></tr>
 *   <tr><td>{@link PartialRetry}</td><td>nack with backoff; the next delivery resumes from {@code cursor}</td></tr>
 *   <tr><td>{@link Throttled}</td><td>nack with visibility timeout {@code retryAfter}; the breaker is open, so stop pulling</td></tr>
 *   <tr><td>{@link PoisonMessage}</td><td>DLQ after max receives, with structured context</td></tr>
 * </table>
 */
public sealed interface FanoutResult {

    /** Every row in the chunk was evaluated. This includes rows superseded by a newer epoch. */
    record Completed(ChunkKey key, ChunkStats stats) implements FanoutResult {
        public Completed {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(stats, "stats");
        }
    }

    /** A transient failure or a persistent conflict. Progress up to {@code cursor} is durable. */
    record PartialRetry(ChunkCursor cursor, String cause) implements FanoutResult {
        public PartialRetry {
            Objects.requireNonNull(cursor, "cursor");
            Objects.requireNonNull(cause, "cause");
        }
    }

    /** The circuit breaker is open, so no work was attempted. */
    record Throttled(ChunkKey key, Duration retryAfter) implements FanoutResult {
        public Throttled {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(retryAfter, "retryAfter");
            if (retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter must be >= 0");
            }
        }
    }

    /**
     * Unrecoverable data or contract error. The chunk goes to the DLQ. Context is limited to
     * {@code publishId}, {@code firmId} and {@code chunkIndex}, all inside {@link ChunkKey}; no
     * engagement content is included.
     */
    record PoisonMessage(ChunkKey key, String reason) implements FanoutResult {
        public PoisonMessage {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
