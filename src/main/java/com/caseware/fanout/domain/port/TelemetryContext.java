package com.caseware.fanout.domain.port;

import com.caseware.fanout.domain.model.FanoutChunk;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.ProductId;
import com.caseware.fanout.domain.model.PublishId;
import com.caseware.fanout.domain.model.TemplateVersion;
import java.util.Objects;
import java.util.Optional;

/**
 * Correlation fields required on every regional log line and span: {@code publish_id},
 * {@code product_id}, {@code template_version}, {@code firm_id} and {@code chunk_id}. It never
 * carries engagement or workpaper content. REGIONAL ONLY.
 */
public record TelemetryContext(
        Optional<PublishId> publishId,
        Optional<ProductId> productId,
        Optional<TemplateVersion> templateVersion,
        Optional<FirmId> firmId,
        Optional<String> chunkId) {

    public static final TelemetryContext EMPTY =
            new TelemetryContext(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    public TelemetryContext {
        Objects.requireNonNull(publishId, "publishId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(templateVersion, "templateVersion");
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(chunkId, "chunkId");
    }

    public static TelemetryContext forChunk(FanoutChunk chunk) {
        return new TelemetryContext(Optional.of(chunk.publishId()), Optional.of(chunk.productId()),
                Optional.of(chunk.templateVersion()), Optional.of(chunk.firmId()), Optional.of(chunk.key().chunkId()));
    }

    public static TelemetryContext forFirm(FirmId firmId) {
        return new TelemetryContext(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(firmId), Optional.empty());
    }
}
