package com.caseware.fanout.domain.service;

import static com.caseware.fanout.doubles.TestData.CA;
import static com.caseware.fanout.doubles.TestData.ENGAGEMENT;
import static com.caseware.fanout.doubles.TestData.EU;
import static com.caseware.fanout.doubles.TestData.FIRM;
import static com.caseware.fanout.doubles.TestData.PRODUCT;
import static com.caseware.fanout.doubles.TestData.node;
import static com.caseware.fanout.doubles.TestData.published;
import static com.caseware.fanout.doubles.TestData.v;
import static com.caseware.fanout.doubles.TestData.versions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.domain.error.NonRetryableDataException;
import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.Market;
import com.caseware.fanout.domain.model.ProductId;
import com.caseware.fanout.domain.model.Source;
import com.caseware.fanout.domain.model.TemplateVersion;
import com.caseware.fanout.domain.model.VersionGraph;
import com.caseware.fanout.domain.model.VersionNode;
import com.caseware.fanout.domain.model.VersionStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Fixed edge cases from {@code testing-and-ai-audit.md} section 1.1 and design doc Appendix F. */
class PendingTargetResolverTest {

    /** v4 -> v5 -> v7 in EU, as in the design doc's section 2 diagram (v6 omitted). */
    private static final VersionGraph V4_V5_V7 = graph(EU, 1,
            published("v4", 4),
            published("v5", 5, "v4"),
            published("v7", 7, "v5"));

    @Test
    void linearUpgradeOffersTheNextVersion() {
        VersionGraph g = graph(EU, 1, published("v1", 1), published("v2", 2, "v1"));
        assertThat(resolve(row("v1"), g)).contains(v("v2"));
    }

    @Test
    @DisplayName("several accumulated updates collapse into one target: v4 -> v7, cumulative")
    void multiHopAccumulationOffersTheLatestDescendant() {
        assertThat(resolve(row("v4"), V4_V5_V7)).contains(v("v7"));
    }

    @Test
    void baseAtTheLatestVersionIsNotPending() {
        assertThat(resolve(row("v7"), V4_V5_V7)).isEmpty();
    }

    @Nested
    class DeclinedPinning {

        @Test
        @DisplayName("declining v5 keeps base v4; publishing v7 (which contains v5) offers v7")
        void declinedVersionIsSkippedButItsDescendantIsOffered() {
            assertThat(resolve(row("v4", "v5"), V4_V5_V7)).contains(v("v7"));
        }

        @Test
        void declinedLatestWithNothingNewerIsNotPending() {
            VersionGraph g = graph(EU, 1, published("v4", 4), published("v5", 5, "v4"));
            assertThat(resolve(row("v4", "v5"), g)).isEmpty();
        }

        @Test
        void decliningTheLatestFallsBackToAnOlderUndeclinedDescendant() {
            assertThat(resolve(row("v4", "v7"), V4_V5_V7)).contains(v("v5"));
        }
    }

    @Nested
    class Withdrawal {

        @Test
        @DisplayName("v7 withdrawn: falls back to v5 when v5 was not declined")
        void withdrawnTargetFallsBackToPreviousPublishedDescendant() {
            VersionGraph afterWithdraw = V4_V5_V7.withWithdrawn(v("v7"));
            assertThat(resolve(row("v4"), afterWithdraw)).contains(v("v5"));
        }

        @Test
        @DisplayName("v7 withdrawn and v5 declined: nothing pending, so the notification is retracted")
        void withdrawnTargetWithDeclinedFallbackClearsPending() {
            VersionGraph afterWithdraw = V4_V5_V7.withWithdrawn(v("v7"));
            assertThat(resolve(row("v4", "v5"), afterWithdraw)).isEmpty();
        }

        @Test
        @DisplayName("a withdrawn intermediate (v5) still links v4 to its published child v7")
        void reachabilityIgnoresStatus() {
            VersionGraph g = V4_V5_V7.withWithdrawn(v("v5"));
            assertThat(resolve(row("v4"), g)).contains(v("v7"));
        }

        @Test
        @DisplayName("design doc diagram: v6 withdrawn is never offered even though it is newer than v5")
        void withdrawnVersionIsNeverOffered() {
            VersionGraph g = graph(EU, 1,
                    published("v4", 4),
                    published("v5", 5, "v4"),
                    node(EU, "v6", 6, VersionStatus.WITHDRAWN, "v5"));
            assertThat(resolve(row("v4"), g)).contains(v("v5"));
        }
    }

