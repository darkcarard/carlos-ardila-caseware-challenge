package com.caseware.fanout.domain.port;

/**
 * Regional telemetry port. Contexts may carry {@code firm_id}, so the sink must be the regional
 * account. Global dashboards only receive ID-free aggregates, derived elsewhere.
 */
public interface FanoutMetrics {

    void increment(String metric, long delta, TelemetryContext context);

    /**
     * A hard invariant was violated, for example an in-flight cap exceeded. Implementations emit
     * a high-severity {@code invariant.violation} metric that pages on-call.
     */
    void invariantViolation(String invariant, TelemetryContext context);

    FanoutMetrics NOOP = new FanoutMetrics() {
        @Override
        public void increment(String metric, long delta, TelemetryContext context) {}

        @Override
        public void invariantViolation(String invariant, TelemetryContext context) {}
    };
}
