package com.caseware.fanout.domain.model;

import com.caseware.fanout.domain.error.NonRetryableDataException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable snapshot of one {@code (product, market)} lineage of the regional version graph, at a
 * given {@code epoch}. The epoch increases on every publish or withdrawal applied to the region.
 *
 * <p>Graph corruption is a permanent data error, not a programming error, so the constructor
 * rejects it with {@link NonRetryableDataException}. That includes cycles, dangling parents,
 * nodes from another lineage and duplicate {@code graphSeq} values.
 */
public record VersionGraph(ProductId productId, Market market, long epoch, Map<TemplateVersion, VersionNode> nodes) {

    public VersionGraph {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(market, "market");
        nodes = Map.copyOf(Objects.requireNonNull(nodes, "nodes"));
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must be >= 0: " + epoch);
        }
        validate(productId, market, nodes);
    }

    /** Builds a graph from a node list keyed by version. */
    public static VersionGraph of(ProductId productId, Market market, long epoch, List<VersionNode> nodes) {
        Map<TemplateVersion, VersionNode> byVersion = new HashMap<>();
        for (VersionNode node : nodes) {
            if (byVersion.put(node.version(), node) != null) {
                throw new NonRetryableDataException("duplicate version " + node.version() + " in " + productId + "/" + market);
            }
        }
        return new VersionGraph(productId, market, epoch, byVersion);
    }

    public Optional<VersionNode> node(TemplateVersion version) {
        return Optional.ofNullable(nodes.get(version));
    }

    public boolean contains(TemplateVersion version) {
        return nodes.containsKey(version);
    }

    /** Returns the direct children of {@code version}, i.e. nodes that list it as a parent. */
    public Set<TemplateVersion> childrenOf(TemplateVersion version) {
        Set<TemplateVersion> children = new HashSet<>();
        for (VersionNode node : nodes.values()) {
            if (node.parents().contains(version)) {
                children.add(node.version());
            }
        }
        return Set.copyOf(children);
    }

    /** Returns a new graph with {@code node} added at {@code epoch + 1} (a publish). */
    public VersionGraph withPublished(VersionNode node) {
        Map<TemplateVersion, VersionNode> next = new HashMap<>(nodes);
        if (next.putIfAbsent(node.version(), node) != null) {
            throw new NonRetryableDataException("version " + node.version() + " already exists");
        }
        return new VersionGraph(productId, market, epoch + 1, next);
    }

    /** Returns a new graph with {@code version} withdrawn at {@code epoch + 1}. */
    public VersionGraph withWithdrawn(TemplateVersion version) {
        VersionNode existing = node(version)
                .orElseThrow(() -> new NonRetryableDataException("cannot withdraw unknown version " + version));
        Map<TemplateVersion, VersionNode> next = new HashMap<>(nodes);
        next.put(version, existing.withdrawn());
        return new VersionGraph(productId, market, epoch + 1, next);
    }

    private static void validate(ProductId productId, Market market, Map<TemplateVersion, VersionNode> nodes) {
        Set<Long> seenSeq = new HashSet<>();
        for (Map.Entry<TemplateVersion, VersionNode> entry : nodes.entrySet()) {
            VersionNode node = entry.getValue();
            if (!entry.getKey().equals(node.version())) {
                throw new NonRetryableDataException("graph key " + entry.getKey() + " does not match node " + node.version());
            }
            if (!node.productId().equals(productId) || !node.market().equals(market)) {
                throw new NonRetryableDataException("node " + node.version() + " belongs to "
                        + node.productId() + "/" + node.market() + ", not " + productId + "/" + market);
            }
            if (!seenSeq.add(node.graphSeq())) {
                throw new NonRetryableDataException("duplicate graphSeq " + node.graphSeq() + " in " + productId + "/" + market);
            }
            for (TemplateVersion parent : node.parents()) {
                if (!nodes.containsKey(parent)) {
                    throw new NonRetryableDataException("node " + node.version() + " has unknown parent " + parent);
                }
            }
        }
        requireAcyclic(nodes);
    }

    /** Kahn's algorithm: if not every node can be topologically ordered, there is a cycle. */
    private static void requireAcyclic(Map<TemplateVersion, VersionNode> nodes) {
        Map<TemplateVersion, Integer> pendingParents = new HashMap<>();
        Map<TemplateVersion, List<TemplateVersion>> children = new HashMap<>();
        for (VersionNode node : nodes.values()) {
            pendingParents.put(node.version(), node.parents().size());
            for (TemplateVersion parent : node.parents()) {
                children.computeIfAbsent(parent, k -> new java.util.ArrayList<>()).add(node.version());
            }
        }
        Deque<TemplateVersion> ready = new ArrayDeque<>();
        pendingParents.forEach((version, count) -> {
            if (count == 0) {
                ready.add(version);
            }
        });
        int ordered = 0;
        while (!ready.isEmpty()) {
            TemplateVersion current = ready.poll();
            ordered++;
            for (TemplateVersion child : children.getOrDefault(current, List.of())) {
                if (pendingParents.merge(child, -1, Integer::sum) == 0) {
                    ready.add(child);
                }
            }
        }
        if (ordered != nodes.size()) {
            List<TemplateVersion> cyclic = pendingParents.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .sorted(Comparator.comparing(TemplateVersion::value))
                    .toList();
            throw new NonRetryableDataException("version graph contains a cycle through " + cyclic);
        }
    }
}