    @Nested
    class Branching {

        /** v4 -> v5 -> v6 (main) and v4 -> v5b (sibling branch, published later). */
        private final VersionGraph branches = graph(EU, 1,
                published("v4", 4),
                published("v5", 5, "v4"),
                published("v6", 6, "v5"),
                published("v5b", 8, "v4"));

        @Test
        void siblingBranchIsNotOfferedToARowOnTheOtherBranch() {
            assertThat(resolve(row("v5"), branches)).contains(v("v6"));
        }

        @Test
        void latestAcrossBranchesIsChosenByPublicationOrder() {
            assertThat(resolve(row("v4"), branches)).contains(v("v5b"));
        }

        @Test
        void mergeNodeIsReachableFromEitherParent() {
            VersionGraph merged = branches.withPublished(published("v9", 9, "v6", "v5b"));
            assertThat(resolve(row("v5"), merged)).contains(v("v9"));
            assertThat(resolve(row("v5b"), merged)).contains(v("v9"));
        }
    }

    @Nested
    class MarketIsolation {

        /** CA reuses the same labels with different content and publication history. */
        private final VersionGraph ca = graph(CA, 1,
                node(CA, "v4", 4, VersionStatus.PUBLISHED),
                node(CA, "v5", 5, VersionStatus.WITHDRAWN, "v4"));

        @Test
        void euPublicationIsNotOfferedToACaEngagement() {
            assertThat(resolve(row(CA, "v4"), ca)).isEmpty();
            assertThat(resolve(row(EU, "v4"), V4_V5_V7)).contains(v("v7"));
        }

        @Test
        void evaluatingARowAgainstAnotherMarketsGraphIsADataError() {
            assertThatThrownBy(() -> resolve(row(CA, "v4"), V4_V5_V7))
                    .isInstanceOf(NonRetryableDataException.class)
                    .hasMessageContaining("CA");
        }

        @Test
        void evaluatingARowAgainstAnotherProductsGraphIsADataError() {
            VersionGraph otherProduct = new VersionGraph(ProductId.of("other"), EU, 1, java.util.Map.of(
                    v("v4"), new VersionNode(v("v4"), ProductId.of("other"), EU, java.util.Set.of(), VersionStatus.PUBLISHED, 4)));
            assertThatThrownBy(() -> resolve(row("v4"), otherProduct)).isInstanceOf(NonRetryableDataException.class);
        }
    }

    @Test
    void lockedEngagementIsNeverOfferedAnUpdate() {
        EngagementRecord locked = new EngagementRecord(FIRM, ENGAGEMENT, PRODUCT, EU, v("v4"), java.util.Set.of(), 3,
                Optional.empty(), Optional.empty(), 0, true, Source.HOOK);
        assertThat(resolve(locked, V4_V5_V7)).isEmpty();
    }

    @Test
    void unknownBaseVersionIsADataErrorNotAGuess() {
        assertThatThrownBy(() -> resolve(row("v3"), V4_V5_V7))
                .isInstanceOf(NonRetryableDataException.class)
                .hasMessageContaining("v3");
    }

    @Test
    void descendantsExcludeTheRootAndIncludeEveryStatus() {
        VersionGraph g = V4_V5_V7.withWithdrawn(v("v5"));
        assertThat(PendingTargetResolver.descendantsOf(g, v("v4"))).isEqualTo(versions("v5", "v7"));
        assertThat(PendingTargetResolver.descendantsOf(g, v("v7"))).isEmpty();
    }

    private static Optional<TemplateVersion> resolve(EngagementRecord row, VersionGraph graph) {
        return PendingTargetResolver.resolve(row, graph);
    }

    private static EngagementRecord row(String base, String... declined) {
        return row(EU, base, declined);
    }

    private static EngagementRecord row(Market market, String base, String... declined) {
        return new EngagementRecord(FIRM, ENGAGEMENT, PRODUCT, market, v(base), versions(declined), 1,
                Optional.empty(), Optional.empty(), 0, false, Source.HOOK);
    }

    private static VersionGraph graph(Market market, long epoch, VersionNode... nodes) {
        return VersionGraph.of(PRODUCT, market, epoch, List.of(nodes));
    }
}
