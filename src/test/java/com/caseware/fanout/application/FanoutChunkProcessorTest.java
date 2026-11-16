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

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.LocaleCode;
import com.caseware.fanout.domain.model.SummaryPair;
import com.caseware.fanout.domain.port.CircuitBreaker;
import com.caseware.fanout.domain.port.DownstreamEngagementClient;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
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
    void fanoutCannotReachTheDownstreamLoaderByConstruction() {
        for (Constructor<?> constructor : FanoutChunkProcessor.class.getConstructors()) {
            assertThat(Arrays.asList(constructor.getParameterTypes())).doesNotContain(DownstreamEngagementClient.class);
        }
        assertThat(Arrays.stream(FanoutChunkProcessor.class.getDeclaredFields()).map(java.lang.reflect.Field::getType))
                .doesNotContain(DownstreamEngagementClient.class);
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
