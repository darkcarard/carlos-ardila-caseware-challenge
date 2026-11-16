package com.caseware.fanout.domain.model;

import java.util.Objects;
import java.util.Set;

/**
 * One version in the regional version-graph projection: {@code (product, market, version,
 * parents, status)}. It holds no template content and no firm data.
 *
 * @param graphSeq per-product publication sequence from the template DB. It gives a total order
 *     used to pick the "latest" eligible descendant deterministically across branches.
 */
public record VersionNode(
        TemplateVersion version,
        ProductId productId,
        Market market,
        Set<TemplateVersion> parents,
        VersionStatus status,
        long graphSeq) {

    public VersionNode {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(market, "market");
        parents = Set.copyOf(Objects.requireNonNull(parents, "parents"));
        Objects.requireNonNull(status, "status");
        if (parents.contains(version)) {
            throw new IllegalArgumentException("version " + version + " cannot be its own parent");
        }
        if (graphSeq < 0) {
            throw new IllegalArgumentException("graphSeq must be >= 0: " + graphSeq);
        }
    }

    public boolean isPublished() {
        return status == VersionStatus.PUBLISHED;
    }

    /** Returns a copy with {@code status = WITHDRAWN}. */
    public VersionNode withdrawn() {
        return new VersionNode(version, productId, market, parents, VersionStatus.WITHDRAWN, graphSeq);
    }
}
