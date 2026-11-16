package com.caseware.fanout.doubles;

import com.caseware.fanout.domain.error.NonRetryableDataException;
import com.caseware.fanout.domain.model.Market;
import com.caseware.fanout.domain.model.ProductId;
import com.caseware.fanout.domain.model.VersionGraph;
import com.caseware.fanout.domain.port.VersionGraphStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Regional graph projection. Like the real one, it applies graph changes in epoch order only. */
public final class InMemoryVersionGraphStore implements VersionGraphStore {

    private record Lineage(ProductId productId, Market market) {}

    private final Map<Lineage, VersionGraph> graphs = new ConcurrentHashMap<>();

    public InMemoryVersionGraphStore(VersionGraph... initial) {
        for (VersionGraph g : initial) {
            apply(g);
        }
    }

    /** Applies a graph change. An older or equal epoch is ignored, like an out-of-order event. */
    public void apply(VersionGraph graph) {
        graphs.merge(new Lineage(graph.productId(), graph.market()), graph,
                (current, incoming) -> incoming.epoch() > current.epoch() ? incoming : current);
    }

    @Override
    public VersionGraph snapshot(ProductId productId, Market market) {
        VersionGraph graph = graphs.get(new Lineage(productId, market));
        if (graph == null) {
            throw new NonRetryableDataException("unknown lineage " + productId + "/" + market);
        }
        return graph;
    }
}
