package com.caseware.fanout.domain.port;

import com.caseware.fanout.domain.model.Market;
import com.caseware.fanout.domain.model.ProductId;
import com.caseware.fanout.domain.model.VersionGraph;

/**
 * Regional projection of the version graph: template metadata only, no content and no firm data.
 * Fed in order by {@code published}/{@code withdrawn} events using the per-product
 * {@code graph_seq}.
 */
public interface VersionGraphStore {

    /**
     * Returns the latest snapshot of the {@code (productId, market)} lineage together with its
     * epoch.
     *
     * @throws com.caseware.fanout.domain.error.NonRetryableDataException if the lineage is unknown
     * @throws com.caseware.fanout.domain.error.TransientInfrastructureException on store outage
     */
    VersionGraph snapshot(ProductId productId, Market market);
}
