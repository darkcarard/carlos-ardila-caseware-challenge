package com.caseware.fanout.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Exponential backoff with deterministic "equal jitter". It only <em>computes</em> delays and
 * never sleeps: the caller hands the delay to the queue (SQS visibility timeout, or a
 * re-enqueue with {@code retryAt}).
 *
 * <p>The jitter is a hash of {@code (key, attempt)}, not a random draw. Retries of different items
 * still spread out, which avoids a thundering herd on the downstream owner, while every test and
 * replay is reproducible.
 *
 * @param base delay for attempt 0, before jitter
 * @param cap upper bound for any delay
 * @param maxAttempts number of attempts after which the item is reported as failed
 */
public record RetryPolicy(Duration base, Duration cap, int maxAttempts) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(Duration.ofSeconds(30), Duration.ofMinutes(30), 6);

    public RetryPolicy {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(cap, "cap");
        if (base.isNegative() || base.isZero() || cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("need 0 < base <= cap");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }

    /** Returns true when an item that has already made {@code attemptsMade} attempts must not be retried. */
    public boolean exhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    /**
     * Returns the delay before the retry that follows attempt number {@code attempt} (0-based).
     * The result is in {@code [exp/2, exp]}, where {@code exp = min(cap, base * 2^attempt)}.
     */
    public Duration delayFor(int attempt, String key) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must be >= 0");
        }
        Objects.requireNonNull(key, "key");
        long capMillis = cap.toMillis();
        long exp = base.toMillis();
        for (int i = 0; i < attempt && exp < capMillis; i++) {
            exp *= 2; // stops as soon as it reaches the cap, so it cannot overflow
        }
        exp = Math.min(exp, capMillis);
        long half = exp / 2;
        long jitter = Math.floorMod(mix(key.hashCode() * 31L + attempt), half + 1);
        return Duration.ofMillis(half + jitter);
    }

    /** SplitMix64 finalizer: spreads similar keys across the jitter range. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
