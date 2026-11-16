package com.caseware.fanout.domain.model;

/** Lifecycle status of a template version in the regional graph projection. */
public enum VersionStatus {
    /** Eligible to be offered as a {@code pending_target}. */
    PUBLISHED,
    /** Never offered. It still links its descendants, so reachability ignores status. */
    WITHDRAWN
}
