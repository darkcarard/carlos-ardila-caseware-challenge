package com.caseware.fanout.infrastructure.resilience;

import com.caseware.fanout.domain.port.CircuitBreaker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * {@code CLOSED -> OPEN -> HALF_OPEN -> CLOSED | OPEN} state machine driven by an injected
 * {@link Clock}. It never reads the system clock directly, so tests can drive every
 * transition deterministically.
 *
 * <ul>
 *   <li>{@code CLOSED}: everything is allowed. {@code failureThreshold} consecutive failures open it.</li>
 *   <li>{@code OPEN}: everything is rejected until {@code openDuration} has elapsed.</li>
 *   <li>{@code HALF_OPEN}: exactly one probe is admitted. A success closes the breaker, a failure
 *       re-opens it with a fresh {@code openDuration}.</li>
 * </ul>
 *
 * <p>It is synchronized: the critical sections are tiny and contention is bounded by worker
 * count.
 */
public final class ClockedCircuitBreaker implements CircuitBreaker {

    private final Clock clock;
    private final int failureThreshold;
    private final Duration openDuration;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt = Instant.EPOCH;
    private boolean probeInFlight;

    public ClockedCircuitBreaker(Clock clock, int failureThreshold, Duration openDuration) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.openDuration = Objects.requireNonNull(openDuration, "openDuration");
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        if (openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("openDuration must be > 0");
        }
        this.failureThreshold = failureThreshold;
    }

    @Override
    public synchronized boolean tryAcquirePermission() {
        switch (state) {
            case CLOSED -> {
                return true;
            }
            case OPEN -> {
                if (clock.instant().isBefore(openedAt.plus(openDuration))) {
                    return false;
                }
                state = State.HALF_OPEN;
                probeInFlight = true;
                return true;
            }
            case HALF_OPEN -> {
                if (probeInFlight) {
                    return false;
                }
                probeInFlight = true;
                return true;
            }
            default -> throw new IllegalStateException("unknown state " + state);
        }
    }

    @Override
    public synchronized void recordSuccess() {
        if (state == State.OPEN) {
            // A late success from a call admitted before the breaker opened. Only a HALF_OPEN probe may close it.
            return;
        }
        consecutiveFailures = 0;
        probeInFlight = false;
        state = State.CLOSED;
    }

    @Override
    public synchronized void recordFailure() {
        if (state == State.OPEN) {
            return; // late failure; already open, do not extend the window
        }
        probeInFlight = false;
        if (state == State.HALF_OPEN) {
            open();
            return;
        }
        consecutiveFailures++;
        if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
            open();
        }
    }

    @Override
    public synchronized void releasePermission() {
        if (state == State.HALF_OPEN) {
            probeInFlight = false;
        }
    }

    @Override
    public synchronized State state() {
        return state;
    }

    @Override
    public synchronized Duration retryAfter() {
        if (state != State.OPEN) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(clock.instant(), openedAt.plus(openDuration));
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    private void open() {
        state = State.OPEN;
        openedAt = clock.instant();
        consecutiveFailures = 0;
    }
}
