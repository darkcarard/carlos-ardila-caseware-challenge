package com.caseware.fanout.domain.model;

import static com.caseware.fanout.doubles.TestData.EU;
import static com.caseware.fanout.doubles.TestData.PRODUCT;
import static com.caseware.fanout.doubles.TestData.v;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SummaryPairTest {

    private static final LocaleCode EN = LocaleCode.of("en-CA");

    @Test
    void rejectsAPairFromAVersionToItself() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SummaryPair(PRODUCT, EU, v("v4"), v("v4"), EN));
    }

    @Test
    void rejectsMissingFields() {
        assertThatNullPointerException().isThrownBy(() -> new SummaryPair(PRODUCT, EU, v("v4"), v("v7"), null));
        assertThatNullPointerException().isThrownBy(() -> new SummaryPair(null, EU, v("v4"), v("v7"), EN));
    }

    @Test
    void manyEngagementsCollapseIntoOnePairByValueEquality() {
        Set<SummaryPair> pairs = Set.of(new SummaryPair(PRODUCT, EU, v("v4"), v("v7"), EN));
        assertThat(pairs).contains(new SummaryPair(PRODUCT, EU, v("v4"), v("v7"), EN));
        assertThat(new SummaryPair(PRODUCT, EU, v("v4"), v("v7"), EN))
                .isNotEqualTo(new SummaryPair(PRODUCT, EU, v("v4"), v("v7"), LocaleCode.of("fr-CA")));
    }
}
