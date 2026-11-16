package com.caseware.fanout.application;

import static com.caseware.fanout.application.FanoutFixture.GRAPH_E3;
import static com.caseware.fanout.application.FanoutFixture.row;
import static com.caseware.fanout.doubles.TestData.FIRM;
import static com.caseware.fanout.doubles.TestData.v;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FanoutChunk;
import com.caseware.fanout.domain.model.Market;
import com.caseware.fanout.domain.port.CircuitBreaker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Chaos, idempotency and partial-failure recovery (design doc Appendix E). */
class FanoutResilienceTest {

    private final FanoutFixture f = new FanoutFixture();

    @Nested
    class Convergence {

        /** Clean reference run: one delivery of each chunk, in order, at the latest epoch. */
        private java.util.Map<String, com.caseware.fanout.domain.model.EngagementRecord> cleanRun(int rows) {
            FanoutFixture clean = new FanoutFixture();
            List<EngagementId> ids = clean.seedRows(rows, "v4");
            clean.graphs.apply(GRAPH_E3);
            clean.plan("withdraw-v7", 3, ids).forEach(clean.processor()::process);
            return clean.registry.snapshot();
        }

        @Test
        void duplicatesReorderingStaleEpochsAndDlqReplayConvergeToTheCleanRun() {
            List<EngagementId> ids = f.seedRows(1_200, "v4"); // 3 chunks: 500, 500, 200
            List<FanoutChunk> publish = f.plan("pub-v7", 2, ids);
            List<FanoutChunk> withdraw = f.plan("withdraw-v7", 3, ids);

            // Publish partially lands, then the withdrawal is projected.
            f.processor().process(publish.get(1));
            f.graphs.apply(GRAPH_E3);

            // Chaos: withdraw chunks reversed and duplicated, stale publish chunks interleaved, then a DLQ replay.
            List<FanoutChunk> deliveries = new ArrayList<>(withdraw);
            Collections.reverse(deliveries);
            deliveries.add(withdraw.get(1));
            deliveries.add(1, publish.get(0));
            deliveries.add(publish.get(2));
            deliveries.forEach(f.processor()::process);
            withdraw.forEach(f.processor()::process); // runbook DLQ replay of the whole set

            assertThat(f.registry.snapshot()).isEqualTo(cleanRun(1_200));
        }

        @Test
        void stalePublishChunksDeliveredAfterTheWithdrawalAreAcknowledgedAsSuperseded() {
            List<EngagementId> ids = f.seedRows(10, "v4");
            f.graphs.apply(GRAPH_E3);
            f.processor().process(f.single("withdraw-v7", 3, ids));

            // The stale publish message is re-evaluated against the current (epoch 3) graph, so it is a no-op.
            FanoutResult stale = f.processor().process(f.single("pub-v7", 2, ids));

            assertThat(stale).isInstanceOf(FanoutResult.Completed.class);
            assertThat(ids).allSatisfy(id -> assertThat(f.row(id).pendingTarget()).contains(v("v5")));
        }
    }

    @Nested
    class CrashMidChunk {

        @Test
        void resumesFromTheLastCheckpointWithoutRedoingCompletedRows() {
            List<EngagementId> ids = f.seedRows(500, "v4");
            FanoutChunk chunk = f.single("pub-v7", 2, ids);
            f.registry.throttleOnWrite(251); // the worker dies while writing row index 250

            FanoutResult first = f.processor().process(chunk);

            assertThat(first).isInstanceOfSatisfying(FanoutResult.PartialRetry.class, r -> {
                assertThat(r.cursor().nextRowOffset()).isEqualTo(250);
                assertThat(r.cause()).contains("RegistryThrottledException");
            });
            int readsBefore = f.registry.reads();

            FanoutResult resumed = f.processor().process(chunk);

            assertThat(resumed).isInstanceOfSatisfying(FanoutResult.Completed.class,
                    c -> assertThat(c.stats().evaluated()).isEqualTo(250));
            assertThat(f.registry.reads() - readsBefore).isEqualTo(250); // rows 0..249 never re-read
            assertThat(f.registry.writesApplied()).isEqualTo(500);       // every row written exactly once
            assertThat(ids).allSatisfy(id -> assertThat(f.row(id).pendingTarget()).contains(v("v7")));
        }

