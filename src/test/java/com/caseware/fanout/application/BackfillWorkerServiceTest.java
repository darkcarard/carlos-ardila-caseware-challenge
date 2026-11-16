package com.caseware.fanout.application;

import static com.caseware.fanout.application.BackfillFixture.items;
import static com.caseware.fanout.application.FanoutFixture.GRAPH_E2;
import static com.caseware.fanout.application.FanoutFixture.GRAPH_E3;
import static com.caseware.fanout.application.FanoutFixture.T0;
import static com.caseware.fanout.doubles.FakeDownstreamClient.loaded;
import static com.caseware.fanout.doubles.TestData.EU;
import static com.caseware.fanout.doubles.TestData.FIRM;
import static com.caseware.fanout.doubles.TestData.PRODUCT;
import static com.caseware.fanout.doubles.TestData.v;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.caseware.fanout.doubles.FakeDownstreamClient;
import com.caseware.fanout.domain.error.DownstreamRateLimitException;
import com.caseware.fanout.domain.error.NonRetryableDataException;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.LoadedEngagement;
import com.caseware.fanout.domain.model.Source;
import com.caseware.fanout.domain.port.CircuitBreaker;
import com.caseware.fanout.domain.port.VersionGraphStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BackfillWorkerServiceTest {

    @Nested
    class Seeding {

        private final BackfillFixture f = new BackfillFixture(FakeDownstreamClient.instant());

        @Test
        void seedsUnseededRowsWithDerivedPendingAndBackfillProvenance() throws InterruptedException {
            BackfillReport report = f.service().run(items(FIRM, 3));

            assertThat(report.count(LoadOutcome.Seeded.class)).isEqualTo(3);
            assertThat(report.of(LoadOutcome.Seeded.class)).allSatisfy(s -> {
                EngagementRecord row = f.registry.row(FIRM, s.item().engagementId()).orElseThrow();
                assertThat(row.source()).isEqualTo(Source.BACKFILL);
                assertThat(row.stateSeq()).isEqualTo(1);
                assertThat(row.graphEpoch()).isEqualTo(2);
                assertThat(row.pendingTarget()).contains(v("v7"));
                assertThat(row.pendingSince()).contains(T0);
            });
            assertThat(f.metrics.count("backfill.load.completed")).isEqualTo(3);
        }

        @Test
        void finalizedEngagementIsSeededLockedAndNeverPending() throws InterruptedException {
            f.client.respondWith((firm, e) -> new LoadedEngagement(firm, e, PRODUCT, EU, v("v4"), Set.of(), 4, true));

            f.service().run(items(FIRM, 1));

            EngagementRecord row = f.registry.row(FIRM, items(FIRM, 1).getFirst().engagementId()).orElseThrow();
            assertThat(row.locked()).isTrue();
            assertThat(row.isPending()).isFalse();
        }

        @Test
        void slowLoadNeverOverwritesANewerHook() throws InterruptedException {
            BackfillItem item = items(FIRM, 1).getFirst();
            EngagementRecord hookRow = FanoutFixture.row(FIRM, item.engagementId(), "v7", Set.of(), 5, false);
            f.registry.seed(hookRow);
            f.client.respondWith((firm, e) -> loaded(firm, e, "v4", 3)); // loaded before the user accepted v7

            BackfillReport report = f.service().run(List.of(item));

            assertThat(report.outcomes()).singleElement()
                    .isEqualTo(new LoadOutcome.SkippedStale(item, 3, 5));
            assertThat(f.registry.row(FIRM, item.engagementId())).contains(hookRow);
        }

        @Test
        void equalStateSeqDefersToTheHookToo() throws InterruptedException {
            BackfillItem item = items(FIRM, 1).getFirst();
            f.registry.seed(FanoutFixture.row(FIRM, item.engagementId(), "v4", Set.of(), 1, false));

            BackfillReport report = f.service().run(List.of(item));

            assertThat(report.count(LoadOutcome.SkippedStale.class)).isEqualTo(1);
        }

        @Test
        void graphAdvancingDuringTheLoadIsReDerivedWithoutReloading() throws InterruptedException {
            // Fan-out already evaluated this row at epoch 3 (v7 withdrawn -> pending v5).
            BackfillItem item = items(FIRM, 1).getFirst();
            EngagementRecord fannedOut = FanoutFixture.row(FIRM, item.engagementId(), "v4", Set.of(), 1, false)
                    .withDerivedPending(Optional.of(v("v5")), 3, T0);
            f.registry.seed(fannedOut);
            f.client.respondWith((firm, e) -> loaded(firm, e, "v4", 2));
            // This worker's first snapshot is still epoch 2 (projection lag); the next one is epoch 3.
            AtomicInteger snapshots = new AtomicInteger();
            VersionGraphStore lagging = (p, m) -> snapshots.getAndIncrement() == 0 ? GRAPH_E2 : GRAPH_E3;

            BackfillReport report = f.service(lagging).run(List.of(item));

            assertThat(report.count(LoadOutcome.Seeded.class)).isEqualTo(1);
            assertThat(f.client.calls()).isEqualTo(1); // no second ~1 minute load
            EngagementRecord row = f.registry.row(FIRM, item.engagementId()).orElseThrow();
            assertThat(row.stateSeq()).isEqualTo(2);
            assertThat(row.graphEpoch()).isEqualTo(3);
            assertThat(row.pendingTarget()).contains(v("v5")); // never the withdrawn v7
            assertThat(f.metrics.count("backfill.write.epoch_retry")).isEqualTo(1);
        }

        @Test
        void graphThatNeverStopsAdvancingIsDeferredNotSpun() throws InterruptedException {
            BackfillItem item = items(FIRM, 1).getFirst();
            f.registry.seed(FanoutFixture.row(FIRM, item.engagementId(), "v4", Set.of(), 1, false)
                    .withDerivedPending(Optional.of(v("v5")), 9, T0));
            f.client.respondWith((firm, e) -> loaded(firm, e, "v4", 2));
            VersionGraphStore alwaysBehind = (p, m) -> GRAPH_E2;

            BackfillReport report = f.service(alwaysBehind).run(List.of(item));

            assertThat(report.outcomes()).singleElement().isInstanceOf(LoadOutcome.Deferred.class);
            assertThat(f.registry.writeAttempts()).isEqualTo(3); // bounded re-derivation, then give up
            assertThat(f.client.calls()).isEqualTo(1);
        }

        @Test
        void registryThrottlingAfterASuccessfulLoadDefersTheItem() throws InterruptedException {
            f.registry.throttleAllWrites(true);
            BackfillItem item = items(FIRM, 1).getFirst();

            BackfillReport report = f.service().run(List.of(item));

            assertThat(report.outcomes()).singleElement().isInstanceOfSatisfying(LoadOutcome.Deferred.class,
                    d -> assertThat(d.next().attemptsMade()).isEqualTo(1)); // the lost load counts as an attempt
            assertThat(f.breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED); // the downstream was healthy
        }

        @Test
        void unknownBaseVersionFailsTheItemWithoutTrippingTheBreaker() throws InterruptedException {
            f.client.respondWith((firm, e) -> loaded(firm, e, "v3-unknown", 1));

            BackfillReport report = f.service().run(items(FIRM, 2));

            assertThat(report.count(LoadOutcome.Failed.class)).isEqualTo(2);
            assertThat(f.breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        void rejectsHookProvenance() {
            assertThatIllegalArgumentException().isThrownBy(() -> new BackfillWorkerService(f.client, f.registry, f.graphs,
                    f.limiter, f.breaker, () -> false, BackfillFixture.RETRY, f.metrics, f.clock, Source.HOOK));
        }
    }

    @Nested
    class DownstreamFailures {

        /** One slot, so loads run strictly one after another and the breaker trip point is exact. */
        private final BackfillFixture f = new BackfillFixture(FakeDownstreamClient.instant(), 1, 1);

        @Test
        void rateLimitOpensTheBreakerAndNoFurtherLoadIsStarted() throws InterruptedException {
            f.client.respondWith((firm, e) -> {
                throw new DownstreamRateLimitException("429 Too Many Requests");
            });
            List<BackfillItem> items = items(FIRM, 10);

            BackfillReport report = f.service().run(items);

            assertThat(f.breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(f.client.calls()).isEqualTo(3); // threshold, then nothing more reaches the owner team
            assertThat(f.metrics.count("backfill.load.transient_failure")).isEqualTo(3);
            List<LoadOutcome.Deferred> deferred = report.of(LoadOutcome.Deferred.class);
            assertThat(deferred).hasSize(10);
            assertThat(deferred.stream().filter(d -> d.reason().contains("429")))
                    .hasSize(3)
                    .allSatisfy(d -> {
                        assertThat(d.next().attemptsMade()).isEqualTo(1);
                        assertThat(d.retryAt()).isBetween(T0.plusSeconds(5), T0.plusSeconds(10));
                    })
                    .extracting(LoadOutcome.Deferred::retryAt)
                    .doesNotHaveDuplicates(); // per-item jitter: retries do not come back as a herd
            assertThat(deferred.stream().filter(d -> d.reason().contains("circuit open")))
                    .hasSize(7)
                    .allSatisfy(d -> {
                        assertThat(d.next()).isEqualTo(d.item()); // not started: no attempt consumed
                        assertThat(d.retryAt()).isEqualTo(T0.plusSeconds(30));
                    });
        }

        @Test
        void afterTheCoolDownTheProbeSucceedsAndDeferredItemsSeed() throws InterruptedException {
            f.client.respondWith((firm, e) -> {
                throw new DownstreamRateLimitException("429");
            });
            BackfillReport first = f.service().run(items(FIRM, 10));
            List<BackfillItem> redeliver = first.of(LoadOutcome.Deferred.class).stream().map(LoadOutcome.Deferred::next).toList();

            f.client.respondWith((firm, e) -> loaded(firm, e, "v4", 1));
            f.clock.advance(Duration.ofSeconds(30));
            BackfillReport second = f.service().run(redeliver);

            assertThat(second.count(LoadOutcome.Seeded.class)).isEqualTo(10);
            assertThat(f.breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        void aLoneItemMetByAnOpenBreakerIsDeferredWithoutConsumingAnAttempt() throws InterruptedException {
            trip(f.breaker);
            BackfillItem item = items(FIRM, 1).getFirst();

            BackfillReport report = f.service().run(List.of(item));

            assertThat(f.client.calls()).isZero();
            assertThat(report.outcomes()).singleElement()
                    .isEqualTo(new LoadOutcome.Deferred(item, item, T0.plusSeconds(30), "downstream circuit open"));
        }

        @Test
        void aBugInTheClientFailsTheItemWithoutKillingTheRun() throws InterruptedException {
            f.client.respondWith((firm, e) -> {
                throw new IllegalStateException("client bug");
            });

            BackfillReport report = f.service().run(items(FIRM, 2));

            assertThat(report.of(LoadOutcome.Failed.class)).hasSize(2)
                    .allSatisfy(failed -> assertThat(failed.reason()).contains("unexpected", "client bug"));
            assertThat(f.limiter.globalInFlight()).isZero();
        }

        @Test
        void exhaustedRetriesBecomeAPermanentFailure() throws InterruptedException {
            f.client.respondWith((firm, e) -> {
                throw new DownstreamRateLimitException("429");
            });
            BackfillItem lastChance = new BackfillItem(FIRM, EngagementId.of("e-tired"), BackfillFixture.RETRY.maxAttempts() - 1);

            BackfillReport report = f.service().run(List.of(lastChance));

            assertThat(report.outcomes()).singleElement().isInstanceOfSatisfying(LoadOutcome.Failed.class,
                    failed -> assertThat(failed.reason()).contains("retries exhausted"));
        }

        @Test
        void corruptEngagementFailsWithoutTrippingTheBreaker() throws InterruptedException {
            f.client.respondWith((firm, e) -> {
                throw new NonRetryableDataException("engagement archive corrupt");
            });

            BackfillReport report = f.service().run(items(FIRM, 5));

            assertThat(report.count(LoadOutcome.Failed.class)).isEqualTo(5);
            assertThat(f.breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(f.client.calls()).isEqualTo(5);
        }
    }

    @Nested
    class KillSwitch {

        @Test
        void trippedBeforeStartLoadsNothing() throws InterruptedException {
            BackfillFixture f = new BackfillFixture(FakeDownstreamClient.instant());
            f.killSwitch.set(true);

            BackfillReport report = f.service().run(items(FIRM, 25));

            assertThat(f.client.calls()).isZero();
            assertThat(report.count(LoadOutcome.Aborted.class)).isEqualTo(25);
        }

        @Test
        void trippedMidRunInterruptsInFlightLoadsAndStartsNoMore() {
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                BackfillFixture f = new BackfillFixture(FakeDownstreamClient.gated(), 3, 3);
                FutureTask<BackfillReport> run = new FutureTask<>(() -> f.service().run(items(FIRM, 20)));
                Thread dispatcher = Thread.ofVirtual().start(run);

                f.client.awaitInFlight(3, Duration.ofSeconds(5));
                // Its only blocking point is the wait for a completion, so WAITING means the dispatcher has
                // already passed its kill-switch check for this round and is parked.
                while (dispatcher.getState() != Thread.State.WAITING) {
                    Thread.onSpinWait();
                }
                f.killSwitch.set(true);
                f.client.releaseNext(Duration.ofSeconds(5)); // the dispatcher notices the switch on this completion

                BackfillReport report = run.get();

                assertThat(f.client.calls()).isEqualTo(3);
                assertThat(report.count(LoadOutcome.Seeded.class)).isEqualTo(1);
                assertThat(report.count(LoadOutcome.Aborted.class)).isEqualTo(19);
                assertThat(report.outcomes()).hasSize(20);
                assertThat(f.limiter.globalInFlight()).isZero(); // no slot leaked by the interrupted loads
                assertThat(f.metrics.count("backfill.kill_switch.tripped")).isEqualTo(1);
            });
        }

        @Test
        void interruptingTheDispatcherCancelsLoadsAndGivesBackSlotsAndTheProbe() {
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                BackfillFixture f = new BackfillFixture(FakeDownstreamClient.gated());
                trip(f.breaker);
                f.clock.advance(Duration.ofSeconds(30)); // the next load is the HALF_OPEN probe
                FutureTask<BackfillReport> run = new FutureTask<>(() -> f.service().run(items(FIRM, 3)));
                Thread dispatcher = Thread.ofVirtual().start(run);
                f.client.awaitInFlight(1, Duration.ofSeconds(5));

                dispatcher.interrupt();

                assertThatThrownBy(run::get).hasCauseInstanceOf(InterruptedException.class);
                assertThat(f.client.inFlight()).isZero();
                assertThat(f.limiter.globalInFlight()).isZero();
                assertThat(f.breaker.tryAcquirePermission()).as("a new probe may start").isTrue();
            });
        }
    }

    @Test
    void whileTheProbeIsInFlightTheDispatcherWaitsForItInsteadOfDeferringOrSpinning() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            BackfillFixture f = new BackfillFixture(FakeDownstreamClient.gated());
            trip(f.breaker);
            f.clock.advance(Duration.ofSeconds(30));
            WaitCheckingBreaker breaker = new WaitCheckingBreaker(f.breaker);
            BackfillWorkerService service = new BackfillWorkerService(f.client, f.registry, f.graphs, f.limiter,
                    breaker, () -> false, BackfillFixture.RETRY, f.metrics, f.clock, Source.BACKFILL);
            // Two firms with one item each: the denied item is its firm's last, so pushing it back must
            // re-register the firm in the round-robin ring or it would be silently dropped.
            List<BackfillItem> work = BackfillFixture.concat(List.of(items(FIRM, 1), items(FirmId.of("firm-other"), 1)));
            FutureTask<BackfillReport> run = new FutureTask<>(() -> service.run(work));
            Thread.ofVirtual().start(run);

            f.client.awaitInFlight(1, Duration.ofSeconds(5));
            assertThat(f.client.calls()).isEqualTo(1); // only the probe; the other item waits for its verdict
            while (!run.isDone()) {
                f.client.releaseNext(Duration.ofMillis(50));
            }

            assertThat(run.get().count(LoadOutcome.Seeded.class)).isEqualTo(2);
            assertThat(breaker.askedAgainWithoutAVerdict()).isFalse();
        });
    }

    private static void trip(CircuitBreaker breaker) {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
    }

    /**
     * Flags a dispatcher that asks the breaker again after a denial before any verdict (success,
     * failure or released permission) reached it: the answer cannot have changed, so asking again
     * means busy-spinning. Every load records its verdict before its future completes, so a
     * dispatcher that waits for a completion always sees a new verdict first.
     */
    private static final class WaitCheckingBreaker implements CircuitBreaker {
        private final CircuitBreaker delegate;
        private int verdicts;
        private int verdictsAtDenial = -1;
        private boolean askedAgainWithoutAVerdict;

        WaitCheckingBreaker(CircuitBreaker delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized boolean tryAcquirePermission() {
            if (verdictsAtDenial == verdicts) {
                askedAgainWithoutAVerdict = true;
            }
            boolean granted = delegate.tryAcquirePermission();
            verdictsAtDenial = granted ? -1 : verdicts;
            return granted;
        }

        @Override
        public synchronized void recordSuccess() {
            verdicts++;
            delegate.recordSuccess();
        }

        @Override
        public synchronized void recordFailure() {
            verdicts++;
            delegate.recordFailure();
        }

        @Override
        public synchronized void releasePermission() {
            verdicts++;
            delegate.releasePermission();
        }

        synchronized boolean askedAgainWithoutAVerdict() {
            return askedAgainWithoutAVerdict;
        }

        @Override
        public State state() {
            return delegate.state();
        }

        @Override
        public Duration retryAfter() {
            return delegate.retryAfter();
        }
    }

    @Test
    void reconciliationUsesItsOwnProvenance() throws InterruptedException {
        BackfillFixture f = new BackfillFixture(FakeDownstreamClient.instant());
        BackfillWorkerService reconciler = new BackfillWorkerService(f.client, f.registry, f.graphs, f.limiter,
                f.breaker, () -> false, BackfillFixture.RETRY, f.metrics, f.clock, Source.RECONCILED);

        reconciler.run(items(FIRM, 1));

        assertThat(f.registry.row(FIRM, items(FIRM, 1).getFirst().engagementId()).orElseThrow().source())
                .isEqualTo(Source.RECONCILED);
    }
}
