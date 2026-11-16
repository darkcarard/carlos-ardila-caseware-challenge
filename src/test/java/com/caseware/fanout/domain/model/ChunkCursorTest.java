package com.caseware.fanout.domain.model;

import static com.caseware.fanout.doubles.TestData.FIRM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class ChunkCursorTest {

    private static final ChunkKey KEY = new ChunkKey(PublishId.of("pub-1"), FIRM, 3);

    @Test
    void advancesForwardAndCompletes() {
        ChunkCursor cursor = ChunkCursor.start(KEY).advanceTo(250).complete(500);
        assertThat(cursor.nextRowOffset()).isEqualTo(500);
        assertThat(cursor.completed()).isTrue();
    }

    @Test
    void neverRegresses() {
        ChunkCursor at250 = ChunkCursor.start(KEY).advanceTo(250);
        assertThatIllegalArgumentException().isThrownBy(() -> at250.advanceTo(100));
    }

    @Test
    void advancingToTheSameOffsetIsAnIdempotentNoOp() {
        ChunkCursor at250 = ChunkCursor.start(KEY).advanceTo(250);
        assertThat(at250.advanceTo(250)).isEqualTo(at250);
        assertThat(at250.complete(250).isAheadOf(at250)).isTrue(); // completion alone is progress
    }

    @Test
    void completedCursorIsTerminal() {
        ChunkCursor done = ChunkCursor.start(KEY).complete(500);
        assertThatIllegalStateException().isThrownBy(() -> done.advanceTo(500));
    }

    @Test
    void aheadOfComparesCompletionThenOffset() {
        ChunkCursor at100 = ChunkCursor.start(KEY).advanceTo(100);
        ChunkCursor at250 = at100.advanceTo(250);
        ChunkCursor done = at250.complete(500);

        assertThat(at250.isAheadOf(at100)).isTrue();
        assertThat(at100.isAheadOf(at250)).isFalse();
        assertThat(done.isAheadOf(at250)).isTrue();
        assertThat(at250.isAheadOf(at250)).isFalse();
        assertThat(at250.isAheadOf(done)).isFalse();
    }

    @Test
    void rejectsOutOfRangeOffsetAndForeignComparison() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkCursor(KEY, 501, false));
        ChunkCursor other = ChunkCursor.start(new ChunkKey(PublishId.of("pub-1"), FIRM, 4));
        assertThatIllegalArgumentException().isThrownBy(() -> ChunkCursor.start(KEY).isAheadOf(other));
    }

    @Test
    void chunkIdCarriesCorrelationContext() {
        assertThat(KEY.chunkId()).isEqualTo("pub-1/firm-acme/3");
    }
}
