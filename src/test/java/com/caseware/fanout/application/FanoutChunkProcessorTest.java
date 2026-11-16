package com.caseware.fanout.application;

import static com.caseware.fanout.application.FanoutFixture.GRAPH_E2;
import static com.caseware.fanout.application.FanoutFixture.GRAPH_E3;
import static com.caseware.fanout.application.FanoutFixture.T0;
import static com.caseware.fanout.application.FanoutFixture.row;
import static com.caseware.fanout.doubles.TestData.EU;
import static com.caseware.fanout.doubles.TestData.FIRM;
import static com.caseware.fanout.doubles.TestData.PRODUCT;
import static com.caseware.fanout.doubles.TestData.v;
import static com.caseware.fanout.doubles.TestData.versions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.FanoutChunk;
import com.caseware.fanout.domain.model.LocaleCode;
import com.caseware.fanout.domain.model.SummaryPair;
import com.caseware.fanout.domain.port.CircuitBreaker;
import com.caseware.fanout.domain.port.DownstreamEngagementClient;
import com.caseware.fanout.domain.port.TelemetryContext;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FanoutChunkProcessorTest {

    private final FanoutFixture f = new FanoutFixture();

    @Test
    void publishMarksEveryAffectedRowPendingOnTheLatestVersion() {
        List<EngagementId> ids = f.seedRows(3, "v4");

        FanoutResult result = f.processor().process(f.single("pub-1", 2, ids));

        assertThat(result).isInstanceOfSatisfying(FanoutResult.Completed.class, c -> {
            assertThat(c.stats().evaluated()).isEqualTo(3);
            assertThat(c.stats().written()).isEqualTo(3);
        });
        assertThat(ids).allSatisfy(id -> {
            EngagementRecord r = f.row(id);
            assertThat(r.pendingTarget()).contains(v("v7"));
            assertThat(r.pendingSince()).contains(T0);
            assertThat(r.graphEpoch()).isEqualTo(2);
            assertThat(r.stateSeq()).isEqualTo(1); // fan-out never touches ground-truth fields
        });
        assertThat(f.checkpoints.load(f.single("pub-1", 2, ids).key())).hasValueSatisfying(c -> assertThat(c.completed()).isTrue());
    }

    @Test
    void summaryPairsAreDeduplicatedPerBaseTargetAndLocale() {
        List<EngagementId> ids = f.seedRows(40, "v4");
        f.registry.seed(row(FIRM, EngagementId.of("z-declined-v7"), "v4", versions("v7"), 1, false));

        f.processor().process(f.single("pub-1", 2, List.copyOf(concat(ids, EngagementId.of("z-declined-v7")))));

        assertThat(f.summaries.allPairs()).containsExactlyInAnyOrder(
                pair("v4", "v7", "en-CA"), pair("v4", "v7", "fr-CA"),
                pair("v4", "v5", "en-CA"), pair("v4", "v5", "fr-CA"));
    }

    @Test
    void lockedRowsAreNeverTouchedNotEvenToBumpTheEpoch() {
        EngagementRecord locked = row(FIRM, EngagementId.of("locked"), "v4", Set.of(), 9, true);
        f.registry.seed(locked);

        FanoutResult result = f.processor().process(f.single("pub-1", 2, List.of(locked.engagementId())));

        assertThat(f.row(locked.engagementId())).isEqualTo(locked);
        assertThat(f.registry.writeAttempts()).isZero();
        assertThat(((FanoutResult.Completed) result).stats().unchanged()).isEqualTo(1);
    }

    @Test
    void alreadyCurrentRowNeedsNoWriteButStillContributesItsSummaryPair() {
        List<EngagementId> ids = f.seedRows(1, "v4");
        f.processor().process(f.single("pub-1", 2, ids));
        int writesAfterFirstRun = f.registry.writeAttempts();

        FanoutResult again = f.processor().process(f.single("pub-1-retry", 2, ids));

        assertThat(f.registry.writeAttempts()).isEqualTo(writesAfterFirstRun);
        assertThat(((FanoutResult.Completed) again).stats().unchanged()).isEqualTo(1);
        assertThat(f.summaries.batches()).hasSize(2);
    }

    @Test
    void sameTargetAtANewerEpochBumpsTheEpochAndPreservesPendingSince() {
        List<EngagementId> ids = f.seedRows(1, "v4");
        f.processor().process(f.single("pub-1", 2, ids));
        f.clock.advance(Duration.ofHours(1));
        // An unrelated publish on another branch bumps the epoch without changing this row's target.
        f.graphs.apply(GRAPH_E2.withPublished(com.caseware.fanout.doubles.TestData.published("v4-hotfix", 8)));

        f.processor().process(f.single("pub-2", 3, ids));

        EngagementRecord r = f.row(ids.getFirst());
        assertThat(r.graphEpoch()).isEqualTo(3);
        assertThat(r.pendingTarget()).contains(v("v7"));
        assertThat(r.pendingSince()).contains(T0);
    }

    @Test
    void withdrawalFallsBackOrRetractsThePendingUpdate() {
        EngagementId plain = EngagementId.of("plain");
        EngagementId declinedV5 = EngagementId.of("declined-v5");
        f.registry.seed(row(FIRM, plain, "v4", Set.of(), 1, false));
        f.registry.seed(row(FIRM, declinedV5, "v4", versions("v5"), 1, false));
        f.processor().process(f.single("pub-1", 2, List.of(plain, declinedV5)));
        assertThat(f.row(plain).pendingTarget()).contains(v("v7"));
        assertThat(f.row(declinedV5).pendingTarget()).contains(v("v7"));

        f.clock.advance(Duration.ofMinutes(5));
        f.graphs.apply(GRAPH_E3);
        f.processor().process(f.single("withdraw-v7", 3, List.of(plain, declinedV5)));

        assertThat(f.row(plain).pendingTarget()).contains(v("v5"));
        assertThat(f.row(plain).pendingSince()).contains(T0.plus(Duration.ofMinutes(5)));
        assertThat(f.row(declinedV5).isPending()).isFalse();
        assertThat(f.row(declinedV5).pendingSince()).isEmpty();
        assertThat(f.row(declinedV5).graphEpoch()).isEqualTo(3);
    }

    @Test
    void missingRowsAreCountedNotCreated() {
        FanoutResult result = f.processor().process(f.single("pub-1", 2, List.of(EngagementId.of("never-seeded"))));

        assertThat(((FanoutResult.Completed) result).stats().missing()).isEqualTo(1);
        assertThat(f.registry.snapshot()).isEmpty();
    }

    @Test
    void laggingGraphProjectionYieldsWithoutWriting() {
        List<EngagementId> ids = f.seedRows(2, "v4");

        FanoutResult result = f.processor().process(f.single("withdraw-v7", 3, ids)); // store is still at epoch 2

        assertThat(result).isInstanceOf(FanoutResult.PartialRetry.class);
        assertThat(f.registry.writeAttempts()).isZero();
        assertThat(f.breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void duplicateDeliveryOfACompletedChunkIsANoOp() {
        List<EngagementId> ids = f.seedRows(10, "v4");
        f.processor().process(f.single("pub-1", 2, ids));
        int reads = f.registry.reads();

        FanoutResult duplicate = f.processor().process(f.single("pub-1", 2, ids));

        assertThat(duplicate).isEqualTo(new FanoutResult.Completed(f.single("pub-1", 2, ids).key(), ChunkStats.ZERO));
        assertThat(f.registry.reads()).isEqualTo(reads);
        assertThat(f.metrics.count("fanout.chunk.duplicate")).isEqualTo(1);
    }

    @Test
    void rowAlreadyAtTheCurrentEpochButWithADriftedTargetIsRepaired() {
        EngagementId id = f.seedRows(1, "v4").getFirst();
        f.registry.seed(f.row(id).withDerivedPending(Optional.of(v("v5")), 2, T0)); // v7 is the right answer

        FanoutResult result = f.processor().process(f.single("pub-1", 2, List.of(id)));

        assertThat(result).isInstanceOfSatisfying(FanoutResult.Completed.class,
                c -> assertThat(c.stats().written()).isEqualTo(1));
        assertThat(f.row(id).pendingTarget()).contains(v("v7"));
    }

    @Test
    void checkpointsEveryNRowsAndOnCompletionButNotTwiceAtTheEnd() {
        List<EngagementId> ids = f.seedRows(100, "v4"); // exactly 2 x CHECKPOINT_EVERY

        f.processor().process(f.single("pub-1", 2, ids));

        assertThat(f.checkpoints.advances()).isEqualTo(2); // offset 50, then complete(100)
    }

    @Test
    void eachFlushPublishesOnlyThePairsFoundSinceThePreviousOne() {
        List<EngagementId> ids = FanoutFixture.ids(100);
        for (int i = 0; i < ids.size(); i++) {
            f.registry.seed(row(FIRM, ids.get(i), i < 50 ? "v4" : "v5", Set.of(), 1, false));
        }

        f.processor().process(f.single("pub-1", 2, ids));

        assertThat(f.summaries.batches()).hasSize(2);
        assertThat(f.summaries.batches().get(1)).containsExactlyInAnyOrder(
                pair("v5", "v7", "en-CA"), pair("v5", "v7", "fr-CA"));
    }

    @Test
    void chunkWithNothingPendingPublishesNoEmptyBatch() {
        List<EngagementId> ids = f.seedRows(3, "v7"); // already on the latest version

        f.processor().process(f.single("pub-1", 2, ids));

        assertThat(f.summaries.batches()).isEmpty();
    }

    @Test
    void completedChunkReportsRowCountersWithChunkCorrelation() {
        List<EngagementId> ids = f.seedRows(3, "v4");
        f.registry.seed(f.row(ids.get(1)).withDerivedPending(Optional.of(v("v5")), 9, T0)); // a newer epoch won
        java.util.concurrent.atomic.AtomicBoolean raced = new java.util.concurrent.atomic.AtomicBoolean();
        f.registry.beforeFanoutWrite(w -> {
            if (w.engagementId().equals(ids.get(2)) && raced.compareAndSet(false, true)) {
                f.registry.applyHook(FIRM, ids.get(2), r -> FanoutFixture.declined(r, "v6"));
            }
        });

        FanoutChunk chunk = f.single("pub-1", 2, ids);
        f.processor().process(chunk);

        assertThat(f.metrics.count("fanout.chunk.completed")).isEqualTo(1);
        assertThat(f.metrics.count("fanout.rows.evaluated")).isEqualTo(3);
        assertThat(f.metrics.count("fanout.rows.written")).isEqualTo(2);
        assertThat(f.metrics.count("fanout.rows.superseded")).isEqualTo(1);
        assertThat(f.metrics.count("fanout.rows.conflict_retries")).isEqualTo(1);
        TelemetryContext ctx = f.metrics.lastContext("fanout.rows.written");
        assertThat(ctx.chunkId()).contains(chunk.key().chunkId());
        assertThat(ctx.firmId()).contains(FIRM);
        assertThat(ctx.publishId()).contains(chunk.publishId());
        assertThat(ctx.templateVersion()).contains(v("v7"));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FanoutChunkProcessor(f.registry, f.graphs,
                f.checkpoints, f.summaries, f.breaker, f.metrics, f.clock, Set.of(), 50));
        assertThatIllegalArgumentException().isThrownBy(() -> new FanoutChunkProcessor(f.registry, f.graphs,
                f.checkpoints, f.summaries, f.breaker, f.metrics, f.clock, FanoutFixture.LOCALES, 0));
        FanoutChunkProcessor everyRow = new FanoutChunkProcessor(f.registry, f.graphs, f.checkpoints, f.summaries,
                f.breaker, f.metrics, f.clock, FanoutFixture.LOCALES, 1);
        everyRow.process(f.single("pub-1", 2, f.seedRows(3, "v4")));
        assertThat(f.checkpoints.advances()).isEqualTo(3); // offsets 1 and 2, then complete(3)
    }

    @Test
    void fanoutCannotReachTheDownstreamLoaderByConstruction() {
        // Explicit List<Class<?>>: Eclipse JDT (VS Code) rejects the inferred capture type that javac accepts.
        for (Constructor<?> constructor : FanoutChunkProcessor.class.getConstructors()) {
            List<Class<?>> parameterTypes = Arrays.asList(constructor.getParameterTypes());
            assertThat(parameterTypes).doesNotContain(DownstreamEngagementClient.class);
        }
        List<Class<?>> fieldTypes = Arrays.stream(FanoutChunkProcessor.class.getDeclaredFields())
                .<Class<?>>map(java.lang.reflect.Field::getType)
                .toList();
        assertThat(fieldTypes).doesNotContain(DownstreamEngagementClient.class);
    }

    private static SummaryPair pair(String from, String to, String locale) {
        return new SummaryPair(PRODUCT, EU, v(from), v(to), LocaleCode.of(locale));
    }

    private static List<EngagementId> concat(List<EngagementId> ids, EngagementId extra) {
        java.util.ArrayList<EngagementId> all = new java.util.ArrayList<>(ids);
        all.add(extra);
        return all;
    }
}
