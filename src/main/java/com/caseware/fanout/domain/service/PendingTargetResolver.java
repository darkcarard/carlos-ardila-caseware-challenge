package com.caseware.fanout.domain.service;

import com.caseware.fanout.domain.error.NonRetryableDataException;
import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.TemplateVersion;
import com.caseware.fanout.domain.model.VersionGraph;
import com.caseware.fanout.domain.model.VersionNode;
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
 * Derives {@code pending_target}. This is a pure, deterministic function of
 * {@code (base_version, declined_versions, locked)} and a version-graph snapshot. It does no I/O
 * and does not read the clock.
 *
 * <p>Rules, from {@code architecture-invariants.md} section 2:
 * <ol>
 *   <li>A locked (finalized) row is never offered anything.</li>
 *   <li>Candidates are the descendants of {@code base_version} in the row's own
 *       {@code (product, market)} lineage. Reachability ignores status: a withdrawn v5 still links
 *       v4 to its published child v7.</li>
 *   <li>Only {@code PUBLISHED} candidates are eligible.</li>
 *   <li>Versions in {@code declined_versions} are excluded. Declining pins nothing about content,
 *       it only suppresses the re-prompt.</li>
 *   <li>The latest eligible candidate by {@code graphSeq} (publication order) wins, which is
 *       deterministic across branches. If there is none, the result is empty.</li>
 *   <li>Withdrawals need no special case. Re-running against the new epoch's graph falls back to
 *       the previous eligible version, or to empty, which retracts the notification.</li>
 * </ol>
 */
public final class PendingTargetResolver {

    private PendingTargetResolver() {}

    /**
     * Returns the version to offer, or empty when nothing is pending.
     *
     * @throws NonRetryableDataException if the graph belongs to another lineage or does not
     *     contain the row's base version (unknown lineage). This is a data error, not a race.
     */
    public static Optional<TemplateVersion> resolve(EngagementRecord record, VersionGraph graph) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(graph, "graph");
        if (record.locked()) {
            return Optional.empty();
        }
        if (!record.productId().equals(graph.productId()) || !record.market().equals(graph.market())) {
            throw new NonRetryableDataException("row lineage " + record.productId() + "/" + record.market()
                    + " evaluated against graph " + graph.productId() + "/" + graph.market());
        }
        if (!graph.contains(record.baseVersion())) {
            throw new NonRetryableDataException("base version " + record.baseVersion()
                    + " is unknown in lineage " + graph.productId() + "/" + graph.market());
        }
        return descendantsOf(graph, record.baseVersion()).stream()
                .map(version -> graph.node(version).orElseThrow())
                .filter(VersionNode::isPublished)
                .filter(node -> !record.declinedVersions().contains(node.version()))
                .max(Comparator.comparingLong(VersionNode::graphSeq))
                .map(VersionNode::version);
    }

    /**
     * Returns all strict descendants of {@code root}, whatever their status. The root itself is
     * excluded, so the base version can never be its own pending target.
     */
    public static Set<TemplateVersion> descendantsOf(VersionGraph graph, TemplateVersion root) {
        Map<TemplateVersion, List<TemplateVersion>> children = childrenIndex(graph);
        Set<TemplateVersion> visited = new HashSet<>();
        Deque<TemplateVersion> frontier = new ArrayDeque<>(children.getOrDefault(root, List.of()));
        while (!frontier.isEmpty()) {
            TemplateVersion current = frontier.poll();
            if (visited.add(current)) {
                frontier.addAll(children.getOrDefault(current, List.of()));
            }
        }
        return Set.copyOf(visited);
    }

    private static Map<TemplateVersion, List<TemplateVersion>> childrenIndex(VersionGraph graph) {
        Map<TemplateVersion, List<TemplateVersion>> children = new HashMap<>();
        for (VersionNode node : graph.nodes().values()) {
            for (TemplateVersion parent : node.parents()) {
                children.computeIfAbsent(parent, k -> new java.util.ArrayList<>()).add(node.version());
            }
        }
        return children;
    }
}
