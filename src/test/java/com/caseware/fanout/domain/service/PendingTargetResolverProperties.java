package com.caseware.fanout.domain.service;

import static com.caseware.fanout.doubles.TestData.ENGAGEMENT;
import static com.caseware.fanout.doubles.TestData.EU;
import static com.caseware.fanout.doubles.TestData.FIRM;
import static com.caseware.fanout.doubles.TestData.PRODUCT;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.Source;
import com.caseware.fanout.domain.model.TemplateVersion;
import com.caseware.fanout.domain.model.VersionGraph;
import com.caseware.fanout.domain.model.VersionNode;
import com.caseware.fanout.domain.model.VersionStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

/**
 * Property tests of {@code pending_target} over generated version DAGs, with branches, merges,
 * withdrawals and declines.
 *
 * <p>The oracle is deliberately a different algorithm from the resolver. It uses the
 * ancestor closure ("is base an ancestor of d?") instead of a BFS over children, so a shared bug
 * cannot make both agree.
 */
class PendingTargetResolverProperties {

    /** A generated DAG plus one row evaluated against it. Node i has graphSeq i (topological order). */
    record Scenario(VersionGraph graph, EngagementRecord row) {

        @Override
        public String toString() {
            String nodes = graph.nodes().values().stream()
                    .sorted(Comparator.comparingLong(VersionNode::graphSeq))
                    .map(n -> n.version() + (n.isPublished() ? "" : "(W)") + "<-" + n.parents())
                    .collect(Collectors.joining(", "));
            return "graph[" + nodes + "] base=" + row.baseVersion() + " declined=" + row.declinedVersions()
                    + " locked=" + row.locked();
        }
    }

    // ---- properties ----

    @Property(tries = 500)
    void agreesWithTheIndependentOracle(@ForAll("scenarios") Scenario s) {
        Optional<TemplateVersion> resolved = PendingTargetResolver.resolve(s.row(), s.graph());

        // Guard against a degenerate generator: the run report must show a mix of sizes and outcomes.
        int size = s.graph().nodes().size();
        Statistics.label("graph size").collect(size <= 3 ? "1-3" : size <= 8 ? "4-8" : "9-14");
        Statistics.label("outcome").collect(resolved.isPresent() ? "pending" : "not pending");
        Statistics.label("has withdrawn").collect(s.graph().nodes().values().stream().anyMatch(n -> !n.isPublished()));
        Statistics.label("has declined").collect(!s.row().declinedVersions().isEmpty());

        assertThat(resolved).isEqualTo(oracle(s.graph(), s.row()));
    }

    @Property(tries = 500)
    void resultIsAlwaysAPublishedUndeclinedStrictDescendant(@ForAll("scenarios") Scenario s) {
        PendingTargetResolver.resolve(s.row(), s.graph()).ifPresent(target -> {
            VersionNode node = s.graph().node(target).orElseThrow();
            assertThat(node.status()).isEqualTo(VersionStatus.PUBLISHED);
            assertThat(s.row().declinedVersions()).doesNotContain(target);
            assertThat(target).isNotEqualTo(s.row().baseVersion());
            assertThat(ancestorsOf(s.graph(), target)).contains(s.row().baseVersion());
        });
    }

    @Property(tries = 300)
    void lockedRowsAreNeverPending(@ForAll("scenarios") Scenario s) {
        EngagementRecord locked = copy(s.row(), s.row().declinedVersions(), true);
        assertThat(PendingTargetResolver.resolve(locked, s.graph())).isEmpty();
    }

    @Property(tries = 300)
    void isDeterministic(@ForAll("scenarios") Scenario s) {
        assertThat(PendingTargetResolver.resolve(s.row(), s.graph()))
                .isEqualTo(PendingTargetResolver.resolve(s.row(), s.graph()));
    }

    @Property(tries = 500)
    void withdrawingTheTargetFallsBackToAnOlderEligibleVersionOrClears(@ForAll("scenarios") Scenario s) {
        Optional<TemplateVersion> before = PendingTargetResolver.resolve(s.row(), s.graph());
        before.ifPresent(target -> {
            VersionGraph withdrawn = s.graph().withWithdrawn(target);
            Optional<TemplateVersion> after = PendingTargetResolver.resolve(s.row(), withdrawn);

            assertThat(after).isNotEqualTo(before);
            after.ifPresent(fallback -> {
                assertThat(withdrawn.node(fallback).orElseThrow().isPublished()).isTrue();
                assertThat(seq(s.graph(), fallback)).isLessThan(seq(s.graph(), target));
            });
        });
    }

    @Property(tries = 500)
    void decliningTheTargetNeverReOffersIt(@ForAll("scenarios") Scenario s) {
        PendingTargetResolver.resolve(s.row(), s.graph()).ifPresent(target -> {
            Set<TemplateVersion> declined = new HashSet<>(s.row().declinedVersions());
            declined.add(target);
            Optional<TemplateVersion> after = PendingTargetResolver.resolve(copy(s.row(), declined, false), s.graph());
            assertThat(after).isNotEqualTo(Optional.of(target));
        });
    }

