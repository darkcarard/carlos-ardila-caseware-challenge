package com.caseware.fanout.domain.port;

import com.caseware.fanout.domain.model.ChunkCursor;
import com.caseware.fanout.domain.model.ChunkKey;
import java.util.Optional;

/**
 * Durable per-{@code (publish_id, firm_id)} chunk cursors. In production this is a conditional
 * write keyed by {@link ChunkKey}.
 */
public interface CheckpointStore {

    Optional<ChunkCursor> load(ChunkKey key);

    /**
     * Monotonic, conditional advance. The proposal is stored only if it is ahead of the stored
     * cursor, so a slow duplicate delivery can never rewind progress.
     *
     * @return the cursor that is stored after the call (the proposal, or the newer existing one)
     */
    ChunkCursor advance(ChunkCursor proposed);
}
