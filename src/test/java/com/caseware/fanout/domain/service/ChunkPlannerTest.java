package com.caseware.fanout.domain.service;

import static com.caseware.fanout.doubles.TestData.EU;
import static com.caseware.fanout.doubles.TestData.FIRM;
import static com.caseware.fanout.doubles.TestData.PRODUCT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FanoutChunk;
import com.caseware.fanout.domain.model.PublishId;
import com.caseware.fanout.domain.model.TemplateVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class ChunkPlannerTest {

    private static final PublishId PUBLISH = PublishId.of("pub-1");
    private static final TemplateVersion VERSION = TemplateVersion.of("v7");

    @Test
    void largestFirmOf40000EngagementsBecomes80FullChunks() {
        List<FanoutChunk> chunks = plan(ids(40_000));

        assertThat(chunks).hasSize(80);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.size()).isEqualTo(500);
            assertThat(c.totalChunks()).isEqualTo(80);
        });
    }

    @Test
    void medianFirmFitsInOneChunk() {
        List<FanoutChunk> chunks = plan(ids(40));
        assertThat(chunks).singleElement().satisfies(c -> {
            assertThat(c.size()).isEqualTo(40);
            assertThat(c.chunkIndex()).isZero();
            assertThat(c.totalChunks()).isEqualTo(1);
        });
    }

    @Test
    void lastChunkCarriesTheRemainder() {
        List<FanoutChunk> chunks = plan(ids(1_001));
        assertThat(chunks).extracting(FanoutChunk::size).containsExactly(500, 500, 1);
    }

    @Test
    void noAffectedEngagementsMeansNoChunks() {
        assertThat(plan(List.of())).isEmpty();
    }

    @Test
    void boundariesAreIndependentOfInputOrderAndDuplicates() {
        List<EngagementId> shuffled = new ArrayList<>(ids(1_200));
        Collections.shuffle(shuffled, new Random(42));
        shuffled.addAll(ids(30)); // GSI-2 pages may repeat ids

        assertThat(plan(shuffled)).isEqualTo(plan(ids(1_200)));
    }

    @Test
    void rejectsChunkSizeAboveTheSqsLimit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ChunkPlanner.plan(PUBLISH, FIRM, PRODUCT, EU, VERSION, 1, ids(10), 501));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ChunkPlanner.plan(PUBLISH, FIRM, PRODUCT, EU, VERSION, 1, ids(10), 0));
    }

    @Property(tries = 200)
    void chunksPartitionTheInputExactly(@ForAll @IntRange(min = 0, max = 3_000) int count,
                                        @ForAll @IntRange(min = 1, max = 500) int chunkSize) {
        List<FanoutChunk> chunks = ChunkPlanner.plan(PUBLISH, FIRM, PRODUCT, EU, VERSION, 1, ids(count), chunkSize);

        List<EngagementId> flattened = chunks.stream().flatMap(c -> c.engagementIds().stream()).toList();
        assertThat(flattened).hasSize(count);
        assertThat(new HashSet<>(flattened)).isEqualTo(Set.copyOf(ids(count)));
        assertThat(chunks).extracting(FanoutChunk::chunkIndex)
                .containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.size()).isBetween(1, chunkSize);
            assertThat(c.totalChunks()).isEqualTo(chunks.size());
        });
    }

    private static List<FanoutChunk> plan(List<EngagementId> ids) {
        return ChunkPlanner.plan(PUBLISH, FIRM, PRODUCT, EU, VERSION, 1, ids);
    }

    private static List<EngagementId> ids(int n) {
        return IntStream.range(0, n).mapToObj(i -> EngagementId.of(String.format("e%06d", i))).toList();
    }
}
