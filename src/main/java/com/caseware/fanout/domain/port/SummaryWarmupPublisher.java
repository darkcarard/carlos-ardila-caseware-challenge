package com.caseware.fanout.domain.port;

import com.caseware.fanout.domain.model.SummaryPair;
import java.util.Set;

/**
 * Outbound, cross-plane port: asks the global diff and summary service to pre-warm summaries.
 *
 * <p><strong>Residency boundary.</strong> It accepts only {@link SummaryPair}, which has no firm
 * or engagement component. Never add an overload that takes a domain entity, a {@code FirmId}
 * or a free-form map. {@code ResidencyBoundaryTest} checks this signature by reflection.
 */
public interface SummaryWarmupPublisher {

    /** Publishes de-duplicated pairs. Idempotent: the global side de-duplicates again. */
    void publish(Set<SummaryPair> pairs);
}
