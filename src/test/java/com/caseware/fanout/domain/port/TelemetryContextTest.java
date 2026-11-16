package com.caseware.fanout.domain.port;

import static com.caseware.fanout.doubles.TestData.EU;
import static com.caseware.fanout.doubles.TestData.FIRM;
import static com.caseware.fanout.doubles.TestData.PRODUCT;
import static com.caseware.fanout.doubles.TestData.v;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FanoutChunk;
import com.caseware.fanout.domain.model.PublishId;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelemetryContextTest {

    @Test
    void chunkContextCarriesTheRequiredCorrelationFields() {
        FanoutChunk chunk = new FanoutChunk(PublishId.of("pub-1"), FIRM, PRODUCT, EU, v("v7"), 2, 0, 1,
                List.of(EngagementId.of("e1")));

        TelemetryContext ctx = TelemetryContext.forChunk(chunk);

        assertThat(ctx.publishId()).contains(PublishId.of("pub-1"));
        assertThat(ctx.productId()).contains(PRODUCT);
        assertThat(ctx.templateVersion()).contains(v("v7"));
        assertThat(ctx.firmId()).contains(FIRM);
        assertThat(ctx.chunkId()).contains("pub-1/firm-acme/0");
    }

    @Test
    void firmContextCarriesOnlyTheFirm() {
        TelemetryContext ctx = TelemetryContext.forFirm(FIRM);

        assertThat(ctx.firmId()).contains(FIRM);
        assertThat(ctx.publishId()).isEmpty();
        assertThat(ctx.chunkId()).isEmpty();
    }

    @Test
    void theDefaultKillSwitchNeverTrips() {
        assertThat(KillSwitch.never().isTripped()).isFalse();
    }
}
