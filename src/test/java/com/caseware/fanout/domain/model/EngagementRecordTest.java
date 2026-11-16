package com.caseware.fanout.domain.model;

import static com.caseware.fanout.doubles.TestData.ENGAGEMENT;
import static com.caseware.fanout.doubles.TestData.EU;
import static com.caseware.fanout.doubles.TestData.FIRM;
import static com.caseware.fanout.doubles.TestData.PRODUCT;
import static com.caseware.fanout.doubles.TestData.row;
import static com.caseware.fanout.doubles.TestData.v;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EngagementRecordTest {

    private static final Instant T0 = Instant.parse("2026-11-16T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);

    @Test
    void lockedRowCanNeverCarryAPendingTarget() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> record(true, Optional.of(v("v5")), Optional.of(T0)))
                .withMessageContaining("locked");
    }

    @Test
    void pendingTargetAndPendingSinceTravelTogether() {
        assertThatIllegalArgumentException().isThrownBy(() -> record(false, Optional.of(v("v5")), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> record(false, Optional.empty(), Optional.of(T0)));
    }

    @Test
    void pendingTargetCannotBeTheBaseVersion() {
        assertThatIllegalArgumentException().isThrownBy(() -> record(false, Optional.of(v("v4")), Optional.of(T0)));
    }

    @Test
    void declinedVersionsAreDefensivelyCopied() {
        Set<TemplateVersion> declined = new HashSet<>(Set.of(v("v5")));
        EngagementRecord r = new EngagementRecord(FIRM, ENGAGEMENT, PRODUCT, EU, v("v4"), declined, 1,
                Optional.empty(), Optional.empty(), 0, false, Source.HOOK);
        declined.add(v("v6"));
        assertThat(r.declinedVersions()).containsExactly(v("v5"));
        assertThatThrownBy(() -> r.declinedVersions().add(v("v7"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void pendingSinceIsSetWhenATargetFirstAppears() {
        EngagementRecord derived = row("v4", 1).withDerivedPending(Optional.of(v("v5")), 3, T0);
        assertThat(derived.pendingTarget()).contains(v("v5"));
        assertThat(derived.pendingSince()).contains(T0);
        assertThat(derived.graphEpoch()).isEqualTo(3);
    }

    @Test
    void pendingSinceIsPreservedWhenTheTargetIsUnchanged() {
        EngagementRecord pending = row("v4", 1).withDerivedPending(Optional.of(v("v5")), 3, T0);
        EngagementRecord again = pending.withDerivedPending(Optional.of(v("v5")), 4, T1);
        assertThat(again.pendingSince()).contains(T0);
    }

    @Test
    void pendingSinceIsResetWhenTheTargetChanges() {
        EngagementRecord pending = row("v4", 1).withDerivedPending(Optional.of(v("v5")), 3, T0);
        EngagementRecord moved = pending.withDerivedPending(Optional.of(v("v7")), 4, T1);
        assertThat(moved.pendingSince()).contains(T1);
    }

    @Test
    void pendingSinceIsRemovedWhenNothingIsPending() {
        EngagementRecord pending = row("v4", 1).withDerivedPending(Optional.of(v("v5")), 3, T0);
        EngagementRecord cleared = pending.withDerivedPending(Optional.empty(), 4, T1);
        assertThat(cleared.isPending()).isFalse();
        assertThat(cleared.pendingSince()).isEmpty();
    }

    @Test
    void rejectsNegativeCounters() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EngagementRecord(FIRM, ENGAGEMENT, PRODUCT, EU,
                v("v4"), Set.of(), -1, Optional.empty(), Optional.empty(), 0, false, Source.HOOK));
        assertThatIllegalArgumentException().isThrownBy(() -> new EngagementRecord(FIRM, ENGAGEMENT, PRODUCT, EU,
                v("v4"), Set.of(), 0, Optional.empty(), Optional.empty(), -1, false, Source.HOOK));
    }

    private static EngagementRecord record(boolean locked, Optional<TemplateVersion> target, Optional<Instant> since) {
        return new EngagementRecord(FIRM, ENGAGEMENT, PRODUCT, EU, v("v4"), Set.of(), 1, target, since, 0, locked, Source.HOOK);
    }
}
