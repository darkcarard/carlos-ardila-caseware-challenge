package com.caseware.fanout.application;

import static com.caseware.fanout.doubles.TestData.EU;
import static com.caseware.fanout.doubles.TestData.FIRM;
import static com.caseware.fanout.doubles.TestData.PRODUCT;
import static com.caseware.fanout.doubles.TestData.published;
import static com.caseware.fanout.doubles.TestData.v;

import com.caseware.fanout.doubles.InMemoryCheckpointStore;
import com.caseware.fanout.doubles.InMemoryRegistryRepository;
import com.caseware.fanout.doubles.InMemoryVersionGraphStore;
import com.caseware.fanout.doubles.MutableClock;
import com.caseware.fanout.doubles.RecordingMetrics;
import com.caseware.fanout.doubles.RecordingSummaryPublisher;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.FanoutChunk;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.LocaleCode;
import com.caseware.fanout.domain.model.PublishId;
import com.caseware.fanout.domain.model.Source;
import com.caseware.fanout.domain.model.TemplateVersion;
import com.caseware.fanout.domain.model.VersionGraph;
import com.caseware.fanout.domain.port.CircuitBreaker;
import com.caseware.fanout.domain.port.VersionGraphStore;
import com.caseware.fanout.domain.service.ChunkPlanner;
import com.caseware.fanout.infrastructure.resilience.ClockedCircuitBreaker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/** Wires a {@link FanoutChunkProcessor} to faithful in-memory doubles. One instance per test. */
final class FanoutFixture {

    static final Instant T0 = Instant.parse("2026-11-16T10:00:00Z");
    static final Set<LocaleCode> LOCALES = Set.of(LocaleCode.of("en-CA"), LocaleCode.of("fr-CA"));
    static final int CHECKPOINT_EVERY = 50;

    /** Epoch 2: v4 -> v5 -> v7, all published (design doc section 2, without v6). */
    static final VersionGraph GRAPH_E2 = VersionGraph.of(PRODUCT, EU, 2, List.of(
            published("v4", 4), published("v5", 5, "v4"), published("v7", 7, "v5")));

    /** Epoch 3: the same graph after v7 is withdrawn. */
    static final VersionGraph GRAPH_E3 = GRAPH_E2.withWithdrawn(v("v7"));

    final MutableClock clock = new MutableClock(T0);
    final InMemoryRegistryRepository registry = new InMemoryRegistryRepository();
    final InMemoryVersionGraphStore graphs = new InMemoryVersionGraphStore(GRAPH_E2);
    final InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
    final RecordingSummaryPublisher summaries = new RecordingSummaryPublisher();
    final RecordingMetrics metrics = new RecordingMetrics();
    final CircuitBreaker breaker = new ClockedCircuitBreaker(clock, 3, Duration.ofSeconds(30));

    FanoutChunkProcessor processor() {
        return processor(graphs);
    }

    /** Returns a processor bound to a specific graph view, e.g. a stale worker's snapshot. */
    FanoutChunkProcessor processor(VersionGraphStore graphView) {
        return new FanoutChunkProcessor(registry, graphView, checkpoints, summaries, breaker, metrics, clock,
                LOCALES, CHECKPOINT_EVERY);
    }

    static VersionGraphStore fixed(VersionGraph graph) {
        return (product, market) -> graph;
    }

    /** Seeds {@code n} unlocked rows on {@code base} at epoch 0, ids e000000..e(n-1). */
    List<EngagementId> seedRows(int n, String base) {
        List<EngagementId> ids = ids(n);
        ids.forEach(id -> registry.seed(row(FIRM, id, base, Set.of(), 1, false)));
        return ids;
    }

    List<FanoutChunk> plan(String publishId, long epoch, List<EngagementId> ids) {
        return ChunkPlanner.plan(PublishId.of(publishId), FIRM, PRODUCT, EU, epoch, ids);
    }

    FanoutChunk single(String publishId, long epoch, List<EngagementId> ids) {
        List<FanoutChunk> chunks = plan(publishId, epoch, ids);
        if (chunks.size() != 1) {
            throw new IllegalArgumentException("expected one chunk, got " + chunks.size());
        }
        return chunks.getFirst();
    }

    EngagementRecord row(EngagementId id) {
        return registry.row(FIRM, id).orElseThrow();
    }

    static List<EngagementId> ids(int n) {
        return IntStream.range(0, n).mapToObj(i -> EngagementId.of(String.format("e%06d", i))).toList();
    }

    static EngagementRecord row(FirmId firm, EngagementId id, String base, Set<TemplateVersion> declined,
                                long stateSeq, boolean locked) {
        return new EngagementRecord(firm, id, PRODUCT, EU, v(base), declined, stateSeq,
                Optional.empty(), Optional.empty(), 0, locked, Source.HOOK);
    }

    /** The engagement system's {@code accepted} hook: base advances, the row is re-derived at epoch 2. */
    static EngagementRecord accepted(EngagementRecord r, String version) {
        return new EngagementRecord(r.firmId(), r.engagementId(), r.productId(), r.market(), v(version),
                r.declinedVersions(), r.stateSeq() + 1, Optional.empty(), Optional.empty(), 2, false, r.source());
    }

    /** The engagement system's {@code declined} hook: the version joins declined_versions, base is unchanged. */
    static EngagementRecord declined(EngagementRecord r, String version) {
        Set<TemplateVersion> declined = new java.util.HashSet<>(r.declinedVersions());
        declined.add(v(version));
        return new EngagementRecord(r.firmId(), r.engagementId(), r.productId(), r.market(), r.baseVersion(),
                declined, r.stateSeq() + 1, Optional.empty(), Optional.empty(), r.graphEpoch(), false, r.source());
    }

    /** The {@code finalized} hook: the row is locked and leaves the pending index. */
    static EngagementRecord finalized(EngagementRecord r) {
        return new EngagementRecord(r.firmId(), r.engagementId(), r.productId(), r.market(), r.baseVersion(),
                r.declinedVersions(), r.stateSeq() + 1, Optional.empty(), Optional.empty(), r.graphEpoch(), true, r.source());
    }
}
