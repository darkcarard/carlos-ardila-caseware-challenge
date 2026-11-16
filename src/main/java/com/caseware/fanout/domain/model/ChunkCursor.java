package com.caseware.fanout.domain.model;

import java.util.Objects;

/**
 * Checkpointed progress of one chunk inside its per-{@code (publish_id, firm_id)} cursor.
 * {@code nextRowOffset} is the first row not yet durably evaluated. Progress only moves forward:
 * a cursor never regresses, and a completed cursor stays completed.
 */
public record ChunkCursor(ChunkKey key, int nextRowOffset, boolean completed) {

    public ChunkCursor {
        Objects.requireNonNull(key, "key");
        if (nextRowOffset < 0 || nextRowOffset > FanoutChunk.MAX_ENGAGEMENTS) {
            throw new IllegalArgumentException("nextRowOffset out of range: " + nextRowOffset);
        }
    }

    public static ChunkCursor start(ChunkKey key) {
        return new ChunkCursor(key, 0, false);
    }

    /** Returns a cursor advanced to {@code offset}. Rejects regressions and changes after completion. */
    public ChunkCursor advanceTo(int offset) {
        if (completed) {
            throw new IllegalStateException("cursor " + key.chunkId() + " is already completed");
        }
        if (offset < nextRowOffset) {
            throw new IllegalArgumentException("cursor cannot regress from " + nextRowOffset + " to " + offset);
        }
        return new ChunkCursor(key, offset, false);
    }

    public ChunkCursor complete(int finalOffset) {
        return advanceTo(finalOffset).asCompleted();
    }

    /**
     * Returns true when this cursor is strictly ahead of {@code other}, i.e. completed where
     * {@code other} is not, or at a larger offset.
     */
    public boolean isAheadOf(ChunkCursor other) {
        if (!key.equals(other.key)) {
            throw new IllegalArgumentException("cannot compare cursors of different chunks");
        }
        if (completed != other.completed) {
            return completed;
        }
        return nextRowOffset > other.nextRowOffset;
    }

    private ChunkCursor asCompleted() {
        return new ChunkCursor(key, nextRowOffset, true);
    }
}
