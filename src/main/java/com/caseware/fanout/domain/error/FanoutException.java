package com.caseware.fanout.domain.error;

import java.io.Serial;

/**
 * Closed failure taxonomy for the fan-out and backfill paths.
 *
 * <ul>
 *   <li>{@link TransientInfrastructureException}: throttling, timeouts, 429/5xx. It trips the
 *       circuit breaker and leads to a retry with backoff. Never poison-pills healthy data.</li>
 *   <li>{@link OptimisticLockConflictException}: {@code state_seq}/{@code graph_epoch} conflicts
 *       that persist after the bounded re-read and recompute loop. The work is yielded and retried.</li>
 *   <li>{@link NonRetryableDataException}: a corrupt payload or an unknown lineage. It goes to the
 *       DLQ and does not trip the downstream circuit breaker.</li>
 * </ul>
 */
public abstract sealed class FanoutException extends RuntimeException
        permits TransientInfrastructureException, OptimisticLockConflictException, NonRetryableDataException {

    @Serial
    private static final long serialVersionUID = 1L;

    protected FanoutException(String message) {
        super(message);
    }

    protected FanoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
