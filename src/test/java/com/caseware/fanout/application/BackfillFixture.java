package com.caseware.fanout.application;

import com.caseware.fanout.doubles.FakeDownstreamClient;
import com.caseware.fanout.doubles.InMemoryRegistryRepository;
import com.caseware.fanout.doubles.InMemoryVersionGraphStore;
import com.caseware.fanout.doubles.MutableClock;
import com.caseware.fanout.doubles.RecordingMetrics;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.Source;
import com.caseware.fanout.domain.port.CircuitBreaker;
import com.caseware.fanout.domain.port.VersionGraphStore;
import com.caseware.fanout.infrastructure.resilience.ClockedCircuitBreaker;
import com.caseware.fanout.infrastructure.resilience.FairCapacityLimiter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

/** Wires a {@link BackfillWorkerService} to in-memory doubles. One instance per test. */
final class BackfillFixture {

    static final RetryPolicy RETRY = new RetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(5), 4);

    final MutableClock clock = new MutableClock(FanoutFixture.T0);
    final InMemoryRegistryRepository registry = new InMemoryRegistryRepository();
    final InMemoryVersionGraphStore graphs = new InMemoryVersionGraphStore(FanoutFixture.GRAPH_E2);
    final RecordingMetrics metrics = new RecordingMetrics();
    final CircuitBreaker breaker = new ClockedCircuitBreaker(clock, 3, Duration.ofSeconds(30));
    final AtomicBoolean killSwitch = new AtomicBoolean();
    final FakeDownstreamClient client;
    final FairCapacityLimiter limiter;

    BackfillFixture(FakeDownstreamClient client, int maxGlobal, int maxPerFirm) {
        this.client = client;
        this.limiter = new FairCapacityLimiter(maxGlobal, maxPerFirm, metrics);
    }

    BackfillFixture(FakeDownstreamClient client) {
        this(client, 100, 10);
    }

    BackfillWorkerService service() {
        return service(graphs);
    }

    BackfillWorkerService service(VersionGraphStore graphView) {
        return new BackfillWorkerService(client, registry, graphView, limiter, breaker, killSwitch::get, RETRY,
                metrics, clock, Source.BACKFILL);
    }

    static List<BackfillItem> items(FirmId firm, int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> BackfillItem.first(firm, EngagementId.of(firm.value() + "-e" + i)))
                .toList();
    }

    static List<BackfillItem> concat(List<List<BackfillItem>> groups) {
        List<BackfillItem> all = new ArrayList<>();
        groups.forEach(all::addAll);
        return all;
    }
}
