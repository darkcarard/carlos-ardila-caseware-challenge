package com.caseware.fanout.domain.error;

import java.io.Serial;

/** The downstream engagement loader returned 429 (rate limited). */
public final class DownstreamRateLimitException extends TransientInfrastructureException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DownstreamRateLimitException(String message) {
        super(message);
    }

    public DownstreamRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
