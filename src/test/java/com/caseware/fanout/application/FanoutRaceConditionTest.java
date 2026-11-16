package com.caseware.fanout.application;

import static com.caseware.fanout.application.FanoutFixture.GRAPH_E2;
import static com.caseware.fanout.application.FanoutFixture.GRAPH_E3;
import static com.caseware.fanout.application.FanoutFixture.accepted;
import static com.caseware.fanout.application.FanoutFixture.declined;
import static com.caseware.fanout.application.FanoutFixture.fixed;
import static com.caseware.fanout.doubles.TestData.FIRM;
import static com.caseware.fanout.doubles.TestData.v;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.FanoutChunk;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Races from design doc Appendix F. Each race is injected deterministically at the exact point
 * between the worker's read and its conditional write. No sleeps, no timing luck.
 */
class FanoutRaceConditionTest {

    private final FanoutFixture f = new FanoutFixture();

    @Nested
    class UserActsMidFanout {

        @Test
        void acceptMidFlightIsNotClobbered() {
            EngagementId id = f.seedRows(1, "v4").getFirst();
            onceBeforeWrite(id, r -> accepted(r, "v7"));

            FanoutResult result = f.processor().process(f.single("pub-1", 2, List.of(id)));

            EngagementRecord row = f.row(id);
            assertThat(row.baseVersion()).isEqualTo(v("v7"));
            assertThat(row.stateSeq()).isEqualTo(2);
            assertThat(row.isPending()).isFalse(); // recomputed from the fresh row: nothing newer than v7
            assertThat(((FanoutResult.Completed) result).stats().conflictRetries()).isEqualTo(1);
        }

        @Test
        void declineMidFlightIsHonouredAndTheNextEligibleVersionIsOffered() {
            EngagementId id = f.seedRows(1, "v4").getFirst();
            onceBeforeWrite(id, r -> declined(r, "v7"));

            f.processor().process(f.single("pub-1", 2, List.of(id)));

            EngagementRecord row = f.row(id);
            assertThat(row.declinedVersions()).containsExactly(v("v7"));
            assertThat(row.baseVersion()).isEqualTo(v("v4"));
            assertThat(row.pendingTarget()).contains(v("v5"));
            assertThat(row.stateSeq()).isEqualTo(2);
        }

        @Test
        void finalizeMidFlightLeavesTheRowLockedAndNeverPending() {
            EngagementId id = f.seedRows(1, "v4").getFirst();
            onceBeforeWrite(id, FanoutFixture::finalized);

            f.processor().process(f.single("pub-1", 2, List.of(id)));

            EngagementRecord row = f.row(id);
            assertThat(row.locked()).isTrue();
            assertThat(row.isPending()).isFalse();
        }

        @Test
        void deleteMidFlightIsCountedAsMissingAndNotResurrected() {
            EngagementId id = f.seedRows(1, "v4").getFirst();
            AtomicBoolean fired = new AtomicBoolean();
            f.registry.beforeFanoutWrite(w -> {
                if (fired.compareAndSet(false, true)) {
                    f.registry.delete(FIRM, id);
                }
            });

            FanoutResult result = f.processor().process(f.single("pub-1", 2, List.of(id)));

            assertThat(f.registry.row(FIRM, id)).isEmpty();
            assertThat(((FanoutResult.Completed) result).stats().missing()).isEqualTo(1);
        }

        @Test
        void persistentConflictYieldsAfterBoundedAttemptsWithoutClobbering() {
            List<EngagementId> ids = f.seedRows(3, "v4");
            EngagementId hot = ids.get(1);
            // A user (or a hook storm) changes this row before every single write attempt.
            f.registry.beforeFanoutWrite(w -> {
                if (w.engagementId().equals(hot)) {
                    f.registry.applyHook(FIRM, hot, r -> declined(r, "v-noise-" + r.stateSeq()));
                }
            });
            FanoutChunk chunk = f.single("pub-1", 2, ids);

            FanoutResult result = f.processor().process(chunk);

            assertThat(result).isInstanceOfSatisfying(FanoutResult.PartialRetry.class, r -> {
                assertThat(r.cursor().nextRowOffset()).isZero(); // failed before the first checkpoint
                assertThat(r.cause()).contains("kept changing");
            });
            assertThat(f.row(hot).stateSeq()).isEqualTo(1 + FanoutChunkProcessor.MAX_WRITE_ATTEMPTS);
            assertThat(f.row(hot).isPending()).isFalse();
            assertThat(f.checkpoints.load(chunk.key())).isEmpty(); // nothing durable to resume from, so not completed
            assertThat(f.breaker.state()).isEqualTo(com.caseware.fanout.domain.port.CircuitBreaker.State.CLOSED);

            // Once the user stops, the redelivered chunk converges.
            f.registry.beforeFanoutWrite(w -> {});
            assertThat(f.processor().process(chunk)).isInstanceOf(FanoutResult.Completed.class);
            assertThat(ids).allSatisfy(id -> assertThat(f.row(id).pendingTarget()).contains(v("v7")));
        }
    }

