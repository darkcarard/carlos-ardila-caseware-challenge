package com.caseware.fanout.infrastructure.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.caseware.fanout.doubles.RecordingMetrics;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.port.CapacityLimiter.Permit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FairCapacityLimiterTest {

    private static final FirmId A = FirmId.of("firm-a");
    private static final FirmId B = FirmId.of("firm-b");

    private final RecordingMetrics metrics = new RecordingMetrics();

    @Test
    void perFirmCapIsEnforced() {
        FairCapacityLimiter limiter = new FairCapacityLimiter(100, 10, metrics);
        List<Permit> held = take(limiter, A, 10);

        assertThat(held).hasSize(10);
        assertThat(limiter.tryAcquire(A)).isEmpty();
        assertThat(limiter.tryAcquire(B)).isPresent();
    }

    @Test
    void globalCapIsEnforcedAcrossFirms() {
        FairCapacityLimiter limiter = new FairCapacityLimiter(3, 2, metrics);
        take(limiter, A, 2);
        take(limiter, B, 1);

        assertThat(limiter.tryAcquire(FirmId.of("firm-c"))).isEmpty();
        assertThat(limiter.globalInFlight()).isEqualTo(3);
    }

    @Test
    void failedGlobalAcquisitionGivesThePerFirmSlotBack() {
        FairCapacityLimiter limiter = new FairCapacityLimiter(1, 1, metrics);
        Permit a = limiter.tryAcquire(A).orElseThrow();

        assertThat(limiter.tryAcquire(B)).isEmpty(); // B got its firm slot, then the global attempt failed
        a.close();

        assertThat(limiter.tryAcquire(B)).isPresent(); // B's firm slot was not leaked
    }

    @Test
    void perFirmRejectionDoesNotConsumeAGlobalSlot() {
        FairCapacityLimiter limiter = new FairCapacityLimiter(2, 1, metrics);
        limiter.tryAcquire(A).orElseThrow();

        assertThat(limiter.tryAcquire(A)).isEmpty();
        assertThat(limiter.globalInFlight()).isEqualTo(1);
        assertThat(limiter.tryAcquire(B)).isPresent();
    }

    @Test
    void closeIsIdempotentAndReleasesBothLevels() {
        FairCapacityLimiter limiter = new FairCapacityLimiter(1, 1, metrics);
        Permit permit = limiter.tryAcquire(A).orElseThrow();

        permit.close();
        permit.close();

        assertThat(limiter.globalInFlight()).isZero();
        assertThat(limiter.inFlight(A)).isZero();
        assertThat(take(limiter, A, 2)).hasSize(1); // a double close must not mint an extra slot
    }

    @Test
    void interruptWhileWaitingForAGlobalSlotLeaksNothing() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            FairCapacityLimiter limiter = new FairCapacityLimiter(1, 1, metrics);
            Permit a = limiter.acquire(A);
            FutureTask<Permit> waiter = new FutureTask<>(() -> limiter.acquire(B));
            Thread thread = Thread.ofVirtual().start(waiter);
            while (thread.getState() != Thread.State.WAITING) {
                Thread.onSpinWait(); // B now holds its firm slot and is parked on the global semaphore
            }

            thread.interrupt();
            assertThatThrownBy(waiter::get).hasCauseInstanceOf(InterruptedException.class);
            a.close();

            assertThat(limiter.tryAcquire(B)).isPresent();
        });
    }

    @Test
    void contentionAcrossManyFirmsIsDeadlockFreeAndNeverExceedsTheCaps() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            FairCapacityLimiter limiter = new FairCapacityLimiter(8, 3, metrics);
            List<FirmId> firms = IntStream.range(0, 12).mapToObj(i -> FirmId.of("f" + i)).toList();
            AtomicInteger peakGlobal = new AtomicInteger();
            AtomicInteger completed = new AtomicInteger();

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> tasks = new ArrayList<>();
                for (int i = 0; i < 2_000; i++) {
                    FirmId firm = firms.get((i * 7) % firms.size());
                    tasks.add(executor.submit(() -> {
                        try (Permit permit = limiter.acquire(firm)) {
                            peakGlobal.accumulateAndGet(limiter.globalInFlight(), Math::max);
                            assertThat(limiter.inFlight(permit.firmId())).isLessThanOrEqualTo(3);
                            Thread.yield();
                        }
                        completed.incrementAndGet();
                        return null;
                    }));
                }
                for (Future<?> task : tasks) {
                    task.get();
                }
            }

            assertThat(completed).hasValue(2_000);
            assertThat(peakGlobal.get()).isLessThanOrEqualTo(8);
            assertThat(limiter.globalInFlight()).isZero();
            assertThat(metrics.violations()).isEmpty();
        });
    }

    @Test
    void blockingAcquireWaitsForAPerFirmSlotEvenWithGlobalSlotsIdle() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            FairCapacityLimiter limiter = new FairCapacityLimiter(10, 1, metrics);
            Permit first = limiter.acquire(A);
            FutureTask<Permit> second = new FutureTask<>(() -> limiter.acquire(A));
            Thread thread = Thread.ofVirtual().start(second);
            while (thread.getState() != Thread.State.WAITING) {
                Thread.onSpinWait();
            }

            assertThat(second.isDone()).isFalse();
            assertThat(limiter.inFlight(A)).isEqualTo(1);
            first.close();
            assertThat(second.get().firmId()).isEqualTo(A);
            assertThat(metrics.violations()).isEmpty();
        });
    }

    @Test
    void inFlightIsTrackedPerFirm() {
        FairCapacityLimiter limiter = new FairCapacityLimiter(10, 5, metrics);
        assertThat(limiter.inFlight(A)).isZero(); // never seen
        take(limiter, A, 2);
        assertThat(limiter.inFlight(A)).isEqualTo(2);
        assertThat(limiter.inFlight(B)).isZero();
    }

    @Test
    void rejectsInvalidCaps() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FairCapacityLimiter(0, 1, metrics));
        assertThatIllegalArgumentException().isThrownBy(() -> new FairCapacityLimiter(1, 0, metrics));
        assertThatIllegalArgumentException().isThrownBy(() -> new FairCapacityLimiter(5, 10, metrics));
        assertThat(new FairCapacityLimiter(1, 1, metrics).tryAcquire(A)).isPresent(); // smallest valid caps
    }

    private static List<Permit> take(FairCapacityLimiter limiter, FirmId firm, int n) {
        List<Permit> permits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Optional<Permit> permit = limiter.tryAcquire(firm);
            permit.ifPresent(permits::add);
        }
        return permits;
    }
}
