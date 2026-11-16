package com.caseware.fanout.domain.port;

import com.caseware.fanout.domain.model.FirmId;
import java.util.Optional;

/**
 * Downstream capacity guard for the ~1 minute load path. It enforces two hard invariants: global
 * in-flight loads never exceed {@code maxGlobal} (100), and in-flight loads per firm never exceed
 * {@code maxPerFirm} (10).
 *
 * <p><strong>Lock ordering contract:</strong> implementations acquire the per-firm permit first,
 * then the global one, and never hold a global slot while blocked on a per-firm slot. A
 * {@link Permit} releases both, in reverse order, on {@link Permit#close()}. Use it with
 * try-with-resources.
 */
public interface CapacityLimiter {

    /** Blocks until both a per-firm and a global slot are held. */
    Permit acquire(FirmId firmId) throws InterruptedException;

    /** Non-blocking variant used by the fair dispatcher. Returns empty if either cap is full. */
    Optional<Permit> tryAcquire(FirmId firmId);

    int globalInFlight();

    int inFlight(FirmId firmId);

    /** Both slots held for one downstream load. Idempotent close. */
    interface Permit extends AutoCloseable {

        FirmId firmId();

        @Override
        void close();
    }
}
