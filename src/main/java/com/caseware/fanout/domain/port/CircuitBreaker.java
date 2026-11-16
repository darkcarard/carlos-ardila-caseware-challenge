package com.caseware.fanout.domain.port;

import java.time.Duration;

/**
 * Circuit breaker around one dependency: the registry for fan-out, the downstream loader for
 * backfill. Only {@link com.caseware.fanout.domain.error.TransientInfrastructureException} counts
 * as a failure. Data errors and optimistic-lock conflicts never trip it.
 *
 * <p>Each successful {@link #tryAcquirePermission()} must be followed by exactly one
 * {@link #recordSuccess()} or {@link #recordFailure()}. In {@code HALF_OPEN} that call releases
 * the single probe slot.
 */
public interface CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    /** Returns false while {@code OPEN}, or while a {@code HALF_OPEN} probe is already in flight. */
    boolean tryAcquirePermission();

    void recordSuccess();

    void recordFailure();

    State state();

    /** Returns the time left before an {@code OPEN} breaker admits a probe; zero otherwise. */
    Duration retryAfter();
}
