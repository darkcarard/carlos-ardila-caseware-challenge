package com.caseware.fanout.domain.model;

/** Market lineage of a product template, e.g. {@code EU} or {@code CA} (global-plane safe). */
public record Market(String value) {

    public Market {
        Ids.requireNonBlank(value, "Market");
    }

    public static Market of(String value) {
        return new Market(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
