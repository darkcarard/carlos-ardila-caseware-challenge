package com.caseware.fanout.domain.error;

/**
 * A conditional write kept failing on {@code state_seq} after the bounded re-read and recompute
 * loop. The row is not corrupt: a user is actively changing it, so the caller yields and retries
 * later.
 */
public final class OptimisticLockConflictException extends FanoutException {

    public OptimisticLockConflictException(String message) {
        super(message);
    }
}
