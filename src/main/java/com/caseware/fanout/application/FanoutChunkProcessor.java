package com.caseware.fanout.application;

import com.caseware.fanout.domain.error.NonRetryableDataException;
import com.caseware.fanout.domain.error.OptimisticLockConflictException;
import com.caseware.fanout.domain.error.TransientInfrastructureException;
import com.caseware.fanout.domain.model.ChunkCursor;
import com.caseware.fanout.domain.model.ChunkKey;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.FanoutChunk;
import com.caseware.fanout.domain.model.LocaleCode;
import com.caseware.fanout.domain.model.SummaryPair;
import com.caseware.fanout.domain.model.TemplateVersion;
import com.caseware.fanout.domain.model.VersionGraph;
import com.caseware.fanout.domain.port.CheckpointStore;
import com.caseware.fanout.domain.port.CircuitBreaker;
import com.caseware.fanout.domain.port.FanoutMetrics;
import com.caseware.fanout.domain.port.FanoutWrite;
import com.caseware.fanout.domain.port.RegistryRepository;
import com.caseware.fanout.domain.port.SummaryWarmupPublisher;
import com.caseware.fanout.domain.port.TelemetryContext;
import com.caseware.fanout.domain.port.VersionGraphStore;
import com.caseware.fanout.domain.port.WriteOutcome;
import com.caseware.fanout.domain.service.PendingTargetResolver;
import java.time.Clock;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Processes one SQS fan-out chunk (500 rows or fewer of one firm) <strong>against the regional
 * registry only</strong>.
 *
 * <p>The constructor deliberately takes no
 * {@link com.caseware.fanout.domain.port.DownstreamEngagementClient}: a publish costs 0
 * downstream loads by construction, not by discipline.
 *
 * <p>Correctness rests on three mechanisms:
 * <ul>
 *   <li><b>Conditional writes.</b> {@code state_seq = :seen AND graph_epoch <= :epoch}. On
 *       failure the row is re-read: a newer epoch means "superseded, ack", a changed
 *       {@code state_seq} means "user acted, recompute" (bounded to {@value #MAX_WRITE_ATTEMPTS}
 *       attempts).</li>
 *   <li><b>Epoch of derivation.</b> The epoch written is the epoch of the graph snapshot the
 *       target was derived from, never the chunk's nominal epoch. A worker that loaded a newer
 *       graph must not stamp it with an older epoch, and vice versa.</li>
 *   <li><b>Checkpoints.</b> Every {@code checkpointEvery} rows, the summary pairs found so far
 *       are published first, then the cursor advances. A crash resumes at the last checkpoint.
 *       Rows between the checkpoint and the crash are re-evaluated, which is harmless because
 *       derivation is pure and writes are conditional.</li>
 * </ul>
 *
 * <p>It is not thread-safe per chunk. SQS delivers each message to one consumer at a time;
 * concurrent duplicates are still safe because every write is conditional.
 */
public final class FanoutChunkProcessor {

    /** Conflict-driven re-read and recompute attempts per row before yielding the chunk. */
    public static final int MAX_WRITE_ATTEMPTS = 3;

    private final RegistryRepository registry;
    private final VersionGraphStore graphs;
    private final CheckpointStore checkpoints;
    private final SummaryWarmupPublisher summaries;
    private final CircuitBreaker breaker;
    private final FanoutMetrics metrics;
    private final Clock clock;
    private final Set<LocaleCode> regionLocales;
    private final int checkpointEvery;

    public FanoutChunkProcessor(RegistryRepository registry, VersionGraphStore graphs, CheckpointStore checkpoints,
                                SummaryWarmupPublisher summaries, CircuitBreaker breaker, FanoutMetrics metrics,
                                Clock clock, Set<LocaleCode> regionLocales, int checkpointEvery) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.breaker = Objects.requireNonNull(breaker, "breaker");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.regionLocales = Set.copyOf(Objects.requireNonNull(regionLocales, "regionLocales"));
        if (this.regionLocales.isEmpty()) {
            throw new IllegalArgumentException("a region needs at least one summary locale");
        }
        if (checkpointEvery < 1) {
            throw new IllegalArgumentException("checkpointEvery must be >= 1");
        }
        this.checkpointEvery = checkpointEvery;
    }

    public FanoutResult process(FanoutChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        ChunkKey key = chunk.key();
        TelemetryContext ctx = TelemetryContext.forChunk(chunk);

        if (!breaker.tryAcquirePermission()) {
            metrics.increment("fanout.chunk.throttled", 1, ctx);
            return new FanoutResult.Throttled(key, breaker.retryAfter());
        }

        ChunkCursor cursor = ChunkCursor.start(key);
        try {
            cursor = checkpoints.load(key).orElse(cursor);
            if (cursor.completed()) {
                breaker.recordSuccess();
                metrics.increment("fanout.chunk.duplicate", 1, ctx);
                return new FanoutResult.Completed(key, ChunkStats.ZERO);
            }

            VersionGraph graph = graphs.snapshot(chunk.productId(), chunk.market());
            if (graph.epoch() < chunk.graphEpoch()) {
                breaker.recordSuccess();
                metrics.increment("fanout.chunk.graph_lagging", 1, ctx);
                return new FanoutResult.PartialRetry(cursor, "graph projection at epoch " + graph.epoch()
                        + " is behind chunk epoch " + chunk.graphEpoch());
            }

            Tally tally = new Tally();
            Set<SummaryPair> pendingPairs = new HashSet<>();
            int start = cursor.nextRowOffset();
            for (int i = start; i < chunk.size(); i++) {
                evaluateRow(chunk, chunk.engagementIds().get(i), graph, tally, pendingPairs, ctx);
                int done = i + 1;
                if (done < chunk.size() && (done - start) % checkpointEvery == 0) {
                    flush(pendingPairs);
                    cursor = checkpoints.advance(cursor.advanceTo(done));
                }
            }
            flush(pendingPairs);
            cursor = checkpoints.advance(cursor.complete(chunk.size()));

            breaker.recordSuccess();
            ChunkStats stats = tally.toStats();
            report(stats, ctx);
            return new FanoutResult.Completed(key, stats);

        } catch (TransientInfrastructureException e) {
            breaker.recordFailure();
            metrics.increment("fanout.chunk.transient_failure", 1, ctx);
            return new FanoutResult.PartialRetry(cursor, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (OptimisticLockConflictException e) {
            breaker.recordSuccess(); // the registry is healthy; a user is busy with one row
            metrics.increment("fanout.chunk.conflict_yield", 1, ctx);
            return new FanoutResult.PartialRetry(cursor, e.getMessage());
        } catch (NonRetryableDataException e) {
            breaker.recordSuccess(); // data errors never trip the breaker
            metrics.increment("fanout.chunk.poison", 1, ctx);
            return new FanoutResult.PoisonMessage(key, e.getMessage());
        }
    }

    private void evaluateRow(FanoutChunk chunk, EngagementId engagementId, VersionGraph graph, Tally tally,
                             Set<SummaryPair> pairs, TelemetryContext ctx) {
        tally.evaluated++;
        Optional<EngagementRecord> seen = registry.get(chunk.firmId(), engagementId);

        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            if (seen.isEmpty()) {
                tally.missing++; // deleted or not yet seeded; its hook/backfill derives on write
                return;
            }
            EngagementRecord row = seen.get();
            if (row.graphEpoch() > graph.epoch()) {
                tally.superseded++;
                return;
            }
            if (row.locked()) {
                tally.unchanged++; // finalized rows are never touched, not even to bump the epoch
                return;
            }

            Optional<TemplateVersion> target;
            try {
                target = PendingTargetResolver.resolve(row, graph);
            } catch (NonRetryableDataException e) {
                tally.unresolvable++;
                metrics.increment("fanout.row.unresolvable", 1, ctx);
                return;
            }

            if (target.equals(row.pendingTarget()) && row.graphEpoch() == graph.epoch()) {
                tally.unchanged++;
                collectPair(chunk, row.baseVersion(), target, pairs);
                return;
            }

            FanoutWrite write = FanoutWrite.derive(row, target, graph.epoch(), clock.instant());
            WriteOutcome outcome = registry.conditionalFanoutWrite(write);
            switch (outcome) {
                case WriteOutcome.Applied applied -> {
                    tally.written++;
                    collectPair(chunk, applied.row().baseVersion(), target, pairs);
                    return;
                }
                case WriteOutcome.ConditionFailed failed -> {
                    // Re-read happened atomically with the failure (ALL_OLD); classify and maybe retry.
                    tally.conflictRetries++;
                    seen = failed.current();
                }
            }
        }
        throw new OptimisticLockConflictException("row " + engagementId + " of chunk " + chunk.key().chunkId()
                + " kept changing after " + MAX_WRITE_ATTEMPTS + " attempts");
    }

    private void collectPair(FanoutChunk chunk, TemplateVersion base, Optional<TemplateVersion> target,
                             Set<SummaryPair> pairs) {
        target.ifPresent(to -> regionLocales.forEach(locale ->
                pairs.add(new SummaryPair(chunk.productId(), chunk.market(), base, to, locale))));
    }

    private void flush(Set<SummaryPair> pairs) {
        if (!pairs.isEmpty()) {
            summaries.publish(Set.copyOf(pairs));
            pairs.clear();
        }
    }

    private void report(ChunkStats s, TelemetryContext ctx) {
        metrics.increment("fanout.chunk.completed", 1, ctx);
        metrics.increment("fanout.rows.evaluated", s.evaluated(), ctx);
        metrics.increment("fanout.rows.written", s.written(), ctx);
        metrics.increment("fanout.rows.superseded", s.superseded(), ctx);
        metrics.increment("fanout.rows.conflict_retries", s.conflictRetries(), ctx);
    }

    /** Mutable counters local to one {@link #process} call. */
    private static final class Tally {
        int evaluated;
        int written;
        int unchanged;
        int superseded;
        int missing;
        int unresolvable;
        int conflictRetries;

        ChunkStats toStats() {
            return new ChunkStats(evaluated, written, unchanged, superseded, missing, unresolvable, conflictRetries);
        }
    }
}
