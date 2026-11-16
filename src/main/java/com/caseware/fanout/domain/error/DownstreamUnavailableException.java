package com.caseware.fanout.domain.error;

import java.io.Serial;

/** The downstream engagement loader returned 5xx or timed out. */
public final class DownstreamUnavailableException extends TransientInfrastructureException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DownstreamUnavailableException(String message) {
        super(message);
    }

    public DownstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
