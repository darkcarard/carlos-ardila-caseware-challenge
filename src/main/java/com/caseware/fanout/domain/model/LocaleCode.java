package com.caseware.fanout.domain.model;

/** Summary locale, e.g. {@code en-CA} (global-plane safe). Configured per region; not stored on registry rows. */
public record LocaleCode(String value) {

    public LocaleCode {
        Ids.requireNonBlank(value, "LocaleCode");
    }

    public static LocaleCode of(String value) {
        return new LocaleCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
