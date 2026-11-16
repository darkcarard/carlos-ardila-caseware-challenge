package com.caseware.fanout.doubles;

import com.caseware.fanout.domain.port.FanoutMetrics;
import com.caseware.fanout.domain.port.TelemetryContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class RecordingMetrics implements FanoutMetrics {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final List<String> violations = new CopyOnWriteArrayList<>();

    @Override
    public void increment(String metric, long delta, TelemetryContext context) {
        counters.computeIfAbsent(metric, k -> new AtomicLong()).addAndGet(delta);
    }

    @Override
    public void invariantViolation(String invariant, TelemetryContext context) {
        violations.add(invariant);
    }

    public long count(String metric) {
        AtomicLong value = counters.get(metric);
        return value == null ? 0 : value.get();
    }

    public List<String> violations() {
        return List.copyOf(violations);
    }
}
