package com.caseware.fanout.domain.port;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.LoadedEngagement;

/**
 * The engagement system's load path: about 1 minute per call, owned by another team, with
 * limited capacity.
 *
 * <p><strong>Only backfill, reconciliation and accept surges may hold this port.</strong>
 * Steady-state publish fan-out never receives it, so its "0 downstream loads per publish"
 * guarantee is enforced by construction. Every call must run under a
 * {@link CapacityLimiter} permit.
 */
public interface DownstreamEngagementClient {

    /**
     * Loads the engagement and returns its ground truth, including the current {@code state_seq}.
     *
     * @throws com.caseware.fanout.domain.error.DownstreamRateLimitException on 429
     * @throws com.caseware.fanout.domain.error.DownstreamUnavailableException on 5xx or timeout
     * @throws com.caseware.fanout.domain.error.NonRetryableDataException if the engagement is
     *     corrupt or unknown
     * @throws InterruptedException if cancelled (kill switch, or circuit breaker opened)
     */
    LoadedEngagement loadEngagement(FirmId firmId, EngagementId engagementId) throws InterruptedException;
}
