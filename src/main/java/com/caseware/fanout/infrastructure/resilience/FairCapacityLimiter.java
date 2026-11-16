package com.caseware.fanout.infrastructure.resilience;

import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.port.CapacityLimiter;
import com.caseware.fanout.domain.port.FanoutMetrics;
import com.caseware.fanout.domain.port.TelemetryContext;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two-level semaphore limiter for the ~1 minute downstream load: global ≤ {@code maxGlobal}
 * (100) and per firm ≤ {@code maxPerFirm} (10).
 *
 * <p><b>Deadlock freedom.</b> Acquisition order is always per-firm first, then global. A thread
 * never holds a global slot while waiting for a per-firm one, so no cycle can form: per-firm
 * waiters hold nothing, and global waiters hold only their own firm's slot, which no other
 * firm's thread needs. Release is in reverse order (global, then per-firm), exactly once, from
 * {@link Permit#close()}.
 *
 * <p><b>Hard invariants.</b> In-flight counters are incremented after both semaphores are held
 * and decremented before either is released, so they are a faithful lower bound of semaphore
 * usage. Exceeding a cap emits {@code invariant.violation} (it pages) and fails under {@code -ea}.
 */
public final class FairCapacityLimiter implements CapacityLimiter {

    private final int maxGlobal;
    private final int maxPerFirm;
    private final FanoutMetrics metrics;
    private final Semaphore global;
    private final ConcurrentHashMap<FirmId, Semaphore> perFirm = new ConcurrentHashMap<>();
    private final AtomicInteger globalInFlight = new AtomicInteger();
    private final ConcurrentHashMap<FirmId, AtomicInteger> firmInFlight = new ConcurrentHashMap<>();

    public FairCapacityLimiter(int maxGlobal, int maxPerFirm, FanoutMetrics metrics) {
        if (maxPerFirm < 1) { // and maxGlobal >= maxPerFirm >= 1 follows from the next check
            throw new IllegalArgumentException("caps must be >= 1");
        }
        if (maxPerFirm > maxGlobal) {
            throw new IllegalArgumentException("per-firm cap cannot exceed the global cap");
        }
        this.maxGlobal = maxGlobal;
        this.maxPerFirm = maxPerFirm;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.global = new Semaphore(maxGlobal, true);
    }

    @Override
    public Permit acquire(FirmId firmId) throws InterruptedException {
        Semaphore firm = firmSemaphore(firmId);
        firm.acquire();
        try {
            global.acquire();
        } catch (InterruptedException e) {
            firm.release(); // never leak the per-firm slot
            throw e;
        }
        return granted(firmId, firm);
    }

    @Override
    public Optional<Permit> tryAcquire(FirmId firmId) {
        Semaphore firm = firmSemaphore(firmId);
        if (!firm.tryAcquire()) {
            return Optional.empty();
        }
        if (!global.tryAcquire()) {
            firm.release();
            return Optional.empty();
        }
        return Optional.of(granted(firmId, firm));
    }

    @Override
    public int globalInFlight() {
        return globalInFlight.get();
    }

    @Override
    public int inFlight(FirmId firmId) {
        AtomicInteger count = firmInFlight.get(firmId);
        return count == null ? 0 : count.get();
    }

    private Semaphore firmSemaphore(FirmId firmId) {
        Objects.requireNonNull(firmId, "firmId");
        return perFirm.computeIfAbsent(firmId, k -> new Semaphore(maxPerFirm, true));
    }

    private Permit granted(FirmId firmId, Semaphore firm) {
        AtomicInteger firmCount = firmInFlight.computeIfAbsent(firmId, k -> new AtomicInteger());
        int g = globalInFlight.incrementAndGet();
        int f = firmCount.incrementAndGet();
        if (g > maxGlobal || f > maxPerFirm) {
            metrics.invariantViolation("downstream.capacity.exceeded", TelemetryContext.forFirm(firmId));
            assert false : "capacity invariant violated: global=" + g + "/" + maxGlobal + " firm=" + f + "/" + maxPerFirm;
        }
        return new SlotPermit(firmId, firm, firmCount);
    }

    private final class SlotPermit implements Permit {
        private final FirmId firmId;
        private final Semaphore firm;
        private final AtomicInteger firmCount;
        private final AtomicBoolean closed = new AtomicBoolean();

        SlotPermit(FirmId firmId, Semaphore firm, AtomicInteger firmCount) {
            this.firmId = firmId;
            this.firm = firm;
            this.firmCount = firmCount;
        }

        @Override
        public FirmId firmId() {
            return firmId;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                firmCount.decrementAndGet();
                globalInFlight.decrementAndGet();
                global.release();
                firm.release();
            }
        }
    }
}
