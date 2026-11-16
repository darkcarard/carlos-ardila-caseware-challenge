package com.caseware.fanout.domain.model;

/** Engagement identifier. REGIONAL ONLY: must never appear on the global plane (DynamoDB sort key). */
public record EngagementId(String value) {

    public EngagementId {
        Ids.requireNonBlank(value, "EngagementId");
    }

    public static EngagementId of(String value) {
        return new EngagementId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