    @Nested
    class PublishRacingWithdrawal {

        private final FanoutChunkProcessor staleWorker = f.processor(fixed(GRAPH_E2)); // took its snapshot before the withdraw
        private final FanoutChunkProcessor freshWorker = f.processor(fixed(GRAPH_E3));

        @Test
        void stalePublishFinishingLastCannotReOfferTheWithdrawnVersion() {
            List<EngagementId> ids = f.seedRows(5, "v4");

            freshWorker.process(f.single("withdraw-v7", 3, ids));
            FanoutResult stale = staleWorker.process(f.single("pub-v7", 2, ids));

            assertThat(((FanoutResult.Completed) stale).stats().superseded()).isEqualTo(5);
            assertThat(ids).allSatisfy(id -> {
                assertThat(f.row(id).pendingTarget()).contains(v("v5"));
                assertThat(f.row(id).graphEpoch()).isEqualTo(3);
            });
        }

        @Test
        void withdrawalLandingBetweenTheStaleReadAndWriteWins() {
            List<EngagementId> ids = f.seedRows(5, "v4");
            AtomicBoolean fired = new AtomicBoolean();
            // The stale worker has read row 0 at epoch 0 and is about to write epoch 2; the withdrawal fan-out
            // completes the whole firm in between.
            f.registry.beforeFanoutWrite(w -> {
                if (w.graphEpoch() == 2 && fired.compareAndSet(false, true)) {
                    freshWorker.process(f.single("withdraw-v7", 3, ids));
                }
            });

            staleWorker.process(f.single("pub-v7", 2, ids));

            assertThat(ids).allSatisfy(id -> {
                assertThat(f.row(id).pendingTarget()).contains(v("v5"));
                assertThat(f.row(id).graphEpoch()).isEqualTo(3);
            });
        }

        @Test
        void rowsAreStampedWithTheEpochOfTheGraphTheyWereDerivedFromNotTheChunksEpoch() {
            List<EngagementId> ids = f.seedRows(3, "v4");
            // A late publish message (nominal epoch 2) is processed after the projection moved to epoch 3.
            f.processor(fixed(GRAPH_E3)).process(f.single("pub-v7-late", 2, ids));

            assertThat(ids).allSatisfy(id -> assertThat(f.row(id).graphEpoch()).isEqualTo(3));

            // Why it matters: had the rows been stamped 2, a genuinely stale epoch-2 worker would pass
            // `graph_epoch <= 2` and re-offer the withdrawn v7.
            staleWorker.process(f.single("pub-v7", 2, ids));
            assertThat(ids).allSatisfy(id -> assertThat(f.row(id).pendingTarget()).contains(v("v5")));
        }

        @Test
        void bothArrivalOrdersConvergeToTheSameRegistryState() {
            FanoutFixture inOrder = new FanoutFixture();
            List<EngagementId> ids = inOrder.seedRows(5, "v4");
            inOrder.processor(fixed(GRAPH_E2)).process(inOrder.single("pub-v7", 2, ids));
            inOrder.processor(fixed(GRAPH_E3)).process(inOrder.single("withdraw-v7", 3, ids));

            f.seedRows(5, "v4");
            freshWorker.process(f.single("withdraw-v7", 3, ids));
            staleWorker.process(f.single("pub-v7", 2, ids));

            assertThat(f.registry.snapshot()).isEqualTo(inOrder.registry.snapshot());
        }
    }

    /** Applies {@code hook} to row {@code id} once, right before the worker's first conditional write to it. */
    private void onceBeforeWrite(EngagementId id, UnaryOperator<EngagementRecord> hook) {
        AtomicBoolean fired = new AtomicBoolean();
        f.registry.beforeFanoutWrite(w -> {
            if (w.engagementId().equals(id) && fired.compareAndSet(false, true)) {
                f.registry.applyHook(FIRM, id, hook);
            }
        });
    }
}
