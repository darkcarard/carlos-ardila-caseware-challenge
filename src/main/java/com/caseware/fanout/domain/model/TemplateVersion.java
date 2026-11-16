package com.caseware.fanout.domain.model;

/** Product template version label, e.g. {@code v7} (global-plane safe). */
public record TemplateVersion(String value) {

    public TemplateVersion {
        Ids.requireNonBlank(value, "TemplateVersion");
    }

    public static TemplateVersion of(String value) {
        return new TemplateVersion(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
