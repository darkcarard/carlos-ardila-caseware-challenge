package com.caseware.fanout.domain.model;

import java.util.Objects;

/**
 * GLOBAL-PLANE DTO: the unit of summary generation, {@code (product, market, from, to, locale)}.
 *
 * <p>This is the only shape that leaves a region. It deliberately has no firm or engagement
 * component, and {@code ResidencyBoundaryTest} fails the build if one is ever added, even "for
 * logging convenience". Many engagements across many firms collapse into the same pair, which is
 * also what makes summary inference cheap.
 */
public record SummaryPair(
        ProductId productId,
        Market market,
        TemplateVersion fromVersion,
        TemplateVersion toVersion,
        LocaleCode locale) {

    public SummaryPair {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(fromVersion, "fromVersion");
        Objects.requireNonNull(toVersion, "toVersion");
        Objects.requireNonNull(locale, "locale");
        if (fromVersion.equals(toVersion)) {
            throw new IllegalArgumentException("a summary pair needs two distinct versions: " + fromVersion);
        }
    }
}
