package com.caseware.fanout.domain.model;

/** Firm identifier. REGIONAL ONLY: must never appear on the global plane (DynamoDB partition key). */
public record FirmId(String value) {

    public FirmId {
        Ids.requireNonBlank(value, "FirmId");
    }

    public static FirmId of(String value) {
        return new FirmId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
