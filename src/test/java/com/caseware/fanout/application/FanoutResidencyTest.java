package com.caseware.fanout.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.SummaryPair;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Runtime half of the residency guardrail (the structural half is
 * {@code com.caseware.fanout.domain.ResidencyBoundaryTest}): what actually reaches the
 * cross-plane publisher contains no firm or engagement identifier, and is de-duplicated.
 */
class FanoutResidencyTest {

    private final FanoutFixture f = new FanoutFixture();

    @Test
    void globalPayloadsCarryNoFirmOrEngagementIdentifiers() {
        List<EngagementId> ids = f.seedRows(500, "v4");

        f.processor().process(f.single("pub-v7", 2, ids));

        List<String> payloads = f.summaries.serializedPayloads();
        assertThat(payloads).isNotEmpty();
        assertThat(payloads).allSatisfy(payload -> {
            assertThat(payload).doesNotContain(com.caseware.fanout.doubles.TestData.FIRM.value());
            ids.forEach(id -> assertThat(payload).doesNotContain(id.value()));
        });
    }

    @Test
    void fiveHundredEngagementsCollapseToOnePairPerLocale() {
        List<EngagementId> ids = f.seedRows(500, "v4");

        f.processor().process(f.single("pub-v7", 2, ids));

        assertThat(f.summaries.allPairs()).hasSize(FanoutFixture.LOCALES.size());
        assertThat(f.summaries.allPairs()).extracting(SummaryPair::toVersion).allMatch(v -> v.value().equals("v7"));
    }
}
