package com.caseware.fanout.doubles;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.EngagementRecord;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.Market;
import com.caseware.fanout.domain.model.ProductId;
import com.caseware.fanout.domain.model.Source;
import com.caseware.fanout.domain.model.TemplateVersion;
import com.caseware.fanout.domain.model.VersionNode;
import com.caseware.fanout.domain.model.VersionStatus;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Small, readable fixture builders shared across test suites. */
public final class TestData {

    public static final ProductId PRODUCT = ProductId.of("audit-pro");
    public static final Market EU = Market.of("EU");
    public static final Market CA = Market.of("CA");
    public static final FirmId FIRM = FirmId.of("firm-acme");
    public static final EngagementId ENGAGEMENT = EngagementId.of("eng-0001");

    private TestData() {}

    public static TemplateVersion v(String label) {
        return TemplateVersion.of(label);
    }

    public static Set<TemplateVersion> versions(String... labels) {
        return Arrays.stream(labels).map(TemplateVersion::of).collect(Collectors.toUnmodifiableSet());
    }

    /** A published EU node with the given parents. {@code graphSeq} is the publish order. */
    public static VersionNode published(String version, long graphSeq, String... parents) {
        return node(EU, version, graphSeq, VersionStatus.PUBLISHED, parents);
    }

    public static VersionNode node(Market market, String version, long graphSeq, VersionStatus status, String... parents) {
        return new VersionNode(v(version), PRODUCT, market, versions(parents), status, graphSeq);
    }

    /** An unlocked, not-pending EU row on {@code base} with nothing declined. */
    public static EngagementRecord row(String base, long stateSeq) {
        return new EngagementRecord(FIRM, ENGAGEMENT, PRODUCT, EU, v(base), Set.of(), stateSeq,
                Optional.empty(), Optional.empty(), 0, false, Source.HOOK);
    }
}
