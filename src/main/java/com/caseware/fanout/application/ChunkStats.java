package com.caseware.fanout.application;

/**
 * Per-chunk counters, reported to telemetry and carried by {@link FanoutResult.Completed}.
 *
 * @param evaluated rows evaluated by this invocation (from the resumed offset)
 * @param written rows whose conditional write was applied
 * @param unchanged rows that needed no write (same target, epoch already current, or locked)
 * @param superseded rows already evaluated at a newer graph epoch (stale work, acknowledged)
 * @param missing rows absent from the registry (deleted or not yet seeded)
 * @param conflictRetries re-read and recompute attempts caused by a {@code state_seq} race
 */
public record ChunkStats(int evaluated, int written, int unchanged, int superseded, int missing, int conflictRetries) {

    public static final ChunkStats ZERO = new ChunkStats(0, 0, 0, 0, 0, 0);

    public ChunkStats {
        if (evaluated < 0 || written < 0 || unchanged < 0 || superseded < 0 || missing < 0 || conflictRetries < 0) {
            throw new IllegalArgumentException("counters must be >= 0");
        }
    }
}
