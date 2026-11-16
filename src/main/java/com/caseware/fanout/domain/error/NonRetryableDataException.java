package com.caseware.fanout.domain.error;

/**
 * Permanent data or contract error, such as a corrupt version graph, an unknown product lineage or
 * a malformed chunk. Retrying cannot help, so the message goes to the DLQ with structured context.
 * It never trips the downstream circuit breaker.
 */
public final class NonRetryableDataException extends FanoutException {

    public NonRetryableDataException(String message) {
        super(message);
    }
}
