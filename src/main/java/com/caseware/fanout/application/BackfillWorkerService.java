package com.caseware.fanout.application;

import com.caseware.fanout.domain.error.NonRetryableDataException;
import com.caseware.fanout.domain.error.TransientInfrastructureException;
import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.LoadedEngagement;
import com.caseware.fanout.domain.model.Source;
import com.caseware.fanout.domain.model.TemplateVersion;
import com.caseware.fanout.domain.model.VersionGraph;
import com.caseware.fanout.domain.port.BackfillWrite;
import com.caseware.fanout.domain.port.CapacityLimiter;
import com.caseware.fanout.domain.port.CircuitBreaker;
import com.caseware.fanout.domain.port.DownstreamEngagementClient;
import com.caseware.fanout.domain.port.FanoutMetrics;
import com.caseware.fanout.domain.port.KillSwitch;
import com.caseware.fanout.domain.port.RegistryRepository;
import com.caseware.fanout.domain.port.TelemetryContext;
import com.caseware.fanout.domain.port.VersionGraphStore;
import com.caseware.fanout.domain.port.WriteOutcome;
import com.caseware.fanout.domain.service.PendingTargetResolver;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Seeds registry rows through the ~1 minute downstream load: the one-time backfill, and with
 * {@link Source#RECONCILED} also sampled reconciliation. Accept surges share the same
 * {@link CapacityLimiter} and downstream {@link CircuitBreaker}. This is the <em>only</em>
 * component that holds a {@link DownstreamEngagementClient}.
 *
 * <p><b>Dispatcher.</b> A single dispatcher thread hands items to virtual threads under these
 * rules:
 * <ul>
 *   <li><b>Caps.</b> No load starts without a {@link CapacityLimiter.Permit}: global ≤ 100, per
 *       firm ≤ 10.</li>
 *   <li><b>Fairness.</b> Round-robin across firms with work. Each turn tries the next firm whose
 *       per-firm cap has room, so a 40,000-file firm holds at most 10 slots and the other 90
 *       rotate among median firms.</li>
 *   <li><b>Kill switch.</b> It is checked before every slot acquisition and after every load
 *       completion. With all slots busy, a trip is therefore noticed within one load (≤ ~1 min).
 *       When tripped, nothing new starts, in-flight loads are interrupted, and every unfinished
 *       item is reported {@code Aborted}.</li>
 *   <li><b>Circuit breaker.</b> A 429 or 5xx counts as a failure. While the breaker is open,
 *       nothing new starts, and the remaining items are reported {@code Deferred} until
 *       {@code retryAfter}. The service never waits on wall-clock time itself, so re-delivery
 *       timing belongs to the queue.</li>
 * </ul>
 * The dispatcher blocks only on load completions, never on time, so the whole service is
 * deterministic under test.
 *
 * <p><b>Write.</b> {@code (attribute_not_exists(state_seq) OR state_seq < :loaded) AND
 * (attribute_not_exists(graph_epoch) OR graph_epoch <= :epoch)}. A slow load never overwrites a
 * newer hook. If the graph advanced during the load, the target is re-derived against the newer
 * graph and the write retried <em>without reloading</em>, because the load is still valid.
 */
public final class BackfillWorkerService {

    static final int MAX_EPOCH_RETRIES = 3;

    private final DownstreamEngagementClient downstream;
    private final RegistryRepository registry;
    private final VersionGraphStore graphs;
    private final CapacityLimiter limiter;
    private final CircuitBreaker breaker;
    private final KillSwitch killSwitch;
    private final RetryPolicy retryPolicy;
    private final FanoutMetrics metrics;
    private final Clock clock;
    private final Source source;

    public BackfillWorkerService(DownstreamEngagementClient downstream, RegistryRepository registry,
                                 VersionGraphStore graphs, CapacityLimiter limiter, CircuitBreaker breaker,
                                 KillSwitch killSwitch, RetryPolicy retryPolicy, FanoutMetrics metrics,
                                 Clock clock, Source source) {
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.breaker = Objects.requireNonNull(breaker, "breaker");
        this.killSwitch = Objects.requireNonNull(killSwitch, "killSwitch");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.source = Objects.requireNonNull(source, "source");
        if (source == Source.HOOK) {
            throw new IllegalArgumentException("backfill writes BACKFILL or RECONCILED provenance");
        }
    }

    /**
     * Processes {@code items} until each one has an outcome. The call blocks the dispatcher thread.
     *
     * @throws InterruptedException if the dispatcher itself is interrupted (in-flight loads are cancelled)
     */
    public BackfillReport run(Collection<BackfillItem> items) throws InterruptedException {
        FairQueue queue = new FairQueue(items);
        List<LoadOutcome> outcomes = new ArrayList<>(items.size());
        Map<Future<LoadOutcome>, InFlight> inFlight = new HashMap<>();
        boolean aborted = false;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutorCompletionService<LoadOutcome> completions = new ExecutorCompletionService<>(executor);
            try {
                while (true) {
                    if (!aborted && killSwitch.isTripped()) {
                        aborted = true;
                        metrics.increment("backfill.kill_switch.tripped", 1, TelemetryContext.EMPTY);
                        inFlight.keySet().forEach(future -> future.cancel(true));
                        queue.drain().forEach(item -> outcomes.add(new LoadOutcome.Aborted(item)));
                    }

                    if (!aborted && tryDispatch(queue, completions, inFlight, outcomes)) {
                        continue;
                    }
                    if (inFlight.isEmpty()) {
                        break; // the queue is drained, deferred or aborted; nothing will change any more
                    }
                    Future<LoadOutcome> done = completions.take();
                    outcomes.add(settle(done, inFlight.remove(done)));
                }
            } catch (InterruptedException e) {
                inFlight.keySet().forEach(future -> future.cancel(true));
                throw e;
            }
        }
        return new BackfillReport(outcomes);
    }

    /** Returns true if a load was started. */
    private boolean tryDispatch(FairQueue queue, ExecutorCompletionService<LoadOutcome> completions,
                                Map<Future<LoadOutcome>, InFlight> inFlight, List<LoadOutcome> outcomes) {
        Optional<FairQueue.Dispatch> next = queue.next(limiter);
        if (next.isEmpty()) {
            return false; // empty queue, or every firm with work is at its cap (or the global cap is full)
        }
        FairQueue.Dispatch dispatch = next.get();
        if (!breaker.tryAcquirePermission()) {
            dispatch.permit().close();
            queue.pushBack(dispatch.item());
            if (inFlight.isEmpty()) {
                // Open, and nothing in flight can close it during this run: defer everything.
                Duration wait = breaker.retryAfter();
                queue.drain().forEach(item -> outcomes.add(new LoadOutcome.Deferred(
                        item, item, clock.instant().plus(wait), "downstream circuit open")));
            }
            return false; // otherwise a probe is in flight; wait for its completion
        }
        BackfillItem item = dispatch.item();
        CapacityLimiter.Permit permit = dispatch.permit();
        Future<LoadOutcome> future = completions.submit(() -> load(item, permit));
        inFlight.put(future, new InFlight(item, permit));
        return true;
    }

    /** Turns a finished future into an outcome. It also covers loads cancelled before they started. */
    private LoadOutcome settle(Future<LoadOutcome> done, InFlight flight) throws InterruptedException {
        flight.permit().close(); // idempotent backstop: the task closes it too, unless it never ran
        try {
            return done.get();
        } catch (CancellationException e) {
            breaker.releasePermission();
            return new LoadOutcome.Aborted(flight.item());
        } catch (ExecutionException e) {
            breaker.releasePermission();
            return new LoadOutcome.Failed(flight.item(), "unexpected: " + e.getCause());
        }
    }

    /** Runs on a virtual thread while holding {@code permit} (per-firm and global slots) and one breaker permission. */
    private LoadOutcome load(BackfillItem item, CapacityLimiter.Permit permit) {
        TelemetryContext ctx = TelemetryContext.forFirm(item.firmId());
        try (permit) {
            LoadedEngagement loaded;
            try {
                loaded = downstream.loadEngagement(item.firmId(), item.engagementId());
            } catch (TransientInfrastructureException e) {
                breaker.recordFailure();
                metrics.increment("backfill.load.transient_failure", 1, ctx);
                return deferOrFail(item, e);
            } catch (NonRetryableDataException e) {
                breaker.recordSuccess(); // the downstream answered; the data is the problem
                return new LoadOutcome.Failed(item, e.getMessage());
            }
            breaker.recordSuccess();
            metrics.increment("backfill.load.completed", 1, ctx);
            // The slots are released here, before the registry write: the downstream is no longer in use.
            permit.close();
            return seed(item, loaded);
        } catch (InterruptedException e) {
            breaker.releasePermission();
            Thread.currentThread().interrupt();
            return new LoadOutcome.Aborted(item);
        }
    }

    private LoadOutcome seed(BackfillItem item, LoadedEngagement loaded) {
        try {
            for (int attempt = 0; attempt < MAX_EPOCH_RETRIES; attempt++) {
                VersionGraph graph = graphs.snapshot(loaded.productId(), loaded.market());
                EngagementRecord row = derive(loaded, graph);
                WriteOutcome outcome = registry.conditionalBackfillWrite(new BackfillWrite(row));
                switch (outcome) {
                    case WriteOutcome.Applied applied -> {
                        return new LoadOutcome.Seeded(item, applied.row());
                    }
                    case WriteOutcome.ConditionFailed failed -> {
                        EngagementRecord current = failed.current().orElseThrow(
                                () -> new IllegalStateException("backfill condition failed on an absent row"));
                        if (current.stateSeq() >= loaded.stateSeq()) {
                            return new LoadOutcome.SkippedStale(item, loaded.stateSeq(), current.stateSeq());
                        }
                        // Otherwise current.graphEpoch > graph.epoch: the graph moved during the load. Re-derive, don't reload.
                        metrics.increment("backfill.write.epoch_retry", 1, TelemetryContext.forFirm(item.firmId()));
                    }
                }
            }
            return deferOrFail(item, new IllegalStateException("graph kept advancing during the backfill write"));
        } catch (NonRetryableDataException e) {
            return new LoadOutcome.Failed(item, e.getMessage());
        } catch (TransientInfrastructureException e) {
            // A registry or graph-store blip after a successful load. The load is lost, so retry later.
            return deferOrFail(item, e);
        }
    }

    private EngagementRecord derive(LoadedEngagement loaded, VersionGraph graph) {
        EngagementRecord unevaluated = new EngagementRecord(loaded.firmId(), loaded.engagementId(),
                loaded.productId(), loaded.market(), loaded.baseVersion(), loaded.declinedVersions(),
                loaded.stateSeq(), Optional.empty(), Optional.empty(), graph.epoch(), loaded.locked(), source);
        Optional<TemplateVersion> target = PendingTargetResolver.resolve(unevaluated, graph);
        return unevaluated.withDerivedPending(target, graph.epoch(), clock.instant());
    }

    private LoadOutcome deferOrFail(BackfillItem item, Exception cause) {
        BackfillItem next = item.nextAttempt();
        if (retryPolicy.exhausted(next.attemptsMade())) {
            return new LoadOutcome.Failed(item, "retries exhausted: " + cause.getMessage());
        }
        Duration delay = retryPolicy.delayFor(item.attemptsMade(), item.key());
        return new LoadOutcome.Deferred(item, next, clock.instant().plus(delay), cause.getMessage());
    }

    private record InFlight(BackfillItem item, CapacityLimiter.Permit permit) {}

    /**
     * Per-firm FIFO queues served round-robin. Only the dispatcher thread touches it. A firm leaves
     * the ring when its queue empties, so a turn costs at most one {@code tryAcquire} per active firm.
     */
    static final class FairQueue {

        record Dispatch(BackfillItem item, CapacityLimiter.Permit permit) {}

        private final Map<FirmId, ArrayDeque<BackfillItem>> byFirm = new LinkedHashMap<>();
        private final ArrayDeque<FirmId> ring = new ArrayDeque<>();

        FairQueue(Collection<BackfillItem> items) {
            for (BackfillItem item : items) {
                byFirm.computeIfAbsent(item.firmId(), firm -> {
                    ring.addLast(firm);
                    return new ArrayDeque<>();
                }).addLast(item);
            }
        }

        Optional<Dispatch> next(CapacityLimiter limiter) {
            for (int tried = 0, firms = ring.size(); tried < firms; tried++) {
                FirmId firm = ring.pollFirst();
                Optional<CapacityLimiter.Permit> permit = limiter.tryAcquire(firm);
                if (permit.isEmpty()) {
                    ring.addLast(firm); // at its cap (or global full); give the next firm a turn
                    continue;
                }
                ArrayDeque<BackfillItem> pending = byFirm.get(firm);
                BackfillItem item = pending.pollFirst();
                if (pending.isEmpty()) {
                    byFirm.remove(firm);
                } else {
                    ring.addLast(firm);
                }
                return Optional.of(new Dispatch(item, permit.get()));
            }
            return Optional.empty();
        }

        /** Puts an item that could not start back at the head of its firm's queue. */
        void pushBack(BackfillItem item) {
            ArrayDeque<BackfillItem> pending = byFirm.get(item.firmId());
            if (pending == null) {
                pending = new ArrayDeque<>();
                byFirm.put(item.firmId(), pending);
                ring.addFirst(item.firmId());
            }
            pending.addFirst(item);
        }

        List<BackfillItem> drain() {
            List<BackfillItem> all = new ArrayList<>();
            byFirm.values().forEach(all::addAll);
            byFirm.clear();
            ring.clear();
            return all;
        }
    }
}
