package com.caseware.fanout.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class IdentifiersTest {

    @Test
    void rejectsNullAndBlankValues() {
        assertThatNullPointerException().isThrownBy(() -> FirmId.of(null));
        assertThatIllegalArgumentException().isThrownBy(() -> EngagementId.of("  "));
        assertThatIllegalArgumentException().isThrownBy(() -> TemplateVersion.of(""));
    }

    @Test
    void renderAsTheirRawValueForKeysAndLogs() {
        assertThat(FirmId.of("firm-1").value()).isEqualTo("firm-1");
        assertThat(List.of(FirmId.of("f"), EngagementId.of("e"), ProductId.of("p"), Market.of("EU"),
                        TemplateVersion.of("v7"), PublishId.of("pub"), LocaleCode.of("fr-CA")))
                .extracting(Object::toString)
                .containsExactly("f", "e", "p", "EU", "v7", "pub", "fr-CA");
    }

    @Test
    void sameValueInDifferentIdTypesIsNotEqual() {
        assertThat((Object) FirmId.of("x")).isNotEqualTo(ProductId.of("x"));
        assertThat(FirmId.of("x")).isEqualTo(FirmId.of("x"));
    }
}
