package com.caseware.fanout.application;

import java.util.List;
import java.util.Objects;

/** All outcomes of one {@link BackfillWorkerService#run} call, in completion order. */
public record BackfillReport(List<LoadOutcome> outcomes) {

    public BackfillReport {
        outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
    }

    public <T extends LoadOutcome> List<T> of(Class<T> type) {
        return outcomes.stream().filter(type::isInstance).map(type::cast).toList();
    }

    public long count(Class<? extends LoadOutcome> type) {
        return outcomes.stream().filter(type::isInstance).count();
    }
}
