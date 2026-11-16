package com.caseware.fanout.domain.model;

/** Product template identifier (global-plane safe). */
public record ProductId(String value) {

    public ProductId {
        Ids.requireNonBlank(value, "ProductId");
    }

    public static ProductId of(String value) {
        return new ProductId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
