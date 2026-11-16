package com.caseware.fanout.domain.model;

/** Identifier of one publish/withdraw fan-out run; scopes chunk cursors. */
public record PublishId(String value) {

    public PublishId {
        Ids.requireNonBlank(value, "PublishId");
    }

    public static PublishId of(String value) {
        return new PublishId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