        @Test
        void rowsBetweenTheCheckpointAndTheCrashAreReEvaluatedIdempotently() {
            List<EngagementId> ids = f.seedRows(500, "v4");
            FanoutChunk chunk = f.single("pub-v7", 2, ids);
            f.registry.throttleOnWrite(275); // crash at row 274; last checkpoint at 250

            f.processor().process(chunk);
            f.processor().process(chunk);

            // Rows 250..273 were written before the crash and are found already current on resume: no second write.
            assertThat(f.registry.writesApplied()).isEqualTo(500);
            assertThat(f.checkpoints.load(chunk.key())).hasValueSatisfying(c -> assertThat(c.completed()).isTrue());
        }

        @Test
        void summaryPairsFoundBeforeTheCrashAreNotLost() {
            // Rows 0..49 are on v4 and rows 50..99 on v5, so (v4 -> v7) can only come from rows before the
            // checkpoint at 50. The resumed run never sees them again.
            List<EngagementId> ids = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                EngagementId id = EngagementId.of(String.format("e%06d", i));
                f.registry.seed(row(FIRM, id, i < 50 ? "v4" : "v5", Set.of(), 1, false));
                ids.add(id);
            }
            FanoutChunk chunk = f.single("pub-v7", 2, ids);
            f.registry.throttleOnWrite(60); // crash at row 59, after the checkpoint at 50

            f.processor().process(chunk);
            f.processor().process(chunk);

