package com.caseware.fanout.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class IdentifiersTest {

    @Test
    void rejectsNullAndBlankValues() {
        assertThatNullPointerException().isThrownBy(() -> FirmId.of(null));
        assertThatIllegalArgumentException().isThrownBy(() -> EngagementId.of("  "));
        assertThatIllegalArgumentException().isThrownBy(() -> TemplateVersion.of(""));
    }

    @Test
    void sameValueInDifferentIdTypesIsNotEqual() {
        assertThat((Object) FirmId.of("x")).isNotEqualTo(ProductId.of("x"));
        assertThat(FirmId.of("x")).isEqualTo(FirmId.of("x"));
    }
}
