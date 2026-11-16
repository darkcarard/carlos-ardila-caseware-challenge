package com.caseware.fanout.doubles;

import com.caseware.fanout.domain.model.SummaryPair;
import com.caseware.fanout.domain.port.SummaryWarmupPublisher;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/** Records each batch exactly as it would be serialized onto the global bus. */
public final class RecordingSummaryPublisher implements SummaryWarmupPublisher {

    private final List<Set<SummaryPair>> batches = new CopyOnWriteArrayList<>();

    @Override
    public void publish(Set<SummaryPair> pairs) {
        batches.add(Set.copyOf(pairs));
    }

    public List<Set<SummaryPair>> batches() {
        return List.copyOf(batches);
    }

    public Set<SummaryPair> allPairs() {
        return batches.stream().flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
    }

    /** Returns the wire payloads. The test serializes with {@code toString}, which covers every component. */
    public List<String> serializedPayloads() {
        return batches.stream().map(Object::toString).toList();
    }
}
