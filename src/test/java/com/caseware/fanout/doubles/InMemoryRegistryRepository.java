package com.caseware.fanout.doubles;

import com.caseware.fanout.domain.error.RegistryThrottledException;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.port.BackfillWrite;
import com.caseware.fanout.domain.port.FanoutWrite;
import com.caseware.fanout.domain.port.RegistryRepository;
import com.caseware.fanout.domain.port.WriteOutcome;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Faithful in-memory DynamoDB registry. Each conditional write evaluates its condition and applies
 * its effect atomically per item ({@link ConcurrentHashMap#compute}), exactly like a single-item
 * {@code UpdateItem} with a {@code ConditionExpression} and
 * {@code ReturnValuesOnConditionCheckFailure=ALL_OLD}.
 *
 * <p>Fault and race injection:
 * <ul>
 *   <li>{@link #throttleOnWrite(int)}: the n-th write attempt (1-based) throws
 *       {@link RegistryThrottledException}, simulating a worker crash mid-chunk.</li>
 *   <li>{@link #throttleAllWrites(boolean)}: a sustained throttling storm.</li>
 *   <li>{@link #beforeFanoutWrite(Consumer)}: runs just before the conditional check, simulating
 *       a user action or a competing worker landing between the read and the write.</li>
 * </ul>
 */
public final class InMemoryRegistryRepository implements RegistryRepository {

    private record Key(FirmId firmId, EngagementId engagementId) {}

    private final ConcurrentHashMap<Key, EngagementRecord> rows = new ConcurrentHashMap<>();
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger writeAttempts = new AtomicInteger();
    private final AtomicInteger writesApplied = new AtomicInteger();
    private final AtomicInteger throttleOnWriteNumber = new AtomicInteger(-1);
    private volatile boolean throttleAll;
    private final AtomicReference<Consumer<FanoutWrite>> beforeFanoutWrite = new AtomicReference<>(w -> {});

    // ---- test-side setup and engagement-system hooks ----

    /** Seeds a row directly, as if written by an earlier hook. Test setup only. */
    public void seed(EngagementRecord row) {
        rows.put(key(row.firmId(), row.engagementId()), row);
    }

    /**
     * Simulates an engagement-system hook (accepted, declined, finalized). It applies the change
     * and increments {@code state_seq}, as the outbox would.
     */
    public void applyHook(FirmId firmId, EngagementId engagementId, UnaryOperator<EngagementRecord> change) {
        rows.computeIfPresent(key(firmId, engagementId), (k, current) -> {
            EngagementRecord changed = change.apply(current);
            if (changed.stateSeq() <= current.stateSeq()) {
                throw new IllegalStateException("hooks must advance state_seq");
            }
            return changed;
        });
    }

    public void delete(FirmId firmId, EngagementId engagementId) {
        rows.remove(key(firmId, engagementId));
    }

    public void throttleOnWrite(int attemptNumber) {
        throttleOnWriteNumber.set(writeAttempts.get() + attemptNumber);
    }

    public void throttleAllWrites(boolean enabled) {
        throttleAll = enabled;
    }

    public void beforeFanoutWrite(Consumer<FanoutWrite> hook) {
        beforeFanoutWrite.set(hook);
    }

    // ---- port ----

    @Override
    public Optional<EngagementRecord> get(FirmId firmId, EngagementId engagementId) {
        reads.incrementAndGet();
        return Optional.ofNullable(rows.get(key(firmId, engagementId)));
    }

    @Override
    public WriteOutcome conditionalFanoutWrite(FanoutWrite write) {
        maybeThrottle();
        beforeFanoutWrite.get().accept(write);

        AtomicReference<WriteOutcome> outcome = new AtomicReference<>();
        rows.compute(key(write.firmId(), write.engagementId()), (k, current) -> {
            // attribute_exists(state_seq) AND state_seq = :seen AND graph_epoch <= :epoch
            if (current == null
                    || current.stateSeq() != write.expectedStateSeq()
                    || current.graphEpoch() > write.graphEpoch()) {
                outcome.set(new WriteOutcome.ConditionFailed(Optional.ofNullable(current)));
                return current;
            }
            EngagementRecord updated = new EngagementRecord(current.firmId(), current.engagementId(),
                    current.productId(), current.market(), current.baseVersion(), current.declinedVersions(),
                    current.stateSeq(), write.pendingTarget(), write.pendingSince(), write.graphEpoch(),
                    current.locked(), current.source());
            outcome.set(new WriteOutcome.Applied(updated));
            writesApplied.incrementAndGet();
            return updated;
        });
        return outcome.get();
    }

    @Override
    public WriteOutcome conditionalBackfillWrite(BackfillWrite write) {
        maybeThrottle();
        EngagementRecord incoming = write.row();
        AtomicReference<WriteOutcome> outcome = new AtomicReference<>();
        rows.compute(key(incoming.firmId(), incoming.engagementId()), (k, current) -> {
            // (attribute_not_exists(state_seq) OR state_seq < :loaded)
            //   AND (attribute_not_exists(graph_epoch) OR graph_epoch <= :epoch)
            boolean newerState = current == null || current.stateSeq() < write.loadedStateSeq();
            boolean epochOk = current == null || current.graphEpoch() <= write.graphEpoch();
            if (!newerState || !epochOk) {
                outcome.set(new WriteOutcome.ConditionFailed(Optional.ofNullable(current)));
                return current;
            }
            outcome.set(new WriteOutcome.Applied(incoming));
            writesApplied.incrementAndGet();
            return incoming;
        });
        return outcome.get();
    }

    // ---- inspection ----

    public Optional<EngagementRecord> row(FirmId firmId, EngagementId engagementId) {
        return Optional.ofNullable(rows.get(key(firmId, engagementId)));
    }

    /** Returns a deterministic, ordered copy of the whole table, for convergence comparisons. */
    public Map<String, EngagementRecord> snapshot() {
        Map<String, EngagementRecord> copy = new TreeMap<>();
        rows.forEach((k, v) -> copy.put(k.firmId() + "#" + k.engagementId(), v));
        return copy;
    }

    public int reads() {
        return reads.get();
    }

    public int writeAttempts() {
        return writeAttempts.get();
    }

    public int writesApplied() {
        return writesApplied.get();
    }

    private void maybeThrottle() {
        int attempt = writeAttempts.incrementAndGet();
        if (throttleAll || attempt == throttleOnWriteNumber.get()) {
            throw new RegistryThrottledException("ProvisionedThroughputExceeded on write attempt " + attempt);
        }
    }

    private static Key key(FirmId firmId, EngagementId engagementId) {
        return new Key(firmId, engagementId);
    }
}
