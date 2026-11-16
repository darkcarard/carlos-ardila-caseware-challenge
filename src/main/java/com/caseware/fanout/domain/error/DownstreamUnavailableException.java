package com.caseware.fanout.domain.error;

/** The downstream engagement loader returned 5xx or timed out. */
public final class DownstreamUnavailableException extends TransientInfrastructureException {

    public DownstreamUnavailableException(String message) {
        super(message);
    }

    public DownstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
