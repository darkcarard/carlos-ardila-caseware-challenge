package com.caseware.fanout.domain.port;

/**
 * Operator kill switch for downstream-loading work (backfill, reconciliation). It is checked
 * before every slot acquisition. When it trips, workers stop dispatching and drain gracefully.
 */
@FunctionalInterface
public interface KillSwitch {

    boolean isTripped();

    static KillSwitch never() {
        return () -> false;
    }
}
