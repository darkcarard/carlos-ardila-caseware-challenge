package com.caseware.fanout.doubles;

import com.caseware.fanout.domain.model.ChunkCursor;
import com.caseware.fanout.domain.model.ChunkKey;
import com.caseware.fanout.domain.port.CheckpointStore;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Monotonic cursor store: a proposal that is not ahead of the stored cursor is ignored. */
public final class InMemoryCheckpointStore implements CheckpointStore {

    private final ConcurrentHashMap<ChunkKey, ChunkCursor> cursors = new ConcurrentHashMap<>();
    private final AtomicInteger advances = new AtomicInteger();

    @Override
    public Optional<ChunkCursor> load(ChunkKey key) {
        return Optional.ofNullable(cursors.get(key));
    }

    @Override
    public ChunkCursor advance(ChunkCursor proposed) {
        advances.incrementAndGet();
        return cursors.merge(proposed.key(), proposed,
                (current, incoming) -> incoming.isAheadOf(current) ? incoming : current);
    }

    public int advances() {
        return advances.get();
    }
}
