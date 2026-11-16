package com.caseware.fanout.domain.model;

import static com.caseware.fanout.doubles.TestData.CA;
import static com.caseware.fanout.doubles.TestData.EU;
import static com.caseware.fanout.doubles.TestData.PRODUCT;
import static com.caseware.fanout.doubles.TestData.node;
import static com.caseware.fanout.doubles.TestData.published;
import static com.caseware.fanout.doubles.TestData.v;
import static com.caseware.fanout.doubles.TestData.versions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.domain.error.NonRetryableDataException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VersionGraphTest {

    @Test
    void acceptsABranchingDag() {
        VersionGraph graph = VersionGraph.of(PRODUCT, EU, 1, List.of(
                published("v4", 4),
                published("v5", 5, "v4"),
                published("v6", 6, "v5"),
                published("v7", 7, "v5")));

        assertThat(graph.childrenOf(v("v5"))).isEqualTo(versions("v6", "v7"));
        assertThat(graph.childrenOf(v("v7"))).isEmpty();
    }

    @Test
    void rejectsACycleAsNonRetryableData() {
        assertThatThrownBy(() -> VersionGraph.of(PRODUCT, EU, 1, List.of(
                        published("v1", 1, "v3"),
                        published("v2", 2, "v1"),
                        published("v3", 3, "v2"))))
                .isInstanceOf(NonRetryableDataException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void cycleErrorNamesOnlyTheVersionsOnTheCycle() {
        assertThatThrownBy(() -> VersionGraph.of(PRODUCT, EU, 1, List.of(
                        published("v1", 1),
                        published("v2", 2, "v1", "v3"),
                        published("v3", 3, "v2"))))
                .hasMessageEndingWith("cycle through [v2, v3]"); // v1 is a healthy root, so it is not blamed
    }

    @Test
    void rejectsANodeFromAnotherProductInTheSameMarket() {
        VersionNode foreign = new VersionNode(v("v2"), ProductId.of("other-product"), EU, versions("v1"),
                VersionStatus.PUBLISHED, 2);
        assertThatThrownBy(() -> VersionGraph.of(PRODUCT, EU, 1, List.of(published("v1", 1), foreign)))
                .isInstanceOf(NonRetryableDataException.class)
                .hasMessageContaining("belongs to");
    }

    @Test
    void publishingAnExistingVersionAgainIsNonRetryable() {
        VersionGraph graph = VersionGraph.of(PRODUCT, EU, 1, List.of(published("v1", 1)));
        assertThatThrownBy(() -> graph.withPublished(published("v1", 2)))
                .isInstanceOf(NonRetryableDataException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void rejectsADanglingParent() {
        assertThatThrownBy(() -> VersionGraph.of(PRODUCT, EU, 1, List.of(published("v5", 5, "v4"))))
                .isInstanceOf(NonRetryableDataException.class)
                .hasMessageContaining("unknown parent");
    }

    @Test
    void rejectsANodeFromAnotherMarketLineage() {
        assertThatThrownBy(() -> VersionGraph.of(PRODUCT, EU, 1, List.of(
                        published("v1", 1),
                        node(CA, "v2", 2, VersionStatus.PUBLISHED, "v1"))))
                .isInstanceOf(NonRetryableDataException.class)
                .hasMessageContaining("belongs to");
    }

    @Test
    void rejectsDuplicateGraphSeqBecauseLatestMustBeDeterministic() {
        assertThatThrownBy(() -> VersionGraph.of(PRODUCT, EU, 1, List.of(published("v1", 1), published("v2", 1, "v1"))))
                .isInstanceOf(NonRetryableDataException.class)
                .hasMessageContaining("graphSeq");
    }

    @Test
    void rejectsDuplicateVersionsAndMismatchedKeys() {
        assertThatThrownBy(() -> VersionGraph.of(PRODUCT, EU, 1, List.of(published("v1", 1), published("v1", 2))))
                .isInstanceOf(NonRetryableDataException.class);
        assertThatThrownBy(() -> new VersionGraph(PRODUCT, EU, 1, Map.of(v("vX"), published("v1", 1))))
                .isInstanceOf(NonRetryableDataException.class);
    }

    @Test
    void rejectsSelfParentAndNegativeEpoch() {
        assertThatIllegalArgumentException().isThrownBy(() -> published("v1", 1, "v1"));
        assertThatIllegalArgumentException().isThrownBy(() -> VersionGraph.of(PRODUCT, EU, -1, List.of()));
    }

    @Test
    void publishAndWithdrawBumpTheEpochAndLeaveTheOriginalUntouched() {
        VersionGraph g1 = VersionGraph.of(PRODUCT, EU, 1, List.of(published("v4", 4), published("v5", 5, "v4")));

        VersionGraph g2 = g1.withPublished(published("v7", 7, "v5"));
        VersionGraph g3 = g2.withWithdrawn(v("v7"));

        assertThat(g2.epoch()).isEqualTo(2);
        assertThat(g3.epoch()).isEqualTo(3);
        assertThat(g3.node(v("v7")).orElseThrow().status()).isEqualTo(VersionStatus.WITHDRAWN);
        assertThat(g2.node(v("v7")).orElseThrow().isPublished()).isTrue();
        assertThat(g1.contains(v("v7"))).isFalse();
    }

    @Test
    void withdrawingAnUnknownVersionIsNonRetryable() {
        VersionGraph g = VersionGraph.of(PRODUCT, EU, 1, List.of(published("v4", 4)));
        assertThatThrownBy(() -> g.withWithdrawn(v("v9"))).isInstanceOf(NonRetryableDataException.class);
    }
}
