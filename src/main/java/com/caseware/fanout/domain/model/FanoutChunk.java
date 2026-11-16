package com.caseware.fanout.domain.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * One SQS fan-out message: at most {@value #MAX_ENGAGEMENTS} engagements of a single firm, to be
 * re-evaluated against the {@code (productId, market)} graph at {@code graphEpoch} or later.
 * REGIONAL ONLY.
 *
 * <p>A 40,000-engagement firm becomes 80 chunks, each checkpointed on its own, so a crash or a
 * throttle resumes from the last unfinished chunk instead of redoing the firm.
 */
public record FanoutChunk(
        PublishId publishId,
        FirmId firmId,
        ProductId productId,
        Market market,
        long graphEpoch,
        int chunkIndex,
        int totalChunks,
        List<EngagementId> engagementIds) {

    /** Maximum number of engagements in one SQS fan-out message. */
    public static final int MAX_ENGAGEMENTS = 500;

    public FanoutChunk {
        Objects.requireNonNull(publishId, "publishId");
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(market, "market");
        engagementIds = List.copyOf(Objects.requireNonNull(engagementIds, "engagementIds"));
        if (graphEpoch < 0) {
            throw new IllegalArgumentException("graphEpoch must be >= 0: " + graphEpoch);
        }
        if (totalChunks < 1 || chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new IllegalArgumentException("chunkIndex " + chunkIndex + " out of range for totalChunks " + totalChunks);
        }
        if (engagementIds.isEmpty()) {
            throw new IllegalArgumentException("a chunk must contain at least one engagement");
        }
        if (engagementIds.size() > MAX_ENGAGEMENTS) {
            throw new IllegalArgumentException("a chunk holds at most " + MAX_ENGAGEMENTS
                    + " engagements, got " + engagementIds.size());
        }
        if (new HashSet<>(engagementIds).size() != engagementIds.size()) {
            throw new IllegalArgumentException("duplicate engagement ids in chunk " + chunkIndex);
        }
    }

    public ChunkKey key() {
        return new ChunkKey(publishId, firmId, chunkIndex);
    }

    public int size() {
        return engagementIds.size();
    }
}
