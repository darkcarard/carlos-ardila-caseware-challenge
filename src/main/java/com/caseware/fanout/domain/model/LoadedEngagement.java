package com.caseware.fanout.domain.model;

import java.util.Objects;
import java.util.Set;

/**
 * Ground truth returned by the ~1 minute downstream engagement load (backfill and reconciliation
 * only). {@code stateSeq} is the engagement's state sequence at load time, so the registry write
 * that follows can be conditional and never overwrite a newer hook. REGIONAL ONLY.
 */
public record LoadedEngagement(
        FirmId firmId,
        EngagementId engagementId,
        ProductId productId,
        Market market,
        TemplateVersion baseVersion,
        Set<TemplateVersion> declinedVersions,
        long stateSeq,
        boolean locked) {

    public LoadedEngagement {
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(baseVersion, "baseVersion");
        declinedVersions = Set.copyOf(Objects.requireNonNull(declinedVersions, "declinedVersions"));
        if (stateSeq < 0) {
            throw new IllegalArgumentException("stateSeq must be >= 0: " + stateSeq);
        }
    }
}