    @Property(tries = 500)
    void aNewPublishedDescendantBecomesTheTarget(@ForAll("scenarios") Scenario s) {
        // Publish a new leaf under the current target (or under the base when nothing is pending).
        TemplateVersion parent = PendingTargetResolver.resolve(s.row(), s.graph()).orElse(s.row().baseVersion());
        long nextSeq = s.graph().nodes().size();
        TemplateVersion leaf = TemplateVersion.of("n" + nextSeq);
        VersionGraph published = s.graph().withPublished(
                new VersionNode(leaf, PRODUCT, EU, Set.of(parent), VersionStatus.PUBLISHED, nextSeq));

        assertThat(PendingTargetResolver.resolve(s.row(), published)).contains(leaf);
    }

    // ---- oracle (independent of the resolver's algorithm) ----

    private static Optional<TemplateVersion> oracle(VersionGraph graph, EngagementRecord row) {
        if (row.locked()) {
            return Optional.empty();
        }
        return graph.nodes().values().stream()
                .filter(VersionNode::isPublished)
                .filter(n -> !row.declinedVersions().contains(n.version()))
                .filter(n -> ancestorsOf(graph, n.version()).contains(row.baseVersion()))
                .max(Comparator.comparingLong(VersionNode::graphSeq))
                .map(VersionNode::version);
    }

    /** Strict ancestors via fix-point iteration over parent edges. */
    private static Set<TemplateVersion> ancestorsOf(VersionGraph graph, TemplateVersion version) {
        Set<TemplateVersion> ancestors = new HashSet<>(graph.node(version).orElseThrow().parents());
        boolean grew = true;
        while (grew) {
            Set<TemplateVersion> next = new HashSet<>(ancestors);
            for (TemplateVersion a : ancestors) {
                next.addAll(graph.node(a).orElseThrow().parents());
            }
            grew = next.size() > ancestors.size();
            ancestors = next;
        }
        return ancestors;
    }

    private static long seq(VersionGraph graph, TemplateVersion version) {
        return graph.node(version).orElseThrow().graphSeq();
    }

    // ---- generators ----

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Arbitraries.integers().between(1, 14).flatMap(this::scenarioOfSize);
    }

    private Arbitrary<Scenario> scenarioOfSize(int size) {
        // Node 0 is the root. Every later node has 1-2 parents among earlier nodes, giving branches and merges.
        List<Arbitrary<Set<Integer>>> parentChoices = new ArrayList<>();
        for (int i = 1; i < size; i++) {
            parentChoices.add(Arbitraries.integers().between(0, i - 1).set().ofMinSize(1).ofMaxSize(Math.min(2, i)));
        }
        Arbitrary<List<Set<Integer>>> parents = parentChoices.isEmpty()
                ? Arbitraries.just(List.of())
                : Combinators.combine(parentChoices).as(List::copyOf);
        Arbitrary<List<Boolean>> withdrawn = Arbitraries.frequency(
                        net.jqwik.api.Tuple.of(4, false), net.jqwik.api.Tuple.of(1, true))
                .list().ofSize(size);
        Arbitrary<Integer> base = Arbitraries.integers().between(0, size - 1);
        Arbitrary<Set<Integer>> declined = Arbitraries.integers().between(0, size - 1).set().ofMaxSize(size);

        return Combinators.combine(parents, withdrawn, base, declined).as((p, w, b, d) -> {
            List<VersionNode> nodes = IntStream.range(0, size).mapToObj(i -> new VersionNode(
                    label(i), PRODUCT, EU,
                    i == 0 ? Set.of() : p.get(i - 1).stream().map(PendingTargetResolverProperties::label).collect(Collectors.toSet()),
                    w.get(i) ? VersionStatus.WITHDRAWN : VersionStatus.PUBLISHED,
                    i)).toList();
            Set<TemplateVersion> declinedVersions = d.stream()
                    .filter(i -> i != b)
                    .map(PendingTargetResolverProperties::label)
                    .collect(Collectors.toSet());
            EngagementRecord row = new EngagementRecord(FIRM, ENGAGEMENT, PRODUCT, EU, label(b), declinedVersions, 1,
                    Optional.empty(), Optional.empty(), 0, false, Source.HOOK);
            return new Scenario(VersionGraph.of(PRODUCT, EU, 1, nodes), row);
        });
    }

    private static TemplateVersion label(int i) {
        return TemplateVersion.of("v" + i);
    }

    private static EngagementRecord copy(EngagementRecord r, Set<TemplateVersion> declined, boolean locked) {
        return new EngagementRecord(r.firmId(), r.engagementId(), r.productId(), r.market(), r.baseVersion(), declined,
                r.stateSeq(), Optional.empty(), Optional.empty(), r.graphEpoch(), locked, r.source());
    }
}
