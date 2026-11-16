package com.caseware.fanout.domain.port;

import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.Source;
import java.util.Objects;

/**
 * Guarded backfill or reconciliation mutation. {@code row.stateSeq()} is the {@code state_seq}
 * returned by the load and {@code row.graphEpoch()} is the epoch of the graph used to derive
 * {@code pending_target}. Both are the write's guards.
 */
public record BackfillWrite(EngagementRecord row) {

    public BackfillWrite {
        Objects.requireNonNull(row, "row");
        if (row.source() == Source.HOOK) {
            throw new IllegalArgumentException("backfill writes must have source BACKFILL or RECONCILED");
        }
    }

    public long loadedStateSeq() {
        return row.stateSeq();
    }

    public long graphEpoch() {
        return row.graphEpoch();
    }
}
