package com.caseware.fanout.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(5), 4);

    @Property(tries = 300)
    void delayIsWithinHalfToFullOfTheCappedExponential(@ForAll @IntRange(min = 0, max = 200) int attempt,
                                                       @ForAll String key) {
        long exp = Math.min(Duration.ofMinutes(5).toMillis(), 10_000L * (1L << Math.min(attempt, 20)));
        Duration delay = policy.delayFor(attempt, key);
        assertThat(delay.toMillis()).isBetween(exp / 2, exp);
    }

    @Test
    void isDeterministicPerKeyAndAttempt() {
        assertThat(policy.delayFor(2, "firm#e1")).isEqualTo(policy.delayFor(2, "firm#e1"));
    }

    @Test
    void spreadsDifferentKeysToAvoidAThunderingHerd() {
        Set<Duration> delays = IntStream.range(0, 50)
                .mapToObj(i -> policy.delayFor(3, "firm#e" + i))
                .collect(Collectors.toSet());
        assertThat(delays).hasSizeGreaterThan(40);
    }

    @Test
    void neverOverflowsForHugeAttempts() {
        assertThat(policy.delayFor(Integer.MAX_VALUE, "k")).isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void exhaustionCountsAttemptsMade() {
        assertThat(policy.exhausted(3)).isFalse();
        assertThat(policy.exhausted(4)).isTrue();
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RetryPolicy(Duration.ZERO, Duration.ofSeconds(1), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new RetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> policy.delayFor(-1, "k"));
    }
}
