package com.caseware.fanout.domain.error;

/** The downstream engagement loader returned 429 (rate limited). */
public final class DownstreamRateLimitException extends TransientInfrastructureException {

    public DownstreamRateLimitException(String message) {
        super(message);
    }

    public DownstreamRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
