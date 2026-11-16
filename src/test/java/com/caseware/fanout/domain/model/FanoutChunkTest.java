package com.caseware.fanout.domain.model;

import static com.caseware.fanout.doubles.TestData.EU;
import static com.caseware.fanout.doubles.TestData.FIRM;
import static com.caseware.fanout.doubles.TestData.PRODUCT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FanoutChunkTest {

    private static final PublishId PUBLISH = PublishId.of("pub-1");

    @Test
    void acceptsExactly500Engagements() {
        FanoutChunk chunk = chunk(0, 80, ids(500));
        assertThat(chunk.size()).isEqualTo(FanoutChunk.MAX_ENGAGEMENTS);
        assertThat(chunk.key()).isEqualTo(new ChunkKey(PUBLISH, FIRM, 0));
    }

    @Test
    void rejects501Engagements() {
        assertThatIllegalArgumentException().isThrownBy(() -> chunk(0, 1, ids(501))).withMessageContaining("at most 500");
    }

    @Test
    void rejectsEmptyAndDuplicateEngagements() {
        assertThatIllegalArgumentException().isThrownBy(() -> chunk(0, 1, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> chunk(0, 1, List.of(EngagementId.of("e1"), EngagementId.of("e1"))));
    }

    @Test
    void rejectsChunkIndexOutsideTotal() {
        assertThatIllegalArgumentException().isThrownBy(() -> chunk(80, 80, ids(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> chunk(-1, 80, ids(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> chunk(0, 0, ids(1)));
    }

    @Test
    void engagementListIsImmutable() {
        FanoutChunk chunk = chunk(0, 1, ids(3));
        assertThat(chunk.engagementIds()).isUnmodifiable();
    }

    static List<EngagementId> ids(int n) {
        return IntStream.range(0, n).mapToObj(i -> EngagementId.of("e" + i)).toList();
    }

    private static FanoutChunk chunk(int index, int total, List<EngagementId> ids) {
        return new FanoutChunk(PUBLISH, FIRM, PRODUCT, EU, TemplateVersion.of("v7"), 1, index, total, ids);
    }
}
