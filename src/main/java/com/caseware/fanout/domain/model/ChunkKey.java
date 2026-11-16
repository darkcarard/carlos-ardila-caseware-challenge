package com.caseware.fanout.domain.model;

import java.util.Objects;

/**
 * Identity of one fan-out chunk: the {@code (publish_id, firm_id)} cursor scope plus the chunk's
 * index. REGIONAL ONLY, because it contains a {@link FirmId}.
 */
public record ChunkKey(PublishId publishId, FirmId firmId, int chunkIndex) {

    public ChunkKey {
        Objects.requireNonNull(publishId, "publishId");
        Objects.requireNonNull(firmId, "firmId");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0: " + chunkIndex);
        }
    }

    /** Returns a stable identifier used as {@code chunk_id} in telemetry and DLQ context. */
    public String chunkId() {
        return publishId + "/" + firmId + "/" + chunkIndex;
    }
}
