package com.caseware.fanout.domain.model;

/** Provenance of a registry row's ground-truth fields ({@code base_version}, {@code state_seq}, ...). */
public enum Source {
    /** Seeded or updated by an engagement-system outbox hook. */
    HOOK,
    /** Seeded by the one-time throttled backfill (~1 min load per file). */
    BACKFILL,
    /** Corrected by sampled reconciliation (~1 min load per file). */
    RECONCILED
}
