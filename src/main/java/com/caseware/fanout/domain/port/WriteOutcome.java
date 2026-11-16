package com.caseware.fanout.domain.port;

import com.caseware.fanout.domain.model.EngagementRecord;
import java.util.Objects;
import java.util.Optional;

/** Result of a conditional registry write. */
public sealed interface WriteOutcome {

    /** The condition held and the row is now {@code row}. */
    record Applied(EngagementRecord row) implements WriteOutcome {
        public Applied {
            Objects.requireNonNull(row, "row");
        }
    }

    /**
     * The condition failed (DynamoDB {@code ConditionalCheckFailedException} with
     * {@code ReturnValuesOnConditionCheckFailure=ALL_OLD}). {@code current} is empty if the row no
     * longer exists, for example after a {@code deleted} hook.
     */
    record ConditionFailed(Optional<EngagementRecord> current) implements WriteOutcome {
        public ConditionFailed {
            Objects.requireNonNull(current, "current");
        }
    }
}
