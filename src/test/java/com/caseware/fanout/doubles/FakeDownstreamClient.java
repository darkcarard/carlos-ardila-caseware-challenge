package com.caseware.fanout.doubles;

import static com.caseware.fanout.doubles.TestData.EU;
import static com.caseware.fanout.doubles.TestData.PRODUCT;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.LoadedEngagement;
import com.caseware.fanout.domain.model.TemplateVersion;
import com.caseware.fanout.domain.port.DownstreamEngagementClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

/**
 * Stand-in for the ~1 minute engagement loader, used as an <strong>independent oracle</strong>
 * for the capacity invariants. It measures concurrency at its own entry and exit, so it does not
 * trust the limiter's counters.
 *
 * <p>In gated mode every call parks until the test releases it, which models a 1-minute load
 * without any sleeping. Waiting for a condition is event-driven (lock and condition), with a
 * timeout used only as a safety net against hangs.
 */
public final class FakeDownstreamClient implements DownstreamEngagementClient {

    /** One parked load. */
    public record Call(FirmId firmId, EngagementId engagementId, CompletableFuture<Void> gate) {
        public void release() {
            gate.complete(null);
        }
    }

    private final boolean gated;
    private volatile BiFunction<FirmId, EngagementId, LoadedEngagement> responder =
            (firm, engagement) -> loaded(firm, engagement, "v4", 1);

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakGlobal = new AtomicInteger();
    private final AtomicInteger peakPerFirm = new AtomicInteger();
    private final Map<FirmId, AtomicInteger> perFirm = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<Call> parked = new LinkedBlockingQueue<>();
    private final List<FirmId> completionOrder = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();

    private FakeDownstreamClient(boolean gated) {
        this.gated = gated;
    }

    /** Returns immediately (with the configured responder's answer). */
    public static FakeDownstreamClient instant() {
        return new FakeDownstreamClient(false);
    }

    /** Parks every call until the test releases it. */
    public static FakeDownstreamClient gated() {
        return new FakeDownstreamClient(true);
    }

    /** Sets the answer (or exception) for every load from now on. */
    public void respondWith(BiFunction<FirmId, EngagementId, LoadedEngagement> responder) {
        this.responder = responder;
    }

    public static LoadedEngagement loaded(FirmId firm, EngagementId engagement, String base, long stateSeq) {
        return new LoadedEngagement(firm, engagement, PRODUCT, EU, TemplateVersion.of(base), Set.of(), stateSeq, false);
    }

    @Override
    public LoadedEngagement loadEngagement(FirmId firmId, EngagementId engagementId) throws InterruptedException {
        calls.incrementAndGet();
        enter(firmId);
        try {
            if (gated) {
                Call call = new Call(firmId, engagementId, new CompletableFuture<>());
                parked.add(call);
                signal();
                try {
                    call.gate().get(); // interruptible: the kill switch cancels us here
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException(e);
                }
            }
            return responder.apply(firmId, engagementId);
        } finally {
            exit(firmId);
        }
    }

    // ---- test driving ----

    /** Blocks until at least {@code n} loads are in flight. */
    public void awaitInFlight(int n, Duration timeout) throws InterruptedException {
        awaitCondition(() -> inFlight.get() >= n, timeout, "in-flight >= " + n + " (was " + inFlight.get() + ")");
    }

    /** Blocks until {@code condition} holds; it is re-checked on every load entry and exit. */
    public void await(java.util.function.BooleanSupplier condition, Duration timeout, String what)
            throws InterruptedException {
        awaitCondition(condition, timeout, what);
    }

    public int completed() {
        return completionOrder.size();
    }

    /** Releases the oldest parked call, waiting up to {@code timeout} for one. Returns false if none appeared. */
    public boolean releaseNext(Duration timeout) throws InterruptedException {
        Call call = parked.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (call == null) {
            return false;
        }
        call.release();
        return true;
    }

    // ---- oracle readings ----

    public int calls() {
        return calls.get();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int inFlight(FirmId firmId) {
        AtomicInteger count = perFirm.get(firmId);
        return count == null ? 0 : count.get();
    }

    public int peakGlobal() {
        return peakGlobal.get();
    }

    public int peakPerFirm() {
        return peakPerFirm.get();
    }

    public List<FirmId> completionOrder() {
        return List.copyOf(completionOrder);
    }

    /**
     * Per-firm first, then global (and the reverse on exit), so that once a waiter sees the global
     * count reach N, every per-firm count behind those N loads is already visible.
     */
    private void enter(FirmId firmId) {
        peakPerFirm.accumulateAndGet(perFirm.computeIfAbsent(firmId, k -> new AtomicInteger()).incrementAndGet(), Math::max);
        peakGlobal.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        signal();
    }

    private void exit(FirmId firmId) {
        perFirm.get(firmId).decrementAndGet();
        inFlight.decrementAndGet();
        completionOrder.add(firmId);
        signal();
    }

    private void signal() {
        lock.lock();
        try {
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition, Duration timeout, String what)
            throws InterruptedException {
        long remaining = timeout.toNanos();
        lock.lock();
        try {
            while (!condition.getAsBoolean()) {
                if (remaining <= 0) {
                    throw new AssertionError("timed out waiting for " + what);
                }
                remaining = changed.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }
}
