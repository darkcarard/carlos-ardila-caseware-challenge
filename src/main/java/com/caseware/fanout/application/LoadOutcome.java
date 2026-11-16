package com.caseware.fanout.application;

import com.caseware.fanout.domain.model.EngagementRecord;
import java.time.Instant;
import java.util.Objects;

/** Outcome of one backfill item. Every item submitted to a run ends in exactly one of these. */
public sealed interface LoadOutcome {

    BackfillItem item();

    /** Loaded and written: the registry row now reflects the load (source {@code BACKFILL}). */
    record Seeded(BackfillItem item, EngagementRecord row) implements LoadOutcome {
        public Seeded {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(row, "row");
        }
    }

    /**
     * Loaded, but a hook already wrote an equal or newer {@code state_seq}, so the hook stays
     * authoritative. The load is not wasted: it confirmed the row.
     */
    record SkippedStale(BackfillItem item, long loadedStateSeq, long currentStateSeq) implements LoadOutcome {
        public SkippedStale {
            Objects.requireNonNull(item, "item");
        }
    }

    /**
     * Transient failure, or not started because the circuit is open. Re-deliver {@code next} no
     * earlier than {@code retryAt}.
     */
    record Deferred(BackfillItem item, BackfillItem next, Instant retryAt, String reason) implements LoadOutcome {
        public Deferred {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(next, "next");
            Objects.requireNonNull(retryAt, "retryAt");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Permanent: a data error, or retries exhausted. It goes to the DLQ for the runbook. */
    record Failed(BackfillItem item, String reason) implements LoadOutcome {
        public Failed {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Not started, or interrupted, because the kill switch tripped. It is safe to re-deliver later. */
    record Aborted(BackfillItem item) implements LoadOutcome {
        public Aborted {
            Objects.requireNonNull(item, "item");
        }
    }
}
