package com.caseware.fanout.application;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import java.util.Objects;

/**
 * One engagement to seed (backfill) or re-verify (reconciliation) through the ~1 minute load.
 * REGIONAL ONLY.
 *
 * @param attemptsMade loads already attempted for this item in earlier runs (0 on first delivery)
 */
public record BackfillItem(FirmId firmId, EngagementId engagementId, int attemptsMade) {

    public BackfillItem {
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(engagementId, "engagementId");
        if (attemptsMade < 0) {
            throw new IllegalArgumentException("attemptsMade must be >= 0");
        }
    }

    public static BackfillItem first(FirmId firmId, EngagementId engagementId) {
        return new BackfillItem(firmId, engagementId, 0);
    }

    public BackfillItem nextAttempt() {
        return new BackfillItem(firmId, engagementId, attemptsMade + 1);
    }

    String key() {
        return firmId + "#" + engagementId;
    }
}
