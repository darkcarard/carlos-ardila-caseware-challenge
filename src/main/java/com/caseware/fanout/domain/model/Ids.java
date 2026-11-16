package com.caseware.fanout.domain.model;

import java.util.Objects;

/**
 * Shared validation for string-backed identifier value objects.
 *
 * <p>Each identifier is its own record type so that a {@link FirmId} can never be passed where a
 * {@link ProductId} is expected. Crucially, it lets cross-plane DTOs such as {@link SummaryPair}
 * be checked by type, not by convention: a global-plane type that does not declare a
 * {@code FirmId} component cannot carry one.
 */
final class Ids {

    private Ids() {}

    static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
