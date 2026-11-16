package com.caseware.fanout.domain.port;

import static com.caseware.fanout.doubles.TestData.row;
import static com.caseware.fanout.doubles.TestData.v;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.Source;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WriteCommandsTest {

    private static final Instant T0 = Instant.parse("2026-11-16T10:00:00Z");

    @Test
    void fanoutWriteCarriesTheSeenStateSeqAsItsGuard() {
        EngagementRecord seen = row("v4", 7);

        FanoutWrite write = FanoutWrite.derive(seen, Optional.of(v("v5")), 3, T0);

        assertThat(write.expectedStateSeq()).isEqualTo(7);
        assertThat(write.graphEpoch()).isEqualTo(3);
        assertThat(write.pendingTarget()).contains(v("v5"));
        assertThat(write.pendingSince()).contains(T0);
    }

    @Test
    void fanoutWritePreservesPendingSinceForTheSameTarget() {
        EngagementRecord seen = row("v4", 7).withDerivedPending(Optional.of(v("v5")), 2, T0);

        FanoutWrite write = FanoutWrite.derive(seen, Optional.of(v("v5")), 3, T0.plusSeconds(600));

        assertThat(write.pendingSince()).contains(T0);
    }

    @Test
    void backfillWriteRejectsHookProvenance() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BackfillWrite(row("v4", 1)));
    }

    @Test
    void backfillWriteExposesItsGuards() {
        EngagementRecord base = row("v4", 9);
        EngagementRecord seeded = new EngagementRecord(base.firmId(), base.engagementId(), base.productId(), base.market(),
                base.baseVersion(), base.declinedVersions(), 9, Optional.empty(), Optional.empty(), 5, false, Source.BACKFILL);

        BackfillWrite write = new BackfillWrite(seeded);

        assertThat(write.loadedStateSeq()).isEqualTo(9);
        assertThat(write.graphEpoch()).isEqualTo(5);
    }
}
