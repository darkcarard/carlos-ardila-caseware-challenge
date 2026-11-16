package com.caseware.fanout.domain.error;

import java.io.Serial;

/** The registry (DynamoDB) throttled the request (ProvisionedThroughputExceeded or a 5xx). */
public final class RegistryThrottledException extends TransientInfrastructureException {

    @Serial
    private static final long serialVersionUID = 1L;

    public RegistryThrottledException(String message) {
        super(message);
    }

    public RegistryThrottledException(String message, Throwable cause) {
        super(message, cause);
    }
}
