package com.caseware.fanout.domain.service;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FanoutChunk;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.Market;
import com.caseware.fanout.domain.model.ProductId;
import com.caseware.fanout.domain.model.PublishId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Splits one firm's affected engagements into SQS fan-out chunks of at most
 * {@link FanoutChunk#MAX_ENGAGEMENTS}. This is a pure function.
 *
 * <p><strong>Deterministic boundaries.</strong> Ids are de-duplicated and sorted before slicing.
 * Re-planning the same publish for the same firm, for example after a planner crash or when
 * GSI-2 pages come back in another order, produces identical chunks. Chunk cursors keyed by
 * {@code (publish_id, firm_id, chunk_index)} therefore keep pointing at the same rows.
 */
public final class ChunkPlanner {

    private ChunkPlanner() {}

    public static List<FanoutChunk> plan(PublishId publishId, FirmId firmId, ProductId productId, Market market,
                                         long graphEpoch, Collection<EngagementId> engagementIds) {
        return plan(publishId, firmId, productId, market, graphEpoch, engagementIds, FanoutChunk.MAX_ENGAGEMENTS);
    }

    static List<FanoutChunk> plan(PublishId publishId, FirmId firmId, ProductId productId, Market market,
                                  long graphEpoch, Collection<EngagementId> engagementIds, int chunkSize) {
        Objects.requireNonNull(engagementIds, "engagementIds");
        if (chunkSize < 1 || chunkSize > FanoutChunk.MAX_ENGAGEMENTS) {
            throw new IllegalArgumentException("chunkSize must be in [1, " + FanoutChunk.MAX_ENGAGEMENTS + "]: " + chunkSize);
        }
        TreeSet<EngagementId> ordered = new TreeSet<>(Comparator.comparing(EngagementId::value));
        ordered.addAll(engagementIds);
        List<EngagementId> ids = List.copyOf(ordered);

        int total = (ids.size() + chunkSize - 1) / chunkSize;
        List<FanoutChunk> chunks = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            int from = index * chunkSize;
            int to = Math.min(from + chunkSize, ids.size());
            chunks.add(new FanoutChunk(publishId, firmId, productId, market, graphEpoch, index, total, ids.subList(from, to)));
        }
        return List.copyOf(chunks);
    }
}
