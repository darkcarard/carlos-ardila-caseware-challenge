package com.caseware.fanout.domain.error;

/** The registry (DynamoDB) throttled the request (ProvisionedThroughputExceeded or a 5xx). */
public final class RegistryThrottledException extends TransientInfrastructureException {

    public RegistryThrottledException(String message) {
        super(message);
    }

    public RegistryThrottledException(String message, Throwable cause) {
        super(message, cause);
    }
}
