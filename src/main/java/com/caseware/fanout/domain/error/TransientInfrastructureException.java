package com.caseware.fanout.domain.error;

/** Retryable infrastructure failure: trips the circuit breaker and triggers backoff. */
public abstract sealed class TransientInfrastructureException extends FanoutException
        permits RegistryThrottledException, DownstreamRateLimitException, DownstreamUnavailableException {

    protected TransientInfrastructureException(String message) {
        super(message);
    }

    protected TransientInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
