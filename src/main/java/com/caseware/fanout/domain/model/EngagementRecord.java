package com.caseware.fanout.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One row of the regional Engagement State Registry (DynamoDB: {@code PK=firm_id},
 * {@code SK=engagement_id}). REGIONAL ONLY.
 *
 * <p>{@code baseVersion}, {@code declinedVersions}, {@code stateSeq} and {@code locked} mirror
 * state owned by the engagement system. {@code pendingTarget} and {@code pendingSince} are
 * derived. {@code graphEpoch} is the version-graph epoch the row was last evaluated against.
 *
 * <p>The constructor enforces these invariants:
 * <ul>
 *   <li>{@code pendingTarget} and {@code pendingSince} are present together or absent together;</li>
 *   <li>a locked (finalized) row never has a {@code pendingTarget};</li>
 *   <li>{@code pendingTarget} is never the base version itself;</li>
 *   <li>counters are non-negative.</li>
 * </ul>
 */
public record EngagementRecord(
        FirmId firmId,
        EngagementId engagementId,
        ProductId productId,
        Market market,
        TemplateVersion baseVersion,
        Set<TemplateVersion> declinedVersions,
        long stateSeq,
        Optional<TemplateVersion> pendingTarget,
        Optional<Instant> pendingSince,
        long graphEpoch,
        boolean locked,
        Source source) {

    public EngagementRecord {
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(baseVersion, "baseVersion");
        declinedVersions = Set.copyOf(Objects.requireNonNull(declinedVersions, "declinedVersions"));
        Objects.requireNonNull(pendingTarget, "pendingTarget");
        Objects.requireNonNull(pendingSince, "pendingSince");
        Objects.requireNonNull(source, "source");
        if (stateSeq < 0) {
            throw new IllegalArgumentException("stateSeq must be >= 0: " + stateSeq);
        }
        if (graphEpoch < 0) {
            throw new IllegalArgumentException("graphEpoch must be >= 0: " + graphEpoch);
        }
        if (pendingTarget.isPresent() != pendingSince.isPresent()) {
            throw new IllegalArgumentException("pendingTarget and pendingSince must be set together");
        }
        if (locked && pendingTarget.isPresent()) {
            throw new IllegalArgumentException("a locked (finalized) engagement can never have a pendingTarget");
        }
        if (pendingTarget.filter(baseVersion::equals).isPresent()) {
            throw new IllegalArgumentException("pendingTarget must differ from baseVersion");
        }
    }

    /** Returns true when a pending update is shown for this engagement. */
    public boolean isPending() {
        return pendingTarget.isPresent();
    }

    /**
     * Returns a copy with a freshly derived {@code pendingTarget} evaluated at {@code epoch}.
     * {@code pendingSince} is preserved when the target is unchanged, set to {@code now} when a
     * new target appears, and cleared when nothing is pending.
     */
    public EngagementRecord withDerivedPending(Optional<TemplateVersion> target, long epoch, Instant now) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(now, "now");
        Optional<Instant> since = target.isEmpty()
                ? Optional.empty()
                : target.equals(pendingTarget) ? pendingSince : Optional.of(now);
        return new EngagementRecord(firmId, engagementId, productId, market, baseVersion,
                declinedVersions, stateSeq, target, since, epoch, locked, source);
    }
}
