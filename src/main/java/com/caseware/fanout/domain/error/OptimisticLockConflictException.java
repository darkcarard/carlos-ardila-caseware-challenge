package com.caseware.fanout.domain.error;

import java.io.Serial;

/**
 * A conditional write kept failing on {@code state_seq} after the bounded re-read and recompute
 * loop. The row is not corrupt: a user is actively changing it, so the caller yields and retries
 * later.
 */
public final class OptimisticLockConflictException extends FanoutException {

    @Serial
    private static final long serialVersionUID = 1L;

    public OptimisticLockConflictException(String message) {
        super(message);
    }
}
