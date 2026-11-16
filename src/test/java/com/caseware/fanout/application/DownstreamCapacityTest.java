package com.caseware.fanout.application;

import static com.caseware.fanout.application.BackfillFixture.concat;
import static com.caseware.fanout.application.BackfillFixture.items;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.caseware.fanout.doubles.FakeDownstreamClient;
import com.caseware.fanout.domain.model.FirmId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Capacity and fairness hard invariants ({@code testing-and-ai-audit.md} section 1.4): a burst of
 * 1,000 loads, made of one whale firm with 800 and ten small firms with 20 each.
 *
 * <p>Concurrency is measured by the fake downstream itself (an independent oracle), not by the
 * limiter under test. Every load parks until the test releases it, so the dispatcher genuinely
 * saturates. There are no sleeps, and {@code assertTimeoutPreemptively} turns a deadlock into a
 * failure instead of a hang.
 */
class DownstreamCapacityTest {

    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final Duration STEP = Duration.ofSeconds(5);

    private static final FirmId WHALE = FirmId.of("firm-whale");
    private static final List<FirmId> SMALL = IntStream.range(0, 10).mapToObj(i -> FirmId.of("firm-small-" + i)).toList();

    @Test
    void burstNeverExceedsTheCapsAndSmallFirmsAreNotStarvedByTheWhale() {
        assertTimeoutPreemptively(DEADLINE, () -> {
            BackfillFixture f = new BackfillFixture(FakeDownstreamClient.gated());
            List<List<BackfillItem>> groups = new ArrayList<>();
            groups.add(items(WHALE, 800)); // the whale is first in the input: the worst case for a FIFO
            SMALL.forEach(firm -> groups.add(items(firm, 20)));

            FutureTask<BackfillReport> run = new FutureTask<>(() -> f.service().run(concat(groups)));
            Thread.ofVirtual().name("backfill-dispatcher").start(run);

            // 1) Saturation: exactly 100 in flight, the whale at its 10, and every small firm already served.
            f.client.awaitInFlight(100, STEP);
            assertThat(f.client.inFlight()).isEqualTo(100);
            assertThat(f.client.inFlight(WHALE)).isEqualTo(10);
            assertThat(SMALL).allSatisfy(firm -> assertThat(f.client.inFlight(firm)).isPositive());

            // 2) Drain in lock-step: release the oldest load, wait for it to finish AND for the dispatcher to
            //    re-saturate, then release the next one. Every scheduling decision is made from a fully
            //    settled state, so the fairness bound below does not depend on CPU timing.
            Map<FirmId, Integer> totals = new HashMap<>();
            groups.forEach(g -> totals.merge(g.getFirst().firmId(), g.size(), Integer::sum));
            for (int released = 1; released <= 1_000; released++) {
                assertThat(f.client.releaseNext(STEP)).as("a parked load to release").isTrue();
                int done = released;
                f.client.await(() -> f.client.completed() >= done, STEP, done + " completions");
                int saturation = saturation(totals, f.client.completionOrder());
                f.client.await(() -> f.client.inFlight() >= saturation, STEP, "re-saturation at " + saturation);
            }
            BackfillReport report = run.get();

            // Hard invariants, from the independent oracle.
            assertThat(f.client.peakGlobal()).isEqualTo(100);
            assertThat(f.client.peakPerFirm()).isEqualTo(10);
            assertThat(f.metrics.violations()).isEmpty();

            // Every item seeded, and every slot given back.
            assertThat(report.count(LoadOutcome.Seeded.class)).isEqualTo(1_000);
            assertThat(f.client.calls()).isEqualTo(1_000);
            assertThat(f.limiter.globalInFlight()).isZero();

            // Fairness: all 200 small-firm loads finish within the first 300 completions, long before the whale.
            List<FirmId> order = f.client.completionOrder();
            int lastSmall = SMALL.stream().mapToInt(order::lastIndexOf).max().orElseThrow();
            assertThat(lastSmall).isLessThan(300);
            assertThat(order.lastIndexOf(WHALE)).isEqualTo(999);
        });
    }

    /** Returns the in-flight count a correct dispatcher reaches with the work left: Σ min(10, remaining per firm), capped at 100. */
    private static int saturation(Map<FirmId, Integer> totals, List<FirmId> completions) {
        Map<FirmId, Long> done = completions.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        int perFirmSum = totals.entrySet().stream()
                .mapToInt(e -> (int) Math.min(10, e.getValue() - done.getOrDefault(e.getKey(), 0L)))
                .sum();
        return Math.min(100, perFirmSum);
    }

    @Test
    void aSingleWhaleNeverExceedsItsPerFirmCapEvenWithTheGlobalPoolIdle() {
        assertTimeoutPreemptively(DEADLINE, () -> {
            BackfillFixture f = new BackfillFixture(FakeDownstreamClient.gated());
            FutureTask<BackfillReport> run = new FutureTask<>(() -> f.service().run(items(WHALE, 50)));
            Thread.ofVirtual().start(run);

            f.client.awaitInFlight(10, STEP);
            assertThat(f.limiter.globalInFlight()).isEqualTo(10); // 90 global slots idle, yet no 11th load
            while (!run.isDone()) {
                f.client.releaseNext(Duration.ofMillis(50));
            }

            assertThat(run.get().count(LoadOutcome.Seeded.class)).isEqualTo(50);
            assertThat(f.client.peakPerFirm()).isEqualTo(10);
            assertThat(f.client.peakGlobal()).isEqualTo(10);
        });
    }
}