            assertThat(f.summaries.allPairs()).extracting(p -> p.fromVersion().value() + "->" + p.toVersion().value())
                    .containsOnly("v4->v7", "v5->v7")
                    .contains("v4->v7");
        }
    }

    @Nested
    class CircuitBreaking {

        @Test
        void sustainedRegistryThrottlingOpensTheBreakerAndStopsPulling() {
            List<EngagementId> ids = f.seedRows(1_500, "v4");
            List<FanoutChunk> chunks = f.plan("pub-v7", 2, ids);
            f.registry.throttleAllWrites(true);

            chunks.forEach(f.processor()::process); // 3 transient failures = threshold
            int attempts = f.registry.writeAttempts();
            FanoutResult next = f.processor().process(chunks.getFirst());

            assertThat(f.breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(next).isInstanceOfSatisfying(FanoutResult.Throttled.class,
                    t -> assertThat(t.retryAfter()).isEqualTo(Duration.ofSeconds(30)));
            assertThat(f.registry.writeAttempts()).isEqualTo(attempts); // no registry traffic while open
        }

        @Test
        void halfOpenProbeClosesTheBreakerOnceTheRegistryRecovers() {
            List<EngagementId> ids = f.seedRows(1_500, "v4");
            List<FanoutChunk> chunks = f.plan("pub-v7", 2, ids);
            f.registry.throttleAllWrites(true);
            chunks.forEach(f.processor()::process);

            f.registry.throttleAllWrites(false);
            f.clock.advance(Duration.ofSeconds(30));
            chunks.forEach(f.processor()::process);

            assertThat(f.breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(ids).allSatisfy(id -> assertThat(f.row(id).pendingTarget()).contains(v("v7")));
        }
    }

    /**
     * A HALF_OPEN probe must end in a verdict whatever the chunk's outcome. Only a transient failure
     * re-opens the breaker; any other outcome proves the registry answered, so it closes. Otherwise the
     * single probe permission would be held forever and every later chunk would be Throttled.
     */
    @Nested
    class ProbeVerdicts {

        private final List<EngagementId> ids = f.seedRows(3, "v4");

        private void armProbe() {
            for (int i = 0; i < 3; i++) {
                f.breaker.recordFailure();
            }
            f.clock.advance(Duration.ofSeconds(30));
        }

        @Test
        void poisonChunkClosesTheBreaker() {
            FanoutChunk poison = new FanoutChunk(f.single("pub", 2, ids).publishId(), FIRM,
                    com.caseware.fanout.doubles.TestData.PRODUCT, Market.of("XX"), v("v7"), 2, 0, 1, ids);
            armProbe();

            assertThat(f.processor().process(poison)).isInstanceOf(FanoutResult.PoisonMessage.class);
            assertThat(f.breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(f.metrics.count("fanout.chunk.poison")).isEqualTo(1);
        }

        @Test
        void duplicateOfACompletedChunkClosesTheBreaker() {
            FanoutChunk chunk = f.single("pub", 2, ids);
            f.processor().process(chunk);
            armProbe();

            assertThat(f.processor().process(chunk)).isInstanceOf(FanoutResult.Completed.class);
            assertThat(f.breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        void laggingGraphProjectionClosesTheBreaker() {
            armProbe();

            assertThat(f.processor().process(f.single("pub", 3, ids))).isInstanceOf(FanoutResult.PartialRetry.class);
            assertThat(f.breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(f.metrics.count("fanout.chunk.graph_lagging")).isEqualTo(1);
        }

        @Test
        void conflictYieldClosesTheBreaker() {
            f.registry.beforeFanoutWrite(w -> f.registry.applyHook(FIRM, w.engagementId(),
                    r -> FanoutFixture.declined(r, "v6"))); // a user who never stops clicking
            armProbe();

            assertThat(f.processor().process(f.single("pub", 2, ids))).isInstanceOf(FanoutResult.PartialRetry.class);
            assertThat(f.breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(f.metrics.count("fanout.chunk.conflict_yield")).isEqualTo(1);
        }

        @Test
        void transientFailureReopensItAndIsCounted() {
            f.registry.throttleAllWrites(true);
            armProbe();

            assertThat(f.processor().process(f.single("pub", 2, ids))).isInstanceOf(FanoutResult.PartialRetry.class);
            assertThat(f.breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(f.metrics.count("fanout.chunk.transient_failure")).isEqualTo(1);
            assertThat(f.processor().process(f.single("pub", 2, ids))).isInstanceOf(FanoutResult.Throttled.class);
            assertThat(f.metrics.count("fanout.chunk.throttled")).isEqualTo(1);
        }
    }

    @Nested
    class PoisonVersusTransient {

        @Test
        void unknownLineageIsPoisonAndNeverTripsTheBreaker() {
            List<EngagementId> ids = f.seedRows(3, "v4");
            FanoutChunk unknownMarket = new FanoutChunk(f.single("pub", 2, ids).publishId(), FIRM,
                    com.caseware.fanout.doubles.TestData.PRODUCT, Market.of("XX"), v("v7"), 2, 0, 1, ids);

            for (int i = 0; i < 5; i++) {
                assertThat(f.processor().process(unknownMarket)).isInstanceOfSatisfying(FanoutResult.PoisonMessage.class, p -> {
                    assertThat(p.key()).isEqualTo(unknownMarket.key());
                    assertThat(p.reason()).contains("XX");
                });
            }
            assertThat(f.breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        void oneCorruptRowIsQuarantinedWithoutPoisoningItsHealthyNeighbours() {
            List<EngagementId> ids = new ArrayList<>(f.seedRows(4, "v4"));
            EngagementId corrupt = EngagementId.of("zz-unknown-base");
            f.registry.seed(row(FIRM, corrupt, "v3-not-in-graph", Set.of(), 1, false));
            ids.add(corrupt);

            FanoutResult result = f.processor().process(f.single("pub-v7", 2, ids));

            assertThat(result).isInstanceOfSatisfying(FanoutResult.Completed.class, c -> {
                assertThat(c.stats().unresolvable()).isEqualTo(1);
                assertThat(c.stats().written()).isEqualTo(4);
            });
            assertThat(f.metrics.count("fanout.row.unresolvable")).isEqualTo(1);
            assertThat(f.row(corrupt).graphEpoch()).isZero(); // left lagging, so the epoch sweep finds it
        }
    }
}
