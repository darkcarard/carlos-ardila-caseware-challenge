package com.caseware.fanout.domain.port;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.TemplateVersion;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Guarded fan-out mutation. {@code expectedStateSeq} and {@code graphEpoch} are mandatory
 * components, so the guard cannot be forgotten.
 *
 * @param expectedStateSeq the {@code state_seq} of the row the derivation was computed from
 * @param graphEpoch the epoch of the version-graph snapshot used for the derivation (not
 *     necessarily the chunk's epoch)
 */
public record FanoutWrite(
        FirmId firmId,
        EngagementId engagementId,
        long expectedStateSeq,
        long graphEpoch,
        Optional<TemplateVersion> pendingTarget,
        Optional<Instant> pendingSince) {

    public FanoutWrite {
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(pendingTarget, "pendingTarget");
        Objects.requireNonNull(pendingSince, "pendingSince");
        if (pendingTarget.isPresent() != pendingSince.isPresent()) {
            throw new IllegalArgumentException("pendingTarget and pendingSince must be set together");
        }
        if (expectedStateSeq < 0 || graphEpoch < 0) {
            throw new IllegalArgumentException("expectedStateSeq and graphEpoch must be >= 0");
        }
    }

    /**
     * Builds the write from the row as read and the freshly derived target. It applies the
     * {@code pending_since} policy: preserve it for the same target, set it to {@code now} for a
     * new one, and remove it when nothing is pending.
     */
    public static FanoutWrite derive(EngagementRecord seen, Optional<TemplateVersion> target, long graphEpoch, Instant now) {
        EngagementRecord derived = seen.withDerivedPending(target, graphEpoch, now);
        return new FanoutWrite(seen.firmId(), seen.engagementId(), seen.stateSeq(), graphEpoch,
                derived.pendingTarget(), derived.pendingSince());
    }
}
