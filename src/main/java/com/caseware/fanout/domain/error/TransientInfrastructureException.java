package com.caseware.fanout.domain.error;

import java.io.Serial;

/** Retryable infrastructure failure: trips the circuit breaker and triggers backoff. */
public abstract sealed class TransientInfrastructureException extends FanoutException
        permits RegistryThrottledException, DownstreamRateLimitException, DownstreamUnavailableException {

    @Serial
    private static final long serialVersionUID = 1L;

    protected TransientInfrastructureException(String message) {
        super(message);
    }

    protected TransientInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
