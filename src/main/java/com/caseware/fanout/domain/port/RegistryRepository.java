package com.caseware.fanout.domain.port;

import com.caseware.fanout.domain.error.RegistryThrottledException;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.FirmId;
import java.util.Optional;

/**
 * Regional Engagement State Registry (DynamoDB, {@code PK=firm_id}, {@code SK=engagement_id}).
 *
 * <p><strong>There is no unconditional {@code save()}, by design.</strong> Each mutation names
 * the writer and carries that writer's optimistic-concurrency guard in its argument type, so an
 * unguarded upsert cannot be expressed. A failed condition is an ordinary result
 * ({@link WriteOutcome.ConditionFailed}), not an exception, because it is the expected outcome
 * of a race.
 *
 * <p>Implementations throw {@link RegistryThrottledException} on throttling or a 5xx.
 */
public interface RegistryRepository {

    /** Strongly consistent read of one row, scoped to its firm partition. */
    Optional<EngagementRecord> get(FirmId firmId, EngagementId engagementId);

    /**
     * Fan-out write. It only re-derives {@code pending_target}, {@code pending_since} and
     * {@code graph_epoch}.
     *
     * <p>Condition: {@code attribute_exists(state_seq) AND state_seq = :expectedStateSeq AND
     * graph_epoch <= :graphEpoch}. It never overwrites a user decision made mid-flight or a newer
     * graph epoch. On failure it returns the current row (if any) so the caller can tell
     * "superseded" from "user acted, recompute".
     */
    WriteOutcome conditionalFanoutWrite(FanoutWrite write);

    /**
     * Backfill or reconciliation write after a ~1 minute load. It replaces the row's ground truth
     * and the derived fields.
     *
     * <p>Condition: {@code (attribute_not_exists(state_seq) OR state_seq < :loadedStateSeq) AND
     * (attribute_not_exists(graph_epoch) OR graph_epoch <= :graphEpoch)}. A slow load never
     * overwrites a newer hook, and a derivation against a stale graph never overwrites a newer
     * evaluation.
     */
    WriteOutcome conditionalBackfillWrite(BackfillWrite write);
}
